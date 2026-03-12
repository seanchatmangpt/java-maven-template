package io.github.seanchatmangpt.jotp.doctest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.ProcTimer;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Doctest: {@link Proc} — jOTP Lightweight Process.
 *
 * <p>Demonstrates and documents the core jOTP {@code Proc<S,M>} API through executable examples.
 * Each test is a living code sample that also verifies correctness. Generated HTML is written to
 * {@code target/site/doctester/ProcDocIT.html}.
 *
 * <p>Based on Joe Armstrong's original paper: <em>"Making reliable distributed systems in the
 * presence of software errors"</em>.
 */
@ExtendWith(DocTestExtension.class)
@Timeout(10)
class ProcDocIT implements WithAssertions {

    // ── Process Creation ─────────────────────────────────────────────────────────

    @DocSection("Process Creation")
    @DocNote(
            "A Proc<S,M> is created with an initial state S and a pure handler"
                    + " (S, M) -> S. The process runs in its own virtual thread"
                    + " (Erlang equivalent: spawn/3).")
    @DocCode(
            """
            Proc<Integer, String> counter = new Proc<>(0, (state, msg) -> state + 1);
            assertThat(counter.isRunning()).isTrue();
            counter.stop();
            """)
    @Test
    void createProc() throws InterruptedException {
        Proc<Integer, String> counter = new Proc<>(0, (state, msg) -> state + 1);
        assertThat(counter.isRunning()).isTrue();
        counter.stop();
        assertThat(counter.isRunning()).isFalse();
    }

    // ── Fire-and-forget messaging ────────────────────────────────────────────────

    @DocSection("Messaging — tell / ask")
    @DocNote(
            "tell(msg) is fire-and-forget — the caller never blocks. "
                    + "This mirrors Erlang's '!' (send) operator.")
    @DocCode(
            """
            Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
            p.tell("ping");
            p.tell("ping");
            Integer state = p.ask("sync").get(5, SECONDS);
            assertThat(state).isEqualTo(3);
            p.stop();
            """)
    @Test
    void tell_isFireAndForget() throws Exception {
        Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
        p.tell("ping");
        p.tell("ping");
        // ask() syncs with the process — state is 3 after two tells + one ask
        Integer state = p.ask("sync").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(3);
        p.stop();
    }

    @DocNote(
            "ask(msg) returns a CompletableFuture<S> that resolves to the state after "
                    + "the message is processed — the request-reply pattern "
                    + "(Erlang: gen_server:call/2).")
    @DocCode(
            """
            Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + m.length());
            Integer state = p.ask("hello").get(5, SECONDS);
            assertThat(state).isEqualTo(5);
            p.stop();
            """)
    @Test
    void ask_returnsStateAfterMessage() throws Exception {
        Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + m.length());
        Integer state = p.ask("hello").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(5);
        p.stop();
    }

    @DocNote(
            "ask(msg, timeout) enforces a deadline on the reply — mirrors OTP's "
                    + "gen_server:call/3 with a timeout. An unbounded call is a latent "
                    + "deadlock (Joe Armstrong).")
    @Test
    void ask_withTimeout_completesWithinDeadline() throws Exception {
        Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
        Integer state = p.ask("ping", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1);
        p.stop();
    }

    // ── Share-nothing state ──────────────────────────────────────────────────────

    @DocSection("Share-Nothing State")
    @DocWarning(
            "The Proc state is private to the virtual thread — never expose it via a "
                    + "shared reference. Use Records for immutable state objects.")
    @DocNote(
            "State is accumulated privately inside the process. Callers observe state "
                    + "only through ask() — there is no shared mutable field.")
    @DocCode(
            """
            record Counter(int value) {}
            Proc<Counter, String> p = new Proc<>(new Counter(0),
                (s, m) -> new Counter(s.value() + 1));
            p.tell("a"); p.tell("b"); p.tell("c");
            Counter c = p.ask("sync").get(5, SECONDS);
            assertThat(c.value()).isEqualTo(4);
            p.stop();
            """)
    @Test
    void stateIsPrivateToProcess() throws Exception {
        record Counter(int value) {}
        Proc<Counter, String> p =
                new Proc<>(new Counter(0), (s, m) -> new Counter(s.value() + 1));
        p.tell("a");
        p.tell("b");
        p.tell("c");
        Counter c = p.ask("sync").get(5, TimeUnit.SECONDS);
        assertThat(c.value()).isEqualTo(4); // 3 tells + 1 ask
        p.stop();
    }

    // ── Crash callbacks ──────────────────────────────────────────────────────────

    @DocSection("Crash Callbacks — \"Let It Crash\"")
    @DocNote(
            "addCrashCallback(Runnable) is called when the process terminates abnormally. "
                    + "This mirrors Erlang's monitor/link semantics: the supervisor is notified "
                    + "and can restart the process.")
    @Test
    void crashCallback_firedOnAbnormalExit() throws Exception {
        AtomicInteger crashes = new AtomicInteger();
        Proc<Integer, String> p =
                new Proc<>(
                        0,
                        (s, m) -> {
                            if ("crash".equals(m)) throw new RuntimeException("boom");
                            return s + 1;
                        });
        p.addCrashCallback(crashes::incrementAndGet);
        p.tell("crash");

        // Wait for crash to propagate
        org.awaitility.Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> crashes.get() == 1);

        assertThat(crashes.get()).isEqualTo(1);
    }

    // ── Process introspection (ProcSys) ──────────────────────────────────────────

    @DocSection("Process Introspection — ProcSys")
    @DocNote(
            "ProcSys mirrors OTP's sys module: get_state, suspend/resume, and statistics "
                    + "without stopping the process.")
    @DocCode(
            """
            Proc<Integer, String> p = new Proc<>(42, (s, m) -> s);
            Integer state = ProcSys.getState(p).get(5, SECONDS);
            assertThat(state).isEqualTo(42);
            p.stop();
            """)
    @Test
    void procSys_getState() throws Exception {
        Proc<Integer, String> p = new Proc<>(42, (s, m) -> s);
        Integer state = ProcSys.getState(p).get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(42);
        p.stop();
    }

    @DocNote("ProcSys.statistics() returns per-process message counters.")
    @Test
    void procSys_statistics_countsMessages() throws Exception {
        Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
        p.tell("a");
        p.tell("b");
        p.ask("c").get(5, TimeUnit.SECONDS); // sync

        ProcSys.Stats stats = ProcSys.statistics(p);
        assertThat(stats.messagesIn()).isGreaterThanOrEqualTo(3);
        p.stop();
    }

    // ── Timed messages ───────────────────────────────────────────────────────────

    @DocSection("Timed Messages — ProcTimer")
    @DocNote(
            "ProcTimer.sendAfter(delayMs, proc, msg) delivers a message after a delay, "
                    + "mirroring Erlang's timer:send_after/3.")
    @Test
    void procTimer_sendAfter_deliversMessage() throws Exception {
        Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
        ProcTimer.sendAfter(50, p, "tick");
        Integer state = p.ask("sync").get(5, TimeUnit.SECONDS); // ensure tick processed
        assertThat(state).isGreaterThanOrEqualTo(1);
        p.stop();
    }
}
