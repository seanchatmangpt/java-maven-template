package org.acme.dogfood.mclaren;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.acme.EventManager;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionEventBus} — the OTP {@code gen_event} implementation for SQL Race
 * session events.
 *
 * <p>Tests cover: handler registration and delivery, crashing-handler isolation (bus continues),
 * {@code syncNotify} ordering, {@code deleteHandler}, and the forwarding-handler factory.
 */
class SessionEventBusTest implements WithAssertions {

    private SessionEventBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.stop();
        }
    }

    private static SqlRaceLap outLap() {
        return SqlRaceLap.outLap(1_000_000_000L);
    }

    private static SqlRaceSessionEvent.AddLap addLapEvent() {
        return new SqlRaceSessionEvent.AddLap(outLap());
    }

    // ── Basic registration + delivery ─────────────────────────────────────────

    @Test
    void registeredHandlerReceivesEvent() throws InterruptedException {
        bus = SessionEventBus.start();
        var received = new CopyOnWriteArrayList<SqlRaceSessionEvent>();
        var latch = new CountDownLatch(1);

        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        received.add(event);
                        latch.countDown();
                    }
                });

        bus.notify(addLapEvent());
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(SqlRaceSessionEvent.AddLap.class);
    }

    @Test
    void multipleHandlersAllReceiveEvent() throws InterruptedException {
        bus = SessionEventBus.start();
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            bus.addHandler(
                    new EventManager.Handler<>() {
                        @Override
                        public void handleEvent(SqlRaceSessionEvent event) {
                            counter.incrementAndGet();
                            latch.countDown();
                        }
                    });
        }

        bus.notify(addLapEvent());
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(3);
    }

    // ── Crash isolation ───────────────────────────────────────────────────────

    @Test
    void crashingHandlerIsRemovedButBusContinues() throws InterruptedException {
        bus = SessionEventBus.start();
        var stableReceived = new CopyOnWriteArrayList<SqlRaceSessionEvent>();
        var stableLatch = new CountDownLatch(1);

        // Handler that throws on every event
        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        throw new RuntimeException("simulated display handler crash");
                    }
                });

        // Stable handler that should still receive events after the crash
        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        stableReceived.add(event);
                        stableLatch.countDown();
                    }
                });

        // syncNotify ensures both handlers have been called before returning
        bus.syncNotify(addLapEvent());

        // The stable handler must have received the event
        assertThat(stableReceived).hasSize(1);
    }

    @Test
    void busRemainsUsableAfterHandlerCrash() throws InterruptedException {
        bus = SessionEventBus.start();
        var received = new CopyOnWriteArrayList<SqlRaceSessionEvent>();

        // Crashing handler — will be removed after first event
        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        throw new RuntimeException("crash on first event");
                    }
                });

        // Send first event (crashes the handler, removes it)
        bus.syncNotify(addLapEvent());

        // Register a new stable handler after the crash
        var latch = new CountDownLatch(1);
        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        received.add(event);
                        latch.countDown();
                    }
                });

        // Second event should still be delivered to the new handler
        var lap2 = SqlRaceLap.flyingLap(2_000_000_000L, 1);
        bus.notify(new SqlRaceSessionEvent.AddLap(lap2));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
    }

    // ── syncNotify ────────────────────────────────────────────────────────────

    @Test
    void syncNotifyBlocksUntilAllHandlersComplete() throws InterruptedException {
        bus = SessionEventBus.start();
        var order = new ArrayList<String>();

        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        order.add("handler");
                    }
                });

        bus.syncNotify(addLapEvent());
        order.add("after-sync");

        // syncNotify must have finished before "after-sync" is added
        assertThat(order).containsExactly("handler", "after-sync");
    }

    @Test
    void syncNotifyWithTwoHandlersWaitsForBoth() throws InterruptedException {
        bus = SessionEventBus.start();
        var received = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            bus.addHandler(
                    new EventManager.Handler<>() {
                        @Override
                        public void handleEvent(SqlRaceSessionEvent event) {
                            received.incrementAndGet();
                        }
                    });
        }

        bus.syncNotify(addLapEvent());
        // After syncNotify returns, both handlers must have completed
        assertThat(received.get()).isEqualTo(2);
    }

    // ── deleteHandler ─────────────────────────────────────────────────────────

    @Test
    void deleteHandlerRemovesItFromBus() throws InterruptedException {
        bus = SessionEventBus.start();
        var received = new AtomicInteger(0);

        var handler =
                new EventManager.Handler<SqlRaceSessionEvent>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        received.incrementAndGet();
                    }
                };

        bus.addHandler(handler);

        // Deliver first event — handler receives it
        bus.syncNotify(addLapEvent());
        assertThat(received.get()).isEqualTo(1);

        // Remove the handler
        boolean removed = bus.removeHandler(handler);
        assertThat(removed).isTrue();

        // Deliver second event — handler must NOT receive it
        bus.syncNotify(addLapEvent());
        assertThat(received.get()).isEqualTo(1); // still 1, not 2
    }

    @Test
    void deleteHandlerReturnsFalseIfNotRegistered() {
        bus = SessionEventBus.start();
        var unregistered =
                new EventManager.Handler<SqlRaceSessionEvent>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {}
                };

        boolean removed = bus.removeHandler(unregistered);
        assertThat(removed).isFalse();
    }

    // ── Forwarding handler ────────────────────────────────────────────────────

    @Test
    void forwardingHandlerRoutesEventsToSession() throws InterruptedException {
        bus = SessionEventBus.start();

        // Construct a live session and wire it to the bus via the forwarding handler
        var session = SqlRaceSession.create("forwarding-test");
        var params =
                List.of(SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph"));
        var channels =
                List.of(
                        SqlRaceChannel.periodic(
                                1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit));
        var conversions = List.<RationalConversion>of();

        // Configure session: Initializing → Live (Configure is the "open" event in SQL Race)
        session.send(new SqlRaceSessionEvent.Configure(params, channels, conversions));

        // Register forwarding handler
        var fwd = SessionEventBus.forwardingHandler(session);
        bus.addHandler(fwd);

        // Publish AddLap via the bus — forwarding handler routes to the session
        bus.syncNotify(new SqlRaceSessionEvent.AddLap(outLap()));

        // Give the session time to process the forwarded event
        Thread.sleep(30);

        // Session should now have a lap in its data
        assertThat(session.data().laps()).hasSize(1);

        session.stop();
    }

    // ── Configure + DataItem events ───────────────────────────────────────────

    @Test
    void configureEventDeliveredToHandler() throws InterruptedException {
        bus = SessionEventBus.start();
        var received = new CopyOnWriteArrayList<SqlRaceSessionEvent>();

        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        received.add(event);
                    }
                });

        var event =
                new SqlRaceSessionEvent.Configure(
                        List.of(SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph")),
                        List.of(
                                SqlRaceChannel.periodic(
                                        1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit)),
                        List.of());

        bus.syncNotify(event);
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(SqlRaceSessionEvent.Configure.class);
    }
}
