package io.github.seanchatmangpt.jotp.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.github.seanchatmangpt.jotp.EventManager;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP {@code gen_event} semantics — decoupled event routing with fault isolation.
 *
 * <p>Armstrong: "gen_event is how OTP decouples event sources from event sinks. The error logger,
 * alarm handler, and SASL all use gen_event. Without it, you hardcode your event routing."
 *
 * <p>OTP invariants under test:
 *
 * <ol>
 *   <li>{@code notify} broadcasts to all registered handlers
 *   <li>Handlers added dynamically receive subsequent events
 *   <li>{@code deleteHandler} stops a handler from receiving events
 *   <li>A crashing handler is removed but does NOT kill the manager (fault isolation)
 *   <li>{@code call} delivers a sync event to one specific handler only
 * </ol>
 */
@Timeout(10)
class EventManagerTest implements WithAssertions {

    sealed interface AppEvent permits AppEvent.UserLogin, AppEvent.OrderPlaced {
        record UserLogin(String userId) implements AppEvent {}

        record OrderPlaced(String orderId, double amount) implements AppEvent {}
    }

    // ── Test 1: notify delivers to all handlers ────────────────────────────

    @Test
    void notify_deliversToAllHandlers() throws Exception {
        var mgr = EventManager.<AppEvent>start();

        var counter1 = new AtomicInteger(0);
        var counter2 = new AtomicInteger(0);

        EventManager.Handler<AppEvent> h1 = event -> counter1.incrementAndGet();
        EventManager.Handler<AppEvent> h2 = event -> counter2.incrementAndGet();

        mgr.addHandler(h1);
        mgr.addHandler(h2);

        mgr.syncNotify(new AppEvent.UserLogin("alice"));
        mgr.syncNotify(new AppEvent.OrderPlaced("order-1", 99.99));

        assertThat(counter1.get()).isEqualTo(2);
        assertThat(counter2.get()).isEqualTo(2);

        mgr.stop();
    }

    // ── Test 2: handler added dynamically receives subsequent events ───────

    @Test
    void addHandler_dynamically_receivesSubsequentEvents() throws Exception {
        var mgr = EventManager.<AppEvent>start();

        var earlyCount = new AtomicInteger(0);
        var lateCount = new AtomicInteger(0);

        EventManager.Handler<AppEvent> early = event -> earlyCount.incrementAndGet();
        mgr.addHandler(early);

        mgr.syncNotify(new AppEvent.UserLogin("bob")); // early only

        EventManager.Handler<AppEvent> late = event -> lateCount.incrementAndGet();
        mgr.addHandler(late);

        mgr.syncNotify(new AppEvent.UserLogin("carol")); // both

        assertThat(earlyCount.get()).isEqualTo(2); // received both events
        assertThat(lateCount.get()).isEqualTo(1); // only received the second

        mgr.stop();
    }

    // ── Test 3: deleteHandler stops receiving events ───────────────────────

    @Test
    void deleteHandler_stopsReceiving() throws Exception {
        var mgr = EventManager.<AppEvent>start();

        var count = new AtomicInteger(0);
        var terminateCalled = new AtomicBoolean(false);

        EventManager.Handler<AppEvent> h =
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(AppEvent event) {
                        count.incrementAndGet();
                    }

                    @Override
                    public void terminate(Throwable reason) {
                        terminateCalled.set(true);
                    }
                };

        mgr.addHandler(h);
        mgr.syncNotify(new AppEvent.UserLogin("dan")); // received

        boolean removed = mgr.deleteHandler(h);
        assertThat(removed).isTrue();
        assertThat(terminateCalled).isTrue();

        mgr.syncNotify(new AppEvent.UserLogin("eve")); // NOT received

        assertThat(count.get()).isEqualTo(1);

        mgr.stop();
    }

    // ── Test 4: crashing handler does NOT kill the manager ────────────────

    @Test
    void crashingHandler_doesNotKillManager() throws Exception {
        var mgr = EventManager.<AppEvent>start();

        var survivorCount = new AtomicInteger(0);
        var terminateCalled = new AtomicBoolean(false);

        EventManager.Handler<AppEvent> crasher =
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(AppEvent event) {
                        throw new RuntimeException("handler crash");
                    }

                    @Override
                    public void terminate(Throwable reason) {
                        terminateCalled.set(reason != null);
                    }
                };

        EventManager.Handler<AppEvent> survivor = event -> survivorCount.incrementAndGet();

        mgr.addHandler(crasher);
        mgr.addHandler(survivor);

        // This should NOT kill the manager
        mgr.syncNotify(new AppEvent.UserLogin("frank"));

        // crasher was removed due to crash; terminate called with non-null reason
        assertThat(terminateCalled).isTrue();

        // manager is still alive and survivor still receives events
        mgr.syncNotify(new AppEvent.UserLogin("grace"));

        assertThat(survivorCount.get()).isEqualTo(2);

        mgr.stop();
    }

    // ── Test 5: call delivers sync event to one specific handler ──────────

    @Test
    void call_synchronouslyCallsSpecificHandler() throws Exception {
        var mgr = EventManager.<AppEvent>start();

        var h1Events = new CopyOnWriteArrayList<AppEvent>();
        var h2Events = new CopyOnWriteArrayList<AppEvent>();

        EventManager.Handler<AppEvent> h1 = h1Events::add;
        EventManager.Handler<AppEvent> h2 = h2Events::add;

        mgr.addHandler(h1);
        mgr.addHandler(h2);

        // call(h1, event) — only h1 should receive it
        mgr.call(h1, new AppEvent.OrderPlaced("call-only", 1.0));

        assertThat(h1Events).hasSize(1);
        assertThat(h2Events).isEmpty();

        mgr.stop();
    }
}
