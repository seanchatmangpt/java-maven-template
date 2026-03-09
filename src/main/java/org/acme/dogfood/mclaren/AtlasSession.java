package org.acme.dogfood.mclaren;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.acme.StateMachine;
import org.acme.StateMachine.Transition;

/**
 * Formal session lifecycle for a McLaren Atlas data acquisition session.
 *
 * <p>Encodes the session FSM as a {@link StateMachine}{@code <SessionState, SessionEvent,
 * SessionData>} — a direct mapping of Erlang/OTP's {@code gen_statem} to the motorsport domain.
 * Concurrent writes from the network receiver and the analyst workstation can no longer corrupt
 * session metadata because all state transitions happen in the state machine's single virtual-thread
 * mailbox.
 *
 * <h2>State diagram</h2>
 *
 * <pre>
 *  ┌────────┐  StartAcquisition   ┌────────────┐  StopAcquisition  ┌────────────┐
 *  │  Idle  │────────────────────►│ Acquiring  │──────────────────►│ Processing │
 *  └────────┘                     └────────────┘                   └────────────┘
 *                                   │ SensorTimeout                       │
 *                                   │ (stay + log warning)                │ ArchiveComplete
 *                                   ▼                                     ▼
 *                                  (self)                           ┌──────────┐
 *                                                                   │ Archived │ (terminal)
 *                                                                   └──────────┘
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var session = AtlasSession.create("GP_Bahrain_2026_FP1");
 *
 * // start data acquisition
 * session.send(new AtlasSession.SessionEvent.StartAcquisition(AtlasChannelRegistry.standard500()));
 *
 * // log a sensor warning without crashing
 * session.send(new AtlasSession.SessionEvent.SensorTimeout("brake_temp_fl"));
 *
 * // stop and archive
 * session.send(new AtlasSession.SessionEvent.StopAcquisition());
 * var data = session.call(new AtlasSession.SessionEvent.ArchiveComplete()).join();
 * System.out.println(data.sessionId()); // GP_Bahrain_2026_FP1
 * }</pre>
 */
public final class AtlasSession {

    private AtlasSession() {}

    // ── State hierarchy ───────────────────────────────────────────────────────

    /**
     * Session lifecycle states — sealed to guarantee exhaustive pattern matching.
     *
     * <p>OTP equivalent: the state term returned from {@code init/1} and {@code handle_event/4}.
     */
    public sealed interface SessionState
            permits SessionState.Idle,
                    SessionState.Acquiring,
                    SessionState.Processing,
                    SessionState.Archived {

        /** Session not yet started; awaiting {@link SessionEvent.StartAcquisition}. */
        record Idle() implements SessionState {}

        /** Actively receiving telemetry from the car. */
        record Acquiring() implements SessionState {}

        /** Acquisition stopped; writing to NVMe ring buffer and cloud archive. */
        record Processing() implements SessionState {}

        /** All data archived; terminal state — session is read-only. */
        record Archived() implements SessionState {}
    }

    // ── Event hierarchy ───────────────────────────────────────────────────────

    /**
     * Session lifecycle events — sealed to guarantee exhaustive pattern matching.
     *
     * <p>OTP equivalent: the event term passed to {@code handle_event/4}.
     */
    public sealed interface SessionEvent
            permits SessionEvent.StartAcquisition,
                    SessionEvent.StopAcquisition,
                    SessionEvent.SensorTimeout,
                    SessionEvent.ArchiveComplete,
                    SessionEvent.GetStatus {

        /**
         * Begin telemetry acquisition.
         *
         * @param channels the channels to acquire for this session
         */
        record StartAcquisition(List<TelemetryChannel> channels) implements SessionEvent {
            public StartAcquisition {
                channels = List.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
            }
        }

        /** Signal that the car has left the garage or crossed the finish line. */
        record StopAcquisition() implements SessionEvent {}

        /**
         * A channel stopped sending samples within the expected interval.
         *
         * @param channelName the affected channel
         */
        record SensorTimeout(String channelName) implements SessionEvent {
            public SensorTimeout {
                Objects.requireNonNull(channelName, "channelName must not be null");
            }
        }

        /** Archive pipeline has written all session data to durable storage. */
        record ArchiveComplete() implements SessionEvent {}

        /** Request the current session status without mutating state. */
        record GetStatus() implements SessionEvent {}
    }

    // ── Session data ──────────────────────────────────────────────────────────

    /**
     * Immutable session context carried across all state transitions.
     *
     * @param sessionId unique Atlas session identifier, e.g. {@code "GP_Bahrain_2026_FP1"}
     * @param channels  channels registered for this session
     * @param startTime wall-clock instant when acquisition began ({@code null} until {@link
     *     SessionEvent.StartAcquisition})
     * @param endTime   wall-clock instant when archiving completed ({@code null} until {@link
     *     SessionEvent.ArchiveComplete})
     * @param warnings  list of channel names that timed out during acquisition
     */
    public record SessionData(
            String sessionId,
            List<TelemetryChannel> channels,
            Instant startTime,
            Instant endTime,
            List<String> warnings) {

        public SessionData {
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            channels = List.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
        }

        /** Returns a copy with the acquisition start time set to {@code now}. */
        SessionData withStartNow() {
            return new SessionData(sessionId, channels, Instant.now(), endTime, warnings);
        }

        /** Returns a copy with the channel list replaced. */
        SessionData withChannels(List<TelemetryChannel> ch) {
            return new SessionData(sessionId, ch, startTime, endTime, warnings);
        }

        /** Returns a copy with the archive end time set to {@code now}. */
        SessionData withEndNow() {
            return new SessionData(sessionId, channels, startTime, Instant.now(), warnings);
        }

        /** Returns a copy with {@code channelName} appended to the warning list. */
        SessionData withWarning(String channelName) {
            var updated = new ArrayList<>(warnings);
            updated.add(channelName);
            return new SessionData(sessionId, channels, startTime, endTime, updated);
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Create a new Atlas session state machine in the {@link SessionState.Idle} state.
     *
     * @param sessionId unique identifier for this session
     * @return a running {@link StateMachine} ready to accept {@link SessionEvent}s
     */
    public static StateMachine<SessionState, SessionEvent, SessionData> create(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        var initial = new SessionData(sessionId, List.of(), null, null, List.of());
        return new StateMachine<>(new SessionState.Idle(), initial, AtlasSession::transition);
    }

    // ── Transition function ───────────────────────────────────────────────────

    /**
     * Pure transition function — exhaustive over all {@link SessionState} × {@link SessionEvent}
     * combinations that matter; unrecognised events are silently dropped ({@code keepState}).
     *
     * <p>This is the OTP {@code handle_event/4} equivalent.
     */
    static Transition<SessionState, SessionData> transition(
            SessionState state, SessionEvent event, SessionData data) {
        return switch (state) {
            case SessionState.Idle() -> switch (event) {
                case SessionEvent.StartAcquisition(var channels) ->
                        Transition.nextState(
                                new SessionState.Acquiring(),
                                data.withChannels(channels).withStartNow());
                default -> Transition.keepState(data);
            };

            case SessionState.Acquiring() -> switch (event) {
                case SessionEvent.StopAcquisition() ->
                        Transition.nextState(new SessionState.Processing(), data);
                case SessionEvent.SensorTimeout(var ch) ->
                        // log the warning; stay alive — supervisor handles the channel restart
                        Transition.keepState(data.withWarning(ch));
                default -> Transition.keepState(data);
            };

            case SessionState.Processing() -> switch (event) {
                case SessionEvent.ArchiveComplete() ->
                        Transition.nextState(new SessionState.Archived(), data.withEndNow());
                default -> Transition.keepState(data);
            };

            case SessionState.Archived() ->
                    // terminal: no further transitions; all events are no-ops
                    Transition.stop("session archived: " + data.sessionId());
        };
    }
}
