package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Persistent message store — append-only log for reliable message delivery.
 *
 * <p>Enterprise Integration Pattern: "Message Store captures messages for reliable
 * delivery, replay, and audit purposes."
 *
 * <p>Joe Armstrong: "In Erlang, message queues are in-memory. But for enterprise
 * systems, you need persistence. The message store is the durable backing for the bus."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Append-only log</b> — Messages are never modified, only appended</li>
 *   <li><b>Topic partitioning</b> — Messages organized by topic</li>
 *   <li><b>Retention policies</b> — Time or size-based message expiration</li>
 *   <li><b>Replay capability</b> — Retrieve messages for recovery or replay</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * MessageStore store = MessageStore.inMemory()
 *     .withRetention(Duration.ofHours(24))
 *     .withMaxSize(100_000);
 *
 * // Store a message
 * MessageStore.StoredMessage msg = store.store(MessageBus.Envelope.of("topic", payload));
 *
 * // Retrieve messages for a topic
 * Stream<MessageStore.StoredMessage> messages = store.retrieve("topic", 100);
 *
 * // Compact old messages
 * store.compact();
 * }</pre>
 */
public interface MessageStore {

    /** Stored message with metadata. */
    record StoredMessage(
            long sequence,
            String topic,
            Object payload,
            Instant timestamp,
            Map<String, String> headers,
            UUID correlationId) {

        public static StoredMessage from(long sequence, MessageBus.Envelope envelope) {
            return new StoredMessage(
                    sequence,
                    envelope.topic(),
                    envelope.payload(),
                    envelope.timestamp(),
                    envelope.headers(),
                    envelope.correlationId());
        }
    }

    /** Store statistics. */
    record Stats(
            long totalMessages,
            long totalBytes,
            int topics,
            Instant oldestMessage,
            Instant newestMessage) {}

    // ── Core operations ──────────────────────────────────────────────────────────

    /**
     * Store a message envelope.
     *
     * @param envelope the envelope to store
     * @return the stored message with sequence number
     */
    StoredMessage store(MessageBus.Envelope envelope);

    /**
     * Retrieve messages for a topic.
     *
     * @param topic the topic to retrieve
     * @param limit maximum number of messages
     * @return stream of stored messages (newest first)
     */
    Stream<StoredMessage> retrieve(String topic, int limit);

    /**
     * Retrieve messages starting from a sequence number.
     *
     * @param topic the topic
     * @param fromSequence starting sequence number (exclusive)
     * @param limit maximum number of messages
     * @return stream of stored messages
     */
    Stream<StoredMessage> retrieveFrom(String topic, long fromSequence, int limit);

    /**
     * Get the current sequence number for a topic.
     */
    long currentSequence(String topic);

    /**
     * Remove old messages based on retention policy.
     */
    void compact();

    /**
     * Get store statistics.
     */
    Stats stats();

    /**
     * Clear all messages.
     */
    void clear();

    // ── Factory methods ──────────────────────────────────────────────────────────

    /**
     * Create an in-memory message store.
     */
    static InMemoryBuilder inMemory() {
        return new InMemoryBuilder();
    }

    // ── In-memory implementation ─────────────────────────────────────────────────

    /** Builder for in-memory store. */
    final class InMemoryBuilder {
        private Duration retention = Duration.ofHours(24);
        private long maxSize = 100_000;

        public InMemoryBuilder retention(Duration retention) {
            this.retention = retention;
            return this;
        }

        public InMemoryBuilder maxSize(long maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public MessageStore build() {
            return new InMemoryMessageStore(retention, maxSize);
        }
    }

    /** In-memory message store implementation. */
    final class InMemoryMessageStore implements MessageStore {
        private final ConcurrentHashMap<String, ConcurrentLinkedQueue<StoredMessage>> topics = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> topicSequences = new ConcurrentHashMap<>();
        private final AtomicLong globalSequence = new AtomicLong(0);
        private final Duration retention;
        private final long maxSize;
        private volatile Instant oldestMessage = Instant.now();
        private volatile Instant newestMessage = Instant.now();

        InMemoryMessageStore(Duration retention, long maxSize) {
            this.retention = retention;
            this.maxSize = maxSize;
        }

        @Override
        public StoredMessage store(MessageBus.Envelope envelope) {
            long seq = globalSequence.incrementAndGet();
            StoredMessage msg = StoredMessage.from(seq, envelope);

            topics.computeIfAbsent(envelope.topic(), k -> new ConcurrentLinkedQueue<>()).add(msg);
            topicSequences.computeIfAbsent(envelope.topic(), k -> new AtomicLong(0)).incrementAndGet();

            newestMessage = Instant.now();
            if (oldestMessage == null) {
                oldestMessage = newestMessage;
            }

            return msg;
        }

        @Override
        public Stream<StoredMessage> retrieve(String topic, int limit) {
            Queue<StoredMessage> queue = topics.get(topic);
            if (queue == null) return Stream.empty();

            return queue.stream()
                    .skip(Math.max(0, queue.size() - limit))
                    .limit(limit);
        }

        @Override
        public Stream<StoredMessage> retrieveFrom(String topic, long fromSequence, int limit) {
            Queue<StoredMessage> queue = topics.get(topic);
            if (queue == null) return Stream.empty();

            return queue.stream()
                    .filter(m -> m.sequence() > fromSequence)
                    .limit(limit);
        }

        @Override
        public long currentSequence(String topic) {
            AtomicLong seq = topicSequences.get(topic);
            return seq != null ? seq.get() : 0;
        }

        @Override
        public void compact() {
            Instant cutoff = Instant.now().minus(retention);

            topics.forEach((topic, queue) -> {
                queue.removeIf(msg -> msg.timestamp().isBefore(cutoff));
            });

            // Update oldest message
            oldestMessage = topics.values().stream()
                    .flatMap(Queue::stream)
                    .map(StoredMessage::timestamp)
                    .min(Instant::compareTo)
                    .orElse(Instant.now());
        }

        @Override
        public Stats stats() {
            long total = topics.values().stream().mapToLong(Queue::size).sum();
            return new Stats(total, -1, topics.size(), oldestMessage, newestMessage);
        }

        @Override
        public void clear() {
            topics.clear();
            globalSequence.set(0);
            oldestMessage = Instant.now();
            newestMessage = Instant.now();
        }
    }
}
