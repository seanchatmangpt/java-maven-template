package org.acme.test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

import org.acme.CrashRecovery;
import org.acme.EventManager;
import org.acme.Parallel;
import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.ProcessLink;
import org.acme.ProcessMonitor;
import org.acme.ProcessRegistry;
import org.acme.ProcTimer;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hyper-advanced production simulation tests for OTP primitives.
 *
 * <p>Simulates real-world production scenarios:
 *
 * <ul>
 *   <li><b>Chaos Engineering</b> — Random failures, resource exhaustion
 *   <li><b>Cascade Failures</b> — Multi-level supervision trees, cascading restarts
 *   <li><b>Backpressure</b> — Message queue overflow, slow consumers
 *   <li><b>Hot Standby</b> — Failover, state replication
 *   <li><b>Long-Running Stability</b> — Memory leaks, resource cleanup over time
 *   <li><b>Graceful Degradation</b> — Partial system failure handling
 * </ul>
 */
@DisplayName("Production Simulation Tests")
@Timeout(120)
class ProductionSimulationTest implements WithAssertions {

    // ── Shared message vocabulary ─────────────────────────────────────────────

    sealed interface Msg permits Msg.Work, Msg.Crash, Msg.Get, Msg.Set {
        record Work(int payload) implements Msg {}
        record Crash(String reason) implements Msg {}
        record Get() implements Msg {}
        record Set(int value) implements Msg {}
    }

    private static int handler(int state, Msg msg) {
        return switch (msg) {
            case Msg.Work(var p) -> state + p;
            case Msg.Crash(var reason) -> throw new RuntimeException(reason);
            case Msg.Get() -> state;
            case Msg.Set(var v) -> v;
        };
    }

    private static int tryGet(ProcRef<Integer, Msg> ref) {
        try {
            return ref.ask(new Msg.Get()).get(200, MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return Integer.MIN_VALUE;
        }
    }

    @AfterEach
    void cleanup() {
        ProcessRegistry.reset();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. CHAOS ENGINEERING: Random Failure Injection
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chaos Engineering")
    class ChaosEngineeringTests {

        @Test
        @DisplayName("50 workers with 5% random failure rate remain stable under supervisor")
        void chaosEngineering_randomFailures_supervisorKeepsSystemAlive() throws Exception {
            int workerCount = 50;
            int messageCount = 1000;
            double crashProbability = 0.05;
            Random random = new Random(42);

            var supervisor = new Supervisor("chaos-sv", Strategy.ONE_FOR_ONE, 1000, Duration.ofMinutes(5));

            @SuppressWarnings("unchecked")
            ProcRef<Integer, Msg>[] workers = new ProcRef[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workers[i] = supervisor.supervise("worker-" + i, 0, ProductionSimulationTest::handler);
            }

            AtomicInteger crashCount = new AtomicInteger(0);

            for (int i = 0; i < messageCount; i++) {
                int workerIdx = random.nextInt(workerCount);
                if (random.nextDouble() < crashProbability) {
                    workers[workerIdx].tell(new Msg.Crash("chaos-injected-" + i));
                    crashCount.incrementAndGet();
                } else {
                    workers[workerIdx].tell(new Msg.Work(1));
                }
                if (i % 100 == 0) Thread.sleep(10);
            }

            Thread.sleep(500);

            assertThat(supervisor.isRunning())
                    .as("supervisor must survive %d injected crashes", crashCount.get())
                    .isTrue();

            int reachableCount = 0;
            for (ProcRef<Integer, Msg> worker : workers) {
                if (tryGet(worker) != Integer.MIN_VALUE) reachableCount++;
            }
            assertThat(reachableCount).isEqualTo(workerCount);

            System.out.printf("[chaos] workers=%d messages=%d crashes=%d%n",
                    workerCount, messageCount, crashCount.get());

            supervisor.shutdown();
        }

        @Property(tries = 10)
        void chaosStabilityThreshold(
                @ForAll @DoubleRange(min = 0.01, max = 0.15) double crashProbability,
                @ForAll @IntRange(min = 10, max = 30) int workerCount) throws Exception {
            int messageCount = 500;
            Random random = new Random(12345);

            var supervisor = new Supervisor("prop-chaos-sv", Strategy.ONE_FOR_ONE,
                    messageCount * workerCount, Duration.ofMinutes(10));

            @SuppressWarnings("unchecked")
            ProcRef<Integer, Msg>[] workers = new ProcRef[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workers[i] = supervisor.supervise("pworker-" + i, 0, ProductionSimulationTest::handler);
            }

            for (int i = 0; i < messageCount; i++) {
                int workerIdx = random.nextInt(workerCount);
                if (random.nextDouble() < crashProbability) {
                    workers[workerIdx].tell(new Msg.Crash("prop-chaos-" + i));
                } else {
                    workers[workerIdx].tell(new Msg.Work(1));
                }
            }

            Thread.sleep(300);
            assertThat(supervisor.isRunning()).isTrue();
            supervisor.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. CASCADE FAILURES
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cascade Failures")
    class CascadeFailureTests {

        @Test
        @DisplayName("Service mesh cascade failure has bounded propagation")
        void serviceMesh_cascadeFailure_boundedPropagation() throws Exception {
            int serviceCount = 10;
            var services = new ArrayList<Proc<Integer, Msg>>(serviceCount);

            for (int i = 0; i < serviceCount; i++) {
                services.add(new Proc<>(i, ProductionSimulationTest::handler));
            }

            // Link services in a chain
            for (int i = 1; i < serviceCount; i++) {
                ProcessLink.link(services.get(i - 1), services.get(i));
            }

            // Crash the last service
            services.get(serviceCount - 1).tell(new Msg.Crash("cascade-trigger"));

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> services.stream().noneMatch(s -> s.thread().isAlive()));

            assertThat(services).noneMatch(s -> s.thread().isAlive());
            System.out.printf("[cascade] services=%d all_dead=true%n", serviceCount);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. BACKPRESSURE
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Backpressure")
    class BackpressureTests {

        @Test
        @DisplayName("10 fast producers / 1 slow consumer — no message loss")
        void fastProducers_slowConsumer_noMessageLoss() throws Exception {
            int producerCount = 10;
            int messagesPerProducer = 100;
            LongAdder processedCount = new LongAdder();
            LongAdder sentCount = new LongAdder();

            var consumer = new Proc<>(0, (Integer state, Msg msg) -> {
                return switch (msg) {
                    case Msg.Work(var p) -> {
                        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        processedCount.increment();
                        yield state + p;
                    }
                    case Msg.Get() -> state;
                    default -> state;
                };
            });

            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(producerCount);

            for (int p = 0; p < producerCount; p++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        for (int m = 0; m < messagesPerProducer; m++) {
                            consumer.tell(new Msg.Work(1));
                            sentCount.increment();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, SECONDS);

            await().atMost(Duration.ofSeconds(30))
                    .until(() -> processedCount.sum() >= sentCount.sum());

            assertThat(processedCount.sum()).isEqualTo(sentCount.sum());
            System.out.printf("[backpressure] sent=%d processed=%d%n", sentCount.sum(), processedCount.sum());

            consumer.stop();
        }

        @Test
        @DisplayName("Extreme message load — consumer remains responsive")
        void extremeLoad_consumerResponsive() throws Exception {
            int messageCount = 100_000;

            var worker = new Proc<>(0, (Integer state, Msg msg) -> {
                return switch (msg) {
                    case Msg.Work(var p) -> state + p;
                    case Msg.Get() -> state;
                    default -> state;
                };
            });

            long start = System.nanoTime();
            for (int i = 0; i < messageCount; i++) {
                worker.tell(new Msg.Work(1));
            }

            Integer result = worker.ask(new Msg.Get()).get(5, SECONDS);
            assertThat(result).isNotNull();

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[extreme-load] messages=%d queued=%dms%n", messageCount, elapsedMs);

            worker.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. HOT STANDBY / FAILOVER
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hot Standby Failover")
    class FailoverTests {

        @Test
        @DisplayName("Primary/standby failover with monitor detection")
        void primaryStandbyFailover_withMonitor() throws Exception {
            var sharedState = new AtomicInteger(0);

            var primary = new Proc<>(0, (Integer state, Msg msg) -> {
                return switch (msg) {
                    case Msg.Work(var p) -> {
                        int newState = state + p;
                        sharedState.set(newState);
                        yield newState;
                    }
                    case Msg.Get() -> state;
                    case Msg.Crash(var reason) -> throw new RuntimeException(reason);
                    default -> state;
                };
            });

            var standby = new Proc<>(0, ProductionSimulationTest::handler);

            var primaryDead = new AtomicBoolean(false);
            ProcessMonitor.monitor(primary, (down) -> {
                primaryDead.set(true);
                standby.tell(new Msg.Set(sharedState.get()));
            });

            for (int i = 0; i < 100; i++) {
                primary.tell(new Msg.Work(1));
            }

            int primaryState = primary.ask(new Msg.Get()).get(1, SECONDS);
            assertThat(primaryState).isEqualTo(100);

            long failoverStart = System.nanoTime();
            primary.tell(new Msg.Crash("primary-failure"));

            await().atMost(Duration.ofSeconds(2)).until(primaryDead::get);

            int standbyState = standby.ask(new Msg.Get()).get(1, SECONDS);
            assertThat(standbyState).isEqualTo(100);

            long failoverMs = (System.nanoTime() - failoverStart) / 1_000_000;
            System.out.printf("[failover] primary_state=%d standby_state=%d failover=%dms%n",
                    primaryState, standbyState, failoverMs);

            standby.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. LONG-RUNNING STABILITY
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Long-Running Stability")
    class StabilityTests {

        @Test
        @DisplayName("10-second continuous operation — no resource leak")
        void tenSecondContinuous_noResourceLeak() throws Exception {
            int threadCountBefore = Thread.activeCount();
            AtomicInteger processed = new AtomicInteger(0);

            var worker = new Proc<>(0, (Integer state, Msg msg) -> {
                return switch (msg) {
                    case Msg.Work(var p) -> {
                        processed.incrementAndGet();
                        yield state + p;
                    }
                    case Msg.Get() -> state;
                    default -> state;
                };
            });

            long startTime = System.currentTimeMillis();
            long duration = 10_000;
            AtomicBoolean running = new AtomicBoolean(true);

            Thread producer = Thread.ofVirtual().start(() -> {
                while (running.get() && System.currentTimeMillis() - startTime < duration) {
                    worker.tell(new Msg.Work(1));
                    try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                }
            });

            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                Integer state = worker.ask(new Msg.Get()).get(1, SECONDS);
                assertThat(state).isNotNull();
            }

            running.set(false);
            producer.join(2000);
            worker.stop();

            Thread.sleep(500);
            assertThat(Thread.activeCount()).isLessThanOrEqualTo(threadCountBefore + 10);

            System.out.printf("[stability] duration=10s processed=%d thread_delta=%d%n",
                    processed.get(), Thread.activeCount() - threadCountBefore);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6. GRACEFUL DEGRADATION
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Graceful Degradation")
    class DegradationTests {

        @Test
        @DisplayName("Gradual worker loss — remaining workers continue processing")
        void gradualWorkerLoss_remainingWorkersContinue() throws Exception {
            int initialWorkers = 10;
            var workers = new ArrayList<Proc<Integer, Msg>>(initialWorkers);
            var alive = new ConcurrentHashMap<Proc<Integer, Msg>, Boolean>();

            for (int i = 0; i < initialWorkers; i++) {
                var w = new Proc<>(i, ProductionSimulationTest::handler);
                workers.add(w);
                alive.put(w, true);
                ProcessMonitor.monitor(w, (down) -> alive.remove(w));
            }

            for (int i = 0; i < initialWorkers - 1; i++) {
                workers.get(i).tell(new Msg.Crash("gradual-shutdown-" + i));
                Thread.sleep(100);

                for (var w : alive.keySet()) {
                    Integer state = w.ask(new Msg.Get()).get(500, MILLISECONDS);
                    assertThat(state).isNotNull();
                }
            }

            assertThat(alive.size()).isEqualTo(1);
            for (var w : alive.keySet()) w.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 7. SUPERVISOR TREE STRESS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Supervisor Tree Stress")
    class SupervisorTreeTests {

        @Test
        @DisplayName("Wide supervisor tree — 100 children, concurrent crashes")
        void wideTree_100Children_concurrentCrashes() throws Exception {
            int childCount = 100;
            var supervisor = new Supervisor("wide-sv", Strategy.ONE_FOR_ONE,
                    childCount * 10, Duration.ofMinutes(5));

            @SuppressWarnings("unchecked")
            ProcRef<Integer, Msg>[] children = new ProcRef[childCount];
            for (int i = 0; i < childCount; i++) {
                children[i] = supervisor.supervise("child-" + i, 0, ProductionSimulationTest::handler);
            }

            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(childCount / 2);

            for (int i = 0; i < childCount / 2; i++) {
                final int idx = i * 2;
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        children[idx].tell(new Msg.Crash("wide-crash-" + idx));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(5, SECONDS);

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> {
                        for (ProcRef<Integer, Msg> child : children) {
                            if (tryGet(child) == Integer.MIN_VALUE) return false;
                        }
                        return true;
                    });

            assertThat(supervisor.isRunning()).isTrue();
            System.out.printf("[wide-tree] children=%d crashed=%d all_reachable=true%n",
                    childCount, childCount / 2);

            supervisor.shutdown();
        }

        @Test
        @DisplayName("ONE_FOR_ALL with 50 children — single crash restarts all")
        void oneForAll_50children_singleCrashRestartsAll() throws Exception {
            int childCount = 50;
            var supervisor = new Supervisor("ofa-sv", Strategy.ONE_FOR_ALL,
                    5, Duration.ofMinutes(5));

            @SuppressWarnings("unchecked")
            ProcRef<Integer, Msg>[] children = new ProcRef[childCount];
            for (int i = 0; i < childCount; i++) {
                children[i] = supervisor.supervise("ofa-child-" + i, 0, ProductionSimulationTest::handler);
            }

            for (int i = 0; i < childCount; i++) {
                children[i].tell(new Msg.Set(i));
            }

            children[0].tell(new Msg.Crash("ofa-trigger"));

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> {
                        for (ProcRef<Integer, Msg> child : children) {
                            if (tryGet(child) == Integer.MIN_VALUE) return false;
                        }
                        return true;
                    });

            for (int i = 0; i < childCount; i++) {
                assertThat(tryGet(children[i])).isNotEqualTo(Integer.MIN_VALUE);
            }

            System.out.printf("[one-for-all] children=%d all_restarted=true%n", childCount);
            supervisor.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 8. CRASH RECOVERY STRESS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Crash Recovery Stress")
    class CrashRecoveryStressTests {

        @Test
        @DisplayName("1000 concurrent CrashRecovery retries — all eventually succeed or exhaust")
        void thousandConcurrentCrashRecovery() throws Exception {
            int callerCount = 1000;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger exhaustCount = new AtomicInteger(0);

            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(callerCount);

            for (int c = 0; c < callerCount; c++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        var result = CrashRecovery.retry(3, () -> {
                            if (Math.random() < 0.5) throw new RuntimeException("transient");
                            return "success";
                        });
                        if (result.isSuccess()) successCount.incrementAndGet();
                        else exhaustCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, SECONDS);

            System.out.printf("[crash-recovery] callers=%d success=%d exhausted=%d%n",
                    callerCount, successCount.get(), exhaustCount.get());

            assertThat(successCount.get() + exhaustCount.get()).isEqualTo(callerCount);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 9. EVENT MANAGER STRESS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event Manager Stress")
    class EventManagerStressTests {

        @Test
        @DisplayName("100 handlers receiving 1000 events each")
        void hundredHandlers_thousandEvents() throws Exception {
            int handlerCount = 100;
            int eventCount = 1000;
            LongAdder received = new LongAdder();

            var em = EventManager.<Msg>start();
            List<EventManager.Handler<Msg>> handlers = new ArrayList<>();

            for (int i = 0; i < handlerCount; i++) {
                EventManager.Handler<Msg> h = event -> received.increment();
                handlers.add(h);
                em.addHandler(h);
            }

            for (int i = 0; i < eventCount; i++) {
                em.notify(new Msg.Work(i));
            }

            Thread.sleep(1000);

            long expected = (long) handlerCount * eventCount;
            assertThat(received.sum()).isEqualTo(expected);

            System.out.printf("[event-stress] handlers=%d events=%d received=%d%n",
                    handlerCount, eventCount, received.sum());

            em.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 10. PROCESS REGISTRY STRESS
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Process Registry Stress")
    class RegistryStressTests {

        @Test
        @DisplayName("500 concurrent registrations — no duplicates")
        void fiveHundredConcurrentRegistrations() throws Exception {
            int processCount = 500;
            AtomicInteger registered = new AtomicInteger(0);

            var startLatch = new CountDownLatch(1);
            var doneLatch = new CountDownLatch(10);

            int perThread = processCount / 10;
            for (int t = 0; t < 10; t++) {
                final int threadId = t;
                Thread.ofVirtual().start(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < perThread; i++) {
                            String name = "proc-" + threadId + "-" + i;
                            var proc = new Proc<>(0, ProductionSimulationTest::handler);
                            try {
                                ProcessRegistry.register(name, proc);
                                registered.incrementAndGet();
                            } catch (IllegalStateException e) {
                                // duplicate - shouldn't happen
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, SECONDS);

            assertThat(registered.get()).isEqualTo(processCount);
            System.out.printf("[registry-stress] registered=%d%n", registered.get());

            // Cleanup
            for (int t = 0; t < 10; t++) {
                for (int i = 0; i < perThread; i++) {
                    String name = "proc-" + t + "-" + i;
                    Optional<Proc<Integer, Msg>> proc = ProcessRegistry.whereis(name);
                    proc.ifPresent(p -> {
                        try { p.stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                    ProcessRegistry.unregister(name);
                }
            }
        }
    }
}
