package io.github.seanchatmangpt.jotp.test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.seanchatmangpt.jotp.Proc;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies timed {@code ask(msg, timeout)} — OTP {@code gen_server:call(Pid, Msg, Timeout)}.
 *
 * <p>Armstrong: "An unbounded call is a latent deadlock. Every call must have a timeout."
 *
 * <p>OTP invariants under test:
 *
 * <ol>
 *   <li>Normal response within timeout → future completes with state
 *   <li>Slow process exceeds timeout → future completes exceptionally with {@link TimeoutException}
 *   <li>Unbounded {@link Proc#ask(Object)} still works (backward compatibility)
 * </ol>
 */
@Timeout(10)
class ProcAskTimeoutTest implements WithAssertions {

    sealed interface Msg permits Msg.SlowWork, Msg.Ping {
        record SlowWork(long sleepMs) implements Msg {}

        record Ping() implements Msg {}
    }

    private static int handle(int state, Msg msg) {
        return switch (msg) {
            case Msg.SlowWork(var ms) -> {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield state + 1;
            }
            case Msg.Ping() -> state;
        };
    }

    // ── Test 1: ask with timeout — response arrives in time ───────────────

    @Test
    void ask_withTimeout_completesNormally() throws Exception {
        var proc = new Proc<>(0, ProcAskTimeoutTest::handle);

        proc.tell(new Msg.SlowWork(50)); // fast enough

        var state = proc.ask(new Msg.Ping(), Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1);

        proc.stop();
    }

    // ── Test 2: ask with timeout — process is too slow ────────────────────

    @Test
    void ask_timeout_completesExceptionally() {
        var proc = new Proc<>(0, ProcAskTimeoutTest::handle);

        // Slow work will block the mailbox for 500ms
        proc.tell(new Msg.SlowWork(500));

        // ask with a very short timeout — should time out
        var future = proc.ask(new Msg.Ping(), Duration.ofMillis(50));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        // The process itself is still alive (timeout is caller-side only)
        try {
            proc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Test 3: unbounded ask still works (no regression) ─────────────────

    @Test
    void ask_unbounded_stillWorks() throws Exception {
        var proc = new Proc<>(0, ProcAskTimeoutTest::handle);

        proc.tell(new Msg.SlowWork(10));
        var state = proc.ask(new Msg.Ping()).get(2, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1);

        proc.stop();
    }
}
