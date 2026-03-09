package org.acme;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link Supervisor} — the OTP supervision tree equivalent.
 *
 * <p>Covers: ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE restart strategies, max-restarts threshold.
 */
@Timeout(15)
class SupervisorTest implements WithAssertions {

    // ── Helper: a process that crashes on "crash" message ────────────────────

    private static String echoHandler(String state, String msg) {
        if ("crash".equals(msg)) throw new RuntimeException("intentional crash");
        return state + msg;
    }

    // ── ONE_FOR_ONE: only crashed child restarts ──────────────────────────────

    @Test
    void oneForOne_restartsCrashedChildOnly() throws Exception {
        var supervisor = new Supervisor(
                Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(30));

        var childA = supervisor.supervise("A", "", SupervisorTest::echoHandler);
        var childB = supervisor.supervise("B", "", SupervisorTest::echoHandler);

        // Send normal messages to both
        childA.tell("hello");
        childB.tell("world");

        // Crash child A
        childA.tell("crash");

        // Wait for A to restart (ProcRef transparently redirects)
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                return childA.ask("ping").get(500, TimeUnit.MILLISECONDS) != null;
            } catch (Exception e) {
                return false;
            }
        });

        // B should still have its state intact ("world")
        var stateB = childB.ask("").get(3, TimeUnit.SECONDS);
        assertThat(stateB).contains("world");

        // A was restarted with fresh state — so it won't have "hello"
        var stateA = childA.ask("").get(3, TimeUnit.SECONDS);
        assertThat(stateA).doesNotContain("hello");

        supervisor.shutdown();
    }

    // ── ONE_FOR_ALL: all children restart when one crashes ───────────────────

    @Test
    void oneForAll_restartsAllChildren() throws Exception {
        var supervisor = new Supervisor(
                Supervisor.Strategy.ONE_FOR_ALL, 5, Duration.ofSeconds(30));

        var childA = supervisor.supervise("A", "", SupervisorTest::echoHandler);
        var childB = supervisor.supervise("B", "", SupervisorTest::echoHandler);

        // Accumulate state in both
        childA.ask("stateA").get(3, TimeUnit.SECONDS);
        childB.ask("stateB").get(3, TimeUnit.SECONDS);

        // Crash A — triggers ONE_FOR_ALL restart of both
        childA.tell("crash");

        // Wait for both to be back alive (ask will succeed once restarted)
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                childA.ask("ping").get(300, TimeUnit.MILLISECONDS);
                childB.ask("ping").get(300, TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        // Both were restarted — fresh state, no accumulated strings
        var stateA = childA.ask("").get(3, TimeUnit.SECONDS);
        var stateB = childB.ask("").get(3, TimeUnit.SECONDS);
        assertThat(stateA).doesNotContain("stateA");
        assertThat(stateB).doesNotContain("stateB");

        supervisor.shutdown();
    }

    // ── REST_FOR_ONE: crashed child + successors restart ─────────────────────

    @Test
    void restForOne_restartsFromCrashedChildOnward() throws Exception {
        var supervisor = new Supervisor(
                Supervisor.Strategy.REST_FOR_ONE, 5, Duration.ofSeconds(30));

        var childA = supervisor.supervise("A", "", SupervisorTest::echoHandler);
        var childB = supervisor.supervise("B", "", SupervisorTest::echoHandler);
        var childC = supervisor.supervise("C", "", SupervisorTest::echoHandler);

        // Accumulate state in all
        childA.ask("alpha").get(3, TimeUnit.SECONDS);
        childB.ask("beta").get(3, TimeUnit.SECONDS);
        childC.ask("gamma").get(3, TimeUnit.SECONDS);

        // Crash B — REST_FOR_ONE should restart B and C (registered after B), leave A alone
        childB.tell("crash");

        // Wait for B and C to come back
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                childB.ask("ping").get(300, TimeUnit.MILLISECONDS);
                childC.ask("ping").get(300, TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        // A keeps its state
        var stateA = childA.ask("").get(3, TimeUnit.SECONDS);
        assertThat(stateA).contains("alpha");

        // B and C are fresh
        var stateB = childB.ask("").get(3, TimeUnit.SECONDS);
        var stateC = childC.ask("").get(3, TimeUnit.SECONDS);
        assertThat(stateB).doesNotContain("beta");
        assertThat(stateC).doesNotContain("gamma");

        supervisor.shutdown();
    }

    // ── Max restarts exceeded terminates supervisor ───────────────────────────

    @Test
    void maxRestarts_exceeded_terminatesSupervisor() throws Exception {
        var supervisor = new Supervisor(
                Supervisor.Strategy.ONE_FOR_ONE, 2, Duration.ofSeconds(30));

        var child = supervisor.supervise("crasher", "", SupervisorTest::echoHandler);

        // Crash 3 times (maxRestarts=2 → on 3rd crash supervisor dies)
        for (int i = 0; i < 3; i++) {
            child.tell("crash");
            // Wait briefly between crashes so supervisor processes each event
            Thread.sleep(100);
        }

        Awaitility.await().atMost(8, TimeUnit.SECONDS)
                .until(() -> !supervisor.isRunning());

        assertThat(supervisor.fatalError()).isNotNull();
        assertThat(supervisor.fatalError()).hasMessage("intentional crash");
    }

    // ── Graceful shutdown stops all children ─────────────────────────────────

    @Test
    void shutdown_stopsAllChildren() throws Exception {
        var supervisor = new Supervisor(
                Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(30));

        var childA = supervisor.supervise("A", "", SupervisorTest::echoHandler);
        var childB = supervisor.supervise("B", "", SupervisorTest::echoHandler);

        supervisor.shutdown();
        assertThat(supervisor.isRunning()).isFalse();
    }

    // ── Supervisor is running initially ──────────────────────────────────────

    @Test
    void supervisor_isRunning_initially() throws Exception {
        var supervisor = new Supervisor(
                Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(30));
        assertThat(supervisor.isRunning()).isTrue();
        assertThat(supervisor.fatalError()).isNull();
        supervisor.shutdown();
    }
}
