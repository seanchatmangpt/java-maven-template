package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Message Expiration pattern with automatic cleanup and TTL enforcement.
 *
 * <p>Messaging pattern: Discards expired messages before processing to prevent
 * stale data from being actioned. Uses JOTP's {@link ProcTimer} for timeout management
 * and periodic cleanup.
 *
 * <p>Joe Armstrong principle: "Messages that are too old should be discarded before
 * processing. In a distributed system with network delays and retries, message age
 * becomes a critical dimension."
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var expiration = MessageExpiration.create(Duration.ofSeconds(30));
 *
 * // Wrap incoming messages with TTL metadata
 * var wrappedMsg = expiration.withExpiration(incomingMessage, 5000);  // 5 second TTL
 *
 * // Before processing, check expiration
 * if (!expiration.isExpired(wrappedMsg)) {
 *     processMessage(wrappedMsg.payload());
 * } else {
 *     alertOps("Message expired: " + wrappedMsg.messageId());
 * }
 *
 * // Cleanup expired messages periodically
 * var cleanupTimer = ProcTimer.sendInterval(10000, cleanupProc,
 *     new CleanupMsg());
 * }</pre>
 */
public final class MessageExpiration {

    /**
     * An expirable message envelope wrapping a base message with TTL metadata.
     *
     * @param message the original message
     * @param ttlMs time-to-live in milliseconds from creation
     * @param createdAt when this message was created
     */
    public record ExpiringMessage<T>(T message, long ttlMs, Instant createdAt) {
        /**
         * Calculate when this message expires.
         *
         * @return the expiration instant
         */
        public Instant expiresAt() {
            return createdAt.plusMillis(ttlMs);
        }

        /**
         * Check if this message has expired as of the given instant.
         *
         * @param now the current time
         * @return true if message is past its expiration instant
         */
        public boolean isExpiredAt(Instant now) {
            return now.isAfter(expiresAt());
        }
    }

    private final long cleanupIntervalMs;

    /**
     * Create a message expiration manager with a cleanup interval.
     *
     * @param cleanupIntervalMs how often (in ms) to run cleanup scans
     */
    private MessageExpiration(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    /**
     * Create a message expiration manager instance.
     *
     * @param cleanupIntervalMs cleanup scan interval in milliseconds
     * @return a new {@code MessageExpiration}
     */
    public static MessageExpiration create(long cleanupIntervalMs) {
        if (cleanupIntervalMs <= 0) {
            throw new IllegalArgumentException("cleanupIntervalMs must be positive");
        }
        return new MessageExpiration(cleanupIntervalMs);
    }

    /**
     * Wrap a message with TTL metadata for expiration tracking.
     *
     * <p>The resulting {@link ExpiringMessage} carries the original message, its TTL,
     * and the creation time. Use {@link #isExpired(ExpiringMessage)} to check before
     * processing.
     *
     * @param message the message to wrap
     * @param ttlMs time-to-live in milliseconds
     * @return an expiring message wrapper
     */
    public <T> ExpiringMessage<T> withExpiration(T message, long ttlMs) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        return new ExpiringMessage<>(message, ttlMs, Instant.now());
    }

    /**
     * Check if a message has expired as of now.
     *
     * @param expiring the expiring message to check
     * @return true if the message is past its TTL
     */
    public <T> boolean isExpired(ExpiringMessage<T> expiring) {
        return expiring.isExpiredAt(Instant.now());
    }

    /**
     * Check if a message expires at a given instant.
     *
     * @param expiring the expiring message
     * @param at the instant to check against
     * @return true if expired at that instant
     */
    public <T> boolean isExpiredAt(ExpiringMessage<T> expiring, Instant at) {
        return expiring.isExpiredAt(at);
    }

    /**
     * Time until expiration in milliseconds (as of now).
     *
     * <p>Returns a negative value if already expired.
     *
     * @param expiring the expiring message
     * @return milliseconds until expiration (negative if already expired)
     */
    public <T> long timeUntilExpiration(ExpiringMessage<T> expiring) {
        Instant now = Instant.now();
        Instant expiresAt = expiring.expiresAt();
        return expiresAt.toEpochMilli() - now.toEpochMilli();
    }

    /**
     * Filter a stream of expiring messages, removing expired ones.
     *
     * <p>Useful for batch processing or cleanup of accumulated messages.
     *
     * @param messages stream of expiring messages
     * @return stream containing only non-expired messages
     */
    public <T> Stream<ExpiringMessage<T>> cleanupExpired(Stream<ExpiringMessage<T>> messages) {
        Instant now = Instant.now();
        return messages.filter(msg -> !msg.isExpiredAt(now));
    }

    /**
     * Drain and filter a collection of expiring messages, removing expired ones.
     *
     * <p>Convenience method for batch cleanup of lists.
     *
     * @param messages list of expiring messages
     * @return list containing only non-expired messages
     */
    public <T> List<ExpiringMessage<T>> retainNonExpired(List<ExpiringMessage<T>> messages) {
        Instant now = Instant.now();
        var result = new ArrayList<ExpiringMessage<T>>();
        for (var msg : messages) {
            if (!msg.isExpiredAt(now)) {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * Remove and return expired messages from a collection.
     *
     * <p>Useful for gathering metrics or alerting on expired messages.
     *
     * @param messages list of expiring messages
     * @return list containing only expired messages (removed from input)
     */
    public <T> List<ExpiringMessage<T>> extractExpired(List<ExpiringMessage<T>> messages) {
        Instant now = Instant.now();
        var expired = new ArrayList<ExpiringMessage<T>>();
        var iterator = messages.iterator();
        while (iterator.hasNext()) {
            var msg = iterator.next();
            if (msg.isExpiredAt(now)) {
                expired.add(msg);
                iterator.remove();
            }
        }
        return expired;
    }

    /**
     * Get the cleanup interval used by this manager.
     *
     * @return cleanup interval in milliseconds
     */
    public long cleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    /**
     * Age of a message in milliseconds (time elapsed since creation).
     *
     * @param expiring the expiring message
     * @return age in milliseconds
     */
    public <T> long ageMs(ExpiringMessage<T> expiring) {
        return System.currentTimeMillis() - expiring.createdAt().toEpochMilli();
    }

    /**
     * Create a Message with standard JOTP message format and TTL.
     *
     * <p>Convenience for wrapping JOTP {@link Message} instances with expiration.
     *
     * @param msg the JOTP message
     * @param ttlMs TTL in milliseconds
     * @return wrapped message
     */
    public ExpiringMessage<Message> wrapMessage(Message msg, long ttlMs) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        return new ExpiringMessage<>(msg, ttlMs, Instant.now());
    }
}
