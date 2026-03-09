package org.acme.dogfood.mclaren;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link AtlasSession} state machine.
 *
 * <p>Exercises every valid transition in the session lifecycle FSM:
 *
 * <pre>
 *  Idle → Acquiring → Processing → Archived (terminal)
 * </pre>
 *
 * <p>Also verifies:
 *
 * <ul>
 *   <li>Sensor timeouts accumulate as warnings without changing state
 *   <li>Unknown events in a given state are silently ignored (keepState)
 *   <li>Archived is a terminal state — subsequent events stop the machine
 * </ul>
 */
class AtlasSessionTest implements WithAssertions {

    private static final List<TelemetryChannel> STANDARD_CHANNELS =
            List.of(TelemetryChannel.SPEED_KPH, TelemetryChannel.RPM, TelemetryChannel.DRS);

    private org.acme.StateMachine<
                    AtlasSession.SessionState,
                    AtlasSession.SessionEvent,
                    AtlasSession.SessionData>
            sm;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (sm != null && sm.isRunning()) {
            sm.stop();
        }
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialStateIsIdle() {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");

        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Idle.class);
        assertThat(sm.data().sessionId()).isEqualTo("GP_Bahrain_2026_FP1");
        assertThat(sm.data().channels()).isEmpty();
        assertThat(sm.data().warnings()).isEmpty();
        assertThat(sm.data().startTime()).isNull();
        assertThat(sm.data().endTime()).isNull();
    }

    // ── Idle → Acquiring ──────────────────────────────────────────────────────

    @Test
    void startAcquisitionTransitionsToAcquiring() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");

        var data = sm.call(new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS))
                .join();

        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Acquiring.class);
        assertThat(data.channels()).isEqualTo(STANDARD_CHANNELS);
        assertThat(data.startTime()).isNotNull();
        assertThat(data.endTime()).isNull();
    }

    @Test
    void unknownEventInIdleIsIgnored() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");

        // StopAcquisition in Idle is unknown — should keepState
        var data = sm.call(new AtlasSession.SessionEvent.StopAcquisition()).join();

        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Idle.class);
        assertThat(data.channels()).isEmpty();
    }

    // ── Acquiring → Processing ────────────────────────────────────────────────

    @Test
    void stopAcquisitionTransitionsToProcessing() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");
        sm.call(new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS)).join();

        var data = sm.call(new AtlasSession.SessionEvent.StopAcquisition()).join();

        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Processing.class);
        assertThat(data.startTime()).isNotNull();
        assertThat(data.endTime()).isNull();
    }

    // ── Sensor timeout accumulates warnings ───────────────────────────────────

    @Test
    void sensorTimeoutAccumulatesWarningWithoutStateChange() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");
        sm.call(new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS)).join();

        sm.call(new AtlasSession.SessionEvent.SensorTimeout("brake_temp_fl")).join();
        var data = sm.call(new AtlasSession.SessionEvent.SensorTimeout("rpm")).join();

        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Acquiring.class);
        assertThat(data.warnings()).containsExactly("brake_temp_fl", "rpm");
    }

    @Test
    void warningsAreRetainedThroughStopAcquisition() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");
        sm.call(new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS)).join();
        sm.call(new AtlasSession.SessionEvent.SensorTimeout("brake_temp_fl")).join();

        var data = sm.call(new AtlasSession.SessionEvent.StopAcquisition()).join();

        assertThat(data.warnings()).containsExactly("brake_temp_fl");
        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Processing.class);
    }

    // ── Processing → Archived ─────────────────────────────────────────────────

    @Test
    void archiveCompleteTransitionsToArchived() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");
        sm.call(new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS)).join();
        sm.call(new AtlasSession.SessionEvent.StopAcquisition()).join();

        var data = sm.call(new AtlasSession.SessionEvent.ArchiveComplete()).join();

        assertThat(sm.state()).isInstanceOf(AtlasSession.SessionState.Archived.class);
        assertThat(data.endTime()).isNotNull();
        assertThat(data.startTime()).isNotNull();
        assertThat(data.startTime()).isBefore(data.endTime());
    }

    // ── Archived is terminal ──────────────────────────────────────────────────

    @Test
    void archivedStateStopsTheMachine() throws Exception {
        sm = AtlasSession.create("GP_Bahrain_2026_FP1");
        sm.call(new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS)).join();
        sm.call(new AtlasSession.SessionEvent.StopAcquisition()).join();
        sm.call(new AtlasSession.SessionEvent.ArchiveComplete()).join();

        // Archived → send another event; machine should be stopped
        assertThat(sm.isRunning()).isFalse();
        assertThat(sm.stopReason()).contains("GP_Bahrain_2026_FP1");
    }

    // ── Direct transition function tests ──────────────────────────────────────

    @Test
    void transitionFunctionIdleStartAcquisition() {
        var data = new AtlasSession.SessionData(
                "TEST", List.of(), null, null, List.of());
        var result = AtlasSession.transition(
                new AtlasSession.SessionState.Idle(),
                new AtlasSession.SessionEvent.StartAcquisition(STANDARD_CHANNELS),
                data);

        assertThat(result)
                .isInstanceOf(org.acme.StateMachine.Transition.NextState.class);
        var ns = (org.acme.StateMachine.Transition.NextState<
                        AtlasSession.SessionState, AtlasSession.SessionData>)
                result;
        assertThat(ns.state()).isInstanceOf(AtlasSession.SessionState.Acquiring.class);
        assertThat(ns.data().channels()).isEqualTo(STANDARD_CHANNELS);
        assertThat(ns.data().startTime()).isNotNull();
    }

    @Test
    void transitionFunctionAcquiringSensorTimeout() {
        var data = new AtlasSession.SessionData(
                "TEST", STANDARD_CHANNELS, java.time.Instant.now(), null, List.of());
        var result = AtlasSession.transition(
                new AtlasSession.SessionState.Acquiring(),
                new AtlasSession.SessionEvent.SensorTimeout("speed_kph"),
                data);

        assertThat(result)
                .isInstanceOf(org.acme.StateMachine.Transition.KeepState.class);
        var ks = (org.acme.StateMachine.Transition.KeepState<
                        AtlasSession.SessionState, AtlasSession.SessionData>)
                result;
        assertThat(ks.data().warnings()).containsExactly("speed_kph");
    }
}
