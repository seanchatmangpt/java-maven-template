package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Lap record — mirrors {@code MESL.SqlRace.Domain.Lap} / {@code ILap}.
 *
 * <p>In ATLAS, a lap is a named time segment within a session. The {@link #startTimeNs()} marks
 * the moment the lap counter was triggered (beacon, GPS crossing, or manual). Laps are appended
 * to a session during live acquisition and are immutable once committed.
 *
 * <p>Real SQL Race C# original:
 *
 * <pre>{@code
 * var lap = new Lap(startTime, number, triggerSource, name, countForFastestLap);
 * session.Laps.Add(lap);
 * }</pre>
 *
 * <p>F1 lap taxonomy:
 *
 * <ul>
 *   <li>Lap 0: the <em>out-lap</em> from pit lane — {@code countForFastestLap = false}
 *   <li>Lap 1..N: flying laps — {@code countForFastestLap = true}
 *   <li>Final lap: the <em>in-lap</em> back to pit lane — {@code countForFastestLap = false}
 * </ul>
 *
 * @param startTimeNs       lap start in nanoseconds since session epoch (GPS-synchronised)
 * @param number            1-based lap counter; lap 0 is the pit-exit out-lap
 * @param triggerSource     source that fired the lap trigger (see constants below)
 * @param name              descriptive label (e.g. {@code "Outlap"}, {@code "Lap 12"},
 *                          {@code "In Lap"})
 * @param countForFastestLap whether this lap counts toward fastest-lap statistics; {@code false}
 *                          for out-laps and in-laps
 */
public record SqlRaceLap(
        long startTimeNs,
        int number,
        byte triggerSource,
        String name,
        boolean countForFastestLap) {

    /** Trigger source: unknown / software-generated. */
    public static final byte TRIGGER_UNKNOWN = 0;

    /** Trigger source: GPS crossing of the start/finish line. */
    public static final byte TRIGGER_GPS = 1;

    /** Trigger source: optical beacon at pit lane entry/exit. */
    public static final byte TRIGGER_BEACON = 2;

    /** Trigger source: engineer manual trigger via ATLAS data server console. */
    public static final byte TRIGGER_MANUAL = 3;

    /**
     * Compact constructor: validates that the lap number is non-negative.
     *
     * @throws IllegalArgumentException if {@code number} is negative
     */
    public SqlRaceLap {
        if (number < 0) {
            throw new IllegalArgumentException("Lap number must be ≥ 0, got: " + number);
        }
    }

    /**
     * Factory for a pit-exit out-lap (lap 0).
     *
     * @param startTimeNs when the car left the pit lane
     * @return out-lap record
     */
    public static SqlRaceLap outLap(long startTimeNs) {
        return new SqlRaceLap(startTimeNs, 0, TRIGGER_BEACON, "Outlap", false);
    }

    /**
     * Factory for a timed flying lap.
     *
     * @param startTimeNs when the car crossed the start/finish line
     * @param number      lap number (≥ 1)
     * @return flying lap record
     */
    public static SqlRaceLap flyingLap(long startTimeNs, int number) {
        return new SqlRaceLap(startTimeNs, number, TRIGGER_GPS, "Lap " + number, true);
    }

    /**
     * Factory for the pit-entry in-lap.
     *
     * @param startTimeNs when the final lap started (usually same as last flying lap's trigger)
     * @param number      in-lap number
     * @return in-lap record
     */
    public static SqlRaceLap inLap(long startTimeNs, int number) {
        return new SqlRaceLap(startTimeNs, number, TRIGGER_GPS, "In Lap", false);
    }

    /**
     * Whether this is the initial out-lap from the pit lane.
     *
     * @return {@code true} when {@link #number()} == 0
     */
    public boolean isOutLap() {
        return number == 0;
    }
}
