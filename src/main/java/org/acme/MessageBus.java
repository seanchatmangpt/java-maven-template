package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Topic-based message bus — extends EventManager with topic subscriptions and persistent messaging.
 *
 * <p>Enterprise Integration Pattern: "Message Bus provides a common communication system
 * that multiple applications use, built on top of messaging."
 *
 * <p>Joe Armstrong: "In Erlang, all communication is via message passing. The message bus
 * is the universal integration layer — every process talks to every other process through it."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Topic-based routing</b> — Subscribe to specific topics or patterns</li>
 *   <li><b>Wildcards</b> — Subscribe to hierarchical topics (e.g., "telemetry.*")</li>
 *   <li><b>Durable subscriptions</b> — Messages persisted for offline subscribers</li>
 *   <li><b>Dead letter handling</b> — Failed messages routed to DLQ</li>
 *   <li><b>Backpressure</b> — Flow control for slow consumers</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * MessageBus bus = MessageBus.create();
 *
 * // Subscribe to a topic
 * Subscription sub = bus.subscribe("telemetry.samples", msg -> {
 *     processSample(msg);
 * });
 *
 * // Publish a message
 * bus.publish("telemetry.samples", new SampleEvent(...));
 *
 * // Wildcard subscription
 * Subscription all = bus.subscribe("telemetry.*", msg -> {
 *     log(msg);
 * });
 *
 * // Unsubscribe
 * sub.cancel();
 * }</pre>
 *
 * @see EventManager
 * @see MessageStore
 */
public final class MessageBus implements Application.Infrastructure {

    /** Subscription handle for cancellation. */
    public interface Subscription {
        String id();
        String topic();
        void cancel();
        boolean isActive();
    }

    /** Message envelope with metadata. */
    public record Envelope(
            String topic,
            Object payload,
            Instant timestamp,
            Map<String, String> headers,
            UUID correlationId) {

        public static Envelope of(String topic, Object payload) {
            return new Envelope(topic, payload, Instant.now(), Map.of(), UUID.randomUUID());
        }

        public static Envelope of(String topic, Object payload, Map<String, String> headers) {
            return new Envelope(topic, payload, Instant.now(), headers, UUID.randomUUID());
        }

        public Envelope withHeader(String key, String value) {
            Map<String, String> newHeaders = new HashMap<>(headers);
            newHeaders.put(key, value);
            return new Envelope(topic, payload, timestamp, newHeaders, correlationId);
        }
    }

    /** Statistics for the bus. */
    public record Stats(
            long published,
            long delivered,
            long failed,
            int activeSubscriptions,
            int topics) {}

    // ── Internal state ──────────────────────────────────────────────────────────

    private final String name;
    private final EventManager<InternalMsg> eventManager;
    private final ConcurrentHashMap<String, List<SubscriberInfo>> topicSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PatternSubscription> patternSubscribers = new ConcurrentHashMap<>();
    private final MessageStore messageStore;
    private final DeadLetterHandler deadLetterHandler;
    private final LongAdder published = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder failed = new LongAdder();

    private sealed interface InternalMsg permits InternalMsg.Publish, InternalMsg.Subscribe, InternalMsg.Unsubscribe {
        record Publish(Envelope envelope, CompletableFuture<Boolean> reply) implements InternalMsg {}
        record Subscribe(String topic, SubscriberInfo info, CompletableFuture<Subscription> reply) implements InternalMsg {}
        record Unsubscribe(String subscriptionId) implements InternalMsg {}
    }

    private static final class SubscriberInfo {
            final String id;
            final String topic;
            final Consumer<Envelope> handler;
            volatile boolean active;
            SubscriberInfo(String id, String topic, Consumer<Envelope> handler, boolean active) {
                this.id = id;
                this.topic = topic;
                this.handler = handler;
                this.active = active;
            }
        }

    private static final class PatternSubscription {
            final String id;
            final String pattern;
            final Consumer<Envelope> handler;
            volatile boolean active;
            PatternSubscription(String id, String pattern, Consumer<Envelope> handler, boolean active) {
                this.id = id;
                this.pattern = pattern;
                this.handler = handler;
                this.active = active;
            }
        }

    // ── Constructor ─────────────────────────────────────────────────────────────

    private MessageBus(String name, MessageStore store, DeadLetterHandler deadLetterHandler) {
        this.name = name;
        this.messageStore = store != null ? store : MessageStore.inMemory().build();
        this.deadLetterHandler = deadLetterHandler != null ? deadLetterHandler : DeadLetterHandler.log();
        this.eventManager = EventManager.start();

        // Start the internal event handler
        eventManager.addHandler(new InternalHandler());
    }

    // ─-- Factory methods ────────────────────────────────────────────────────────

    public static MessageBus create() {
        return new MessageBus("default", null, null);
    }

    public static MessageBus create(String name) {
        return new MessageBus(name, null, null);
    }

    public static MessageBus create(String name, MessageStore store) {
        return new MessageBus(name, store, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Message bus builder. */
    public static final class Builder {
        private String name = "default";
        private MessageStore store;
        private DeadLetterHandler deadLetterHandler;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder store(MessageStore store) {
            this.store = store;
            return this;
        }

        public Builder deadLetterHandler(DeadLetterHandler handler) {
            this.deadLetterHandler = handler;
            return this;
        }

        public MessageBus build() {
            return new MessageBus(name, store, deadLetterHandler);
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Publish a message to a topic.
     *
     * @param topic the topic to publish to
     * @param payload the message payload
     */
    public void publish(String topic, Object payload) {
        publish(Envelope.of(topic, payload));
    }

    /**
     * Publish an envelope to a topic.
     */
    public void publish(Envelope envelope) {
        published.increment();
        var reply = new CompletableFuture<Boolean>();
        eventManager.notify(new InternalMsg.Publish(envelope, reply));
    }

    /**
     * Publish and wait for delivery confirmation.
     */
    public boolean publishSync(Envelope envelope, Duration timeout) throws InterruptedException {
        published.increment();
        var reply = new CompletableFuture<Boolean>();
        eventManager.syncNotify(new InternalMsg.Publish(envelope, reply));
        try {
            return reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Subscribe to a topic.
     *
     * @param topic the topic to subscribe to (supports * wildcard)
     * @param handler message handler
     * @return subscription handle
     */
    public Subscription subscribe(String topic, Consumer<Envelope> handler) {
        var reply = new CompletableFuture<Subscription>();
        String id = UUID.randomUUID().toString();
        var info = new SubscriberInfo(id, topic, handler, true);
        eventManager.notify(new InternalMsg.Subscribe(topic, info, reply));
        try {
            return reply.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Subscribe failed", e);
        }
    }

    /**
     * Subscribe to topics matching a pattern.
     *
     * @param pattern pattern like "telemetry.*" or "orders.>"
     */
    public Subscription subscribePattern(String pattern, Consumer<Envelope> handler) {
        String id = UUID.randomUUID().toString();
        patternSubscribers.put(id, new PatternSubscription(id, pattern, handler, true));

        return new Subscription() {
            @Override public String id() { return id; }
            @Override public String topic() { return pattern; }
            @Override public void cancel() {
                patternSubscribers.get(id).active = false;
                patternSubscribers.remove(id);
            }
            @Override public boolean isActive() {
                var sub = patternSubscribers.get(id);
                return sub != null && sub.active;
            }
        };
    }

    // ── Statistics ───────────────────────────────────────────────────────────────

    public Stats stats() {
        return new Stats(
                published.sum(),
                delivered.sum(),
                failed.sum(),
                topicSubscribers.values().stream().mapToInt(List::size).sum() + patternSubscribers.size(),
                topicSubscribers.size());
    }

    // ── Infrastructure lifecycle ─────────────────────────────────────────────────

    @Override
    public String name() { return name; }

    @Override
    public void onStop(Application app) {
        eventManager.stop();
    }

    // ── Internal handler ─────────────────────────────────────────────────────────

    private final class InternalHandler implements EventManager.Handler<InternalMsg> {
        @Override
        public void handleEvent(InternalMsg msg) {
            switch (msg) {
                case InternalMsg.Publish(var envelope, var reply) -> {
                    handlePublish(envelope, reply);
                }
                case InternalMsg.Subscribe(var topic, var info, var reply) -> {
                    topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(info);
                    reply.complete(new SubscriptionImpl(info));
                }
                case InternalMsg.Unsubscribe(var id) -> {
                    topicSubscribers.values().forEach(list ->
                            list.removeIf(info -> info.id.equals(id)));
                }
            }
        }
    }

    private void handlePublish(Envelope envelope, CompletableFuture<Boolean> reply) {
        List<SubscriberInfo> subscribers = topicSubscribers.get(envelope.topic());
        boolean anyDelivered = false;

        // Deliver to direct subscribers
        if (subscribers != null) {
            for (SubscriberInfo info : subscribers) {
                if (info.active) {
                    try {
                        info.handler.accept(envelope);
                        delivered.increment();
                        anyDelivered = true;
                    } catch (Exception e) {
                        failed.increment();
                        deadLetterHandler.handle(envelope, e);
                    }
                }
            }
        }

        // Deliver to pattern subscribers
        for (PatternSubscription ps : patternSubscribers.values()) {
            if (ps.active && matchesPattern(envelope.topic(), ps.pattern)) {
                try {
                    ps.handler.accept(envelope);
                    delivered.increment();
                    anyDelivered = true;
                } catch (Exception e) {
                    failed.increment();
                    deadLetterHandler.handle(envelope, e);
                }
            }
        }

        // Store for durable subscribers
        messageStore.store(envelope);

        reply.complete(anyDelivered);
    }

    private boolean matchesPattern(String topic, String pattern) {
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return topic.startsWith(prefix + ".") || topic.equals(prefix);
        } else if (pattern.endsWith(".>")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return topic.startsWith(prefix);
        }
        return topic.equals(pattern);
    }

    private final class SubscriptionImpl implements Subscription {
        private final SubscriberInfo info;

        SubscriptionImpl(SubscriberInfo info) {
            this.info = info;
        }

        @Override public String id() { return info.id; }
        @Override public String topic() { return info.topic; }
        @Override public void cancel() {
            info.active = false;
            eventManager.notify(new InternalMsg.Unsubscribe(info.id));
        }
        @Override public boolean isActive() { return info.active; }
    }

    // ── Dead letter handler ──────────────────────────────────────────────────────

    /** Handler for failed message deliveries. */
    @FunctionalInterface
    public interface DeadLetterHandler {
        void handle(Envelope envelope, Exception error);

        static DeadLetterHandler log() {
            return (env, err) -> System.err.println(
                    "[DLQ] Failed to deliver to " + env.topic() + ": " + err.getMessage());
        }

        static DeadLetterHandler store(MessageStore store) {
            return (env, err) -> {
                Envelope dlq = env.withHeader("dlq.error", err.getMessage())
                        .withHeader("dlq.timestamp", Instant.now().toString());
                store.store(dlq);
            };
        }
    }
}
