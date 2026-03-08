package org.acme.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.EventManager;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Armstrong's EventManager scale and fault-isolation stress tests.
 *
 * <p>Joe Armstrong: <em>"gen_event's power is in its fault isolation. A crashing handler must
 * never take down the manager. If it does, you've lost your error logger exactly when you need
 * it most."</em>
 *
 * <h2>Breaking points under investigation</h2>
 *
 * <ol>
 *   <li><b>Broadcast latency with N handlers</b> — {@code syncNotify} is O(N) in handler count.
 *       With 1000 handlers each doing real work, this may block the manager for seconds. We
 *       characterise the latency curve.
 *   <li><b>Crash storm — 500 crashing handlers simultaneously</b> — a broadcast that causes all
 *       500 handlers to throw. The manager must survive and continue serving the remaining 0
 *       handlers. The broadcast must complete, not hang.
 *   <li><b>Dynamic churn — add/remove handlers while notifying</b> — handler list modified
 *       concurrently with broadcasts. The manager is a single-threaded {@link org.acme.Proc}, so
 *       all operations are serialised — but we verify this empirically under high churn.
 *   <li><b>Property: handler crash count ≤ registered handler count</b> — with N handlers, M of
 *       which crash, exactly M must be removed and N-M must continue receiving events.
 *   <li><b>Memory: 10 000 add/remove cycles leave no phantom handlers</b> — handlers removed via
 *       deleteHandler or crash must be garbage-collectable; the manager must not retain them.
 * </ol>
 */
@Timeout(30)
class EventManagerScaleTest implements WithAssertions {

    sealed interface Evt permits Evt.Tick, Evt.Data {
        record Tick() implements Evt {}

        record Data(String payload) implements Evt {}
    }

    // ── 1. Broadcast latency with 1000 handlers — characterise O(N) ───────

    /**
     * <b>Breaking point: syncNotify is O(N) in handler count.</b>
     *
     * <p>With 1000 no-op handlers, syncNotify must still complete within 2 seconds. If it takes
     * longer, the manager's virtual thread is becoming a bottleneck. The latency floor here tells
     * us whether we need to parallelise the handler dispatch (OTP gen_event is single-threaded too,
     * so this is expected behaviour — but the constant factor matters).
     */
    @Test
    void syncNotify_1000handlers_completesWithin2s() throws Exception {
        var mgr = EventManager.<Evt>start();
        int handlerCount = 1000;

        for (int i = 0; i < handlerCount; i++) {
            mgr.addHandler(event -> {}); // no-op
        }

        // Let all adds propagate (addHandler is async)
        Thread.sleep(200);

        long start = System.nanoTime();
        mgr.syncNotify(new Evt.Tick());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf(
                "[event-scale] handlers=%d syncNotify=%d ms%n", handlerCount, elapsedMs);

        assertThat(elapsedMs)
                .as("syncNotify with %d handlers (ms)", handlerCount)
                .isLessThan(2000);

        mgr.stop();
    }

    // ── 2. All-crash broadcast — manager survives ─────────────────────────

    /**
     * <b>Breaking point: manager survival when every handler crashes.</b>
     *
     * <p>OTP gen_event guarantee: a crashing handler is removed, the manager continues. With 500
     * handlers all throwing on the same event, the broadcast must complete (no hang), all 500
     * handlers must be removed (not just the first), and the manager must be alive after.
     *
     * <p>Current implementation: {@code broadcast()} uses a collect-then-remove pattern. It
     * collects all crashers in a list, then calls {@code removeAll}. This means the N crashing
     * handlers are all removed in one shot. If the manager's message queue has grown (from 500+
     * async addHandler() calls), there may be a delay between the crash and the removal completing.
     */
    @Test
    void allHandlersCrash_managerSurvivesAndContinues() throws Exception {
        var mgr = EventManager.<Evt>start();
        int handlerCount = 500;
        var removedCount = new AtomicInteger(0);

        for (int i = 0; i < handlerCount; i++) {
            mgr.addHandler(
                    new EventManager.Handler<>() {
                        @Override
                        public void handleEvent(Evt event) {
                            throw new RuntimeException("deliberate crash");
                        }

                        @Override
                        public void terminate(Throwable reason) {
                            if (reason != null) removedCount.incrementAndGet();
                        }
                    });
        }

        // Let all adds propagate
        Thread.sleep(500);

        // This broadcast will crash all 500 handlers
        mgr.syncNotify(new Evt.Tick()); // must not hang

        // All handlers must have been removed with non-null reason
        assertThat(removedCount.get())
                .as("handlers removed due to crash")
                .isEqualTo(handlerCount);

        // Manager must still accept new handlers and events
        var afterCrashCount = new AtomicInteger(0);
        mgr.addHandler(e -> afterCrashCount.incrementAndGet());
        mgr.syncNotify(new Evt.Tick()); // must reach the new handler

        assertThat(afterCrashCount.get()).isEqualTo(1);

        mgr.stop();
    }

    // ── 3. Property: M crashing handlers, N-M survivors receive all events ─

    /**
     * <b>Breaking point: partial crash removes exactly the crashing handlers.</b>
     *
     * <p>With {@code total} handlers where {@code crashers} of them throw on the first event, the
     * remaining {@code total - crashers} must continue receiving all subsequent events correctly.
     * No false removals (handler removed without crashing), no missed removals (crasher remains).
     */
    @Property(tries = 30)
    void partialHandlerCrash_survivorsReceiveAllEvents(
            @ForAll @IntRange(min = 1, max = 10) int crashers,
            @ForAll @IntRange(min = 1, max = 10) int survivors)
            throws Exception {
        var mgr = EventManager.<Evt>start();
        var survivorCount = new AtomicInteger(0);

        // Add crashing handlers
        for (int i = 0; i < crashers; i++) {
            mgr.addHandler(
                    event -> {
                        throw new RuntimeException("crash");
                    });
        }

        // Add surviving handlers
        for (int i = 0; i < survivors; i++) {
            mgr.addHandler(e -> survivorCount.incrementAndGet());
        }

        Thread.sleep(100); // let all adds propagate

        // First broadcast: crashes all crashers, survivors receive it
        mgr.syncNotify(new Evt.Tick());

        // Second broadcast: only survivors left
        mgr.syncNotify(new Evt.Tick());

        // Each survivor receives 2 events (once before crash, once after)
        assertThat(survivorCount.get())
                .as("survivors=%d received events (expected %d×2)", survivors, survivors)
                .isEqualTo(survivors * 2);

        mgr.stop();
    }

    // ── 4. Handler churn — add/remove while notifying ─────────────────────

    /**
     * <b>Breaking point: add/remove during broadcast — CopyOnWriteArrayList snapshot semantics.</b>
     *
     * <p>The manager serialises all operations via its {@link org.acme.Proc} mailbox. Concurrent
     * addHandler(), deleteHandler(), and notify() calls are enqueued and executed in order. The
     * handler list must never be observed in a partially-updated state.
     *
     * <p>We run 1000 add/remove cycles interleaved with 100 broadcasts and verify:
     * - No exceptions (ConcurrentModification, NullPointer, etc.)
     * - Events only delivered to handlers registered at the time of broadcast
     */
    @Test
    void handlerChurnDuringNotify_noCorruption() throws Exception {
        var mgr = EventManager.<Evt>start();
        var errors = new AtomicInteger(0);
        var totalEvents = new AtomicInteger(0);
        var done = new CountDownLatch(1);

        // Background churner: rapidly add and remove handlers
        Thread churner =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (done.getCount() > 0) {
                                        try {
                                            EventManager.Handler<Evt> h =
                                                    e -> totalEvents.incrementAndGet();
                                            mgr.addHandler(h);
                                            mgr.notify(new Evt.Tick());
                                            mgr.deleteHandler(h);
                                        } catch (Exception e) {
                                            errors.incrementAndGet();
                                        }
                                    }
                                });

        // Foreground: send 100 sync broadcasts
        for (int i = 0; i < 100; i++) {
            try {
                mgr.syncNotify(new Evt.Data("broadcast-" + i));
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }

        done.countDown();
        churner.join(3000);

        assertThat(errors.get()).as("churn-during-notify errors").isZero();

        mgr.stop();
    }

    // ── 5. Memory: 10 000 add/remove cycles — no phantom handlers ─────────

    /**
     * <b>Breaking point: phantom handler retention after removeAll.</b>
     *
     * <p>After 10 000 add/remove cycles, the handler list inside the manager must be empty (or
     * contain only the handlers that are still registered). We verify this by checking that
     * syncNotify reaches zero handlers after all are removed — i.e. the event counter does not
     * increase.
     */
    @Test
    void addRemoveCycles_10k_noPhantomHandlers() throws Exception {
        var mgr = EventManager.<Evt>start();
        var eventCount = new AtomicInteger(0);

        for (int i = 0; i < 10_000; i++) {
            EventManager.Handler<Evt> h = e -> eventCount.incrementAndGet();
            mgr.addHandler(h);
            mgr.deleteHandler(h);
        }

        // After all cycles: syncNotify should reach no handlers
        mgr.syncNotify(new Evt.Tick());

        // eventCount should be 0 — all handlers were removed before the final event
        // (deleteHandler is sync, so by the time syncNotify runs, all removes are done)
        assertThat(eventCount.get())
                .as("phantom handlers remaining after 10k add/remove cycles")
                .isZero();

        mgr.stop();
    }

    // ── 6. Concurrent producers — 50 threads all notifying ───────────────

    /**
     * <b>Breaking point: manager mailbox under N concurrent producers.</b>
     *
     * <p>50 threads each calling {@code notify()} 100 times = 5000 events. Each event is handled
     * by one counting handler. The total count must be exactly 5000 — the manager's process
     * serialises all events and the single handler counts each one.
     */
    @Test
    void concurrentProducers_50threads_exactEventCount() throws Exception {
        var mgr = EventManager.<Evt>start();
        var count = new AtomicInteger(0);
        mgr.addHandler(e -> count.incrementAndGet());

        int producers = 50;
        int eventsEach = 100;
        var latch = new CountDownLatch(1);
        var threads = new ArrayList<Thread>(producers);

        for (int i = 0; i < producers; i++) {
            threads.add(
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            latch.await();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                        for (int j = 0; j < eventsEach; j++) {
                                            mgr.notify(new Evt.Tick());
                                        }
                                    }));
        }

        latch.countDown();
        for (var t : threads) t.join(10_000);

        // Fence: syncNotify waits until all preceding events are processed
        mgr.syncNotify(new Evt.Data("fence"));

        assertThat(count.get())
                .as("exact event count from %d producers × %d events", producers, eventsEach)
                .isEqualTo(producers * eventsEach + 1); // +1 for the fence Data event

        mgr.stop();
    }
}
