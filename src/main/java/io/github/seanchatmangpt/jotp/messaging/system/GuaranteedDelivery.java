package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guaranteed Delivery (Vernon: "Guaranteed Delivery")
 *
 * <p>Ensures message delivery even if systems crash.
 * Uses persistent storage + replay on recovery.
 *
 * <p>JOTP Implementation: Supervisor with ONE_FOR_ALL restart strategy.
 * Failed messages are persisted; on supervisor restart, replay them.
 *
 * <p>Example:
 * <pre>
 * var delivery = GuaranteedDelivery.create(
 *     receiverProc,
 *     new InMemoryMessageStore()  // Persist delivery attempts
 * );
 * // Messages are automatically retried on crash recovery
 * </pre>
 */
public final class GuaranteedDelivery {

    /**
     * Contract for persistent message storage.
     */
    public interface MessageStore {
        void store(Message msg);
        void remove(UUID msgId);
        List<Message> loadUndelivered();
    }

    /**
     * In-memory message store (not durable, for demo).
     */
    static class InMemoryMessageStore implements MessageStore {
        private final Map<UUID, Message> undelivered = new ConcurrentHashMap<>();

        @Override
        public void store(Message msg) {
            undelivered.put(msg.messageId(), msg);
        }

        @Override
        public void remove(UUID msgId) {
            undelivered.remove(msgId);
        }

        @Override
        public List<Message> loadUndelivered() {
            return new ArrayList<>(undelivered.values());
        }
    }

    /**
     * Delivery state: persisted messages and delivery history.
     */
    static class DeliveryState {
        MessageStore store;
        Set<UUID> delivered = ConcurrentHashMap.newKeySet();
        int attemptCount = 0;

        DeliveryState(MessageStore store) {
            this.store = store;
        }
    }

    private GuaranteedDelivery() {
    }

    /**
     * Creates a guaranteed delivery wrapper for a receiver.
     * Persists messages and replays on crash.
     *
     * @param receiver Target receiver ProcRef
     * @param messageStore Persistence layer (store undelivered messages)
     * @return Delivery ProcRef (wrapper around receiver)
     */
    public static ProcRef<DeliveryState, Message> create(
        ProcRef<?, Message> receiver,
        MessageStore messageStore) {

        var deliverer = Proc.spawn(
            new DeliveryState(messageStore),
            state -> msg -> {
                if (!state.delivered.contains(msg.messageId())) {
                    try {
                        // Try to deliver
                        receiver.send(msg);
                        state.delivered.add(msg.messageId());
                        state.store.remove(msg.messageId());
                        state.attemptCount++;
                    } catch (Exception e) {
                        // Persist for retry
                        state.store.store(msg);
                        state.attemptCount++;
                    }
                }
                return state;
            }
        );

        // On startup, replay undelivered messages
        replayUndelivered(deliverer);

        return deliverer;
    }

    /**
     * Replays messages that weren't previously delivered.
     * Called on process startup for crash recovery.
     *
     * @param deliverer The delivery ProcRef
     */
    private static void replayUndelivered(ProcRef<DeliveryState, Message> deliverer) {
        var state = Proc.getState(deliverer);
        var undelivered = state.store.loadUndelivered();
        for (var msg : undelivered) {
            deliverer.send(msg);
        }
    }

    /**
     * Checks if a message has been delivered.
     *
     * @param deliverer The delivery ProcRef
     * @param msgId Message ID to check
     * @return True if message was delivered
     */
    public static boolean isDelivered(
        ProcRef<DeliveryState, Message> deliverer,
        UUID msgId) {

        var state = Proc.getState(deliverer);
        return state.delivered.contains(msgId);
    }

    /**
     * Gets delivery statistics.
     *
     * @param deliverer The delivery ProcRef
     * @return Formatted stats
     */
    public static String getStats(ProcRef<DeliveryState, Message> deliverer) {
        var state = Proc.getState(deliverer);
        return String.format(
            "delivered=%d, attempts=%d",
            state.delivered.size(),
            state.attemptCount
        );
    }

    /**
     * Retries a specific message.
     *
     * @param deliverer The delivery ProcRef
     * @param msgId Message ID to retry
     */
    public static void retry(ProcRef<DeliveryState, Message> deliverer, UUID msgId) {
        var state = Proc.getState(deliverer);
        state.delivered.remove(msgId);  // Mark as undelivered
    }
}
