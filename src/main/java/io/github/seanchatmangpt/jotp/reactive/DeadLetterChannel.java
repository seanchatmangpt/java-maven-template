package io.github.seanchatmangpt.jotp.reactive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;

/**
 * Dead-letter channel: captures messages that could not be delivered or processed.
 *
 * <p>Enterprise Integration Pattern: <em>Dead Letter Channel</em> (EIP §6.3). Erlang analog: the
 * OTP error logger accumulating crash reports; the Erlang "error kernel" pattern where unhandled
 * messages accumulate for inspection.
 *
 * <p>Undeliverable messages arrive here with a reason and timestamp. Operators may inspect, retry,
 * alert, or purge the dead letters. This implements the <em>supervisory layer</em> for messaging —
 * analogous to how a {@link org.acme.Supervisor} manages process crashes, the dead-letter channel
 * manages message delivery failures.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var dead = new DeadLetterChannel<Order>();
 * var router = MessageRouter.<Order>builder()
 *     .route(o -> o.isValid(), fulfilmentChannel)
 *     .otherwise(dead)
 *     .build();
 *
 * router.send(invalidOrder);  // → dead letter channel
 * dead.drain().forEach(dl -> alertOps(dl.message(), dl.reason()));
 * }</pre>
 *
 * @param <T> message type
 */
public final class DeadLetterChannel<T> implements MessageChannel<T> {

    /**
     * A dead-letter envelope capturing the original message, the reason it was not delivered, and
     * the timestamp of arrival.
     *
     * @param message the undeliverable message
     * @param reason human-readable explanation (e.g. "no matching route", "consumer threw", etc.)
     * @param arrivedAt when the message entered the dead-letter channel
     */
    public record DeadLetter<T>(T message, String reason, Instant arrivedAt) {
        /** Convenience constructor with current timestamp. */
        public DeadLetter(T message, String reason) {
            this(message, reason, Instant.now());
        }
    }

    private final LinkedTransferQueue<DeadLetter<T>> store = new LinkedTransferQueue<>();

    /**
     * Deliver a message to the dead-letter channel with a generic "undeliverable" reason.
     *
     * <p>This is the standard {@link MessageChannel#send} path — invoked when a {@link
     * MessageRouter} or {@link MessageFilter} has nowhere else to route the message.
     *
     * @param message the undeliverable message
     */
    @Override
    public void send(T message) {
        store.add(new DeadLetter<>(message, "undeliverable"));
    }

    /**
     * Deliver a message with an explicit failure reason.
     *
     * @param message the undeliverable message
     * @param reason why the message could not be delivered
     */
    public void send(T message, String reason) {
        store.add(new DeadLetter<>(message, reason));
    }

    /**
     * Remove and return all accumulated dead letters.
     *
     * <p>Analogous to flushing an Erlang error logger. The returned list is a snapshot; subsequent
     * sends will not appear in it.
     *
     * @return all dead letters since the last drain (or channel start)
     */
    public List<DeadLetter<T>> drain() {
        var result = new ArrayList<DeadLetter<T>>();
        store.drainTo(result);
        return result;
    }

    /** Number of messages currently in the dead-letter store. */
    public int size() {
        return store.size();
    }

    /**
     * Dead-letter channel does not manage a consumer thread, so stop is a no-op. Retained for
     * interface consistency.
     */
    @Override
    public void stop() {}
}
