package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.acme.Proc;
import org.acme.ProcTimer;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP timer semantics in Java 26.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>{@code send_after} delivers exactly one message after the delay
 *   <li>{@code send_interval} delivers messages repeatedly at the period
 *   <li>{@code cancel} prevents delivery of a pending one-shot timer
 *   <li>{@code cancel} stops a repeating interval timer
 * </ol>
 */
@Timeout(10)
class ProcTimerTest implements WithAssertions {

    sealed interface Msg permits Msg.Tick, Msg.Ping {
        record Tick() implements Msg {}

        record Ping() implements Msg {}
    }

    /** Counter process: increments state on Tick, no-op on Ping (used for ask-to-read). */
    static Proc<Integer, Msg> counter() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Tick() -> state + 1;
                            case Msg.Ping() -> state;
                        });
    }

    // -------------------------------------------------------------------------
    // 1. send_after delivers exactly one message after delay
    // -------------------------------------------------------------------------

    @Test
    void sendAfter_deliversOneMessageAfterDelay() throws InterruptedException {
        var proc = counter();

        ProcTimer.sendAfter(100, proc, new Msg.Tick());

        // Before delay: still 0
        Thread.sleep(20);
        assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(0);

        // After delay: exactly 1
        await().atMost(Duration.ofSeconds(3)).until(() -> proc.ask(new Msg.Ping()).join() == 1);

        // Stays at 1 (one-shot, not repeating)
        Thread.sleep(200);
        assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(1);

        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 2. send_interval delivers messages repeatedly
    // -------------------------------------------------------------------------

    @Test
    void sendInterval_deliversRepeatingMessages() throws InterruptedException {
        var proc = counter();
        var ref = ProcTimer.sendInterval(50, proc, new Msg.Tick());

        // Wait for at least 3 ticks
        await().atMost(Duration.ofSeconds(3)).until(() -> proc.ask(new Msg.Ping()).join() >= 3);

        ProcTimer.cancel(ref);
        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 3. cancel prevents one-shot delivery
    // -------------------------------------------------------------------------

    @Test
    void cancel_preventsOneShot() throws InterruptedException {
        var proc = counter();

        var ref = ProcTimer.sendAfter(200, proc, new Msg.Tick());
        ProcTimer.cancel(ref); // cancel immediately

        Thread.sleep(350); // well past delay
        assertThat(proc.ask(new Msg.Ping()).join()).isEqualTo(0);

        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 4. cancel stops interval timer
    // -------------------------------------------------------------------------

    @Test
    void cancel_stopsInterval() throws InterruptedException {
        var proc = counter();
        var ref = ProcTimer.sendInterval(50, proc, new Msg.Tick());

        // Wait for a couple of ticks
        await().atMost(Duration.ofSeconds(3)).until(() -> proc.ask(new Msg.Ping()).join() >= 2);

        ProcTimer.cancel(ref);
        int countAfterCancel = proc.ask(new Msg.Ping()).join();

        Thread.sleep(200); // wait well past another period
        int countLater = proc.ask(new Msg.Ping()).join();

        // Should not have grown significantly (allow +1 for in-flight)
        assertThat(countLater).isLessThanOrEqualTo(countAfterCancel + 1);

        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 5. TimerRef.cancel() returns true when pending, false when already fired
    // -------------------------------------------------------------------------

    @Test
    void timerRef_cancel_returnsTrueWhenPending() {
        var proc = counter();
        var ref = ProcTimer.sendAfter(5000, proc, new Msg.Tick()); // very long delay

        assertThat(ref.cancel()).isTrue(); // was still pending
        assertThat(ref.cancel()).isFalse(); // already cancelled
    }

    // -------------------------------------------------------------------------
    // 6. Multiple concurrent timers all fire independently
    // -------------------------------------------------------------------------

    @Test
    void multipleConcurrentTimers_allFire() throws InterruptedException {
        var ticks = new AtomicInteger(0);
        var proc =
                new Proc<Integer, Msg>(
                        0,
                        (state, msg) -> {
                            ticks.incrementAndGet();
                            return state + 1;
                        });

        ProcTimer.sendAfter(50, proc, new Msg.Tick());
        ProcTimer.sendAfter(80, proc, new Msg.Tick());
        ProcTimer.sendAfter(110, proc, new Msg.Tick());

        await().atMost(Duration.ofSeconds(3)).until(() -> ticks.get() == 3);

        proc.stop();
    }
}
