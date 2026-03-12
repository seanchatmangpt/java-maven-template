package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.BiFunction;

/**
 * Dead Letter Channel pattern with supervisor-managed crash recovery.
 *
 * <p>Messaging pattern: Routes poison pill and failed messages to a separate channel for
 * inspection, alerting, or manual intervention. Integrates with JOTP's {@link Supervisor}
 * and {@link CrashRecovery} to automatically recover from processing failures.
 *
 * <p>Joe Armstrong principle: "If you can't process a message, don't crash silently — store
 * it in a dead letter channel where operators can inspect it and decide what to do."
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var dlc = DeadLetterChannel.<Order>create();
 * var supervisor = new Supervisor("dlc-supervisor", Supervisor.Strategy.ONE_FOR_ONE, 5,
 *     Duration.ofSeconds(60));
 *
 * // Process messages with failure recovery
 * var handler = supervisor.supervise("order-handler", dlc, (deadLetters, msg) -> {
 *     try {
 *         if (msg instanceof Order order) {
 *             processOrder(order);  // May throw for poison pills
 *         }
 *     } catch (Exception e) {
 *         deadLetters.onFailure(msg, e.getClass().getSimpleName() + ": " + e.getMessage());
 *     }
 *     return deadLetters;
 * });
 *
 * // Send messages
 * handler.tell(validOrder);
 * handler.tell(poisonPill);
 *
 * // Inspect dead letters
 * dlc.drain().forEach(dl -> alertOps(dl.message(), dl.reason()));
 * }</pre>
 *
 * @param <T> message type routed to this dead letter channel
 */
public final class DeadLetterChannel<T> {

    /**
     * A dead letter envelope capturing the failed message, reason for failure, and arrival
     * timestamp.
     *
     * @param message the unprocessable message
     * @param reason human-readable failure reason (e.g., "NullPointerException: order is null")
     * @param arrivedAt when the message entered the dead letter channel
     */
    public record DeadLetter<T>(T message, String reason, Instant arrivedAt) {
        /** Convenience constructor with current timestamp. */
        public DeadLetter(T message, String reason) {
            this(message, reason, Instant.now());
        }
    }

    private final LinkedTransferQueue<DeadLetter<T>> store = new LinkedTransferQueue<>();

    /**
     * Create a new dead letter channel instance.
     *
     * @param <T> message type
     * @return a new {@code DeadLetterChannel}
     */
    public static <T> DeadLetterChannel<T> create() {
        return new DeadLetterChannel<>();
    }

    /**
     * Send a failed message to the dead letter channel with an explicit failure reason.
     *
     * <p>Called by crash handlers or middleware when a message cannot be processed.
     *
     * @param message the failed message
     * @param reason why the message could not be processed (e.g., exception message)
     */
    public void onFailure(T message, String reason) {
        store.add(new DeadLetter<>(message, reason));
    }

    /**
     * Send a message directly (for generic undeliverable messages).
     *
     * @param message the message
     * @param reason the failure reason
     */
    public void send(T message, String reason) {
        onFailure(message, reason);
    }

    /**
     * Drain all accumulated dead letters atomically.
     *
     * <p>Returns a snapshot of all dead letters since the last drain. Subsequent calls will
     * not include the previously drained letters.
     *
     * @return list of all dead letters (may be empty if none have arrived)
     */
    public List<DeadLetter<T>> drain() {
        var result = new ArrayList<DeadLetter<T>>();
        store.drainTo(result);
        return result;
    }

    /**
     * Returns the current count of dead letters in the channel (volatile).
     *
     * <p>This count may be stale by the time this method returns; use {@link #drain()} for
     * accurate retrieval of all accumulated letters.
     *
     * @return number of dead letters currently queued
     */
    public int size() {
        return store.size();
    }

    /**
     * Supervisor-compatible handler: wraps a throwing message processor with crash recovery
     * and automatic routing to this dead letter channel on failure.
     *
     * <p>This is a convenience factory to create a supervisor-ready handler that routes
     * exceptions to the dead letter channel.
     *
     * <p>Example:
     * <pre>{@code
     * var dlc = DeadLetterChannel.<Order>create();
     * var supervisor = new Supervisor("order-sup", Strategy.ONE_FOR_ONE, 5,
     *     Duration.ofSeconds(60));
     *
     * var orderHandler = supervisor.supervise("orders", dlc,
     *     dlc.withCrashHandler((order) -> {
     *         if (order.total() < 0) throw new IllegalArgumentException("negative total");
     *         processOrder(order);
     *         return order;
     *     })
     * );
     * }</pre>
     *
     * @param processor a function that processes a message and may throw
     * @return a message handler that routes exceptions to this DLC
     */
    public BiFunction<DeadLetterChannel<T>, T, DeadLetterChannel<T>> withCrashHandler(
        Processor<T> processor) {
        return (dlc, message) -> {
            try {
                processor.process(message);
            } catch (Exception e) {
                dlc.onFailure(
                    message,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return dlc;
        };
    }

    /**
     * Functional interface for message processors that may throw.
     *
     * @param <T> message type
     */
    @FunctionalInterface
    public interface Processor<T> {
        void process(T message) throws Exception;
    }

    /**
     * Drain and filter dead letters by reason substring.
     *
     * <p>Useful for categorizing failures (e.g., all timeout-related dead letters).
     *
     * @param reasonFilter substring to match in reason field
     * @return list of dead letters whose reason contains the filter string
     */
    public List<DeadLetter<T>> drainByReason(String reasonFilter) {
        var all = drain();
        return all.stream()
            .filter(dl -> dl.reason().contains(reasonFilter))
            .toList();
    }

    /**
     * Drain and filter dead letters arrived after a given instant.
     *
     * @param since filter for dead letters that arrived at or after this time
     * @return list of dead letters arrived since the given time
     */
    public List<DeadLetter<T>> drainSince(Instant since) {
        var all = drain();
        return all.stream()
            .filter(dl -> !dl.arrivedAt().isBefore(since))
            .toList();
    }

    /**
     * Peek at the oldest dead letter without removing it.
     *
     * <p>Non-destructive inspection for monitoring or alerting.
     *
     * @return the oldest dead letter, or {@code null} if the channel is empty
     */
    public DeadLetter<T> peek() {
        return store.peek();
    }

    /**
     * Clear all dead letters from the channel.
     *
     * <p>Use with caution — this is permanent and will discard unprocessed failures.
     */
    public void clear() {
        store.clear();
    }
}
