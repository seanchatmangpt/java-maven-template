package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.List;

/**
 * SQL Race session events — the {@code E} type parameter for {@link SqlRaceSession}'s
 * {@code StateMachine}.
 *
 * <p>Each record maps directly to an ATLAS SQL Race operation:
 *
 * <ul>
 *   <li>{@link Configure} → {@code config.AddChannel/AddParameter/AddGroup/Commit()}
 *   <li>{@link AddLap} → {@code session.Laps.Add(lap)}
 *   <li>{@link AddDataItem} → {@code session.Items.Add(item)}
 *   <li>{@link SessionSaved} → async notification from the SQL Race write-behind thread
 *   <li>{@link Close} → {@code clientSession.Close()}
 * </ul>
 *
 * <p>OTP equivalence: these are the messages sent via {@code gen_statem:cast/2} (fire-and-forget)
 * or {@code gen_statem:call/3} (synchronous). {@code StateMachine.send(event)} is cast;
 * {@code StateMachine.call(event)} is the synchronous form.
 */
public sealed interface SqlRaceSessionEvent
        permits SqlRaceSessionEvent.Configure,
                SqlRaceSessionEvent.AddLap,
                SqlRaceSessionEvent.AddDataItem,
                SqlRaceSessionEvent.SessionSaved,
                SqlRaceSessionEvent.Close {

    /**
     * Commit session configuration: channels, parameters, conversions, and groups.
     *
     * <p>Triggers the {@code Initializing → Live} transition. Equivalent to calling
     * {@code config.Commit()} in SQL Race after adding all channels and parameters.
     *
     * @param parameters   parameter definitions to register
     * @param channels     data channels backing those parameters
     * @param conversions  rational conversion functions
     */
    record Configure(
            List<SqlRaceParameter> parameters,
            List<SqlRaceChannel> channels,
            List<RationalConversion> conversions)
            implements SqlRaceSessionEvent {}

    /**
     * Append a completed lap to the session.
     *
     * <p>Fires while in {@code Live} state; keeps state, appends to laps list.
     * OTP: {@code {keep_state, Data#{laps := [Lap | Laps]}}}.
     *
     * @param lap the lap to append
     */
    record AddLap(SqlRaceLap lap) implements SqlRaceSessionEvent {}

    /**
     * Attach a metadata item to the session (circuit, driver, tyre compound, etc.).
     *
     * @param item key-value metadata
     */
    record AddDataItem(SqlRaceSessionDataItem item) implements SqlRaceSessionEvent {}

    /**
     * Notification from the SQL Race archive thread that all ring-buffer data has been flushed to
     * persistent storage.
     *
     * <p>Triggers {@code Live → Closing} transition.
     */
    record SessionSaved() implements SqlRaceSessionEvent {}

    /**
     * Close the session permanently.
     *
     * <p>If in {@code Closing}: transitions to {@code Closed}.
     * If in any other state: {@code Transition.stop("session closed")} — the gen_statem process
     * terminates.
     */
    record Close() implements SqlRaceSessionEvent {}
}
