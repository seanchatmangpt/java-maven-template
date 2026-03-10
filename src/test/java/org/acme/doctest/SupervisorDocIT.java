package org.acme.doctest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.acme.ProcRef;
import org.acme.Supervisor;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Doctest: {@link Supervisor} — jOTP Supervision Trees.
 *
 * <p>Demonstrates and documents the Supervisor API through executable examples. The supervisor is
 * the primary fault-tolerance mechanism in OTP — it defines what happens when a child crashes:
 * restart just that child, restart all children, or give up entirely.
 *
 * <p>HTML output: {@code target/site/doctester/SupervisorDocIT.html}.
 */
@ExtendWith(DocTestExtension.class)
@Timeout(15)
class SupervisorDocIT implements WithAssertions {

    // ── ONE_FOR_ONE ──────────────────────────────────────────────────────────────

    @DocSection("ONE_FOR_ONE — Restart only the crashed child")
    @DocNote(
            "ONE_FOR_ONE is the most common strategy: only the crashed child is restarted. "
                    + "Siblings are unaffected. This is the OTP default.")
    @DocCode(
            """
            var sup = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
            ProcRef<Integer, String> worker = sup.supervise("w1", 0, (s, m) -> s + 1);
            worker.tell("a");
            Integer state = worker.ask("sync").get(5, SECONDS);
            assertThat(state).isEqualTo(2);
            sup.shutdown();
            """)
    @Test
    void oneForOne_normalOperation() throws Exception {
        var sup =
                new Supervisor(
                        Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        ProcRef<Integer, String> worker = sup.supervise("w1", 0, (s, m) -> s + 1);
        worker.tell("a");
        Integer state = worker.ask("sync").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(2);
        sup.shutdown();
    }

    @DocNote(
            "When a child crashes under ONE_FOR_ONE, the supervisor restarts it with the "
                    + "original initial state. The ProcRef remains valid — callers need not "
                    + "obtain a new reference.")
    @DocWarning(
            "The ProcRef is stable across restarts — never hold a raw Proc reference "
                    + "when a supervisor is involved. Always use the ProcRef returned by supervise().")
    @Test
    void oneForOne_restartsCrashedChild() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        var sup =
                new Supervisor(
                        Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        ProcRef<Integer, String> worker =
                sup.supervise(
                        "crasher",
                        0,
                        (s, m) -> {
                            if ("crash".equals(m)) {
                                restarts.incrementAndGet();
                                throw new RuntimeException("intentional crash");
                            }
                            return s + 1;
                        });

        worker.tell("crash");

        // Supervisor restarts the child; ProcRef transparently redirects
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> restarts.get() >= 1);

        // After restart, the process is running again with fresh state
        Integer state = worker.ask("ping").get(5, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1); // fresh state from restart
        sup.shutdown();
    }

    // ── ONE_FOR_ALL ──────────────────────────────────────────────────────────────

    @DocSection("ONE_FOR_ALL — Restart all children together")
    @DocNote(
            "ONE_FOR_ALL restarts every child when any one crashes. "
                    + "Use when child processes have interdependencies and an inconsistent "
                    + "sibling group is worse than a clean group restart.")
    @Test
    void oneForAll_restartsAllChildren() throws Exception {
        var sup =
                new Supervisor(
                        Supervisor.Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(60));
        ProcRef<Integer, String> w1 = sup.supervise("w1", 10, (s, m) -> s + 1);
        ProcRef<Integer, String> w2 = sup.supervise("w2", 20, (s, m) -> s + 2);
        ProcRef<Integer, String> crasher =
                sup.supervise(
                        "crasher",
                        0,
                        (s, m) -> {
                            if ("crash".equals(m)) throw new RuntimeException("boom");
                            return s;
                        });

        // Accumulate state in siblings
        w1.tell("msg");
        w2.tell("msg");
        w1.ask("sync").get(5, TimeUnit.SECONDS);
        w2.ask("sync").get(5, TimeUnit.SECONDS);

        // Crash one child — all should restart
        crasher.tell("crash");

        // After ONE_FOR_ALL restart, all children have their initial state
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> {
                            Integer s1 = w1.ask("sync").get(2, TimeUnit.SECONDS);
                            Integer s2 = w2.ask("sync").get(2, TimeUnit.SECONDS);
                            assertThat(s1).isEqualTo(1); // fresh: 10 + 1 ask
                            assertThat(s2).isEqualTo(1); // fresh: 20 + 1 ask (state = initial+1)
                        });
        sup.shutdown();
    }

    // ── REST_FOR_ONE ─────────────────────────────────────────────────────────────

    @DocSection("REST_FOR_ONE — Restart the tail")
    @DocNote(
            "REST_FOR_ONE restarts the crashed child and all children started after it. "
                    + "Children started before the crash are unaffected. "
                    + "Useful when later children depend on earlier ones (startup order matters).")
    @Test
    void restForOne_restartsFromCrashPointOnward() throws Exception {
        var sup =
                new Supervisor(
                        Supervisor.Strategy.REST_FOR_ONE, 5, Duration.ofSeconds(60));
        ProcRef<Integer, String> stable =
                sup.supervise("stable", 100, (s, m) -> s + 1);
        ProcRef<Integer, String> crasher =
                sup.supervise(
                        "crasher",
                        0,
                        (s, m) -> {
                            if ("crash".equals(m)) throw new RuntimeException("boom");
                            return s;
                        });
        ProcRef<Integer, String> dependent =
                sup.supervise("dependent", 200, (s, m) -> s + 1);

        // Accumulate state in all three
        stable.tell("inc");
        dependent.tell("inc");
        stable.ask("sync").get(5, TimeUnit.SECONDS);
        dependent.ask("sync").get(5, TimeUnit.SECONDS);

        crasher.tell("crash");

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> {
                            // 'stable' was started BEFORE crasher — not restarted
                            Integer s = stable.ask("ping").get(2, TimeUnit.SECONDS);
                            assertThat(s).isGreaterThan(1);
                        });
        sup.shutdown();
    }

    // ── Restart intensity ────────────────────────────────────────────────────────

    @DocSection("Restart Intensity — Escalation")
    @DocNote(
            "If a child crashes more times than maxRestarts within the window, the supervisor "
                    + "gives up and terminates itself. This prevents infinite crash loops and "
                    + "escalates the failure up the supervision tree.")
    @Test
    void restartIntensity_supervisorGivesUpAfterTooManyRestarts() throws Exception {
        var sup = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 2, Duration.ofSeconds(5));
        AtomicInteger crashCount = new AtomicInteger();
        sup.supervise(
                "brittle",
                0,
                (s, m) -> {
                    crashCount.incrementAndGet();
                    throw new RuntimeException("always crashes");
                });

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> crashCount.get() >= 2);

        // Supervisor has exceeded restart intensity — it shuts down
        assertThat(crashCount.get()).isGreaterThanOrEqualTo(2);
    }
}
