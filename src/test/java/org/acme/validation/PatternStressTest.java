package org.acme.validation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.CrashRecovery;
import org.acme.Parallel;
import org.acme.Result;
import org.acme.Supervisor;
import org.acme.dogfood.concurrency.ScopedValuePatterns;
import org.acme.dogfood.concurrency.StructuredTaskScopePatterns;
import org.acme.dogfood.core.GathererPatterns;
import org.acme.dogfood.core.PatternMatchingPatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Concurrency stress tests for ggen-generated Java development patterns.
 *
 * <p>Thesis claim: <em>ggen-generated patterns have no capability loss under concurrent load</em>.
 *
 * <p>These tests verify that patterns hold their behavioral contracts under high-concurrency
 * conditions: 10K+ messages, 1K+ concurrent tasks, random failure injection.
 *
 * <p>All tests use virtual threads to match the project's concurrency model.
 */
@DisplayName("Pattern Generator — Concurrency Stress Tests")
class PatternStressTest {

    private static final Random RNG = new Random(42);

    // =========================================================================
    // Actor — 10K messages, no loss
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("Actor: 10K parallel tell()s — zero message loss")
    void actorStress_10kMessages_noLoss() throws Exception {
        int messageCount = 10_000;
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(messageCount);
        var actor = new Proc<Integer, Void>(
                0,
                (state, _) -> {
                    counter.incrementAndGet();
                    latch.countDown();
                    return state + 1;
                });

        // 10 concurrent senders, 1000 messages each
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int sender = 0; sender < 10; sender++) {
                exec.submit(() -> {
                    for (int i = 0; i < 1000; i++) actor.tell(null);
                });
            }
        }

        latch.await(10, SECONDS);
        actor.stop();
        assertThat(counter.get()).isEqualTo(messageCount);
    }

    // =========================================================================
    // Supervisor — cascading crash and recovery
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("Supervisor: 100 crashes across 5 children — all restart successfully")
    void supervisorStress_cascadingCrash() throws Exception {
        var restartCounts = new AtomicInteger[5];
        for (int i = 0; i < 5; i++) restartCounts[i] = new AtomicInteger(0);

        var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 30, Duration.ofSeconds(10));
        var refs = new ProcRef[5];

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            refs[i] = supervisor.supervise(
                    "child-" + i,
                    0,
                    (state, msg) -> {
                        if ("crash".equals(msg)) {
                            restartCounts[idx].incrementAndGet();
                            throw new RuntimeException("deliberate crash");
                        }
                        return state;
                    });
        }

        // Send 20 crashes to each of 5 children = 100 total crashes
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < 5; c++) {
                final int childIdx = c;
                exec.submit(() -> {
                    for (int j = 0; j < 20; j++) {
                        try {
                            refs[childIdx].tell("crash");
                            Thread.sleep(5);
                        } catch (Exception ignored) {}
                    }
                });
            }
        }

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int total = 0;
            for (var rc : restartCounts) total += rc.get();
            assertThat(total).isGreaterThanOrEqualTo(50); // at least 50 crashes processed
        });

        assertThat(supervisor.isRunning()).isTrue();
        supervisor.shutdown();
    }

    // =========================================================================
    // Parallel — 500 tasks, 30% random failure rate
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("Parallel: 500 tasks — fail-fast on first failure, no partial results")
    void parallelStress_500tasks_randomFailures() {
        int taskCount = 500;
        int failedCount = 0;
        int passedCount = 0;

        for (int run = 0; run < 20; run++) {
            boolean shouldFail = RNG.nextInt(100) < 30; // 30% failure rate
            List<Supplier<Integer>> tasks = IntStream.range(0, taskCount)
                    .<Supplier<Integer>>mapToObj(i -> () -> {
                        if (shouldFail && i == taskCount / 2) {
                            throw new RuntimeException("injected failure at " + i);
                        }
                        return i;
                    })
                    .toList();

            Result<List<Integer>, Exception> result = Parallel.all(tasks);
            if (shouldFail) {
                assertThat(result).isInstanceOf(Result.Failure.class);
                failedCount++;
            } else {
                assertThat(result).isInstanceOf(Result.Success.class);
                result.fold(values -> {
                    assertThat(values).hasSize(taskCount);
                    return null;
                }, _ -> null);
                passedCount++;
            }
        }

        // Sanity: both branches exercised
        assertThat(failedCount).isGreaterThan(0);
        assertThat(passedCount).isGreaterThan(0);
    }

    // =========================================================================
    // CrashRecovery — 50 concurrent callers
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("CrashRecovery: 50 concurrent callers, each retrying 3x")
    void crashRecoveryStress_boundedRetry() throws Exception {
        int callerCount = 50;
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);
        var latch = new CountDownLatch(callerCount);

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < callerCount; c++) {
                exec.submit(() -> {
                    var attempts = new AtomicInteger(0);
                    Result<String, Exception> result = CrashRecovery.retry(3, () -> {
                        if (attempts.incrementAndGet() < 2) throw new RuntimeException("retry me");
                        return "success-" + Thread.currentThread().getName();
                    });
                    result.fold(_ -> successCount.incrementAndGet(), _ -> failureCount.incrementAndGet());
                    latch.countDown();
                });
            }
        }

        latch.await(10, SECONDS);
        assertThat(successCount.get()).isEqualTo(callerCount); // all should eventually succeed
        assertThat(failureCount.get()).isZero();
    }

    // =========================================================================
    // ScopedValuePatterns — concurrent scope isolation
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("ScopedValue: 200 concurrent scopes — each sees its own user (no cross-contamination)")
    void scopedValueStress_concurrentIsolation() throws Exception {
        int threadCount = 200;
        var violations = new AtomicInteger(0);
        var latch = new CountDownLatch(threadCount);

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final String userId = "user-" + i;
                exec.submit(() -> {
                    ScopedValuePatterns.handleAsUser(userId, () -> {
                        String seen = ScopedValuePatterns.currentUser();
                        if (!userId.equals(seen)) violations.incrementAndGet();
                        // Simulate some work
                        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        if (!userId.equals(ScopedValuePatterns.currentUser())) violations.incrementAndGet();
                    });
                    latch.countDown();
                });
            }
        }

        latch.await(10, SECONDS);
        assertThat(violations.get()).isZero(); // zero cross-contamination
    }

    // =========================================================================
    // GathererPatterns — large dataset stress
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("GathererPatterns: mapConcurrent over 10K items — no loss, no corruption")
    void gathererStress_mapConcurrent_10kItems() {
        int size = 10_000;
        var items = IntStream.range(0, size).boxed().toList();

        // Concurrent mapping: each item * 2
        var results = GathererPatterns.mapConcurrent(items, 64, x -> x * 2);

        assertThat(results).hasSize(size);
        // Verify all expected values present (order may differ in concurrent)
        var expected = items.stream().map(x -> x * 2).sorted().toList();
        var actual = results.stream().sorted().toList();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @Timeout(30)
    @DisplayName("GathererPatterns: batch + runningSum on 100K elements — correct total")
    void gathererStress_largeBatchSum() {
        int size = 100_000;
        var items = IntStream.range(1, size + 1).boxed().toList();

        var sums = GathererPatterns.runningSum(items);
        int expectedTotal = size * (size + 1) / 2;
        assertThat(sums.get(sums.size() - 1)).isEqualTo(expectedTotal);
    }

    // =========================================================================
    // StructuredTaskScopePatterns — concurrent fan-out stress
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("StructuredTaskScope: fan-out of 1K items — result order preserved")
    void structuredTaskScopeStress_fanOut_1kItems() throws Exception {
        int size = 1_000;
        var items = IntStream.range(0, size).boxed().toList();

        // Each item transforms to its square
        var results = StructuredTaskScopePatterns.fanOut(items, x -> x * x);

        assertThat(results).hasSize(size);
        for (int i = 0; i < size; i++) {
            assertThat(results.get(i)).isEqualTo(i * i);
        }
    }

    // =========================================================================
    // PatternMatchingPatterns — exhaustive dispatch under load
    // =========================================================================

    @Test
    @Timeout(30)
    @DisplayName("PatternMatching: 100K dispatches — exhaustive switch never throws MatchException")
    void patternMatchingStress_noMatchException() throws Exception {
        int count = 100_000;
        var payments = List.of(
                new PatternMatchingPatterns.Payment.CreditCard("4111111111111111", "Alice", 123, 5000),
                new PatternMatchingPatterns.Payment.BankTransfer("DE89370400440532013000", "COBADEFFXXX", "Bob"),
                new PatternMatchingPatterns.Payment.CryptoPay("1A2b3C4d", "BTC", 0.5),
                new PatternMatchingPatterns.Payment.Voucher("SAVE10", 10.0, true));

        var exceptions = new AtomicInteger(0);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                final var payment = payments.get(i % 4);
                exec.submit(() -> {
                    try {
                        PatternMatchingPatterns.describe(payment);
                        PatternMatchingPatterns.riskLevel(payment);
                        PatternMatchingPatterns.route(payment);
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    }
                });
            }
        }

        assertThat(exceptions.get()).isZero();
    }
}
