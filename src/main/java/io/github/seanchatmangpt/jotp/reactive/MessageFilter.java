package io.github.seanchatmangpt.jotp.reactive;

import java.util.function.Predicate;

/**
 * Selective filter: passes messages satisfying a predicate to a downstream channel; all others go
 * to a dead-letter channel (or are silently dropped).
 *
 * <p>Enterprise Integration Pattern: <em>Message Filter</em> (EIP §8.2). Erlang analog: Erlang
 * selective receive guards ({@code receive Msg when is_integer(Msg) -> ...}) — only certain message
 * shapes are consumed; unmatched messages remain in the mailbox (or, here, are forwarded to dead
 * letters).
 *
 * <p>A {@code MessageFilter} is itself a {@link MessageChannel}: route messages into it and the
 * accepted ones appear on the downstream channel.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var validOrders = new PointToPointChannel<Order>(order -> process(order));
 * var dead = new DeadLetterChannel<Order>();
 *
 * var filter = MessageFilter.<Order>of(
 *     order -> order.quantity() > 0 && order.customerId() != null,
 *     validOrders,
 *     dead);
 *
 * filter.send(validOrder);    // → validOrders
 * filter.send(invalidOrder);  // → dead (quantity <= 0)
 * }</pre>
 *
 * @param <T> message type
 */
public final class MessageFilter<T> implements MessageChannel<T> {

    private final Predicate<T> accept;
    private final MessageChannel<T> accepted;
    private final MessageChannel<T> rejected;

    private MessageFilter(
            Predicate<T> accept, MessageChannel<T> accepted, MessageChannel<T> rejected) {
        this.accept = accept;
        this.accepted = accepted;
        this.rejected = rejected;
    }

    /**
     * Create a filter that silently drops rejected messages (no dead-letter channel).
     *
     * @param predicate the acceptance condition
     * @param downstream the channel for accepted messages
     * @param <T> message type
     * @return the filter channel
     */
    public static <T> MessageFilter<T> of(Predicate<T> predicate, MessageChannel<T> downstream) {
        return new MessageFilter<>(predicate, downstream, null);
    }

    /**
     * Create a filter that forwards rejected messages to {@code rejected}.
     *
     * @param predicate the acceptance condition
     * @param accepted channel for messages that pass
     * @param rejected channel for messages that fail (typically a {@link DeadLetterChannel})
     * @param <T> message type
     * @return the filter channel
     */
    public static <T> MessageFilter<T> of(
            Predicate<T> predicate, MessageChannel<T> accepted, MessageChannel<T> rejected) {
        return new MessageFilter<>(predicate, accepted, rejected);
    }

    /**
     * Test the predicate; forward the message to the accepted or rejected channel accordingly.
     *
     * <p>Non-blocking — mirrors Erlang's pattern guard evaluation which is always local and
     * side-effect-free.
     *
     * @param message the message to filter
     */
    @Override
    public void send(T message) {
        if (accept.test(message)) {
            accepted.send(message);
        } else if (rejected != null) {
            rejected.send(message);
        }
    }

    /**
     * Stop both the accepted and rejected downstream channels.
     *
     * @throws InterruptedException if interrupted while waiting for channels to drain
     */
    @Override
    public void stop() throws InterruptedException {
        accepted.stop();
        if (rejected != null) {
            rejected.stop();
        }
    }
}
