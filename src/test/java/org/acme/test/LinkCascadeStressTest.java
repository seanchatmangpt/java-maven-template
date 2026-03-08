package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.ExitSignal;
import org.acme.Proc;
import org.acme.ProcessLink;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Armstrong's link cascade stress tests — finding the breaking points of {@link ProcessLink}.
 *
 * <p>Joe Armstrong: <em>"Links are the fundamental mechanism for building fault-tolerant systems.
 * But a link chain is also a fault amplifier — one crash propagates to everything connected."</em>
 *
 * <h2>Breaking points under investigation</h2>
 *
 * <ol>
 *   <li><b>Chain depth limit</b> — A→B→C→...→N: how deep before cascade propagation takes > 1s?
 *       Each crash callback fires in the crashed process's virtual thread, then interrupts the next,
 *       which fires its callbacks, etc. This is O(N) virtual thread interrupts in sequence.
 *   <li><b>Death star topology</b> — one hub linked to N workers: hub crash → N simultaneous
 *       interrupts. With N = 1000, this floods the JVM scheduler with 1000 concurrent interrupts.
 *   <li><b>Exit signal flood to trapping process</b> — 100 linked processes crash simultaneously;
 *       trapping process must receive exactly 100 {@link ExitSignal} messages, none lost.
 *   <li><b>Simultaneous bilateral crash</b> — A and B crash at the exact same time. Both call
 *       {@code deliverExitSignal} on each other. Must not deadlock or lose exit signals.
 *   <li><b>Property: crash count ≤ link count + 1</b> — in any star topology with N workers linked
 *       to a hub, exactly N+1 processes must die when the hub crashes (hub + N workers).
 * </ol>
 */
@Timeout(30)
class LinkCascadeStressTest implements WithAssertions {

    sealed interface Msg permits Msg.Work, Msg.Crash, Msg.Ping {
        record Work() implements Msg {}

        record Crash() implements Msg {}

        record Ping() implements Msg {}
    }

    private static int handle(int state, Msg msg) {
        return switch (msg) {
            case Msg.Work() -> state + 1;
            case Msg.Ping() -> state;
            case Msg.Crash() -> throw new RuntimeException("deliberate");
        };
    }

    // ── 1. Chain: A→B→C→...→N, crash A, all must die ─────────────────────

    /**
     * <b>Breaking point: chain cascade O(N) propagation time.</b>
     *
     * <p>With N=500, the cascade must complete within 5 seconds. If propagation takes > 10ms per
     * hop, a 500-depth chain takes 5s — right at the boundary. If it takes > 10ms per hop we have
     * a real latency problem (each hop is just a thread interrupt, should be < 1ms).
     */
    @Test
    void chainCascade_500deep_allDieWithin5s() throws Exception {
        int depth = 500;
        var procs = new ArrayList<Proc<Integer, Msg>>(depth);

        // Build the chain
        procs.add(new Proc<>(0, LinkCascadeStressTest::handle));
        for (int i = 1; i < depth; i++) {
            Proc<Integer, Msg> prev = procs.get(i - 1);
            Proc<Integer, Msg> next = new Proc<>(0, LinkCascadeStressTest::handle);
            ProcessLink.link(prev, next);
            procs.add(next);
        }

        long start = System.nanoTime();

        // Crash the head
        procs.get(0).tell(new Msg.Crash());

        // All must die
        Proc<Integer, Msg> tail = procs.get(depth - 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !tail.thread().isAlive());

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[chain-cascade] depth=%d elapsed=%d ms%n", depth, elapsedMs);
    }

    // ── 2. Death star: hub + N workers, hub crash kills all ───────────────

    /**
     * <b>Breaking point: JVM scheduler under N simultaneous interrupts.</b>
     *
     * <p>The hub's crash callback fires N times, each interrupting a worker. With N = 1000, the
     * JVM must schedule 1000 virtual thread exits concurrently. This reveals whether our crash
     * callback loop (sequential iteration over {@code crashCallbacks}) becomes a bottleneck when
     * it issues 1000 interrupts back-to-back before yielding.
     *
     * <p>Expected: all workers dead within 3 seconds. If the loop blocks on each interrupt, this
     * degrades to O(N × interrupt_latency).
     */
    @Test
    void deathStar_1000workers_hubCrashKillsAll() throws Exception {
        int workerCount = 1000;
        var hub = new Proc<>(0, LinkCascadeStressTest::handle);
        var workers = new ArrayList<Proc<Integer, Msg>>(workerCount);

        for (int i = 0; i < workerCount; i++) {
            Proc<Integer, Msg> worker = new Proc<>(0, LinkCascadeStressTest::handle);
            ProcessLink.link(hub, worker);
            workers.add(worker);
        }

        long start = System.nanoTime();
        hub.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(5))
                .until(() -> workers.stream().noneMatch(w -> w.thread().isAlive()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf(
                "[death-star] workers=%d elapsed=%d ms%n", workerCount, elapsedMs);

        // Verify every worker is actually dead
        assertThat(workers).noneMatch(w -> w.thread().isAlive());
    }

    // ── 3. Exit signal flood to trapping process ──────────────────────────

    /**
     * <b>Breaking point: exit signal delivery under concurrent crash flood.</b>
     *
     * <p>100 processes crash simultaneously; one trapping process must receive exactly 100 {@link
     * ExitSignal} messages. No signals must be lost, duplicated, or silently dropped.
     *
     * <p>This tests the thread-safety of {@code Proc.deliverExitSignal()} — specifically whether
     * concurrent calls to {@code mailbox.add()} from different crashing virtual threads ever lose
     * entries (they should not: {@code LinkedTransferQueue} is thread-safe).
     */
    @Test
    void exitSignalFlood_100crashers_trappingProcessReceivesAll() throws Exception {
        int crasherCount = 100;
        var received = new AtomicInteger(0);

        // Trapping process counts every ExitSignal it receives
        var trapping =
                new Proc<>(
                        0,
                        (Integer state, Object msg) ->
                                switch (msg) {
                                    case ExitSignal sig -> {
                                        received.incrementAndGet();
                                        yield state + 1;
                                    }
                                    default -> state;
                                });
        trapping.trapExits(true);

        // Wire: each crasher's crash fires deliverExitSignal on the trapping proc
        var crashers = new ArrayList<Proc<Integer, Msg>>(crasherCount);
        for (int i = 0; i < crasherCount; i++) {
            Proc<Integer, Msg> crasher = new Proc<>(0, LinkCascadeStressTest::handle);
            crasher.addCrashCallback(() -> trapping.deliverExitSignal(crasher.lastError));
            crashers.add(crasher);
        }

        // Launch all crashes simultaneously via a start latch
        var startLatch = new CountDownLatch(1);
        for (Proc<Integer, Msg> crasher : crashers) {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    startLatch.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                crasher.tell(new Msg.Crash());
                            });
        }
        startLatch.countDown(); // fire all simultaneously

        // Wait until all crashers are dead
        await().atMost(Duration.ofSeconds(5))
                .until(() -> crashers.stream().noneMatch(c -> c.thread().isAlive()));

        // Fence: ask trapping process to drain its mailbox
        trapping.ask(new Object()).get(3, TimeUnit.SECONDS);

        // Exactly crasherCount signals must have been received
        assertThat(received.get())
                .as("ExitSignal messages received by trapping process")
                .isEqualTo(crasherCount);

        trapping.stop();
    }

    // ── 4. Property: star topology — exactly N+1 processes die ───────────

    /**
     * <b>Breaking point: link count determines blast radius, not more, not less.</b>
     *
     * <p>In a star with {@code n} workers linked to one hub, exactly {@code n + 1} processes must
     * die when the hub crashes. Not n-1 (signal lost), not n+2 (spurious death).
     *
     * <p>This is the most fundamental OTP link invariant: blast radius equals link graph size.
     */
    @Property(tries = 20)
    void starTopology_hubCrash_exactlyNPlusOneProcessesDie(
            @ForAll @IntRange(min = 1, max = 50) int workerCount) throws Exception {
        var hub = new Proc<>(0, LinkCascadeStressTest::handle);
        var workers = new ArrayList<Proc<Integer, Msg>>(workerCount);

        for (int i = 0; i < workerCount; i++) {
            Proc<Integer, Msg> w = new Proc<>(0, LinkCascadeStressTest::handle);
            ProcessLink.link(hub, w);
            workers.add(w);
        }

        hub.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3))
                .until(() -> workers.stream().noneMatch(w -> w.thread().isAlive()));

        // Hub is also dead
        await().atMost(Duration.ofSeconds(1)).until(() -> !hub.thread().isAlive());

        // All workers dead
        assertThat(workers).noneMatch(w -> w.thread().isAlive());
    }

    // ── 5. Simultaneous bilateral crash — no deadlock ────────────────────

    /**
     * <b>Breaking point: deadlock in bilateral crash scenario.</b>
     *
     * <p>A and B are linked. Both crash at exactly the same moment. Each fires its crash callbacks
     * on the other. Since {@code deliverExitSignal} just sets a field and calls {@code
     * thread.interrupt()} (non-blocking), there should be no deadlock. But if the crash callback
     * loop were to wait for the peer to die, this would deadlock.
     *
     * <p>We run 500 pairs in parallel to increase the chance of observing the race.
     */
    @Test
    void simultaneousBilateralCrash_500pairs_neverDeadlocks() throws Exception {
        int pairs = 500;
        var allDead = new CountDownLatch(pairs * 2);

        for (int i = 0; i < pairs; i++) {
            var a = new Proc<>(0, LinkCascadeStressTest::handle);
            var b = new Proc<>(0, LinkCascadeStressTest::handle);
            ProcessLink.link(a, b);
            a.addCrashCallback(allDead::countDown);
            b.addCrashCallback(allDead::countDown);

            // Crash both "simultaneously" from separate virtual threads
            var latch = new CountDownLatch(1);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    latch.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                a.tell(new Msg.Crash());
                            });
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    latch.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                b.tell(new Msg.Crash());
                            });
            latch.countDown();
        }

        // All 1000 crash callbacks must fire
        assertThat(allDead.await(10, TimeUnit.SECONDS))
                .as("all %d bilateral crash callbacks fired", pairs * 2)
                .isTrue();
    }
}
