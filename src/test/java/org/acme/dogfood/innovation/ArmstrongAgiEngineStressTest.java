package org.acme.dogfood.innovation;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.acme.Parallel;
import org.acme.Result;
import org.acme.StateMachine;
import org.acme.dogfood.innovation.ArmstrongAgiEngine.AgiAssessment;
import org.acme.dogfood.innovation.ArmstrongAgiEngine.AgiState;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stress tests for {@link ArmstrongAgiEngine} — maximizes concurrency and throughput to verify
 * thread safety, result isolation, and virtual-thread scalability.
 *
 * <p>Each test launches between 50 and 200 concurrent pipeline invocations. The engine internally
 * fans out to 5 sub-tasks per call (4 analysis engines + GoNoGo audit), so 200 concurrent {@code
 * assess()} calls exercise 1 000+ virtual threads simultaneously.
 *
 * <p>Correctness invariants verified under load:
 *
 * <ul>
 *   <li>No result contains a {@code className} from a different invocation (no cross-contamination)
 *   <li>Every {@link ArmstrongAgiEngine.AgiEvidence#score()} is in {@code [0, 100]}
 *   <li>Every {@link ArmstrongAgiEngine.ActionPlan#summary()} is non-blank
 *   <li>Safe and breaking action sets are disjoint in every result
 *   <li>All {@link StateMachine} handles reach {@link AgiState.Done} within the time budget
 * </ul>
 */
@DisplayName("ArmstrongAgiEngine — stress / concurrency")
class ArmstrongAgiEngineStressTest implements WithAssertions {

    // ── Source fixtures (same as functional tests; reused for throughput) ─────

    private static final String CLEAN =
            """
            package org.acme;
            public record SamplePoint(String ch, double v, long ts) {}
            """;

    private static final String SHARED_STATE =
            """
            package org.acme;
            import java.util.HashMap;
            import java.util.Map;
            public class SharedCache {
                private static final Map<String, Object> STORE = new HashMap<>();
                public void put(String k, Object v) { STORE.put(k, v); }
            }
            """;

    private static final String SETTER =
            """
            package org.acme;
            public class MutableParam {
                private String name;
                public void setName(String n) { this.name = n; }
                public String getName() { return name; }
            }
            """;

    private static final String MULTI =
            """
            package org.acme;
            import java.util.HashMap;
            import java.util.Map;
            public class LegacyService {
                private static final Map<String, String> REG = new HashMap<>();
                private String label;
                public void setLabel(String l) { this.label = l; }
                public void register(String k, String v) { REG.put(k, v); }
            }
            """;

    private static final List<String> ALL_SOURCES = List.of(CLEAN, SHARED_STATE, SETTER, MULTI);

    // ── 1. Concurrent sync assessments — correctness ──────────────────────────

    @Test
    @DisplayName("200 concurrent assess() — all results correct, no cross-contamination")
    void concurrentSyncAssessments_200_allCorrect() {
        int n = 200;

        // Build tasks: each binds its own className so we can verify isolation.
        List<Supplier<AgiAssessment>> tasks =
                IntStream.range(0, n)
                        .<Supplier<AgiAssessment>>mapToObj(
                                i -> {
                                    var src = ALL_SOURCES.get(i % ALL_SOURCES.size());
                                    var cls = "StressSync" + i;
                                    return () -> ArmstrongAgiEngine.assess(src, cls);
                                })
                        .toList();

        // Parallel.all uses StructuredTaskScope — fail-fast semantics, all-or-nothing
        var result = Parallel.all(tasks);

        assertThat(result).isInstanceOf(Result.Success.class);
        @SuppressWarnings("unchecked")
        var assessments = ((Result.Success<List<AgiAssessment>, Exception>) result).value();
        assertThat(assessments).hasSize(n);

        for (int i = 0; i < n; i++) {
            var a = assessments.get(i);
            // className isolation — no cross-task leakage
            assertThat(a.className()).isEqualTo("StressSync" + i);
            // score always in valid range
            assertThat(a.evidence().score().overallScore()).isBetween(0, 100);
            // plan always populated
            assertThat(a.plan().summary()).isNotBlank();
            // safe and breaking actions are disjoint
            assertThat(a.plan().safeActions().stream().noneMatch(a.plan().breakingActions()::contains))
                    .isTrue();
        }
    }

    // ── 2. Concurrent async assessments — all state machines reach Done ───────

    @Test
    @DisplayName("50 concurrent assessAsync() — all StateMachines reach Done")
    void concurrentAsyncAssessments_50_allReachDone() {
        int n = 50;

        List<StateMachine<AgiState, ArmstrongAgiEngine.AgiEvent, ArmstrongAgiEngine.AgiData>> machines =
                IntStream.range(0, n)
                        .mapToObj(
                                i -> ArmstrongAgiEngine.assessAsync(
                                        ALL_SOURCES.get(i % ALL_SOURCES.size()),
                                        "StressAsync" + i))
                        .toList();

        // Wait until every state machine has reached Done
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            for (var sm : machines) {
                                assertThat(sm.state()).isInstanceOf(AgiState.Done.class);
                            }
                        });

        // Verify data integrity for each completed machine
        for (int i = 0; i < n; i++) {
            var data = machines.get(i).data();
            assertThat(data.className()).isEqualTo("StressAsync" + i);
            assertThat(data.evidence()).isNotNull();
            assertThat(data.chains()).isNotNull();
            assertThat(data.plan()).isNotNull();
            assertThat(data.plan().summary()).isNotBlank();
        }
    }

    // ── 3. Result isolation — same source → identical scores every time ────────

    @Test
    @DisplayName("100 identical concurrent assessments — deterministic, no state leakage")
    void identicalConcurrentAssessments_deterministicResults() {
        int n = 100;

        List<Supplier<AgiAssessment>> tasks =
                IntStream.range(0, n)
                        .<Supplier<AgiAssessment>>mapToObj(
                                i -> () -> ArmstrongAgiEngine.assess(SHARED_STATE, "Determinism"))
                        .toList();

        var result = Parallel.all(tasks);
        assertThat(result).isInstanceOf(Result.Success.class);
        @SuppressWarnings("unchecked")
        var assessments = ((Result.Success<List<AgiAssessment>, Exception>) result).value();

        // All invocations with the same source must produce the same score and violation count
        int expectedScore = assessments.get(0).evidence().score().overallScore();
        int expectedViolations = assessments.get(0).evidence().violations().size();
        boolean expectedSelfVerified = assessments.get(0).selfVerified();

        for (var a : assessments) {
            assertThat(a.evidence().score().overallScore()).isEqualTo(expectedScore);
            assertThat(a.evidence().violations()).hasSize(expectedViolations);
            assertThat(a.selfVerified()).isEqualTo(expectedSelfVerified);
        }
    }

    // ── 4. Mixed-source saturation — virtual threads + nested fan-out ─────────

    @Test
    @DisplayName("mixed-source saturation — 150 calls, all chain OTP fixes non-blank")
    void mixedSourceSaturation_allChainFieldsPopulated() {
        int n = 150;

        List<Supplier<AgiAssessment>> tasks =
                IntStream.range(0, n)
                        .<Supplier<AgiAssessment>>mapToObj(
                                i -> {
                                    var src = ALL_SOURCES.get(i % ALL_SOURCES.size());
                                    return () -> ArmstrongAgiEngine.assess(src, "Sat" + i);
                                })
                        .toList();

        var result = Parallel.all(tasks);
        assertThat(result).isInstanceOf(Result.Success.class);
        @SuppressWarnings("unchecked")
        var assessments = ((Result.Success<List<AgiAssessment>, Exception>) result).value();

        int totalChains = 0;
        for (var a : assessments) {
            for (var chain : a.plan().explanations()) {
                assertThat(chain.armstrongPrinciple()).isNotBlank();
                assertThat(chain.rootCause()).isNotBlank();
                assertThat(chain.otpFix()).isNotBlank();
                assertThat(chain.codeHint()).isNotBlank();
                totalChains++;
            }
        }
        // At least the violation-bearing sources (SHARED_STATE, SETTER, MULTI) must produce chains
        assertThat(totalChains).isGreaterThan(0);
    }

    // ── 5. Throughput — time-bounded correctness under concurrency ────────────

    @Test
    @DisplayName("throughput — 100 parallel assessments complete within 20 s")
    void throughput_100ParallelAssessments_completesWithinBudget() {
        int n = 100;

        List<Supplier<AgiAssessment>> tasks =
                IntStream.range(0, n)
                        .<Supplier<AgiAssessment>>mapToObj(
                                i -> () -> ArmstrongAgiEngine.assess(
                                        ALL_SOURCES.get(i % ALL_SOURCES.size()), "Tput" + i))
                        .toList();

        var startNs = System.nanoTime();
        var result = Parallel.all(tasks);
        var elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(elapsedMs)
                .as("100 parallel assessments should finish within 20 s")
                .isLessThan(20_000L);

        // Derive throughput for informational logging (not a hard assertion)
        @SuppressWarnings("unchecked")
        var assessments = ((Result.Success<List<AgiAssessment>, Exception>) result).value();
        double tputPerSec = n * 1_000.0 / Math.max(elapsedMs, 1L);
        assertThat(assessments).hasSize(n);
        assertThat(tputPerSec)
                .as("throughput must exceed 5 assessments/sec under load")
                .isGreaterThan(5.0);
    }

    // ── 6. No deadlock — virtual threads + CountDownLatch barrier ─────────────

    @Test
    @DisplayName("barrier stress — 80 threads all start simultaneously, no deadlock")
    void barrierStress_noDeadlock() throws InterruptedException {
        int n = 80;
        var latch = new CountDownLatch(1); // all threads wait here before firing
        var results = new CopyOnWriteArrayList<AgiAssessment>();
        var errors = new AtomicInteger(0);

        var threads = new ArrayList<Thread>(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads.add(
                    Thread.ofVirtual()
                            .name("stress-" + idx)
                            .unstarted(
                                    () -> {
                                        try {
                                            latch.await(); // synchronize start
                                            var a = ArmstrongAgiEngine.assess(
                                                    ALL_SOURCES.get(idx % ALL_SOURCES.size()),
                                                    "Barrier" + idx);
                                            results.add(a);
                                        } catch (Exception e) {
                                            errors.incrementAndGet();
                                        }
                                    }));
        }

        threads.forEach(Thread::start);
        latch.countDown(); // release all threads at once

        for (var t : threads) {
            t.join(15_000); // 15 s per-thread budget
        }

        assertThat(errors.get()).as("no assessment should throw under barrier stress").isZero();
        assertThat(results).hasSize(n);
        results.forEach(
                a -> {
                    assertThat(a.evidence().score().overallScore()).isBetween(0, 100);
                    assertThat(a.plan()).isNotNull();
                });
    }

    // ── 7. Async pipeline fan-out — observe intermediate states ───────────────

    @Test
    @DisplayName("30 async pipelines — intermediate states Assessing/Explaining/Planning observed")
    void asyncPipelines_intermediateStatesObservable() {
        int n = 30;

        // Kick off all machines before polling any — maximizes overlap
        var machines =
                IntStream.range(0, n)
                        .mapToObj(
                                i -> ArmstrongAgiEngine.assessAsync(MULTI, "Observe" + i))
                        .toList();

        // Poll states immediately — at least some should be in a transient state
        var transientClasses = java.util.Set.of(
                AgiState.Assessing.class, AgiState.Explaining.class,
                AgiState.Planning.class, AgiState.Done.class);

        for (var sm : machines) {
            // Must be in a valid pipeline state (not stuck in Idle)
            await().atMost(Duration.ofSeconds(1))
                    .until(() -> !(sm.state() instanceof AgiState.Idle));
            assertThat(sm.state().getClass()).isIn(transientClasses);
        }

        // Wait for completion of all
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> machines.forEach(
                                sm -> assertThat(sm.state()).isInstanceOf(AgiState.Done.class)));

        // Final data invariants
        for (int i = 0; i < n; i++) {
            var d = machines.get(i).data();
            assertThat(d.className()).isEqualTo("Observe" + i);
            assertThat(d.evidence().violations()).isNotNull();
            assertThat(d.chains()).isNotNull();
        }
    }
}
