package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publish-Subscribe Channel (Vernon: "Publish-Subscribe Channel")
 *
 * <p>A messaging channel that broadcasts each message to all subscribers.
 * Perfect for 1:N event distribution where decoupling is critical.
 *
 * <p>JOTP Implementation: Uses {@code EventManager<Message>} to manage typed
 * event subscribers. Each subscriber receives a copy of the published message.
 *
 * <p>Example:
 * <pre>
 * // Create a pub-sub channel
 * var channel = PublishSubscribeChannel.create();
 *
 * // Subscribe (multiple subscribers welcome)
 * channel.subscribe(msg -> System.out.println("Subscriber 1: " + msg));
 * channel.subscribe(msg -> System.out.println("Subscriber 2: " + msg));
 *
 * // Publish (all subscribers notified)
 * channel.publish(Message.event("OrderCreated", order));
 * </pre>
 */
public sealed class PublishSubscribeChannel<E extends Message> implements PubSubChannelOps<E> {

    private final EventManager<E> eventManager;
    private final List<EventHandler<E>> handlers;

    /**
     * Creates a new Publish-Subscribe channel.
     */
    private PublishSubscribeChannel() {
        this.eventManager = EventManager.create();
        this.handlers = new CopyOnWriteArrayList<>();
    }

    /**
     * Factory method to create a new pub-sub channel.
     *
     * @param <E> Message type
     * @return New PublishSubscribeChannel instance
     */
    public static <E extends Message> PublishSubscribeChannel<E> create() {
        return new PublishSubscribeChannel<>();
    }

    /**
     * Publishes a message to all active subscribers (1:N broadcast).
     * Non-blocking; subscribers are notified asynchronously.
     *
     * @param message The message to publish
     */
    @Override
    public void publish(E message) {
        eventManager.notify(message);
    }

    /**
     * Publishes a message and waits for all subscribers to acknowledge (blocking).
     *
     * @param message The message to publish
     * @return Number of subscribers notified
     */
    @Override
    public int publishSync(E message) {
        return eventManager.syncNotify(message);
    }

    /**
     * Subscribes a handler to this channel.
     * Handler will be called for every published message.
     *
     * @param handler Function to invoke on each message
     * @return Handler reference (use for unsubscribe)
     */
    @Override
    public EventHandler<E> subscribe(EventHandler<E> handler) {
        eventManager.addHandler(handler);
        handlers.add(handler);
        return handler;
    }

    /**
     * Unsubscribes a handler from this channel.
     *
     * @param handler Handler reference returned from subscribe()
     */
    @Override
    public void unsubscribe(EventHandler<E> handler) {
        eventManager.deleteHandler(handler);
        handlers.remove(handler);
    }

    /**
     * Returns the current number of active subscribers.
     *
     * @return Subscriber count
     */
    @Override
    public int subscriberCount() {
        return handlers.size();
    }

    /**
     * Unsubscribes all handlers from this channel.
     */
    @Override
    public void unsubscribeAll() {
        handlers.forEach(eventManager::deleteHandler);
        handlers.clear();
    }

    /**
     * Operations contract for Publish-Subscribe channels.
     */
    public interface PubSubChannelOps<E extends Message> {
        void publish(E message);
        int publishSync(E message);
        EventHandler<E> subscribe(EventHandler<E> handler);
        void unsubscribe(EventHandler<E> handler);
        int subscriberCount();
        void unsubscribeAll();
    }
}
