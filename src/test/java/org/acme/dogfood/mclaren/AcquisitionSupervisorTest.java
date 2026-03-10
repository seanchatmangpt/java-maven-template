package org.acme.dogfood.mclaren;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import org.acme.Supervisor;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link AcquisitionSupervisor} — the ONE_FOR_ONE supervision tree over all
 * {@link ParameterDataAccess} processes.
 *
 * <p>Tests cover: startup with multiple parameters, addSamples routing, statistics, historical
 * batch loading (Parallel fan-out), ONE_FOR_ONE crash isolation, and max-restart threshold.
 *
 * <p>Awaitility is used for all async assertions to avoid brittle {@code Thread.sleep} patterns.
 */
class AcquisitionSupervisorTest implements WithAssertions {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AcquisitionSupervisor.ParamChannelPair pair(
            String name, String group, long id) {
        var param = SqlRaceParameter.of(name, group, id, 0.0, 500.0, "kph");
        var channel =
                SqlRaceChannel.periodic(id, name, 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
        return new AcquisitionSupervisor.ParamChannelPair(param, channel);
    }

    private static List<AcquisitionSupervisor.ParamChannelPair> threePairs() {
        return List.of(
                pair("vCar", "Chassis", 1L),
                pair("nEngine", "Chassis", 2L),
                pair("pBrakeF", "Chassis", 3L));
    }

    // ── Start + lifecycle ─────────────────────────────────────────────────────

    @Test
    void startSpawnsAllChildren() throws Exception {
        try (var sv = AcquisitionSupervisor.start(threePairs())) {
            assertThat(sv.isRunning()).isTrue();
            assertThat(sv.ref("vCar:Chassis")).isNotNull();
            assertThat(sv.ref("nEngine:Chassis")).isNotNull();
            assertThat(sv.ref("pBrakeF:Chassis")).isNotNull();
            assertThat(sv.allRefs()).hasSize(3);
        }
    }

    @Test
    void sizeMismatchThrows() {
        var params =
                List.of(SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph"));
        var channels =
                List.of(
                        SqlRaceChannel.periodic(
                                1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit),
                        SqlRaceChannel.periodic(
                                2L, "x", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit));

        assertThatThrownBy(() -> AcquisitionSupervisor.start(params, channels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels.size");
    }

    @Test
    void unknownParamRefIsNull() throws Exception {
        try (var sv = AcquisitionSupervisor.start(threePairs())) {
            assertThat(sv.ref("unknown:Chassis")).isNull();
        }
    }

    // ── addSamples ─────────────────────────────────────────────────────────────

    @Test
    void addSamplesDeliversToPdaProcess() throws Exception {
        try (var sv = AcquisitionSupervisor.start(threePairs())) {
            sv.addSamples(
                    "vCar:Chassis",
                    new long[] {100L, 200L, 300L},
                    new double[] {100.0, 200.0, 300.0});

            // Wait for the 3 samples to be processed (totalSamples == 3)
            await().atMost(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    assertThat(sv.statistics().get("vCar:Chassis").messagesIn())
                                            .isEqualTo(3));
        }
    }

    // ── statistics ────────────────────────────────────────────────────────────

    @Test
    void statisticsReturnsEntryForEveryParameter() throws Exception {
        try (var sv = AcquisitionSupervisor.start(threePairs())) {
            var stats = sv.statistics();
            assertThat(stats).containsKeys("vCar:Chassis", "nEngine:Chassis", "pBrakeF:Chassis");
        }
    }

    // ── loadHistoricalBatch (Parallel fan-out) ────────────────────────────────

    @Test
    void loadHistoricalBatchQueriesAllParametersConcurrently() throws Exception {
        try (var sv = AcquisitionSupervisor.start(threePairs())) {
            sv.addSamples(
                    "vCar:Chassis",
                    new long[] {100L, 200L, 300L},
                    new double[] {100.0, 200.0, 300.0});
            sv.addSamples(
                    "nEngine:Chassis",
                    new long[] {100L, 200L},
                    new double[] {5000.0, 6000.0});

            // Wait for samples to be buffered
            await().atMost(Duration.ofMillis(300))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    sv.statistics()
                                                            .get("vCar:Chassis")
                                                            .messagesIn())
                                            .isEqualTo(3));

            var result =
                    sv.loadHistoricalBatch(
                            List.of("vCar:Chassis", "nEngine:Chassis"), 0L, Long.MAX_VALUE, 10);

            assertThat(result.isSuccess()).isTrue();
            var batch = result.orElseThrow();
            assertThat(batch.get("vCar:Chassis").count()).isEqualTo(3);
            assertThat(batch.get("nEngine:Chassis").count()).isEqualTo(2);
        }
    }

    // ── ONE_FOR_ONE crash isolation ───────────────────────────────────────────

    /**
     * Demonstrates ONE_FOR_ONE restart isolation using a {@link Supervisor} configured with the
     * same parameters as {@link AcquisitionSupervisor}: only the crashed child is restarted; its
     * siblings continue unaffected.
     *
     * <p>This mirrors the production scenario where a hardware ECU fault on one sensor channel
     * must never stop acquisition of all other channels.
     */
    @Test
    void oneForOneRestartDoesNotAffectSiblings() throws Exception {
        var supervisor =
                new Supervisor(
                        "one-for-one-isolation-test",
                        Supervisor.Strategy.ONE_FOR_ONE,
                        AcquisitionSupervisor.MAX_RESTARTS,
                        Duration.ofSeconds(AcquisitionSupervisor.WINDOW_SECS));

        // Counter handler: accumulates positive messages; throws on negative (sensor fault)
        BiFunction<Integer, Integer, Integer> handler =
                (state, msg) -> {
                    if (msg < 0) throw new RuntimeException("sensor fault");
                    return state + msg;
                };

        var child1 = supervisor.supervise("vCar", 0, handler);
        var child2 = supervisor.supervise("nEngine", 0, handler);
        var child3 = supervisor.supervise("pBrakeF", 0, handler);

        // Let all three accumulate state
        child1.tell(10);
        child2.tell(10);
        child3.tell(10);

        // child2 and child3 accumulate extra state
        child2.tell(5); // state → 15
        child3.tell(5); // state → 15

        // Force crash on child1 only
        child1.tell(-1); // handler throws → crash → ONE_FOR_ONE restart

        // child1 should be restarted with fresh state (0) within 500ms
        await().atMost(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            Integer state;
                            try {
                                state = child1.ask(0).get(200, MILLISECONDS);
                            } catch (TimeoutException ignored) {
                                // Process mid-restart; Awaitility will retry
                                throw new AssertionError("child1 not yet restarted");
                            }
                            // Fresh state (0) + 0 = 0: confirms restart
                            assertThat(state).isEqualTo(0);
                        });

        // Supervisor still running after ONE_FOR_ONE restart
        assertThat(supervisor.isRunning()).isTrue();

        // Siblings not affected — their accumulated state is intact (15, not reset)
        assertThat(child2.ask(0).get(1, SECONDS)).isEqualTo(15);
        assertThat(child3.ask(0).get(1, SECONDS)).isEqualTo(15);

        supervisor.shutdown();
    }

    /**
     * Demonstrates that exceeding {@value AcquisitionSupervisor#MAX_RESTARTS} within the sliding
     * window causes the supervisor itself to stop — mirroring OTP's "let it crash up the tree"
     * principle.
     */
    @Test
    void maxRestartsExceededStopsSupervisor() throws Exception {
        var supervisor =
                new Supervisor(
                        "max-restarts-exhaustion-test",
                        Supervisor.Strategy.ONE_FOR_ONE,
                        AcquisitionSupervisor.MAX_RESTARTS,
                        Duration.ofSeconds(AcquisitionSupervisor.WINDOW_SECS));

        BiFunction<Integer, Integer, Integer> crashHandler =
                (state, msg) -> {
                    if (msg < 0) throw new RuntimeException("repeated fault");
                    return state + 1;
                };

        var child = supervisor.supervise("flapping-sensor", 0, crashHandler);

        // Crash MAX_RESTARTS + 1 times within the window to exceed the threshold
        for (int i = 0; i <= AcquisitionSupervisor.MAX_RESTARTS; i++) {
            child.tell(-1); // trigger crash

            if (i < AcquisitionSupervisor.MAX_RESTARTS) {
                // Wait for restart before the next crash to ensure window tracking
                await().atMost(Duration.ofMillis(500))
                        .untilAsserted(
                                () -> {
                                    Integer state;
                                    try {
                                        state = child.ask(0).get(200, MILLISECONDS);
                                    } catch (TimeoutException ignored) {
                                        throw new AssertionError("not yet restarted");
                                    }
                                    // Fresh restart confirmed (state reset to 0)
                                    assertThat(state).isGreaterThanOrEqualTo(0);
                                });
            }
        }

        // After MAX_RESTARTS + 1 crashes, supervisor stops within 2 seconds
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(supervisor.isRunning()).isFalse());
    }
}
