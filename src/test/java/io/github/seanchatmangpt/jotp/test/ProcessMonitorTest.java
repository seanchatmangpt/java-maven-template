package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcessMonitor;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP process-monitor semantics in Java 26.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>Monitor fires DOWN with non-null reason on abnormal exit
 *   <li>Monitor fires DOWN with null reason on normal exit ({@code stop()})
 *   <li>Monitor does NOT kill the monitoring side (unilateral, unlike link)
 *   <li>{@code demonitor} prevents DOWN from firing after cancellation
 * </ol>
 */
@Timeout(10)
class ProcessMonitorTest implements WithAssertions {

    sealed interface Msg permits Msg.Ping, Msg.Crash {
        record Ping() implements Msg {}

        record Crash() implements Msg {}
    }

    static Proc<Integer, Msg> counter() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Ping() -> state + 1;
                            case Msg.Crash() -> throw new RuntimeException("BOOM");
                        });
    }

    // -------------------------------------------------------------------------
    // 1. Abnormal exit fires DOWN with the exception as reason
    // -------------------------------------------------------------------------

    @Test
    void monitor_abnormalExit_firesDownWithReason() {
        var target = counter();
        var downReason = new AtomicReference<Throwable>();
        var downFired = new AtomicBoolean(false);

        ProcessMonitor.monitor(
                target,
                reason -> {
                    downReason.set(reason);
                    downFired.set(true);
                });

        target.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3)).untilTrue(downFired);
        assertThat(downReason.get()).isNotNull().hasMessage("BOOM");
    }

    // -------------------------------------------------------------------------
    // 2. Normal exit fires DOWN with null reason
    // -------------------------------------------------------------------------

    @Test
    void monitor_normalExit_firesDownWithNullReason() throws InterruptedException {
        var target = counter();
        var downReason = new AtomicReference<Throwable>(new RuntimeException("sentinel"));
        var downFired = new AtomicBoolean(false);

        ProcessMonitor.monitor(
                target,
                reason -> {
                    downReason.set(reason); // should be null for normal exit
                    downFired.set(true);
                });

        target.stop(); // graceful shutdown

        await().atMost(Duration.ofSeconds(3)).untilTrue(downFired);
        assertThat(downReason.get()).isNull(); // null = normal exit reason
    }

    // -------------------------------------------------------------------------
    // 3. Monitor does NOT kill the monitoring side (unilateral)
    // -------------------------------------------------------------------------

    @Test
    void monitor_targetCrashes_monitoringSideKeepsRunning() throws InterruptedException {
        var target = counter();
        var watcher = counter();
        var aliveAfterCrash = new AtomicBoolean(false);

        ProcessMonitor.monitor(
                target,
                reason -> {
                    // Watcher still runs — tell it a Ping to confirm
                    watcher.tell(new Msg.Ping());
                });

        target.tell(new Msg.Crash()); // target crashes

        await().atMost(Duration.ofSeconds(3)).until(() -> watcher.ask(new Msg.Ping()).join() >= 1);
        aliveAfterCrash.set(true);

        watcher.stop();
        assertThat(aliveAfterCrash.get()).isTrue();
    }

    // -------------------------------------------------------------------------
    // 4. demonitor prevents DOWN from firing
    // -------------------------------------------------------------------------

    @Test
    void demonitor_preventsDownOnSubsequentCrash() throws InterruptedException {
        var target = counter();
        var downFired = new AtomicBoolean(false);

        var ref = ProcessMonitor.monitor(target, _ -> downFired.set(true));

        ProcessMonitor.demonitor(ref); // cancel before crash

        target.tell(new Msg.Crash());
        target.thread().join();

        // Give a moment for any spurious callback
        Thread.sleep(100);
        assertThat(downFired.get()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 5. Multiple monitors on same target all fire
    // -------------------------------------------------------------------------

    @Test
    void monitor_multipleMonitors_allFire() {
        var target = counter();
        var fired1 = new AtomicBoolean(false);
        var fired2 = new AtomicBoolean(false);

        ProcessMonitor.monitor(target, _ -> fired1.set(true));
        ProcessMonitor.monitor(target, _ -> fired2.set(true));

        target.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3)).until(() -> fired1.get() && fired2.get());
    }
}
