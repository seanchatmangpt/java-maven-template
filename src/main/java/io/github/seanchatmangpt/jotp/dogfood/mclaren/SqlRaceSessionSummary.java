package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Session summary — mirrors {@code MESL.SqlRace.Domain.SessionSummary} / {@code ISessionSummary}.
 *
 * <p>In ATLAS, {@code SessionManager} returns a {@code SessionSummary} for each session found in
 * a database or file store. The Telemetry Analytics API (TAPI) uses summaries for search and
 * aggregation queries without loading the full session.
 *
 * <p>Factory: {@link SqlRaceSessionData#toSummary()}.
 *
 * @param key            session identity
 * @param identifier     human-readable name
 * @param startTimeNs    session start (nanoseconds since Unix epoch)
 * @param endTimeNs      session end (0 if session is still live)
 * @param state          current lifecycle state
 * @param lapCount       number of committed laps
 * @param parameterCount number of configured parameters
 */
public record SqlRaceSessionSummary(
        SqlRaceSessionKey key,
        String identifier,
        long startTimeNs,
        long endTimeNs,
        SqlRaceSessionState state,
        int lapCount,
        int parameterCount) {

    /**
     * Derive a summary from the current session data envelope.
     *
     * @param data  current session data
     * @param state current lifecycle state
     * @return summary suitable for Telemetry Analytics API responses
     */
    public static SqlRaceSessionSummary from(SqlRaceSessionData data, SqlRaceSessionState state) {
        return new SqlRaceSessionSummary(
                data.key(),
                data.identifier(),
                data.startTimeNs(),
                data.endTimeNs(),
                state,
                data.laps().size(),
                data.parameters().size());
    }

    /** Whether the session is still accepting live data. */
    public boolean isLive() {
        return state instanceof SqlRaceSessionState.Live;
    }

    /** Session duration in nanoseconds, or 0 if the session has not ended. */
    public long durationNs() {
        return endTimeNs > 0 ? endTimeNs - startTimeNs : 0;
    }
}
