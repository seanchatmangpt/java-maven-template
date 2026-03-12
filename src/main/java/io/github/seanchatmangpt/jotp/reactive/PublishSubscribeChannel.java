package io.github.seanchatmangpt.jotp.reactive;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import io.github.seanchatmangpt.jotp.EventManager;

/**
 * Publish-subscribe channel: every subscriber receives a copy of every message.
 *
 * <p>Enterprise Integration Pattern: <em>Publish-Subscribe Channel</em> (EIP §6.2). Erlang analog:
 * {@code gen_event} — one event manager process, arbitrarily many handlers that each receive every
 * event.
 *
 * <p>Backed by {@link EventManager} — itself built on {@link org.acme.Proc} — so all OTP fault
 * isolation guarantees apply: a crashing subscriber is removed silently without killing the channel
 * or other subscribers.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var bus = new PublishSubscribeChannel<DomainEvent>();
 * bus.subscribe(event -> auditLog.record(event));
 * bus.subscribe(event -> metricsCollector.count(event));
 * bus.send(new OrderPlaced(orderId, customerId));
 * bus.stop();
 * }</pre>
 *
 * @param <T> event/message type — use a sealed interface of Records for compile-time exhaustiveness
 */
public final class PublishSubscribeChannel<T> implements MessageChannel<T> {

    private final EventManager<T> eventManager = EventManager.start();

    /**
     * Tracks the {@link EventManager.Handler} wrapper for each {@link Consumer} subscriber so that
     * {@link #unsubscribe} can remove the correct handler by reference.
     */
    private final ConcurrentHashMap<Consumer<T>, EventManager.Handler<T>> handlerRegistry =
            new ConcurrentHashMap<>();

    /**
     * Register a subscriber that will receive every subsequent message.
     *
     * <p>Mirrors OTP {@code gen_event:add_handler/3}. The subscriber is wrapped in a fault-isolated
     * handler: if it throws a {@link RuntimeException}, it is removed and other subscribers
     * continue unaffected.
     *
     * @param subscriber the consumer to register
     */
    public void subscribe(Consumer<T> subscriber) {
        EventManager.Handler<T> handler = event -> subscriber.accept(event);
        handlerRegistry.put(subscriber, handler);
        eventManager.addHandler(handler);
    }

    /**
     * Remove a previously registered subscriber.
     *
     * <p>Mirrors OTP {@code gen_event:delete_handler/3}.
     *
     * @param subscriber the consumer to remove
     * @return {@code true} if the subscriber was registered and removed
     */
    public boolean unsubscribe(Consumer<T> subscriber) {
        EventManager.Handler<T> handler = handlerRegistry.remove(subscriber);
        if (handler == null) return false;
        return eventManager.deleteHandler(handler);
    }

    /**
     * Broadcast a message to all registered subscribers asynchronously.
     *
     * <p>Mirrors OTP {@code gen_event:notify/2} — fire-and-forget, returns immediately.
     *
     * @param message the message to broadcast
     */
    @Override
    public void send(T message) {
        eventManager.notify(message);
    }

    /**
     * Broadcast a message and block until all subscribers have processed it.
     *
     * <p>Mirrors OTP {@code gen_event:sync_notify/2}.
     *
     * @param message the message to broadcast
     * @throws InterruptedException if interrupted while waiting
     */
    public void sendSync(T message) throws InterruptedException {
        eventManager.syncNotify(message);
    }

    /**
     * Shut down the channel, notifying all subscribers of termination.
     *
     * <p>Mirrors OTP {@code gen_event:stop/1}.
     */
    @Override
    public void stop() throws InterruptedException {
        eventManager.stop();
    }
}
