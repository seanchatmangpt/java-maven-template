package org.acme.test.patterns;

import java.util.UUID;

/**
 * Shared domain types for McLaren Atlas SQL Race EIP pattern tests.
 *
 * <p>These types represent the core telemetry domain model:
 * <ul>
 *   <li>SessionId - correlation identifier for session saga</li>
 *   <li>Timestamp - nanosecond-precision timestamps for idempotency</li>
 *   <li>ParameterId - sensor channel identifier</li>
 *   <li>Sample - raw 16-bit sensor reading with quality status</li>
 *   <li>DataStatusType - quality status (Good | OutOfRange | InvalidData)</li>
 * </ul>
 *
 * <p>Also provides a sealed Query interface hierarchy for type-safe state queries,
 * replacing awkward patterns like sending Sample with timestamp -1.
 */
public final class AtlasDomain {

    private AtlasDomain() {
        // Utility class - no instantiation
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DATA STATUS TYPE (sealed hierarchy for exhaustive pattern matching)
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Data quality status following McLaren telemetry standards. */
    public sealed interface DataStatusType permits DataStatusType.Good, DataStatusType.OutOfRange, DataStatusType.InvalidData {

        /** Good quality data - within valid range. */
        record Good() implements DataStatusType {}

        /** Data is outside valid range. */
        record OutOfRange(double min, double max) implements DataStatusType {}

        /** Data is invalid due to sensor error or disconnection. */
        record InvalidData(String reason) implements DataStatusType {}
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CORE DOMAIN TYPES
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Sensor channel identifier (e.g., "BRAKE_PRESSURE_FL", "ENGINE_RPM"). */
    public record ParameterId(String id) {}

    /** Nanosecond-precision timestamp for idempotency and sequencing. */
    public record Timestamp(long nanos) {}

    /** Session identifier - correlation ID for session saga. */
    public record SessionId(UUID id) {
        public static SessionId generate() {
            return new SessionId(UUID.randomUUID());
        }
    }

    /** Lap number in a race session. */
    public record LapNumber(int number) {}

    /** Raw 16-bit sensor sample with quality status. */
    public record Sample(
            ParameterId parameterId, Timestamp timestamp, short rawValue, DataStatusType status) {}

    /** Parameter specification with validation bounds. */
    public record ParameterSpec(
            ParameterId id, String name, double minValid, double maxValid, String unit) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // QUERY INTERFACE (type-safe state queries)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Sealed interface for type-safe state queries.
     *
     * <p>Instead of using awkward patterns like:
     * <ul>
     *   <li>Sample with timestamp -1</li>
     *   <li>Empty RoutingSlip</li>
     *   <li>SessionEvent with QUERY state</li>
     * </ul>
     *
     * <p>Use explicit query types:
     * <pre>{@code
     * // Query list state
     * var samples = proc.ask(QueryState.SAMPLES).get();
     *
     * // Query count
     * var count = proc.ask(QueryState.COUNT).get();
     *
     * // Query full state
     * var state = proc.ask(QueryState.FULL).get();
     * }</pre>
     */
    public sealed interface QueryState permits QueryState.Samples, QueryState.Count, QueryState.Full, QueryState.DeadLetters, QueryState.RoutingLog {

        /** Query for list of samples. */
        record Samples() implements QueryState {}

        /** Query for count of processed items. */
        record Count() implements QueryState {}

        /** Query for full state snapshot. */
        record Full() implements QueryState {}

        /** Query for dead letter entries. */
        record DeadLetters() implements QueryState {}

        /** Query for routing/processing log. */
        record RoutingLog() implements QueryState {}
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CANONICAL MESSAGE MODEL
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Canonical message model - all telemetry messages implement this sealed interface.
     *
     * <p>Provides compile-time exhaustiveness checking for pattern matching.
     */
    public sealed interface AtlasMsg permits AtlasMsg.SampleMsg, AtlasMsg.SessionEventMsg, AtlasMsg.LapEventMsg, AtlasMsg.StrategyCmdMsg {

        SessionId sessionId();

        /** Telemetry sample message. */
        record SampleMsg(SessionId sessionId, Sample sample) implements AtlasMsg {}

        /** Session state change event. */
        record SessionEventMsg(SessionId sessionId, SessionState state) implements AtlasMsg {}

        /** Lap completion event. */
        record LapEventMsg(SessionId sessionId, LapNumber lap, Timestamp beaconTs) implements AtlasMsg {}

        /** Strategy command with reply-to future. */
        record StrategyCmdMsg(
                SessionId sessionId, RaceState state, java.util.concurrent.CompletableFuture<Recommendation> replyTo)
                implements AtlasMsg {}
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SESSION LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Session lifecycle states. */
    public sealed interface SessionState permits SessionState.Initialized, SessionState.Configured, SessionState.GoLive, SessionState.Recording, SessionState.Saving, SessionState.Closed {

        record Initialized() implements SessionState {}
        record Configured() implements SessionState {}
        record GoLive() implements SessionState {}
        record Recording() implements SessionState {}
        record Saving() implements SessionState {}
        record Closed() implements SessionState {}
    }

    /** Race state for strategy commands. */
    public record RaceState(String description) {}

    /** Strategy recommendation from the strategy engine. */
    public record Recommendation(String strategy, double confidence) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // DEAD LETTER
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Dead letter entry with reason and timestamp. */
    public record DeadLetterEntry(AtlasMsg msg, String reason, long timestamp) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // ANALYSIS RESULT (for Scatter-Gather)
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Analysis result from parallel parameter analysis. */
    public record AnalysisResult(ParameterId param, double value, String status) {}
}
