package org.acme.dogfood.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Message Bus pattern using EventManager.
 *
 * <p>Generated from {@code templates/java/messaging/message-bus.tera}.
 *
 * <p>Demonstrates the Message Bus integration pattern - a centralized communication hub that
 * decouples message producers from consumers. This pattern is the backbone of event-driven
 * architectures and microservices communication.
 *
 * <p><strong>Pattern contracts validated:</strong>
 *
 * <ul>
 *   <li>{@code subscribe(Consumer<T>)} - register a message handler
 *   <li>{@code publish(T)} - async broadcast to all subscribers
 *   <li>{@code publishSync(T)} - sync broadcast, waits for all handlers
 *   <li>{@code unsubscribe(Consumer<T>)} - remove a subscriber
 * </ul>
 *
 * @param <T> message type - use a Record or sealed interface
 */
public final class MessageBusPatterns<T> {

    private final List<Consumer<T>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Subscribe to all messages on this bus.
     *
     * @param handler the message handler
     */
    public void subscribe(Consumer<T> handler) {
        subscribers.add(handler);
    }

    /**
     * Publish a message asynchronously to all subscribers.
     *
     * <p>Each subscriber receives the message in a separate virtual thread.
     *
     * @param message the message to publish
     */
    public void publish(T message) {
        for (var handler : subscribers) {
            Thread.ofVirtual().start(() -> handler.accept(message));
        }
    }

    /**
     * Publish a message synchronously to all subscribers.
     *
     * <p>Waits for all handlers to complete before returning.
     *
     * @param message the message to publish
     * @throws InterruptedException if interrupted while waiting
     */
    public void publishSync(T message) throws InterruptedException {
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (var handler : subscribers) {
            var future = new CompletableFuture<Void>();
            futures.add(future);
            Thread.ofVirtual()
                    .start(() -> {
                        try {
                            handler.accept(message);
                            future.complete(null);
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Handler execution failed", e);
        }
    }

    /**
     * Unsubscribe a handler from this bus.
     *
     * @param handler the handler to remove
     * @return true if the handler was subscribed and removed
     */
    public boolean unsubscribe(Consumer<T> handler) {
        return subscribers.remove(handler);
    }

    /**
     * Returns the current number of subscribers.
     *
     * @return subscriber count
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Clears all subscribers from this bus.
     */
    public void clear() {
        subscribers.clear();
    }

    /**
     * Creates a new message bus.
     *
     * @param <T> message type
     * @return a new message bus
     */
    public static <T> MessageBusPatterns<T> create() {
        return new MessageBusPatterns<>();
    }
}
