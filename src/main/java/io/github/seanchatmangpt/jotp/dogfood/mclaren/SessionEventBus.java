package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import io.github.seanchatmangpt.jotp.EventManager;

/**
 * SQL Race session event bus — OTP {@code gen_event} mapped to ATLAS {@code SessionManager}
 * event infrastructure.
 *
 * <p>In ATLAS, the SQL Race {@code SessionManager} fires typed events ({@code SessionCreated},
 * {@code LapStarted}, etc.) to all registered listeners. This Java 26 / OTP refactor wraps
 * an {@link EventManager}{@code <SqlRaceSessionEvent>} to provide the same decoupled pub/sub
 * with process-level fault isolation.
 *
 * <p>A crashing handler is automatically removed from the bus without affecting other handlers
 * or the bus itself — exactly OTP's {@code gen_event} guarantee:
 *
 * <pre>{@code
 * // Register a live display handler — if it crashes, the bus continues
 * bus.addHandler(new SessionEventBus.Handler() {
 *     public void handleEvent(SqlRaceSessionEvent event) {
 *         liveDisplay.update(event);  // may throw; gen_event isolates the crash
 *     }
 * });
 * bus.syncNotify(new SqlRaceSessionEvent.AddLap(lap));  // blocks until all handlers complete
 * }</pre>
 *
 * <h2>Handlers registered by the ATLAS system</h2>
 *
 * <ul>
 *   <li>{@code AdvancedStreams.streamingHandler()} — fans events out to Kafka / InfluxDB
 *   <li>{@code SessionMonitor.watchHandler()} — fires DOWN notifications on {@code Close}
 *   <li>{@code SqlRaceSession.updateHandler()} — drives state machine via cast
 * </ul>
 */
public final class SessionEventBus {

    private final EventManager<SqlRaceSessionEvent> manager;

    private SessionEventBus(EventManager<SqlRaceSessionEvent> manager) {
        this.manager = manager;
    }

    /**
     * Start a new session event bus.
     *
     * @return running bus process
     */
    public static SessionEventBus start() {
        return new SessionEventBus(EventManager.start());
    }

    /**
     * Register a handler — mirrors {@code gen_event:add_handler/3}.
     *
     * @param handler receives all subsequent events
     */
    public void addHandler(EventManager.Handler<SqlRaceSessionEvent> handler) {
        manager.addHandler(handler);
    }

    /**
     * Remove a handler — mirrors {@code gen_event:delete_handler/3}.
     *
     * @param handler the handler to remove
     * @return {@code true} if the handler was registered
     */
    public boolean removeHandler(EventManager.Handler<SqlRaceSessionEvent> handler) {
        return manager.deleteHandler(handler);
    }

    /**
     * Broadcast an event asynchronously — mirrors {@code gen_event:notify/2}.
     *
     * @param event the event to broadcast
     */
    public void notify(SqlRaceSessionEvent event) {
        manager.notify(event);
    }

    /**
     * Broadcast synchronously, blocking until all handlers have processed the event —
     * mirrors {@code gen_event:sync_notify/2}.
     *
     * @param event the event to broadcast
     * @throws InterruptedException if interrupted while waiting
     */
    public void syncNotify(SqlRaceSessionEvent event) throws InterruptedException {
        manager.syncNotify(event);
    }

    /**
     * Stop the event bus and remove all handlers.
     */
    public void stop() {
        manager.stop();
    }

    /**
     * Create a handler that forwards all session events to a target {@link SqlRaceSession}.
     *
     * <p>Useful for wiring the event bus as a front-end dispatcher to a session state machine.
     * Demonstrates OTP's "forwarding handler" pattern where a gen_event handler translates
     * events into gen_statem casts.
     *
     * @param session the target session
     * @return handler suitable for {@link #addHandler(EventManager.Handler)}
     */
    public static EventManager.Handler<SqlRaceSessionEvent> forwardingHandler(
            SqlRaceSession session) {
        return new EventManager.Handler<>() {
            @Override
            public void handleEvent(SqlRaceSessionEvent event) {
                session.send(event);
            }

            @Override
            public void terminate(Throwable reason) {
                // Normal removal or crash: session is not affected
            }
        };
    }
}
