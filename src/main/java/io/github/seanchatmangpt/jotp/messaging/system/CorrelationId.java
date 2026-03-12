package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlation ID (Vernon: "Correlation Identifier")
 *
 * <p>Links related messages together (e.g., request ↔ reply, saga steps).
 * Enables tracking message flows across systems.
 *
 * <p>Pattern: Message carries a correlation UUID that associates it with
 * a logical conversation or transaction.
 *
 * <p>Example:
 * <pre>
 * var correlationId = CorrelationId.generate();
 * var msg1 = CorrelationId.withId(message1, correlationId);
 * var msg2 = CorrelationId.withId(message2, correlationId);
 * // msg1 and msg2 are now linked
 * </pre>
 */
public final class CorrelationId {

    /**
     * Message wrapper with correlation tracking.
     */
    public record CorrelatedMessage(
        Message message,
        UUID correlationId,
        UUID causationId,  // Parent message ID that caused this one
        Map<String, String> metadata
    ) {
        public CorrelatedMessage {
            if (correlationId == null) {
                throw new IllegalArgumentException("correlationId must not be null");
            }
        }
    }

    // Correlation tracking: for distributed tracing
    private static final Map<UUID, CorrelationContext> CORRELATION_CONTEXTS =
        new ConcurrentHashMap<>();

    /**
     * Correlation context: tracks all messages in a correlation.
     */
    public static class CorrelationContext {
        public final UUID correlationId;
        public final Map<UUID, CorrelatedMessage> messages = new HashMap<>();
        public final long createdAt;

        public CorrelationContext(UUID corrId) {
            this.correlationId = corrId;
            this.createdAt = System.currentTimeMillis();
        }

        public void addMessage(UUID msgId, CorrelatedMessage msg) {
            messages.put(msgId, msg);
        }

        public int messageCount() {
            return messages.size();
        }

        public long duration() {
            return System.currentTimeMillis() - createdAt;
        }
    }

    private CorrelationId() {
    }

    /**
     * Generates a new correlation ID.
     *
     * @return New UUID
     */
    public static UUID generate() {
        return UUID.randomUUID();
    }

    /**
     * Wraps a message with a correlation ID.
     *
     * @param message The message to correlate
     * @param correlationId The correlation ID
     * @return CorrelatedMessage
     */
    public static CorrelatedMessage withId(Message message, UUID correlationId) {
        return new CorrelatedMessage(
            message,
            correlationId,
            null,  // No parent
            Map.of(
                "timestamp", String.valueOf(System.currentTimeMillis()),
                "trace-id", correlationId.toString()
            )
        );
    }

    /**
     * Wraps a message as a child (caused by another message).
     *
     * @param message The new message
     * @param parent The message that caused this one
     * @return CorrelatedMessage with causation chain
     */
    public static CorrelatedMessage withParent(CorrelatedMessage parent, Message message) {
        return new CorrelatedMessage(
            message,
            parent.correlationId,  // Same correlation
            parent.message.messageId(),  // Record parent
            Map.of(
                "timestamp", String.valueOf(System.currentTimeMillis()),
                "trace-id", parent.correlationId.toString(),
                "parent-id", parent.message.messageId().toString()
            )
        );
    }

    /**
     * Starts tracking a correlation (for distributed tracing).
     *
     * @param correlationId The correlation ID
     * @return CorrelationContext
     */
    public static CorrelationContext startTracking(UUID correlationId) {
        var context = new CorrelationContext(correlationId);
        CORRELATION_CONTEXTS.put(correlationId, context);
        return context;
    }

    /**
     * Records a message in its correlation context.
     *
     * @param correlatedMsg The correlated message
     */
    public static void recordMessage(CorrelatedMessage correlatedMsg) {
        var context = CORRELATION_CONTEXTS.get(correlatedMsg.correlationId);
        if (context != null) {
            context.addMessage(correlatedMsg.message.messageId(), correlatedMsg);
        }
    }

    /**
     * Gets the correlation context for a correlation ID.
     *
     * @param correlationId The correlation ID
     * @return CorrelationContext or null if not tracked
     */
    public static CorrelationContext getContext(UUID correlationId) {
        return CORRELATION_CONTEXTS.get(correlationId);
    }

    /**
     * Completes tracking for a correlation.
     *
     * @param correlationId The correlation ID
     * @return Final CorrelationContext
     */
    public static CorrelationContext completeTracking(UUID correlationId) {
        return CORRELATION_CONTEXTS.remove(correlationId);
    }

    /**
     * Gets correlation ID from a correlated message.
     *
     * @param correlatedMsg The correlated message
     * @return The correlation UUID
     */
    public static UUID getId(CorrelatedMessage correlatedMsg) {
        return correlatedMsg.correlationId;
    }

    /**
     * Checks if two messages are in the same correlation.
     *
     * @param msg1 First correlated message
     * @param msg2 Second correlated message
     * @return True if both share the same correlation ID
     */
    public static boolean isCorrelated(CorrelatedMessage msg1, CorrelatedMessage msg2) {
        return msg1.correlationId.equals(msg2.correlationId);
    }
}
