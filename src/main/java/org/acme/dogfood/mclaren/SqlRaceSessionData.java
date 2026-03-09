package org.acme.dogfood.mclaren;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable session data envelope — the {@code D} type parameter for {@link SqlRaceSession}'s
 * {@code StateMachine}.
 *
 * <p>In OTP's gen_statem, the data ({@code D}) is the mutable payload carried through state
 * transitions. Each transition function receives the current data and returns a new instance —
 * exactly like Erlang's functional update pattern {@code Data#{laps := [NewLap | Data#laps]}}.
 *
 * <p>This Java 26 equivalent uses immutable records with explicit {@code with*()} builder methods
 * (the preview {@code with} expression pattern). All list fields are wrapped via
 * {@code List.copyOf()} to guarantee immutability across transitions.
 *
 * @param key          unique session identity
 * @param identifier   human-readable session name
 * @param startTimeNs  session start (nanoseconds; set on {@link SqlRaceSessionEvent.Configure})
 * @param endTimeNs    session end (nanoseconds; set on {@link SqlRaceSessionEvent.Close})
 * @param laps         ordered list of committed laps
 * @param parameters   parameter definitions committed during configure phase
 * @param channels     data channels committed during configure phase
 * @param conversions  rational conversion functions
 * @param dataItems    key-value session metadata
 * @param sampleCount  rolling count of samples written across all parameters
 */
public record SqlRaceSessionData(
        SqlRaceSessionKey key,
        String identifier,
        long startTimeNs,
        long endTimeNs,
        List<SqlRaceLap> laps,
        List<SqlRaceParameter> parameters,
        List<SqlRaceChannel> channels,
        List<RationalConversion> conversions,
        List<SqlRaceSessionDataItem> dataItems,
        int sampleCount) {

    /**
     * Compact constructor: defensively copies all list fields.
     *
     * <p>This ensures that transitions returning {@code Transition.keepState(data)} cannot
     * accidentally share mutable list references.
     */
    public SqlRaceSessionData {
        laps = List.copyOf(laps);
        parameters = List.copyOf(parameters);
        channels = List.copyOf(channels);
        conversions = List.copyOf(conversions);
        dataItems = List.copyOf(dataItems);
    }

    /**
     * Factory: create a fresh session data envelope for a newly opened session.
     *
     * @param key        session identity
     * @param identifier human-readable name
     * @return empty session data in {@code Initializing} phase
     */
    public static SqlRaceSessionData empty(SqlRaceSessionKey key, String identifier) {
        return new SqlRaceSessionData(
                key, identifier, 0L, 0L,
                List.of(), List.of(), List.of(), List.of(), List.of(), 0);
    }

    /** Return a copy with the start timestamp set. */
    public SqlRaceSessionData withStartTime(long startNs) {
        return new SqlRaceSessionData(key, identifier, startNs, endTimeNs, laps, parameters,
                channels, conversions, dataItems, sampleCount);
    }

    /** Return a copy with the end timestamp set. */
    public SqlRaceSessionData withEndTime(long endNs) {
        return new SqlRaceSessionData(key, identifier, startTimeNs, endNs, laps, parameters,
                channels, conversions, dataItems, sampleCount);
    }

    /** Return a copy with the given lap appended. */
    public SqlRaceSessionData withLap(SqlRaceLap lap) {
        var next = new ArrayList<>(laps);
        next.add(lap);
        return new SqlRaceSessionData(key, identifier, startTimeNs, endTimeNs, next, parameters,
                channels, conversions, dataItems, sampleCount);
    }

    /** Return a copy with configuration committed (parameters, channels, conversions). */
    public SqlRaceSessionData withConfiguration(
            List<SqlRaceParameter> params,
            List<SqlRaceChannel> chans,
            List<RationalConversion> convs) {
        return new SqlRaceSessionData(key, identifier, startTimeNs, endTimeNs, laps, params, chans,
                convs, dataItems, sampleCount);
    }

    /** Return a copy with the given metadata item appended. */
    public SqlRaceSessionData withDataItem(SqlRaceSessionDataItem item) {
        var next = new ArrayList<>(dataItems);
        next.add(item);
        return new SqlRaceSessionData(key, identifier, startTimeNs, endTimeNs, laps, parameters,
                channels, conversions, next, sampleCount);
    }

    /** Return a copy with the sample counter incremented by {@code delta}. */
    public SqlRaceSessionData withSamples(int delta) {
        return new SqlRaceSessionData(key, identifier, startTimeNs, endTimeNs, laps, parameters,
                channels, conversions, dataItems, sampleCount + delta);
    }
}
