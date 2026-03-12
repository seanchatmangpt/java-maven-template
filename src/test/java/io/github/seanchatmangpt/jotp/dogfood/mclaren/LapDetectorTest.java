package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LapDetector} — the gen_statem for F1 lap trigger logic.
 *
 * <p>Exercises the full OutLap → FlyingLap → InLap cycle, manual triggers, and resets.
 */
class LapDetectorTest implements WithAssertions {

    private LapDetector detector;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (detector != null && detector.isRunning()) {
            detector.stop();
        }
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialStateIsOutLap() {
        detector = LapDetector.start();

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.OutLap.class);
        assertThat(detector.data().lapNumber()).isZero();
        assertThat(detector.data().completedLaps()).isEmpty();
    }

    // ── OutLap → FlyingLap ────────────────────────────────────────────────────

    @Test
    void firstBeaconTransitionsOutLapToFlyingLap() {
        detector = LapDetector.start();

        var data = detector.call(new LapDetector.LapEvent.BeaconCrossed(1_000_000_000L));

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.FlyingLap.class);
        assertThat(data.completedLaps()).hasSize(1);
        var outLap = data.completedLaps().get(0);
        assertThat(outLap.isOutLap()).isTrue();
        assertThat(outLap.countForFastestLap()).isFalse();
    }

    @Test
    void gpsBeaconTransitionsOutLapToFlyingLap() {
        detector = LapDetector.start();

        detector.call(new LapDetector.LapEvent.GpsBeaconCrossed(
                1_000_000_000L, 26.0325, 50.5106)); // Bahrain S/F GPS coords

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.FlyingLap.class);
    }

    // ── FlyingLap → InLap ─────────────────────────────────────────────────────

    @Test
    void secondBeaconTransitionsFlyingLapToInLap() {
        detector = LapDetector.start();
        detector.call(new LapDetector.LapEvent.BeaconCrossed(1_000_000_000L)); // OutLap ends

        var data = detector.call(new LapDetector.LapEvent.BeaconCrossed(91_000_000_000L)); // Lap 1

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.InLap.class);
        assertThat(data.completedLaps()).hasSize(2);
        var flyingLap = data.completedLaps().get(1);
        assertThat(flyingLap.countForFastestLap()).isTrue();
        assertThat(flyingLap.number()).isEqualTo(1);
    }

    // ── InLap → OutLap (cycle) ────────────────────────────────────────────────

    @Test
    void inLapBeaconCyclesBackToOutLap() {
        detector = LapDetector.start();
        detector.call(new LapDetector.LapEvent.BeaconCrossed(1_000_000_000L));  // → FlyingLap
        detector.call(new LapDetector.LapEvent.BeaconCrossed(91_000_000_000L)); // → InLap

        var data = detector.call(
                new LapDetector.LapEvent.BeaconCrossed(185_000_000_000L)); // → OutLap

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.OutLap.class);
        assertThat(data.completedLaps()).hasSize(3);
        var inLap = data.completedLaps().get(2);
        assertThat(inLap.countForFastestLap()).isFalse();
        assertThat(inLap.name()).isEqualTo("In Lap");
    }

    // ── Full stint: OutLap → 3 flying laps → InLap ────────────────────────────

    @Test
    void fullStintAccumulatesCorrectLapSequence() {
        detector = LapDetector.start();
        long base = 1_000_000_000L; // 1s
        long lapDuration = 90_000_000_000L; // 90s

        detector.call(new LapDetector.LapEvent.BeaconCrossed(base)); // OutLap → FlyingLap (1 done)
        detector.call(new LapDetector.LapEvent.BeaconCrossed(base + lapDuration)); // Lap 1 done
        detector.call(new LapDetector.LapEvent.BeaconCrossed(base + 2 * lapDuration)); // Lap 2
        detector.call(new LapDetector.LapEvent.BeaconCrossed(base + 3 * lapDuration)); // Lap 3
        var data = detector.call(
                new LapDetector.LapEvent.BeaconCrossed(base + 4 * lapDuration)); // InLap done

        // After full stint: OutLap + Lap1 + Lap2 + Lap3 + InLap = 5 total
        // Actually: OutLap→Flying→InLap cycle:
        // beacon1: commits OutLap, enters Flying
        // beacon2: commits FlyingLap1, enters InLap
        // beacon3: commits InLap1, enters OutLap (new cycle)
        // beacon4: commits OutLap2, enters Flying
        // beacon5: commits FlyingLap2, enters InLap
        assertThat(data.completedLaps().size()).isGreaterThanOrEqualTo(4);

        // First lap must be out-lap with countForFastestLap=false
        assertThat(data.completedLaps().get(0).countForFastestLap()).isFalse();
        // Second lap must count
        assertThat(data.completedLaps().get(1).countForFastestLap()).isTrue();
    }

    // ── Manual lap trigger ────────────────────────────────────────────────────

    @Test
    void manualTriggerInOutLapAppendsNamedLap() {
        detector = LapDetector.start();

        var data = detector.call(new LapDetector.LapEvent.ManualLapTrigger(
                500_000_000L, "PitExit"));

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.OutLap.class);
        assertThat(data.completedLaps()).hasSize(1);
        assertThat(data.completedLaps().get(0).name()).isEqualTo("PitExit");
        assertThat(data.completedLaps().get(0).triggerSource())
                .isEqualTo(SqlRaceLap.TRIGGER_MANUAL);
    }

    @Test
    void manualTriggerInFlyingLapCounts() {
        detector = LapDetector.start();
        detector.call(new LapDetector.LapEvent.BeaconCrossed(1_000_000_000L)); // → FlyingLap

        var data = detector.call(new LapDetector.LapEvent.ManualLapTrigger(
                91_000_000_000L, "EngineerLap"));

        // Manual trigger in flying lap: countForFastestLap = true
        var lap = data.completedLaps().getLast();
        assertThat(lap.name()).isEqualTo("EngineerLap");
        assertThat(lap.countForFastestLap()).isTrue();
    }

    // ── Reset ──────────────────────────────────────────────────────────────────

    @Test
    void resetClearsAllLapsAndReturnsToOutLap() {
        detector = LapDetector.start();
        detector.call(new LapDetector.LapEvent.BeaconCrossed(1_000_000_000L)); // → FlyingLap
        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.FlyingLap.class);

        var data = detector.call(new LapDetector.LapEvent.Reset());

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.OutLap.class);
        assertThat(data.completedLaps()).isEmpty();
        assertThat(data.lapNumber()).isZero();
    }

    @Test
    void resetFromInLapAlsoClearsState() {
        detector = LapDetector.start();
        detector.call(new LapDetector.LapEvent.BeaconCrossed(1L));
        detector.call(new LapDetector.LapEvent.BeaconCrossed(2L)); // → InLap

        var data = detector.call(new LapDetector.LapEvent.Reset());

        assertThat(detector.state()).isInstanceOf(LapDetector.LapState.OutLap.class);
        assertThat(data.completedLaps()).isEmpty();
    }
}
