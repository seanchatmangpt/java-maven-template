package org.acme.dogfood.mclaren;

import java.util.ArrayList;
import java.util.List;
import org.acme.StateMachine;
import org.acme.StateMachine.Transition;

/**
 * Lap detection state machine — OTP {@code gen_statem} mapped to ATLAS lap trigger logic.
 *
 * <p>In Formula 1 testing, the ATLAS data server detects laps via GPS-synchronised beacon
 * crossings. Lap types follow a strict sequence per stint:
 *
 * <pre>
 *   Pit lane exit
 *       │
 *       ▼
 *   ┌─────────┐  beacon crossed   ┌────────────┐  beacon crossed  ┌───────┐
 *   │ OutLap  │──────────────────▶│ FlyingLap  │─────────────────▶│ InLap │
 *   └─────────┘                   └────────────┘                   └───────┘
 *       ▲                                                               │
 *       └─────────────────── Reset() or next stint ────────────────────┘
 * </pre>
 *
 * <p>Only {@link SqlRaceSessionState} {@code FlyingLap} triggers {@code countForFastestLap=true}.
 * Out-laps and in-laps are recorded but excluded from fastest-lap statistics.
 *
 * <p>OTP equivalence:
 *
 * <ul>
 *   <li>{@link LapState} → {@code gen_statem} state ({@code S})
 *   <li>{@link LapEvent} → gen_statem events ({@code E})
 *   <li>{@link LapData} → gen_statem data ({@code D}) — accumulated laps + lap counter
 * </ul>
 */
public final class LapDetector {

    // ---------------------------------------------------------------------------
    // States
    // ---------------------------------------------------------------------------

    /** Lap detection state — position in the OutLap → FlyingLap → InLap cycle. */
    public sealed interface LapState
            permits LapState.OutLap, LapState.FlyingLap, LapState.InLap {

        /** Pit-exit out-lap: car is on its warm-up lap before the flying lap. */
        record OutLap() implements LapState {}

        /** Flying lap: car is on a timed lap, eligible for fastest-lap statistics. */
        record FlyingLap() implements LapState {}

        /** In-lap: car is returning to pit lane after the final flying lap. */
        record InLap() implements LapState {}
    }

    // ---------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------

    /** Lap trigger events sent to the detector. */
    public sealed interface LapEvent
            permits LapEvent.BeaconCrossed,
                    LapEvent.GpsBeaconCrossed,
                    LapEvent.ManualLapTrigger,
                    LapEvent.Reset {

        /** Optical beacon at pit lane / track boundary was crossed. */
        record BeaconCrossed(long timestampNs) implements LapEvent {}

        /** GPS-synchronised start/finish line crossing with coordinate. */
        record GpsBeaconCrossed(long timestampNs, double latitude, double longitude)
                implements LapEvent {}

        /**
         * Engineer manually triggered a lap marker from the ATLAS data server console.
         *
         * @param name custom label for the lap
         */
        record ManualLapTrigger(long timestampNs, String name) implements LapEvent {}

        /**
         * Reset the detector to {@link LapState.OutLap} (e.g. between stints or after
         * red flag). All accumulated laps are cleared.
         */
        record Reset() implements LapEvent {}
    }

    // ---------------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------------

    /**
     * Immutable lap accumulator — carries the lap counter and completed lap list across
     * transitions.
     *
     * @param lapNumber       current lap number (0 = out-lap, 1+ = flying laps)
     * @param lastBeaconNs    timestamp of the most recent beacon crossing
     * @param completedLaps   all laps committed so far (immutable list)
     */
    public record LapData(int lapNumber, long lastBeaconNs, List<SqlRaceLap> completedLaps) {

        /** Compact constructor: defensively copies the laps list. */
        public LapData {
            completedLaps = List.copyOf(completedLaps);
        }

        /** Initial data for a fresh stint. */
        public static LapData empty() {
            return new LapData(0, 0L, List.of());
        }

        /** Return a copy with the given lap appended and the counter incremented. */
        public LapData withLap(SqlRaceLap lap) {
            var next = new ArrayList<>(completedLaps);
            next.add(lap);
            return new LapData(lapNumber + 1, lap.startTimeNs(), next);
        }

        /** Return a copy with the beacon timestamp updated (without committing a lap). */
        public LapData withBeacon(long beaconNs) {
            return new LapData(lapNumber, beaconNs, completedLaps);
        }
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    private final StateMachine<LapState, LapEvent, LapData> machine;

    private LapDetector(StateMachine<LapState, LapEvent, LapData> machine) {
        this.machine = machine;
    }

    /**
     * Create and start a lap detector for a new stint.
     *
     * <p>Initial state is {@link LapState.OutLap}, lap counter = 0.
     *
     * @return running lap detector
     */
    public static LapDetector start() {
        return new LapDetector(
                new StateMachine<>(new LapState.OutLap(), LapData.empty(), LapDetector::transition));
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Fire a lap event — mirrors {@code gen_statem:cast/2}.
     *
     * @param event beacon crossing or reset event
     */
    public void send(LapEvent event) {
        machine.send(event);
    }

    /**
     * Synchronous lap trigger — mirrors {@code gen_statem:call/3}.
     *
     * @param event lap event
     * @return lap data after the transition
     */
    public LapData call(LapEvent event) {
        return machine.call(event).join();
    }

    /** Current detector state (volatile read). */
    public LapState state() {
        return machine.state();
    }

    /** Current accumulated laps (volatile read). */
    public LapData data() {
        return machine.data();
    }

    /** Whether the detector is still running. */
    public boolean isRunning() {
        return machine.isRunning();
    }

    /**
     * Stop the detector.
     *
     * @throws InterruptedException if interrupted
     */
    public void stop() throws InterruptedException {
        machine.stop();
    }

    // ---------------------------------------------------------------------------
    // gen_statem transition function
    // ---------------------------------------------------------------------------

    private static Transition<LapState, LapData> transition(
            LapState state, LapEvent event, LapData data) {
        return switch (state) {
            case LapState.OutLap ignored -> handleOutLap(event, data);
            case LapState.FlyingLap ignored -> handleFlyingLap(event, data);
            case LapState.InLap ignored -> handleInLap(event, data);
        };
    }

    private static Transition<LapState, LapData> handleOutLap(LapEvent event, LapData data) {
        return switch (event) {
            case LapEvent.BeaconCrossed(var ts) -> {
                // First beacon crossing: commit out-lap, enter flying lap
                var outLap = SqlRaceLap.outLap(data.lastBeaconNs > 0 ? data.lastBeaconNs : ts);
                yield Transition.nextState(new LapState.FlyingLap(), data.withLap(outLap));
            }
            case LapEvent.GpsBeaconCrossed(var ts, var lat, var lon) -> {
                var outLap = SqlRaceLap.outLap(data.lastBeaconNs > 0 ? data.lastBeaconNs : ts);
                yield Transition.nextState(new LapState.FlyingLap(), data.withLap(outLap));
            }
            case LapEvent.ManualLapTrigger(var ts, var name) -> {
                var lap = new SqlRaceLap(ts, 0, SqlRaceLap.TRIGGER_MANUAL, name, false);
                yield Transition.keepState(data.withLap(lap).withBeacon(ts));
            }
            case LapEvent.Reset ignored ->
                Transition.nextState(new LapState.OutLap(), LapData.empty());
        };
    }

    private static Transition<LapState, LapData> handleFlyingLap(LapEvent event, LapData data) {
        return switch (event) {
            case LapEvent.BeaconCrossed(var ts) -> {
                // Second crossing: commit flying lap (countForFastestLap=true)
                int lapNum = data.lapNumber;
                var flyingLap = SqlRaceLap.flyingLap(ts, lapNum);
                yield Transition.nextState(new LapState.InLap(), data.withLap(flyingLap));
            }
            case LapEvent.GpsBeaconCrossed(var ts, var lat, var lon) -> {
                var flyingLap = SqlRaceLap.flyingLap(ts, data.lapNumber);
                yield Transition.nextState(new LapState.InLap(), data.withLap(flyingLap));
            }
            case LapEvent.ManualLapTrigger(var ts, var name) -> {
                var lap = new SqlRaceLap(ts, data.lapNumber, SqlRaceLap.TRIGGER_MANUAL, name, true);
                yield Transition.keepState(data.withLap(lap).withBeacon(ts));
            }
            case LapEvent.Reset ignored ->
                Transition.nextState(new LapState.OutLap(), LapData.empty());
        };
    }

    private static Transition<LapState, LapData> handleInLap(LapEvent event, LapData data) {
        return switch (event) {
            case LapEvent.BeaconCrossed(var ts) -> {
                // In-lap: commit in-lap, cycle back to OutLap for next stint
                var inLap = SqlRaceLap.inLap(ts, data.lapNumber);
                yield Transition.nextState(new LapState.OutLap(), data.withLap(inLap));
            }
            case LapEvent.GpsBeaconCrossed(var ts, var lat, var lon) -> {
                var inLap = SqlRaceLap.inLap(ts, data.lapNumber);
                yield Transition.nextState(new LapState.OutLap(), data.withLap(inLap));
            }
            case LapEvent.ManualLapTrigger(var ts, var name) -> {
                var lap = new SqlRaceLap(ts, data.lapNumber, SqlRaceLap.TRIGGER_MANUAL, name, false);
                yield Transition.keepState(data.withLap(lap).withBeacon(ts));
            }
            case LapEvent.Reset ignored ->
                Transition.nextState(new LapState.OutLap(), LapData.empty());
        };
    }
}
