package org.acme.dogfood.mclaren;

/**
 * SQL Race session lifecycle states — the {@code S} type parameter for
 * {@link SqlRaceSession}'s {@code StateMachine}.
 *
 * <p>Maps to the real ATLAS session state machine ({@code SessionState} enum + recorder
 * lifecycle):
 *
 * <pre>
 *   ┌──────────────┐  Configure   ┌──────┐  SessionSaved  ┌─────────┐  Close  ┌────────┐
 *   │ Initializing │─────────────▶│ Live │───────────────▶│ Closing │────────▶│ Closed │
 *   └──────────────┘              └──────┘                └─────────┘         └────────┘
 *                                   │  ▲
 *                                   │  │ AddLap / AddDataItem (keep_state)
 *                                   └──┘
 * </pre>
 *
 * <p>OTP equivalence:
 *
 * <ul>
 *   <li>{@code Initializing} — gen_statem initial state; the session has a key but no
 *       configuration committed yet. Equivalent to {@code SessionState.Live} before
 *       {@code config.Commit()} is called.
 *   <li>{@code Live} — active acquisition; parameters and laps are being appended in real time.
 *       Equivalent to {@code SessionState.Live} post-commit in ATLAS.
 *   <li>{@code Closing} — archive write triggered; waiting for the SQL Race engine to flush all
 *       ring-buffer pages to persistent storage.
 *   <li>{@code Closed} — terminal state; session is read-only in the SQL Race database. Any
 *       further messages → {@code Transition.stop("session closed")}.
 * </ul>
 */
public sealed interface SqlRaceSessionState
        permits SqlRaceSessionState.Initializing,
                SqlRaceSessionState.Live,
                SqlRaceSessionState.Closing,
                SqlRaceSessionState.Closed {

    /** Session has a key but the configuration has not been committed yet. */
    record Initializing() implements SqlRaceSessionState {}

    /** Configuration committed; real-time data acquisition is active. */
    record Live() implements SqlRaceSessionState {}

    /** Archive flush triggered; transitioning to read-only storage. */
    record Closing() implements SqlRaceSessionState {}

    /** Session is persisted and read-only. Terminal — no further events accepted. */
    record Closed() implements SqlRaceSessionState {}
}
