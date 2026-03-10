package org.acme.dogfood.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Publish-Subscribe pattern.
 *
 * <p>Generated from {@code templates/java/messaging/pub-sub.tera}.
 *
 * <p>Implements topic-based publish-subscribe messaging. Publishers send messages to topics,
 * and all subscribers to that topic receive the message. This enables loose coupling between
 * components in distributed systems.
 *
 * <p><strong>Pattern contracts validated:</strong>
 *
 * <ul>
 *   <li>Topic-based subscription
 *   <li>Event notification to all subscribers
 *   <li>Multi-subscriber broadcast
 * </ul>
 *
 * @param <T> message type
 */
public final class PubSubPatterns<T> {

    private final Map<String, List<Consumer<T>>> topicSubscribers = new ConcurrentHashMap<>();

    /**
     * Subscribe to a topic.
     *
     * @param topic the topic to subscribe to
     * @param subscriber the message handler
     */
    public void subscribe(String topic, Consumer<T> subscriber) {
        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Unsubscribe from a topic.
     *
     * @param topic the topic to unsubscribe from
     * @param subscriber the subscriber to remove
     * @return true if the subscriber was removed
     */
    public boolean unsubscribe(String topic, Consumer<T> subscriber) {
        var subscribers = topicSubscribers.get(topic);
        return subscribers != null && subscribers.remove(subscriber);
    }

    /**
     * Publish a message to all subscribers of a topic.
     *
     * @param topic the topic to publish to
     * @param message the message to publish
     * @return the number of subscribers that received the message
     */
    public int publish(String topic, T message) {
        var subscribers = topicSubscribers.get(topic);
        if (subscribers == null || subscribers.isEmpty()) {
            return 0;
        }

        for (var subscriber : subscribers) {
            Thread.ofVirtual().start(() -> subscriber.accept(message));
        }
        return subscribers.size();
    }

    /**
     * Publish a message synchronously to all subscribers of a topic.
     *
     * @param topic the topic to publish to
     * @param message the message to publish
     */
    public void publishSync(String topic, T message) {
        var subscribers = topicSubscribers.get(topic);
        if (subscribers != null) {
            for (var subscriber : subscribers) {
                subscriber.accept(message);
            }
        }
    }

    /**
     * Returns the number of subscribers for a topic.
     *
     * @param topic the topic
     * @return subscriber count
     */
    public int subscriberCount(String topic) {
        var subscribers = topicSubscribers.get(topic);
        return subscribers == null ? 0 : subscribers.size();
    }

    /**
     * Returns all topics that have subscribers.
     *
     * @return list of active topics
     */
    public List<String> activeTopics() {
        return new ArrayList<>(topicSubscribers.keySet());
    }

    /**
     * Clears all subscribers from all topics.
     */
    public void clear() {
        topicSubscribers.clear();
    }

    /**
     * Creates a new pub-sub broker.
     *
     * @param <T> message type
     * @return a new pub-sub broker
     */
    public static <T> PubSubPatterns<T> create() {
        return new PubSubPatterns<>();
    }
}
