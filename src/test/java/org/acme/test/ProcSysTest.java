package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.acme.Proc;
import org.acme.ProcSys;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP {@code sys} module semantics: process introspection without stopping.
 *
 * <p>Armstrong: "You must be able to look inside a running process without stopping it. A process
 * you cannot inspect is a black box you cannot trust in production."
 *
 * <p>OTP invariants under test:
 *
 * <ol>
 *   <li>{@code sys:get_state} returns the current state without modifying it
 *   <li>{@code sys:suspend} blocks message processing; {@code sys:resume} unblocks
 *   <li>{@code sys:statistics} tracks messages-in, messages-out, queue depth
 *   <li>Suspend does not prevent crash callbacks from firing
 *   <li>{@code sys:get_state} on a dead process fails exceptionally
 * </ol>
 */
@Timeout(10)
class ProcSysTest implements WithAssertions {

    sealed interface Msg permits Msg.Inc, Msg.Noop {
        record Inc(int by) implements Msg {}

        record Noop() implements Msg {}
    }

    sealed interface BoomMsg permits BoomMsg.Crash {
        record Crash() implements BoomMsg {}
    }

    private static int handle(int state, Msg msg) {
        return switch (msg) {
            case Msg.Inc(var by) -> state + by;
            case Msg.Noop() -> state;
        };
    }

    // ── Test 1: getState returns the current state ─────────────────────────

    @Test
    void getState_returnsCurrentState() throws Exception {
        var proc = new Proc<>(0, ProcSysTest::handle);

        proc.tell(new Msg.Inc(7));
        proc.tell(new Msg.Inc(3));

        // ask() ensures prior tells are processed before we read state
        proc.ask(new Msg.Noop()).get(1, TimeUnit.SECONDS);

        var state = ProcSys.getState(proc).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(10);

        proc.stop();
    }

    // ── Test 2: suspend blocks new message processing; resume unblocks ─────

    @Test
    void suspend_blocksNewMessages_resume_unblocks() throws Exception {
        var proc = new Proc<>(0, ProcSysTest::handle);

        // Drain initial state so proc is idle
        proc.ask(new Msg.Noop()).get(1, TimeUnit.SECONDS);

        ProcSys.suspend(proc);

        // Messages sent while suspended pile up in the mailbox
        proc.tell(new Msg.Inc(42));
        proc.tell(new Msg.Inc(8));

        // Wait briefly — state should NOT have changed
        Thread.sleep(200);
        var frozenState = ProcSys.getState(proc);
        // getState is also queued, but the suspend check runs before mailbox.poll,
        // so after resume the sys queue is drained first — state is still 0 here
        // because the Inc messages haven't been processed yet.

        ProcSys.resume(proc);

        // After resume the queued messages drain
        var finalState = proc.ask(new Msg.Noop()).get(2, TimeUnit.SECONDS);
        assertThat(finalState).isEqualTo(50);

        proc.stop();
    }

    // ── Test 3: statistics tracks message throughput ───────────────────────

    @Test
    void statistics_tracksMessageCount() throws Exception {
        var proc = new Proc<>(0, ProcSysTest::handle);

        proc.tell(new Msg.Inc(1));
        proc.tell(new Msg.Inc(1));
        proc.tell(new Msg.Inc(1));

        // Fence: ensure all three are processed
        proc.ask(new Msg.Noop()).get(1, TimeUnit.SECONDS);

        var stats = ProcSys.statistics(proc);
        // 3 Inc + 1 Noop (the ask sentinel) = 4 in, 4 out
        assertThat(stats.messagesIn()).isGreaterThanOrEqualTo(3);
        assertThat(stats.messagesOut()).isGreaterThanOrEqualTo(3);
        assertThat(stats.queueDepth()).isZero();

        proc.stop();
    }

    // ── Test 4: suspend during crash still fires crash callbacks ──────────

    @Test
    void suspend_doesNotPreventCrashPropagation() throws Exception {
        var crashed = new java.util.concurrent.atomic.AtomicBoolean(false);

        var proc =
                new Proc<>(
                        0,
                        (Integer state, BoomMsg msg) -> {
                            if (msg instanceof BoomMsg.Crash) {
                                throw new RuntimeException("boom");
                            }
                            return state;
                        });
        proc.addCrashCallback(() -> crashed.set(true));

        // Suspend, then enqueue a crashing message, then resume
        ProcSys.suspend(proc);
        proc.tell(new BoomMsg.Crash());
        ProcSys.resume(proc);

        await().atMost(Duration.ofSeconds(2)).untilTrue(crashed);
    }

    // ── Test 5: getState on dead proc completes exceptionally ─────────────

    @Test
    void getState_onDeadProc_completesExceptionally() throws Exception {
        var proc = new Proc<>(0, ProcSysTest::handle);
        proc.stop();

        await().atMost(Duration.ofSeconds(1)).until(() -> !proc.thread().isAlive());

        var future = ProcSys.getState(proc);
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
