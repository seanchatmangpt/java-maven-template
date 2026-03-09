package org.acme.test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.ProcRef;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Demonstrates Joe Armstrong's OTP supervision tree in Java 26.
 *
 * <p>Armstrong: <em>"A supervisor process that is responsible for starting, stopping and monitoring
 * child processes is the key to reliable systems."</em>
 *
 * <p>Each test shows a different facet of supervision: crash-and-restart, isolation between
 * children (ONE_FOR_ONE), cascaded restart (ONE_FOR_ALL), and max-restart threshold propagation.
 */
@Execution(ExecutionMode.SAME_THREAD)  // Isolate from parallel tests due to timing sensitivity
class SupervisorTest implements WithAssertions {

    // ── Shared message vocabulary ──────────────────────────────────────────

    sealed interface CounterMsg permits CounterMsg.Inc, CounterMsg.Boom, CounterMsg.Get {
        record Inc(int by) implements CounterMsg {}

        record Boom(String reason) implements CounterMsg {}

        record Get() implements CounterMsg {}
    }

    private static int counterHandler(int state, CounterMsg msg) {
        return switch (msg) {
            case CounterMsg.Inc(var by) -> state + by;
            case CounterMsg.Boom(var reason) -> throw new RuntimeException(reason);
            case CounterMsg.Get() -> state;
        };
    }

    // ── Helper: ask with a short timeout (returns -1 on failure) ──────────

    private static int tryGet(ProcRef<Integer, CounterMsg> ref) {
        try {
            return ref.ask(new CounterMsg.Get()).get(200, MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return -1;
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void crashAndRestartResumesService() throws Exception {
        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
        var ref = sup.supervise("counter", 0, SupervisorTest::counterHandler);

        // Normal operation
        ref.tell(new CounterMsg.Inc(10));
        assertThat(ref.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(10);

        // Crash it
        ref.tell(new CounterMsg.Boom("injected fault"));

        // Supervisor restarts; process resets to initial state (0)
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(0));

        // Back to full operation after restart
        ref.tell(new CounterMsg.Inc(5));
        await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(5));

        sup.shutdown();
    }

    @Test
    void oneForOneOnlyRestartsTheCrashedChild() throws Exception {
        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
        var ref1 = sup.supervise("c1", 0, SupervisorTest::counterHandler);
        var ref2 = sup.supervise("c2", 100, SupervisorTest::counterHandler);

        // Accumulate state in c2 so we can tell if it was restarted
        ref2.tell(new CounterMsg.Inc(42));
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);

        // Crash only c1
        ref1.tell(new CounterMsg.Boom("c1 crash"));

        // c1 restarts to 0
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(tryGet(ref1)).isEqualTo(0));

        // c2 was NOT restarted — its state is preserved at 142
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(142);

        sup.shutdown();
    }

    @Test
    void oneForAllRestartsAllChildren() throws Exception {
        var sup = new Supervisor(Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(10));
        var ref1 = sup.supervise("c1", 0, SupervisorTest::counterHandler);
        var ref2 = sup.supervise("c2", 100, SupervisorTest::counterHandler);

        // Build up state in both
        ref1.tell(new CounterMsg.Inc(7));
        ref2.tell(new CounterMsg.Inc(9));
        assertThat(ref1.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(7);
        assertThat(ref2.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(109);

        // Crash c1 — ONE_FOR_ALL restarts both
        ref1.tell(new CounterMsg.Boom("trigger ONE_FOR_ALL"));

        // Both reset to their initial states (0 and 100)
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            assertThat(tryGet(ref1)).isEqualTo(0);
                            assertThat(tryGet(ref2)).isEqualTo(100);
                        });

        sup.shutdown();
    }

    @Test
    void maxRestartsExceededTerminatesSupervisor() throws Exception {
        // Allow only 2 restarts in a 5-second window
        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 2, Duration.ofSeconds(5));
        var ref = sup.supervise("fragile", 0, SupervisorTest::counterHandler);

        // Crash 3 times — 3rd pushes over maxRestarts=2
        for (int i = 0; i < 3; i++) {
            ref.tell(new CounterMsg.Boom("crash " + i));
            // Wait for restart before next crash to reliably hit the threshold
            Thread.sleep(50);
        }

        // Supervisor should have terminated itself
        await().atMost(Duration.ofSeconds(5)).until(() -> !sup.isRunning());
        assertThat(sup.fatalError()).isNotNull();
        assertThat(sup.fatalError().getMessage()).startsWith("crash");
    }

    @Property
    void supervisedProcessIsAlwaysEventuallyReachable(
            @ForAll @IntRange(min = 1, max = 5) int crashCount) throws Exception {
        var sup = new Supervisor(Strategy.ONE_FOR_ONE, crashCount + 2, Duration.ofSeconds(30));
        var ref = sup.supervise("resilient", 0, SupervisorTest::counterHandler);

        for (int i = 0; i < crashCount; i++) {
            ref.tell(new CounterMsg.Boom("crash " + i));
            // After each crash the process must eventually respond
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> tryGet(ref) >= 0); // -1 means timed-out (restarting)
        }

        // Final state: reachable and reset
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(0));

        assertThat(sup.isRunning()).isTrue();
        sup.shutdown();
    }
}
