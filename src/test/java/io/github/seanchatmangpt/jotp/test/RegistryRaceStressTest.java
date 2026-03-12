package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcessRegistry;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Armstrong's registry race condition stress tests — breaking points of {@link ProcessRegistry}.
 *
 * <p>Joe Armstrong: <em>"A global name registry is a shared mutable resource. Every concurrent
 * access is a potential race. The question is not whether races occur but whether they corrupt
 * state."</em>
 *
 * <h2>Breaking points under investigation</h2>
 *
 * <ol>
 *   <li><b>Registration stampede</b> — N threads race to register the same name simultaneously.
 *       Exactly one must win; all others must get {@code IllegalStateException}. Zero silent
 *       overwrites.
 *   <li><b>Deregister-then-reregister race</b> — process A dies (auto-deregisters) while process B
 *       races to claim the same name. The two-arg {@code ConcurrentHashMap.remove(name, proc)}
 *       must prevent B from removing A's entry by mistake.
 *   <li><b>Property: registered() count is always ≤ total live processes</b> — the registry must
 *       never hold a reference to a dead process. Verified by killing processes and checking.
 *   <li><b>Auto-deregister under crash storm</b> — 500 registered processes crash simultaneously;
 *       the registry must be empty afterward (all auto-deregistered).
 *   <li><b>whereis() consistency during concurrent modification</b> — readers never see a
 *       partially-updated entry; they see either the old entry or the new one, never null when the
 *       name is live.
 * </ol>
 */
@Timeout(30)
@Execution(ExecutionMode.SAME_THREAD)  // Isolate from parallel tests due to global ProcessRegistry
class RegistryRaceStressTest implements WithAssertions {

    sealed interface Msg permits Msg.Noop, Msg.Crash {
        record Noop() implements Msg {}

        record Crash() implements Msg {}
    }

    private static int handle(int state, Msg msg) {
        return switch (msg) {
            case Msg.Noop() -> state;
            case Msg.Crash() -> throw new RuntimeException("crash");
        };
    }

    @BeforeEach
    void resetRegistry() {
        ProcessRegistry.reset();
    }

    // ── 1. Registration stampede — only one winner ────────────────────────

    /**
     * <b>Breaking point: lost-update race in register().</b>
     *
     * <p>ConcurrentHashMap.putIfAbsent() guarantees atomicity. But we verify this empirically:
     * with N threads all calling {@code register("race", proc_i)}, exactly one must return
     * successfully. If any two succeed, the registry has silently overwritten a name — a critical
     * safety violation in OTP (two processes believing they own the same name).
     */
    @Test
    void registrationStampede_exactlyOneWinner() throws Exception {
        int competitors = 100;
        var successCount = new AtomicInteger(0);
        var latch = new CountDownLatch(1);
        var procs = new ArrayList<Proc<Integer, Msg>>(competitors);

        for (int i = 0; i < competitors; i++) {
            procs.add(new Proc<>(0, RegistryRaceStressTest::handle));
        }

        var threads = new ArrayList<Thread>(competitors);
        for (Proc<Integer, Msg> proc : procs) {
            threads.add(
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            latch.await();
                                            ProcessRegistry.register("stampede-race", proc);
                                            successCount.incrementAndGet();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        } catch (IllegalStateException ignored) {
                                            // Expected: name already taken
                                        }
                                    }));
        }

        latch.countDown(); // fire all simultaneously
        for (var t : threads) t.join(5000);

        assertThat(successCount.get())
                .as("exactly one registration must succeed")
                .isEqualTo(1);

        // Cleanup
        ProcessRegistry.unregister("stampede-race");
        procs.forEach(p -> {
            try {
                p.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ── 2. Auto-deregister under crash storm ──────────────────────────────

    /**
     * <b>Breaking point: auto-deregister race under 500 simultaneous crashes.</b>
     *
     * <p>Each process registers a unique name, then crashes. The termination callback must reliably
     * deregister the name using the two-arg {@code remove(name, proc)} to prevent phantom entries.
     *
     * <p>After all crashes, {@code ProcessRegistry.registered()} must be empty.
     */
    @Test
    void crashStorm_500registeredProcesses_registryEmptyAfter() throws Exception {
        int count = 500;
        var procs = new ArrayList<Proc<Integer, Msg>>(count);

        // Register all processes with unique names
        for (int i = 0; i < count; i++) {
            Proc<Integer, Msg> proc = new Proc<>(0, RegistryRaceStressTest::handle);
            ProcessRegistry.register("stress-" + i, proc);
            procs.add(proc);
        }

        assertThat(ProcessRegistry.registered().stream()
                        .filter(n -> n.startsWith("stress-"))
                        .count())
                .as("stress entries registered")
                .isEqualTo(count);

        // Crash all simultaneously
        var latch = new CountDownLatch(1);
        for (Proc<Integer, Msg> proc : procs) {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    latch.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                proc.tell(new Msg.Crash());
                            });
        }
        latch.countDown();

        // Wait for all to die
        await().atMost(Duration.ofSeconds(10))
                .until(() -> procs.stream().noneMatch(p -> p.thread().isAlive()));

        // Registry must be empty — all auto-deregistered
        // Give more time for the termination callbacks to complete (500 concurrent crashes)
        await().atMost(Duration.ofSeconds(5))
                .until(() -> {
                    var registered = ProcessRegistry.registered();
                    return registered.stream().noneMatch(n -> n.startsWith("stress-"));
                });

        var remaining =
                ProcessRegistry.registered().stream()
                        .filter(n -> n.startsWith("stress-"))
                        .toList();
        assertThat(remaining).as("phantom registry entries after crash storm").isEmpty();
    }

    // ── 3. Property: registered() never contains dead processes ───────────

    /**
     * <b>Breaking point: zombie entries — registry retains dead process references.</b>
     *
     * <p>After stopping N processes, none of their names should appear in registered(). A zombie
     * entry means the auto-deregister callback failed to fire, or fired with the wrong process
     * reference (two-arg remove bug).
     */
    @Property(tries = 50)
    void registeredSet_neverContainsDeadProcesses(
            @ForAll @IntRange(min = 1, max = 30) int count) throws Exception {
        var names = new ArrayList<String>(count);

        for (int i = 0; i < count; i++) {
            String name = "prop-" + System.nanoTime() + "-" + i;
            Proc<Integer, Msg> proc = new Proc<>(0, RegistryRaceStressTest::handle);
            ProcessRegistry.register(name, proc);
            names.add(name);
            proc.stop(); // graceful stop → terminationCallback fires → auto-deregister
        }

        // Allow deregister callbacks to propagate
        await().atMost(Duration.ofSeconds(3))
                .until(() -> names.stream().allMatch(n -> ProcessRegistry.whereis(n).isEmpty()));

        for (String name : names) {
            assertThat(ProcessRegistry.whereis(name))
                    .as("dead process '%s' must not be in registry", name)
                    .isEmpty();
        }
    }

    // ── 4. whereis() never returns stale entry during rapid churn ─────────

    /**
     * <b>Breaking point: stale read during concurrent register/die cycle.</b>
     *
     * <p>Rapid cycle: register name → kill process (auto-deregister) → new process registers
     * same name. At no point should {@code whereis()} return the dead process. It may transiently
     * return empty (between unregister and re-register), but never a dead process.
     *
     * <p>This tests whether the termination callback executes atomically enough that a reader
     * never sees a process reference whose thread has already exited.
     */
    @Test
    void whereis_duringChurn_neverReturnsDeadProcess() throws Exception {
        String name = "churn-test";
        int cycles = 100;
        var badReads = new AtomicInteger(0);

        // Reader thread: spin-checking that whereis() never returns a dead process
        var stop = new CountDownLatch(1);
        Thread reader =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (stop.getCount() > 0) {
                                        ProcessRegistry.whereis(name)
                                                .ifPresent(
                                                        proc -> {
                                                            // A true zombie: dead proc still in
                                                            // registry (not just a stale reference
                                                            // obtained before process died).
                                                            if (!proc.thread().isAlive()
                                                                    && ProcessRegistry.whereis(name)
                                                                            .map(r -> r == proc)
                                                                            .orElse(false)) {
                                                                badReads.incrementAndGet();
                                                            }
                                                        });
                                        Thread.yield();
                                    }
                                });

        // Writer: rapid register→die cycles
        for (int i = 0; i < cycles; i++) {
            Proc<Integer, Msg> proc = new Proc<>(0, RegistryRaceStressTest::handle);
            try {
                ProcessRegistry.register(name, proc);
            } catch (IllegalStateException e) {
                // Previous auto-deregister not yet complete; wait briefly
                await().atMost(Duration.ofMillis(100))
                        .until(() -> ProcessRegistry.whereis(name).isEmpty());
                ProcessRegistry.register(name, proc);
            }
            proc.stop(); // triggers auto-deregister
            await().atMost(Duration.ofMillis(500))
                    .until(() -> ProcessRegistry.whereis(name).isEmpty());
        }

        stop.countDown();
        reader.join(2000);

        assertThat(badReads.get())
                .as("whereis() returned a dead process %d times", badReads.get())
                .isZero();
    }

    // ── 5. Concurrent whereis() under heavy register/unregister ──────────

    /**
     * <b>Breaking point: ConcurrentHashMap read consistency under high write rate.</b>
     *
     * <p>50 readers continuously calling whereis() while 50 writers register/unregister different
     * names. Readers must never throw ConcurrentModificationException or see corrupted state
     * (e.g. wrong process type). This verifies ConcurrentHashMap's linearisability guarantee.
     */
    @Test
    void heavyConcurrentReadWrite_neverThrows() throws Exception {
        int readers = 50;
        int writers = 50;
        int durationMs = 1000;
        var errors = new AtomicInteger(0);
        var done = new CountDownLatch(1);

        // Writers: register and immediately unregister unique names
        for (int i = 0; i < writers; i++) {
            final int id = i;
            Thread.ofVirtual()
                    .start(
                            () -> {
                                int iteration = 0;
                                while (done.getCount() > 0) {
                                    String name = "concurrent-" + id + "-" + iteration++;
                                    try {
                                        Proc<Integer, Msg> proc =
                                                new Proc<>(0, RegistryRaceStressTest::handle);
                                        ProcessRegistry.register(name, proc);
                                        proc.stop();
                                    } catch (Exception e) {
                                        errors.incrementAndGet();
                                    }
                                }
                            });
        }

        // Readers: repeatedly call registered() and whereis() for random names
        for (int i = 0; i < readers; i++) {
            final int id = i;
            Thread.ofVirtual()
                    .start(
                            () -> {
                                while (done.getCount() > 0) {
                                    try {
                                        var all = ProcessRegistry.registered();
                                        for (String name : all) {
                                            ProcessRegistry.whereis(name); // just must not throw
                                        }
                                    } catch (Exception e) {
                                        errors.incrementAndGet();
                                    }
                                }
                            });
        }

        Thread.sleep(durationMs);
        done.countDown();

        assertThat(errors.get())
                .as("concurrent registry read/write errors")
                .isZero();
    }
}
