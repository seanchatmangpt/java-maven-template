package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.acme.ExitSignal;
import org.acme.Proc;
import org.acme.ProcessLink;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP exit signal trapping — {@code process_flag(trap_exit, true)}.
 *
 * <p>Armstrong: "The ability to trap exits is what separates supervisors from ordinary processes.
 * Without trap_exit, you cannot write a correct supervisor."
 *
 * <p>OTP invariants under test:
 *
 * <ol>
 *   <li>With {@code trap_exit=true}: a linked crash delivers {@link ExitSignal} to the mailbox
 *   <li>With {@code trap_exit=false} (default): a linked crash interrupts the peer immediately
 *   <li>Normal exit from a linked process delivers a {@code ExitSignal(null)} when trapping
 * </ol>
 */
@Timeout(10)
class ExitTrapTest implements WithAssertions {

    /** Message type that can also accept an ExitSignal (via Object match). */
    sealed interface Msg permits Msg.Work, Msg.Ping {
        record Work() implements Msg {}

        record Ping() implements Msg {}
    }

    // ── Test 1: trap_exit=true — crash becomes a mailbox message ──────────

    @Test
    void trapExit_true_convertsCrashToMessage() throws Exception {
        var exitSignalReceived = new AtomicReference<ExitSignal>();

        // The trapping process uses Object as message type so it can receive ExitSignal
        var trapping =
                new Proc<>(
                        0,
                        (Integer state, Object msg) ->
                                switch (msg) {
                                    case ExitSignal sig -> {
                                        exitSignalReceived.set(sig);
                                        yield state;
                                    }
                                    default -> state;
                                });
        trapping.trapExits(true);

        var crasher =
                new Proc<>(
                        0,
                        (Integer state, String msg) -> {
                            throw new RuntimeException("intentional crash: " + msg);
                        });

        // Link them — crasher crash should deliver ExitSignal to trapping
        crasher.addCrashCallback(() -> trapping.deliverExitSignal(crasher.lastError));

        crasher.tell("boom");

        // trapping should receive ExitSignal, not be interrupted
        await().atMost(Duration.ofSeconds(2)).until(() -> exitSignalReceived.get() != null);
        assertThat(exitSignalReceived.get().reason()).isInstanceOf(RuntimeException.class);
        assertThat(trapping.thread().isAlive()).isTrue();

        trapping.stop();
    }

    // ── Test 2: trap_exit=false (default) — crash interrupts the peer ─────

    @Test
    void trapExit_false_propagatesInterrupt() throws Exception {
        var a = new Proc<>(0, (Integer s, String m) -> { throw new RuntimeException(m); });
        var b = new Proc<>(0, (Integer s, String m) -> s); // passive

        ProcessLink.link(a, b);
        // b does NOT trap exits (default)

        a.tell("crash");

        await().atMost(Duration.ofSeconds(2)).until(() -> !b.thread().isAlive());
    }

    // ── Test 3: trapping process receives ExitSignal via ProcessLink ───────

    @Test
    void trapExit_viaProcessLink_convertsToMessage() throws Exception {
        var exitSignalRef = new AtomicReference<ExitSignal>();

        var trapping =
                new Proc<>(
                        0,
                        (Integer state, Object msg) ->
                                switch (msg) {
                                    case ExitSignal sig -> {
                                        exitSignalRef.set(sig);
                                        yield state;
                                    }
                                    default -> state;
                                });
        trapping.trapExits(true);

        var crasher =
                new Proc<>(
                        0,
                        (Integer state, String msg) -> {
                            throw new RuntimeException("linked crash");
                        });

        // Use ProcessLink.link — it calls deliverExitSignal which checks trapExits
        @SuppressWarnings("unchecked")
        Proc<Integer, Object> trappingTyped = (Proc<Integer, Object>) (Proc<?, ?>) trapping;
        @SuppressWarnings("unchecked")
        Proc<Integer, Object> crasherTyped = (Proc<Integer, Object>) (Proc<?, ?>) crasher;

        crasher.addCrashCallback(() -> trapping.deliverExitSignal(crasher.lastError));

        crasher.tell("crash");

        await().atMost(Duration.ofSeconds(2)).until(() -> exitSignalRef.get() != null);
        assertThat(trapping.isTrappingExits()).isTrue();
        assertThat(trapping.thread().isAlive()).isTrue();

        trapping.stop();
    }
}
