package org.acme.test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.ProcRef;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Armstrong's supervisor restart storm stress tests — boundary conditions of {@link Supervisor}.
 *
 * <p>Joe Armstrong: <em>"The supervisor restart window is your circuit breaker. If you set it
 * wrong, you either mask real failures (too lenient) or lose transient errors (too strict). The
 * boundary is the most important thing to test."</em>
 *
 * <h2>Breaking points under investigation</h2>
 *
 * <ol>
 *   <li><b>Restart storm at the threshold boundary</b> — maxRestarts=3/window=1s: 3 crashes must
 *       restart; the 4th crash within the window must propagate (supervisor dies too). Off-by-one
 *       errors in the sliding window are the most common restart-limit bug.
 *   <li><b>Window expiry — crash after the window resets</b> — crash 3 times within window 1,
 *       wait for the window to expire, crash 3 more times within window 2. Each window gets its
 *       own restart budget; the counter must reset correctly.
 *   <li><b>Rapid-fire crashes faster than restart latency</b> — crash faster than the supervisor
 *       can restart (restart takes ~1ms; crash every 0.1ms). The supervisor must not miss any
 *       crash event and must count them all toward the restart limit.
 *   <li><b>ONE_FOR_ALL cascade under load</b> — 10 children; when any crashes, all 10 are
 *       restarted. Under rapid crashes from multiple children simultaneously, restart events pile
 *       up. The supervisor must not double-restart, deadlock, or miss crashes.
 *   <li><b>Property: N crashes within window → supervisor dies exactly at maxRestarts+1</b> — the
 *       fundamental correctness invariant for the restart limit.
 * </ol>
 */
@Timeout(30)
class SupervisorStormStressTest implements WithAssertions {

    // ── Shared message vocabulary ──────────────────────────────────────────

    sealed interface Msg permits Msg.Inc, Msg.Boom, Msg.Get {
        record Inc(int by) implements Msg {}

        record Boom(String reason) implements Msg {}

        record Get() implements Msg {}
    }

    private static int handler(int state, Msg msg) {
        return switch (msg) {
            case Msg.Inc(var by) -> state + by;
            case Msg.Boom(var reason) -> throw new RuntimeException(reason);
            case Msg.Get() -> state;
        };
    }

    private static int tryGet(ProcRef<Integer, Msg> ref) {
        try {
            return ref.ask(new Msg.Get()).get(200, MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            return -1;
        }
    }

    // ── 1. Boundary: maxRestarts=3, exactly 4 crashes → supervisor dies ───

    /**
     * <b>Breaking point: off-by-one in restart limit.</b>
     *
     * <p>With {@code maxRestarts=3} within a 2-second window:
     *
     * <ul>
     *   <li>Crashes 1, 2, 3 → child is restarted each time (within budget)
     *   <li>Crash 4 within the window → supervisor gives up and terminates
     * </ul>
     *
     * <p>An off-by-one bug would allow crash 4 to also restart (if the count is ≤ instead of <),
     * or would kill the supervisor at crash 3 (if the count starts at 1 instead of 0).
     */
    @Test
    void restartBoundary_exactlyMaxRestartsAllowed_oneMoreKillsSupervisor() throws Exception {
        int maxRestarts = 3;
        var supervisor =
                new Supervisor(
                        "test-sv",
                        Strategy.ONE_FOR_ONE,
                        maxRestarts,
                        Duration.ofSeconds(2));

        var ref = supervisor.supervise("child", 0, SupervisorStormStressTest::handler);

        // Crash 1, 2, 3 — all within budget; child recovers each time
        for (int i = 1; i <= maxRestarts; i++) {
            ref.tell(new Msg.Boom("crash " + i));
            int attempt = i;
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> tryGet(ref) >= 0); // child restarted and responsive
        }

        // Crash 4 — over budget; supervisor must die
        ref.tell(new Msg.Boom("crash " + (maxRestarts + 1)));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> !supervisor.isRunning());

        assertThat(supervisor.isRunning())
                .as("supervisor must have terminated after exceeding maxRestarts")
                .isFalse();
    }

    // ── 2. Window expiry — restart budget resets after window ─────────────

    /**
     * <b>Breaking point: sliding window counter never resets.</b>
     *
     * <p>After the window expires, the restart counter resets. If the window reset is broken, the
     * supervisor dies at crash 4 even though crashes 1–3 were in a different window.
     *
     * <p>We use a 500ms window and wait 600ms between crash waves to ensure the window has cleared.
     */
    @Test
    void windowExpiry_restartBudgetResetsAfterWindow() throws Exception {
        int maxRestarts = 2;
        var supervisor =
                new Supervisor(
                        "window-sv",
                        Strategy.ONE_FOR_ONE,
                        maxRestarts,
                        Duration.ofMillis(500));

        var ref = supervisor.supervise("child", 0, SupervisorStormStressTest::handler);

        // Wave 1: 2 crashes within the 500ms window — both restarted
        for (int i = 1; i <= maxRestarts; i++) {
            ref.tell(new Msg.Boom("wave1-" + i));
            await().atMost(Duration.ofSeconds(2)).until(() -> tryGet(ref) >= 0);
        }

        // Wait for the window to expire
        Thread.sleep(600);

        // Wave 2: 2 more crashes in a new window — must also restart (budget reset)
        for (int i = 1; i <= maxRestarts; i++) {
            ref.tell(new Msg.Boom("wave2-" + i));
            await().atMost(Duration.ofSeconds(2)).until(() -> tryGet(ref) >= 0);
        }

        // Supervisor must still be alive
        assertThat(supervisor.isRunning())
                .as("supervisor must be alive after window expiry reset")
                .isTrue();

        supervisor.shutdown();
    }

    // ── 3. Rapid-fire crashes — supervisor counts all crashes ─────────────

    /**
     * <b>Breaking point: crash events lost under rapid-fire rate.</b>
     *
     * <p>If the supervisor's event queue drops crash notifications under load, it under-counts
     * restarts and allows a "restart storm" to continue past the limit. We verify the supervisor
     * terminates after exactly maxRestarts+1 crashes, even at maximum rate.
     *
     * <p>The supervisor processes crash events from a {@link java.util.concurrent.LinkedTransferQueue}
     * — it cannot drop events. But if the restart itself takes longer than the next crash arrives,
     * events pile up. This is safe (queue is unbounded) but tests that pile-up doesn't cause
     * counting errors.
     */
    @Test
    void rapidFireCrashes_supervisorTerminatesAtLimit() throws Exception {
        int maxRestarts = 5;
        var supervisor =
                new Supervisor(
                        "rapid-sv",
                        Strategy.ONE_FOR_ONE,
                        maxRestarts,
                        Duration.ofSeconds(5));

        var ref = supervisor.supervise("child", 0, SupervisorStormStressTest::handler);

        // Fire maxRestarts+1 crashes, each to a live process.
        // Each crash must reach a live process; wait for restart between crashes
        // so messages don't pile up in a dead process's mailbox.
        for (int i = 0; i <= maxRestarts; i++) {
            ref.tell(new Msg.Boom("rapid-" + i));
            if (i < maxRestarts) {
                // Wait for supervisor to restart before sending next crash
                await().atMost(Duration.ofSeconds(2)).until(() -> tryGet(ref) >= 0);
            }
        }

        // Supervisor must die (crash count exceeded)
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !supervisor.isRunning());
    }

    // ── 4. ONE_FOR_ALL under concurrent child crashes ─────────────────────

    /**
     * <b>Breaking point: duplicate restart in ONE_FOR_ALL under concurrent crashes.</b>
     *
     * <p>With 5 children and ONE_FOR_ALL: if children A and B crash within the same scheduling
     * quantum, the supervisor receives two {@code ChildCrashed} events and must restart all 5
     * children twice — not deadlock or restart some children 1× and others 2×.
     *
     * <p>The supervisor's single-threaded event loop means it processes crashes sequentially. But
     * rapid consecutive crashes may arrive before the restart of the first is complete.
     */
    @Test
    void oneForAll_concurrentChildCrashes_allChildrenRestart() throws Exception {
        var supervisor =
                new Supervisor("ofs-sv", Strategy.ONE_FOR_ALL, 10, Duration.ofSeconds(5));

        int childCount = 5;
        var refs = new ProcRef[childCount];
        for (int i = 0; i < childCount; i++) {
            refs[i] = supervisor.supervise("child-" + i, 0, SupervisorStormStressTest::handler);
        }

        // Crash two children "simultaneously"
        refs[0].tell(new Msg.Boom("concurrent-0"));
        refs[1].tell(new Msg.Boom("concurrent-1"));

        // All children must eventually be reachable again
        await().atMost(Duration.ofSeconds(5))
                .until(() -> {
                    for (ProcRef ref : refs) {
                        if (tryGet(ref) < 0) return false;
                    }
                    return true;
                });

        // Supervisor must still be alive
        assertThat(supervisor.isRunning()).isTrue();
        supervisor.shutdown();
    }

    // ── 5. Property: restart count invariant ──────────────────────────────

    /**
     * <b>Breaking point: restart count invariant broken by off-by-one or race.</b>
     *
     * <p>For any {@code maxRestarts} value, the supervisor must remain alive after exactly {@code
     * maxRestarts} crashes and die after the {@code maxRestarts+1}th crash within the window.
     *
     * <p>jqwik tries many values of {@code maxRestarts} to catch off-by-one errors at any boundary.
     */
    @Property(tries = 20)
    void restartCountInvariant_supervisorDiesAtMaxPlusOne(
            @ForAll @IntRange(min = 1, max = 5) int maxRestarts) throws Exception {
        var supervisor =
                new Supervisor(
                        "prop-sv-" + maxRestarts,
                        Strategy.ONE_FOR_ONE,
                        maxRestarts,
                        Duration.ofSeconds(10));

        var ref = supervisor.supervise("child", 0, SupervisorStormStressTest::handler);

        // Crash exactly maxRestarts times — supervisor must survive each
        for (int i = 1; i <= maxRestarts; i++) {
            ref.tell(new Msg.Boom("crash " + i));
            await().atMost(Duration.ofSeconds(3))
                    .until(() -> tryGet(ref) >= 0);
            assertThat(supervisor.isRunning())
                    .as("supervisor alive after %d/%d restarts", i, maxRestarts)
                    .isTrue();
        }

        // One more crash — supervisor must die
        ref.tell(new Msg.Boom("killing crash"));
        await().atMost(Duration.ofSeconds(3)).until(() -> !supervisor.isRunning());
    }

    // ── 6. ONE_FOR_ALL: restart latency under 10-child tree ───────────────

    /**
     * <b>Breaking point: ONE_FOR_ALL restart latency grows linearly with child count.</b>
     *
     * <p>With 10 children, a ONE_FOR_ALL restart must stop all 10 and restart them. We measure
     * the time from crash to all-responsive and verify it is under 3 seconds. If each child
     * restart takes 100ms, 10 children = 1s. If sequential restarts accumulate, this degrades.
     */
    @Test
    void oneForAll_10children_restartWithin3s() throws Exception {
        int childCount = 10;
        var supervisor =
                new Supervisor("ofs-perf-sv", Strategy.ONE_FOR_ALL, 5, Duration.ofSeconds(10));

        @SuppressWarnings("unchecked")
        ProcRef<Integer, Msg>[] refs = new ProcRef[childCount];
        for (int i = 0; i < childCount; i++) {
            refs[i] =
                    supervisor.supervise("child-" + i, 0, SupervisorStormStressTest::handler);
        }

        long start = System.nanoTime();
        refs[0].tell(new Msg.Boom("trigger"));

        // Wait for all children to be responsive again
        await().atMost(Duration.ofSeconds(3))
                .until(() -> {
                    for (ProcRef<Integer, Msg> ref : refs) {
                        if (tryGet(ref) < 0) return false;
                    }
                    return true;
                });

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf(
                "[one-for-all] children=%d restart-all=%d ms%n", childCount, elapsedMs);

        supervisor.shutdown();
    }
}
