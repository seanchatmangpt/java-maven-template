package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Polling Consumer (Vernon: "Polling Consumer")
 *
 * <p>Periodically polls a message source to check for new messages.
 * Useful for integrating with systems that don't support push notifications.
 *
 * <p>JOTP Implementation: Uses ProcTimer for periodic polling,
 * Proc<S,M> for stateful consumer.
 *
 * <p>Example:
 * <pre>
 * var source = () -> Message.event("DATA", fetchFromDatabase());
 * var consumer = PollingConsumer.create(source, 1000); // poll every 1s
 * // Messages automatically pulled and queued
 * </pre>
 */
public final class PollingConsumer {

    public interface MessageSource {
        Message poll();
    }

    /**
     * Consumer state: queue of polled messages.
     */
    static class PollerState {
        final Queue<Message> messages = new ConcurrentLinkedQueue<>();
        long lastPoll = System.currentTimeMillis();
        int pollCount = 0;

        void addMessage(Message msg) {
            if (msg != null) {
                messages.offer(msg);
            }
        }

        Message next() {
            return messages.poll();
        }

        int size() {
            return messages.size();
        }
    }

    private PollingConsumer() {
    }

    /**
     * Creates a polling consumer that periodically fetches messages.
     *
     * @param source Message source (typically external system)
     * @param intervalMs Polling interval in milliseconds
     * @return ProcRef for the consumer
     */
    public static ProcRef<PollerState, String> create(
        MessageSource source,
        long intervalMs) {

        var consumer = Proc.spawn(new PollerState(), state -> msg -> {
            if ("POLL".equals(msg)) {
                var message = source.poll();
                state.addMessage(message);
                state.lastPoll = System.currentTimeMillis();
                state.pollCount++;
            }
            return state;
        });

        // Schedule periodic polling via timer
        ProcTimer.sendInterval(consumer, "POLL", intervalMs);

        return consumer;
    }

    /**
     * Retrieves next polled message (non-blocking).
     *
     * @param consumer The consumer ProcRef
     * @return Next message or null if queue empty
     */
    public static Message getNext(ProcRef<PollerState, String> consumer) {
        var state = Proc.getState(consumer);
        return state.next();
    }

    /**
     * Gets number of pending messages.
     *
     * @param consumer The consumer ProcRef
     * @return Queue size
     */
    public static int pendingCount(ProcRef<PollerState, String> consumer) {
        var state = Proc.getState(consumer);
        return state.size();
    }

    /**
     * Gets polling statistics.
     *
     * @param consumer The consumer ProcRef
     * @return Formatted stats string
     */
    public static String getStats(ProcRef<PollerState, String> consumer) {
        var state = Proc.getState(consumer);
        return String.format(
            "polls=%d, pending=%d, lastPoll=%d",
            state.pollCount,
            state.size(),
            System.currentTimeMillis() - state.lastPoll
        );
    }

    /**
     * Stops polling.
     *
     * @param consumer The consumer ProcRef
     */
    public static void stop(ProcRef<PollerState, String> consumer) {
        // Cancel the timer
        ProcTimer.cancel(consumer);
    }
}
