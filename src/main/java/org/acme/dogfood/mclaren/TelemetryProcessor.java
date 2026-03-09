package org.acme.dogfood.mclaren;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import org.acme.Proc;
import org.acme.Result;

/**
 * Realtime telemetry channel processor — one {@link Proc} per {@link TelemetryChannel}.
 *
 * <p>Implements the OTP "let it crash" philosophy for the Atlas telemetry pipeline:
 *
 * <ul>
 *   <li><b>Hardware faults</b> on {@link TelemetryChannel.Powertrain#critical() critical} channels
 *       throw, crashing the process. The {@link ChannelSupervisor} restarts it within 50 ms.
 *   <li><b>Out-of-range samples</b> are routed through {@link Result#failure} — the channel stays
 *       alive, the error is recorded, and acquisition continues.
 *   <li><b>Normal samples</b> are validated, appended to the ring buffer, and acknowledged via
 *       {@link Result#success}.
 * </ul>
 *
 * <p>All channel state is held as a local variable inside the virtual-thread loop — never shared,
 * never synchronized. Joe Armstrong: "Processes share nothing; they communicate only by message
 * passing."
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * TelemetryChannel ch = TelemetryChannel.SPEED_KPH;
 * Proc<TelemetryProcessor.ChannelState, TelemetryProcessor.Msg> proc =
 *     TelemetryProcessor.spawn(ch);
 *
 * // send a valid sample
 * proc.tell(new TelemetryProcessor.Msg.RawSample(235.4, Instant.now()));
 *
 * // query stats
 * var state = proc.ask(new TelemetryProcessor.Msg.GetStats()).join();
 * System.out.println(state.samplesReceived()); // 1
 *
 * proc.stop();
 * }</pre>
 */
public final class TelemetryProcessor {

    private TelemetryProcessor() {}

    // ── Message hierarchy ─────────────────────────────────────────────────────

    /**
     * Messages accepted by a channel processor — sealed for exhaustive pattern matching.
     *
     * <p>OTP equivalent: the {@code M} type parameter in {@code Proc<S, M>}.
     */
    public sealed interface Msg
            permits Msg.RawSample, Msg.HardwareFault, Msg.GetStats {

        /**
         * A raw telemetry sample arriving from the DAS network.
         *
         * @param value     the raw sensor reading in the channel's engineering unit
         * @param timestamp GPS-synchronized acquisition timestamp
         */
        record RawSample(double value, Instant timestamp) implements Msg {
            public RawSample {
                Objects.requireNonNull(timestamp, "timestamp must not be null");
            }
        }

        /**
         * A hardware fault reported by the sensor interface layer.
         *
         * <p>On {@link TelemetryChannel.Powertrain#critical()} channels this causes the process to
         * crash so the supervisor can restart it with a clean state. On non-critical channels the
         * fault is recorded as a validation error.
         *
         * @param description human-readable fault description
         */
        record HardwareFault(String description) implements Msg {
            public HardwareFault {
                Objects.requireNonNull(description, "description must not be null");
            }
        }

        /** Query the current channel statistics without mutating state. */
        record GetStats() implements Msg {}
    }

    // ── Validated sample ──────────────────────────────────────────────────────

    /**
     * A sample that has passed range validation — safe to persist and distribute.
     *
     * @param channelName name from {@link TelemetryChannel#name()}
     * @param value       validated sensor value
     * @param timestamp   acquisition timestamp
     */
    public record ValidSample(String channelName, double value, Instant timestamp) {
        public ValidSample {
            Objects.requireNonNull(channelName, "channelName must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    // ── Validation error ──────────────────────────────────────────────────────

    /**
     * Sealed validation error hierarchy — one variant per failure mode.
     *
     * <p>Used as the failure type in {@code Result<ValidSample, ValidationError>}.
     */
    public sealed interface ValidationError
            permits ValidationError.OutOfRange, ValidationError.HardwareFault {

        /**
         * Sample value outside the channel's declared valid range.
         *
         * @param channelName name of the affected channel
         * @param value       the offending sample value
         * @param min         declared minimum
         * @param max         declared maximum
         */
        record OutOfRange(String channelName, double value, double min, double max)
                implements ValidationError {}

        /**
         * Hardware fault on a non-critical channel (logged, not crashed).
         *
         * @param channelName name of the affected channel
         * @param description fault description
         */
        record HardwareFault(String channelName, String description)
                implements ValidationError {}
    }

    // ── Channel state ─────────────────────────────────────────────────────────

    /**
     * Immutable state carried by the channel processor between messages.
     *
     * @param channel          the {@link TelemetryChannel} this processor is responsible for
     * @param samplesReceived  total raw samples received (including invalid ones)
     * @param samplesValid     samples that passed range validation
     * @param errors           list of validation errors accumulated since last reset
     * @param buffer           ring buffer of the most recent valid samples (capped at 1 000)
     */
    public record ChannelState(
            TelemetryChannel channel,
            long samplesReceived,
            long samplesValid,
            List<ValidationError> errors,
            List<ValidSample> buffer) {

        /** Maximum number of valid samples retained in memory. */
        static final int MAX_BUFFER = 1_000;

        public ChannelState {
            Objects.requireNonNull(channel, "channel must not be null");
            errors = List.copyOf(Objects.requireNonNull(errors, "errors must not be null"));
            buffer = List.copyOf(Objects.requireNonNull(buffer, "buffer must not be null"));
        }

        /** Returns the initial state for a freshly started processor. */
        static ChannelState initial(TelemetryChannel ch) {
            return new ChannelState(ch, 0L, 0L, List.of(), List.of());
        }

        /** Returns a copy with a valid sample appended (ring buffer capped at MAX_BUFFER). */
        ChannelState record(ValidSample sample) {
            var newBuffer = new ArrayList<>(buffer);
            if (newBuffer.size() >= MAX_BUFFER) newBuffer.removeFirst();
            newBuffer.add(sample);
            return new ChannelState(channel, samplesReceived + 1, samplesValid + 1, errors, newBuffer);
        }

        /** Returns a copy with a validation error appended. */
        ChannelState recordError(ValidationError error) {
            var newErrors = new ArrayList<>(errors);
            newErrors.add(error);
            return new ChannelState(channel, samplesReceived + 1, samplesValid, newErrors, buffer);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Spawn a supervised channel processor for {@code channel}.
     *
     * <p>The returned {@link Proc} is ready to accept {@link Msg} messages. Pass it to
     * {@link ChannelSupervisor#supervise} to enable automatic crash recovery.
     *
     * @param channel the telemetry channel to process
     * @return a running {@code Proc<ChannelState, Msg>}
     */
    public static Proc<ChannelState, Msg> spawn(TelemetryChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        return new Proc<>(ChannelState.initial(channel), handler(channel));
    }

    // ── Handler (package-private for testing) ─────────────────────────────────

    /**
     * Returns the pure transition function for the given channel.
     *
     * <p>Kept package-private so tests can call {@link #validate} and compose handlers
     * without spawning a live virtual thread.
     */
    static BiFunction<ChannelState, Msg, ChannelState> handler(TelemetryChannel channel) {
        return (state, msg) -> switch (msg) {
            case Msg.RawSample(var value, var ts) ->
                    validate(channel, value, ts).fold(
                            state::record,       // valid: append to ring buffer
                            state::recordError   // invalid: log error, stay alive
                    );

            case Msg.HardwareFault(var description) -> {
                boolean critical = switch (channel) {
                    case TelemetryChannel.Powertrain pt -> pt.critical();
                    default -> false;
                };
                if (critical) {
                    // OTP: "let it crash" — supervisor will restart with clean state
                    throw new RuntimeException(
                            "hardware fault on critical channel %s: %s"
                                    .formatted(channel.name(), description));
                }
                // Non-critical: log the fault, keep acquiring
                yield state.recordError(
                        new ValidationError.HardwareFault(channel.name(), description));
            }

            case Msg.GetStats() -> state; // no-op; reply carries current state
        };
    }

    /**
     * Validate a raw sample against the channel's declared valid range.
     *
     * <p>Returns {@link Result#success(Object)} if {@code minValid ≤ value ≤ maxValid}, or
     * {@link Result#failure(Object)} with an {@link ValidationError.OutOfRange} describing the
     * violation. Never throws.
     *
     * @param channel   the channel whose range to validate against
     * @param value     raw sensor reading
     * @param timestamp acquisition timestamp
     * @return a {@code Result<ValidSample, ValidationError>}
     */
    public static Result<ValidSample, ValidationError> validate(
            TelemetryChannel channel, double value, Instant timestamp) {
        if (value >= channel.minValid() && value <= channel.maxValid()) {
            return Result.success(new ValidSample(channel.name(), value, timestamp));
        }
        return Result.failure(
                new ValidationError.OutOfRange(
                        channel.name(), value, channel.minValid(), channel.maxValid()));
    }
}
