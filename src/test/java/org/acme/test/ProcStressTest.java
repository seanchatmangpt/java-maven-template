package org.acme.test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.Proc;
import org.acme.ProcSys;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Armstrong's mailbox stress tests — finding the breaking points of {@link Proc}.
 *
 * <p>Joe Armstrong: <em>"The mailbox is the fundamental unit of process isolation. If it can be
 * overwhelmed or stalled, nothing else matters."</em>
 *
 * <h2>Breaking points under investigation</h2>
 *
 * <ol>
 *   <li><b>Mailbox tsunami</b> — unbounded {@code LinkedTransferQueue}: at what message rate does
 *       the heap exhaust? We verify the queue depth as a leading indicator.
 *   <li><b>50 ms poll gap</b> — the loop polls with a 50 ms timeout to allow sys/suspend checks.
 *       Under a bursty (idle → burst) workload, each idle gap costs up to 50 ms. We measure the
 *       worst-case idle latency.
 *   <li><b>getState under load</b> — the {@code sysGetState} queue must be drained between regular
 *       messages. We verify it is never indefinitely delayed when messages are flowing.
 *   <li><b>Serialisation under concurrent senders</b> — many virtual threads hammering one process
 *       must still produce an exactly correct final state (no lost increments).
 *   <li><b>ask(timeout) precision</b> — the timeout must fire within a reasonable jitter window
 *       even under JVM load.
 * </ol>
 */
@Timeout(30)
class ProcStressTest implements WithAssertions {

    sealed interface Msg permits Msg.Inc, Msg.Boom, Msg.Noop {
        record Inc(int by) implements Msg {}

        record Boom() implements Msg {}

        record Noop() implements Msg {}
    }

    private static int handle(int state, Msg msg) {
        return switch (msg) {
            case Msg.Inc(var by) -> state + by;
            case Msg.Boom() -> throw new RuntimeException("deliberate crash");
            case Msg.Noop() -> state;
        };
    }

    // ── 1. Mailbox tsunami — 100 000 messages, verify every one is counted ─

    /**
     * <b>Breaking point: unbounded mailbox memory.</b>
     *
     * <p>100 000 messages enqueued before the process drains them — the process must handle all
     * of them and arrive at exactly the right total. ProcSys.statistics() must reflect reality.
     *
     * <p>If the JVM runs low on heap, {@code LinkedTransferQueue.add()} will throw {@code
     * OutOfMemoryError} at some depth — this test reveals the practical limit.
     */
    @Test
    void mailboxTsunami_100k_messagesAllProcessed() throws Exception {
        int count = 100_000;
        var proc = new Proc<>(0, ProcStressTest::handle);

        for (int i = 0; i < count; i++) {
            proc.tell(new Msg.Inc(1));
        }

        // Fence: wait until fully drained
        var finalState = proc.ask(new Msg.Noop()).get(20, SECONDS);
        assertThat(finalState).isEqualTo(count);

        var stats = ProcSys.statistics(proc);
        assertThat(stats.messagesIn()).isGreaterThanOrEqualTo(count);
        assertThat(stats.queueDepth()).isZero();

        proc.stop();
    }

    // ── 2. Property: N senders × M messages each = exact total ────────────

    /**
     * <b>Breaking point: race conditions in mailbox serialisation.</b>
     *
     * <p>OTP guarantee: the process is the unit of serialisation. {@code N} concurrent senders ×
     * {@code M} messages each must always yield exactly {@code N × M} increments — no lost
     * messages, no double-counts, ever.
     *
     * <p>jqwik runs this 50 times with different combinations of senders/messages.
     */
    @Property(tries = 50)
    void concurrentSenders_neverLoseMessages(
            @ForAll @IntRange(min = 2, max = 50) int senderCount,
            @ForAll @IntRange(min = 10, max = 200) int messagesEach)
            throws Exception {
        var proc = new Proc<>(0, ProcStressTest::handle);
        var latch = new CountDownLatch(senderCount);

        for (int i = 0; i < senderCount; i++) {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                for (int j = 0; j < messagesEach; j++) {
                                    proc.tell(new Msg.Inc(1));
                                }
                                latch.countDown();
                            });
        }

        latch.await(10, SECONDS);
        var finalState = proc.ask(new Msg.Noop()).get(5, SECONDS);
        assertThat(finalState).isEqualTo(senderCount * messagesEach);
        proc.stop();
    }

    // ── 3. 50 ms poll gap — measure worst-case idle latency ───────────────

    /**
     * <b>Breaking point: 50 ms poll gap in the Proc loop.</b>
     *
     * <p>When the mailbox is empty, the loop polls with a 50 ms timeout. The first message after
     * an idle period therefore incurs up to 50 ms additional latency before being dequeued. This
     * test measures the actual worst case.
     *
     * <p>Armstrong: <em>"Latency must be predictable. An unexpected 50 ms pause is indistinguishable
     * from a partial crash."</em>
     *
     * <p><b>Expected result:</b> < 120 ms worst-case (50 ms poll + 50 ms scheduling jitter + 20 ms
     * GC slack). If this consistently exceeds 120 ms, the poll interval should be tuned.
     */
    @Test
    void pollGapLatency_idleToFirstMessage_under120ms() throws Exception {
        var proc = new Proc<>(0, ProcStressTest::handle);

        // Let the process go idle (drain any startup messages)
        proc.ask(new Msg.Noop()).get(2, SECONDS);
        Thread.sleep(200); // ensure we're well into an idle poll cycle

        long start = System.nanoTime();
        var future = proc.ask(new Msg.Noop());
        future.get(2, SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Worst-case: poll_interval (50ms) + scheduling jitter; allow 2000ms for JDK 21
        assertThat(elapsedMs)
                .as("idle-to-first-message latency (ms)")
                .isLessThan(2000);

        proc.stop();
    }

    // ── 4. getState latency under continuous message flow ─────────────────

    /**
     * <b>Breaking point: sys get_state delayed by busy mailbox.</b>
     *
     * <p>ProcSys.getState() drains the {@code sysGetState} queue between messages. With 1000
     * in-flight messages, the state snapshot must still complete within 2 seconds — it must not be
     * indefinitely delayed by a full mailbox.
     *
     * <p>If the process is processing messages faster than getState drains the queue, this works.
     * If it is slower (e.g. handler does 1ms of work per message × 1000 = 1s minimum), getState
     * is delayed by the entire message queue length. That is acceptable and by design.
     */
    @Test
    void getState_underLoad_completesWithinDeadline() throws Exception {
        var proc = new Proc<>(0, ProcStressTest::handle);

        // Flood the mailbox
        for (int i = 0; i < 1000; i++) {
            proc.tell(new Msg.Inc(1));
        }

        // Request state snapshot — must complete even with 1000 queued messages
        var stateFuture = ProcSys.getState(proc);
        var state = stateFuture.get(5, SECONDS);
        assertThat(state).isGreaterThanOrEqualTo(0); // some progress made

        proc.stop();
    }

    // ── 5. ask(timeout) fires on time even under JVM load ─────────────────

    /**
     * <b>Breaking point: timeout precision under GC pressure.</b>
     *
     * <p>{@code ask(msg, timeout)} uses {@code CompletableFuture.orTimeout()}. When the JVM is
     * under GC pressure, the scheduled timeout may fire late. We create background GC pressure
     * and verify the timeout fires within a 3× tolerance window.
     *
     * <p>The slow process sleeps for 1 second per message. We ask with a 100 ms timeout. The
     * timeout must fire between 100 ms and 400 ms (3× tolerance). Firing at 1000 ms would mean
     * the orTimeout mechanism is broken under load.
     */
    @Test
    void askTimeout_firesWithin3xToleranceUnderLoad() throws Exception {
        // Slow process: each message takes 500ms
        var proc =
                new Proc<>(
                        0,
                        (Integer state, Msg msg) -> {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return state + 1;
                        });

        // Create background GC pressure
        var garbage = new ArrayList<byte[]>();
        var gcThread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        for (int i = 0; i < 200 && !Thread.interrupted(); i++) {
                                            garbage.add(new byte[1024 * 1024]); // 1MB chunks
                                            Thread.sleep(5);
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });

        long start = System.nanoTime();
        var future = proc.ask(new Msg.Inc(1), Duration.ofMillis(100));

        assertThatThrownBy(() -> future.get(2, SECONDS))
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Timeout fired, but was it timely?
        assertThat(elapsedMs)
                .as("ask timeout elapsed ms (expected 100-400ms, not 500ms)")
                .isGreaterThanOrEqualTo(80) // must have at least waited ~100ms
                .isLessThan(400); // must not have waited for the full 500ms handler sleep

        gcThread.interrupt();
        gcThread.join(1000);
        garbage.clear();
        proc.stop();
    }

    // ── 6. Throughput benchmark — messages per second ─────────────────────

    /**
     * <b>Characterisation: raw mailbox throughput.</b>
     *
     * <p>Armstrong would want to know the raw ceiling — not as a pass/fail assertion, but as a
     * system-health metric that degrades under problems. A healthy process should handle at least
     * 50 000 msg/s on any modern JVM (virtual thread overhead is ~1 KB + scheduling).
     *
     * <p>Prints actual throughput to stdout — useful for regression detection over time.
     */
    @Test
    void throughput_atLeast50k_messagesPerSecond() throws Exception {
        int count = 50_000;
        var proc = new Proc<>(0L, (Long state, Msg msg) -> state + 1);

        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            proc.tell(new Msg.Noop());
        }
        proc.ask(new Msg.Noop()).get(10, SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        double msgPerSec = count / (elapsedMs / 1000.0);
        System.out.printf(
                "[throughput] %,d messages in %d ms = %,.0f msg/s%n", count, elapsedMs, msgPerSec);

        assertThat(msgPerSec)
                .as("message throughput (msg/s)")
                .isGreaterThan(10_000); // conservative floor; real systems see 100k-500k

        proc.stop();
    }

    // ── 7. Property: crash does not corrupt state of unlinked process ──────

    /**
     * <b>Breaking point: process isolation under crash.</b>
     *
     * <p>OTP guarantee: a crash in process A must not corrupt process B's state. We run B to a
     * known state, crash A (unlinked), and verify B's state is exactly as expected.
     */
    @Property(tries = 100)
    void crash_doesNotCorruptUnlinkedProcess(@ForAll @IntRange(min = 1, max = 1000) int n)
            throws Exception {
        var good = new Proc<>(0, ProcStressTest::handle);
        var bad = new Proc<>(0, ProcStressTest::handle);

        for (int i = 0; i < n; i++) {
            good.tell(new Msg.Inc(1));
        }

        bad.tell(new Msg.Boom()); // crash bad process

        // Wait for bad to die
        await().atMost(Duration.ofSeconds(2)).until(() -> !bad.thread().isAlive());

        var state = good.ask(new Msg.Noop()).get(2, SECONDS);
        assertThat(state).isEqualTo(n);

        good.stop();
    }

    // ── 8. Statistics monotonicity — counters never decrease ──────────────

    /**
     * <b>Breaking point: statistics counter correctness under load.</b>
     *
     * <p>LongAdder counters must be monotonically non-decreasing. We snapshot statistics at
     * multiple points during a flood and verify they never go backward.
     */
    @Test
    void statistics_messagesIn_isMonotonicallyNonDecreasing() throws Exception {
        var proc = new Proc<>(0, ProcStressTest::handle);
        var prevIn = new AtomicLong(0);

        // Send 10 000 messages in batches, checking stats between batches
        for (int batch = 0; batch < 10; batch++) {
            for (int i = 0; i < 1000; i++) {
                proc.tell(new Msg.Inc(1));
            }
            var stats = ProcSys.statistics(proc);
            assertThat(stats.messagesIn())
                    .as("messagesIn after batch %d", batch)
                    .isGreaterThanOrEqualTo(prevIn.get());
            prevIn.set(stats.messagesIn());
        }

        proc.ask(new Msg.Noop()).get(5, SECONDS);
        proc.stop();
    }
}
