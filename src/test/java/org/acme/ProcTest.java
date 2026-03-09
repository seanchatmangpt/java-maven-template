package org.acme;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link Proc} — the virtual-thread process primitive.
 *
 * <p>Covers: tell, ask, crash callbacks, trapExits, graceful stop.
 */
@Timeout(10)
class ProcTest implements WithAssertions {

    // ── tell: fire-and-forget ────────────────────────────────────────────────

    @Test
    void tell_updatesState() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state + (int) msg);

        proc.tell(1);
        proc.tell(2);
        proc.tell(3);

        // ask syncs — we see state after all prior tells are processed
        var state = proc.ask(0).get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(6);
        proc.stop();
    }

    // ── ask: request-reply ───────────────────────────────────────────────────

    @Test
    void ask_returnsStateAfterMessage() throws Exception {
        var proc = new Proc<>("", (state, msg) -> state + msg);

        var state = proc.ask("hello").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo("hello");

        state = proc.ask(" world").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo("hello world");
        proc.stop();
    }

    // ── ask with timeout ─────────────────────────────────────────────────────

    @Test
    void ask_withTimeout_completesIfFast() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state + (int) msg);

        var state = proc.ask(42, Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(42);
        proc.stop();
    }

    // ── crash callback ───────────────────────────────────────────────────────

    @Test
    void crashCallback_firedOnException() throws Exception {
        var crashed = new AtomicInteger(0);
        var proc = new Proc<Integer, String>(0, (state, msg) -> {
            if ("boom".equals(msg)) throw new RuntimeException("intentional crash");
            return state;
        });
        proc.addCrashCallback(crashed::incrementAndGet);

        proc.tell("boom");

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> crashed.get() == 1);
        assertThat(proc.lastError).hasMessage("intentional crash");
    }

    // ── graceful stop does NOT fire crash callback ───────────────────────────

    @Test
    void stop_doesNotFireCrashCallback() throws Exception {
        var crashed = new AtomicInteger(0);
        var proc = new Proc<>(0, (state, msg) -> state);
        proc.addCrashCallback(crashed::incrementAndGet);

        proc.stop();

        // Give it a moment to ensure no callback fires
        Thread.sleep(100);
        assertThat(crashed.get()).isZero();
    }

    // ── trapExits: ExitSignal becomes a mailbox message ──────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void trapExits_convertsSignalToMailboxMessage() throws Exception {
        var received = new AtomicReference<ExitSignal>(null);

        // The message type must accommodate ExitSignal (cast is safe in test context)
        var proc = new Proc<String, Object>("", (state, msg) -> {
            if (msg instanceof ExitSignal sig) {
                received.set(sig);
            }
            return state;
        });
        proc.trapExits(true);
        assertThat(proc.isTrappingExits()).isTrue();

        var cause = new RuntimeException("link died");
        proc.deliverExitSignal(cause);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(received.get().reason()).isSameAs(cause);
        proc.stop();
    }

    // ── trapExits false: signal kills the process ────────────────────────────

    @Test
    void noTrapExits_signalKillsProcess() throws Exception {
        var crashed = new AtomicInteger(0);
        var proc = new Proc<>(0, (state, msg) -> state);
        proc.addCrashCallback(crashed::incrementAndGet);
        proc.trapExits(false);

        proc.deliverExitSignal(new RuntimeException("remote crash"));

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> !proc.thread().isAlive());
    }

    // ── multiple crash callbacks (composable) ────────────────────────────────

    @Test
    void multipleCrashCallbacks_allFired() throws Exception {
        var count = new AtomicInteger(0);
        var proc = new Proc<Integer, String>(0, (state, msg) -> {
            throw new RuntimeException("crash");
        });
        proc.addCrashCallback(count::incrementAndGet);
        proc.addCrashCallback(count::incrementAndGet);
        proc.addCrashCallback(count::incrementAndGet);

        proc.tell("any");

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> count.get() == 3);
    }

    // ── high-volume tell: mailbox serializes correctly ───────────────────────

    @Test
    void tell_manyMessages_allProcessed() throws Exception {
        int count = 500;
        var proc = new Proc<>(0, (state, msg) -> state + 1);

        for (int i = 0; i < count; i++) proc.tell("x");

        var state = proc.ask("x").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(count + 1);
        proc.stop();
    }
}
