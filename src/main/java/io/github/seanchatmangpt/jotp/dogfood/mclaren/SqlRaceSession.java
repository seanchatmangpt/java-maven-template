package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.time.Instant;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;

/**
 * SQL Race session state machine — OTP {@code gen_statem} mapped to McLaren ATLAS session
 * lifecycle.
 *
 * <p>In ATLAS the C# SQL Race API manages session state via a mutable {@code IClientSession}
 * object. The Java 26 / OTP refactor replaces that with an immutable gen_statem:
 *
 * <pre>
 *   Initializing ──Configure──▶ Live ──SessionSaved──▶ Closing ──Close──▶ Closed
 *                                │ ▲
 *                                └─┘  AddLap / AddDataItem  (keep_state)
 * </pre>
 *
 * <p>Each transition is a pure function {@code (State, Event, Data) → Transition<State, Data>},
 * exactly matching the OTP {@code gen_statem} callback contract:
 *
 * <pre>{@code
 * % Erlang gen_statem callback:
 * handle_event(cast, {add_lap, Lap}, live, Data) ->
 *     {keep_state, Data#{laps := [Lap | Data#data.laps]}};
 * }</pre>
 *
 * <h2>Real ATLAS mapping</h2>
 *
 * <table>
 *   <tr><th>ATLAS SQL Race C# call</th><th>Java 26 OTP equivalent</th></tr>
 *   <tr><td>{@code config.Commit()}</td>
 *       <td>{@code session.send(new Configure(params, channels, convs))}</td></tr>
 *   <tr><td>{@code session.Laps.Add(lap)}</td>
 *       <td>{@code session.send(new AddLap(lap))}</td></tr>
 *   <tr><td>{@code clientSession.Close()}</td>
 *       <td>{@code session.send(new Close())}</td></tr>
 * </table>
 */
public final class SqlRaceSession {

    private final StateMachine<SqlRaceSessionState, SqlRaceSessionEvent, SqlRaceSessionData>
            machine;

    private SqlRaceSession(
            StateMachine<SqlRaceSessionState, SqlRaceSessionEvent, SqlRaceSessionData> machine) {
        this.machine = machine;
    }

    /**
     * Create and start a new SQL Race session.
     *
     * <p>The session begins in {@link SqlRaceSessionState.Initializing} — no channels or
     * parameters are committed yet. The first event must be a
     * {@link SqlRaceSessionEvent.Configure} to transition to {@link SqlRaceSessionState.Live}.
     *
     * @param identifier human-readable session name (e.g. {@code "Bahrain_FP2_Car1_2025-03-01"})
     * @return running session state machine
     */
    public static SqlRaceSession create(String identifier) {
        var key = SqlRaceSessionKey.newKey(identifier);
        var initial = SqlRaceSessionData.empty(key, identifier);
        var machine = new StateMachine<>(
                new SqlRaceSessionState.Initializing(),
                initial,
                SqlRaceSession::transition);
        return new SqlRaceSession(machine);
    }

    // ---------------------------------------------------------------------------
    // Public API — mirrors IClientSession / ISession
    // ---------------------------------------------------------------------------

    /**
     * Send an event asynchronously — mirrors {@code gen_statem:cast/2}.
     *
     * @param event the session event to dispatch
     */
    public void send(SqlRaceSessionEvent event) {
        machine.send(event);
    }

    /**
     * Send an event and wait for the resulting data — mirrors {@code gen_statem:call/3}.
     *
     * @param event the session event
     * @return the session data after the transition completes
     * @throws RuntimeException if interrupted or timed out
     */
    public SqlRaceSessionData call(SqlRaceSessionEvent event) {
        try {
            return machine.call(event).join();
        } catch (Exception e) {
            throw new RuntimeException("session call failed", e);
        }
    }

    /**
     * Current session state (volatile read — may lag by one message).
     *
     * @return current {@link SqlRaceSessionState}
     */
    public SqlRaceSessionState state() {
        return machine.state();
    }

    /**
     * Current session data (volatile read — may lag by one message).
     *
     * @return current {@link SqlRaceSessionData}
     */
    public SqlRaceSessionData data() {
        return machine.data();
    }

    /**
     * Build a {@link SqlRaceSessionSummary} from the current data snapshot.
     *
     * @return summary record suitable for Telemetry Analytics API responses
     */
    public SqlRaceSessionSummary summary() {
        return SqlRaceSessionSummary.from(machine.data(), machine.state());
    }

    /**
     * Check whether the session state machine is still running.
     *
     * @return {@code false} once {@link SqlRaceSessionState.Closed} is reached
     */
    public boolean isRunning() {
        return machine.isRunning();
    }

    /**
     * Stop the session state machine immediately (for testing / forced shutdown).
     *
     * @throws InterruptedException if interrupted while waiting for the machine to drain
     */
    public void stop() throws InterruptedException {
        machine.stop();
    }

    // ---------------------------------------------------------------------------
    // gen_statem transition function
    // ---------------------------------------------------------------------------

    private static Transition<SqlRaceSessionState, SqlRaceSessionData> transition(
            SqlRaceSessionState state,
            SqlRaceSessionEvent event,
            SqlRaceSessionData data) {
        return switch (state) {
            case SqlRaceSessionState.Initializing ignored -> handleInitializing(event, data);
            case SqlRaceSessionState.Live ignored -> handleLive(event, data);
            case SqlRaceSessionState.Closing ignored -> handleClosing(event, data);
            case SqlRaceSessionState.Closed ignored ->
                // Terminal state — any event stops the machine
                Transition.stop("session already closed");
        };
    }

    private static Transition<SqlRaceSessionState, SqlRaceSessionData> handleInitializing(
            SqlRaceSessionEvent event, SqlRaceSessionData data) {
        return switch (event) {
            case SqlRaceSessionEvent.Configure(var params, var chans, var convs) -> {
                // config.Commit() equivalent: register channels/parameters and open session
                long now = Instant.now().toEpochMilli() * 1_000_000L;
                var next = data.withConfiguration(params, chans, convs).withStartTime(now);
                yield Transition.nextState(new SqlRaceSessionState.Live(), next);
            }
            default ->
                // Any other event while Initializing: keep state, ignore
                Transition.keepState(data);
        };
    }

    private static Transition<SqlRaceSessionState, SqlRaceSessionData> handleLive(
            SqlRaceSessionEvent event, SqlRaceSessionData data) {
        return switch (event) {
            case SqlRaceSessionEvent.AddLap(var lap) ->
                // session.Laps.Add(lap) — keep_state, update data
                Transition.keepState(data.withLap(lap));

            case SqlRaceSessionEvent.AddDataItem(var item) ->
                // session.Items.Add(item) — keep_state, update data
                Transition.keepState(data.withDataItem(item));

            case SqlRaceSessionEvent.SessionSaved ignored ->
                // Archive flush triggered: move to Closing
                Transition.nextState(new SqlRaceSessionState.Closing(), data);

            case SqlRaceSessionEvent.Close ignored ->
                // clientSession.Close() — stop the gen_statem immediately
                Transition.stop("session closed by request");

            default -> Transition.keepState(data);
        };
    }

    private static Transition<SqlRaceSessionState, SqlRaceSessionData> handleClosing(
            SqlRaceSessionEvent event, SqlRaceSessionData data) {
        return switch (event) {
            case SqlRaceSessionEvent.Close ignored -> {
                long now = Instant.now().toEpochMilli() * 1_000_000L;
                yield Transition.nextState(new SqlRaceSessionState.Closed(), data.withEndTime(now));
            }
            default -> Transition.keepState(data);
        };
    }
}
