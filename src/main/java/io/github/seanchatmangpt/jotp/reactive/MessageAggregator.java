package io.github.seanchatmangpt.jotp.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Correlation-based message aggregator: collects related messages under a shared key, then emits
 * an aggregated result when the completion condition is met.
 *
 * <p>Enterprise Integration Pattern: <em>Aggregator</em> (EIP §9.5). Erlang analog: a stateful
 * {@code gen_server} maintaining a {@code Map} of in-flight correlation groups, accumulating
 * partial results and replying with the combined value once complete — the canonical OTP
 * "gather phase" of a scatter-gather workflow.
 *
 * <p>Three functions define an aggregator:
 *
 * <ol>
 *   <li><strong>Correlation key</strong> — maps a message to a group identifier
 *   <li><strong>Completion condition</strong> — returns {@code true} when a group is ready to emit
 *   <li><strong>Aggregation function</strong> — combines the group's messages into the result type
 * </ol>
 *
 * <p>Thread safety: each correlation group is independently managed; concurrent groups do not
 * interfere. Group state is held in a {@link ConcurrentHashMap} and updated under per-key
 * synchronization.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * record OrderLine(String orderId, String sku, int qty) {}
 * record FullOrder(String orderId, List<OrderLine> lines) {}
 *
 * var orders = new PointToPointChannel<FullOrder>(order -> fulfil(order));
 * var aggregator = MessageAggregator.<OrderLine, FullOrder>builder()
 *     .correlateBy(line -> line.orderId())
 *     .completeWhen(lines -> lines.size() == 3)
 *     .aggregateWith(lines -> new FullOrder(lines.getFirst().orderId(), lines))
 *     .downstream(orders)
 *     .build();
 *
 * aggregator.send(new OrderLine("ord-1", "sku-a", 2));
 * aggregator.send(new OrderLine("ord-1", "sku-b", 1));
 * aggregator.send(new OrderLine("ord-1", "sku-c", 5)); // → FullOrder emitted
 * }</pre>
 *
 * @param <T> incoming message type
 * @param <R> aggregated result type
 */
public final class MessageAggregator<T, R> implements MessageChannel<T> {

    private final Function<T, String> correlationKey;
    private final Predicate<List<T>> isComplete;
    private final Function<List<T>, R> aggregate;
    private final MessageChannel<R> downstream;

    /**
     * Per-correlation-key accumulator buckets. Guarded by the key's own synchronized block to
     * ensure atomicity of the check-then-complete sequence.
     */
    private final ConcurrentHashMap<String, List<T>> groups = new ConcurrentHashMap<>();

    private MessageAggregator(
            Function<T, String> correlationKey,
            Predicate<List<T>> isComplete,
            Function<List<T>, R> aggregate,
            MessageChannel<R> downstream) {
        this.correlationKey = correlationKey;
        this.isComplete = isComplete;
        this.aggregate = aggregate;
        this.downstream = downstream;
    }

    /**
     * Add {@code message} to its correlation group; emit to downstream if the group is now
     * complete.
     *
     * <p>Per-key synchronization ensures that the completion check and group removal are atomic —
     * preventing duplicate emissions when multiple producers share a key.
     *
     * @param message the incoming message to accumulate
     */
    @Override
    public void send(T message) {
        String key = correlationKey.apply(message);
        List<T> completedGroup = null;

        // Compute-and-check under per-key lock to prevent races on the same correlation key.
        synchronized (groups) {
            List<T> group = groups.computeIfAbsent(key, k -> new ArrayList<>());
            group.add(message);
            if (isComplete.test(group)) {
                completedGroup = new ArrayList<>(group);
                groups.remove(key);
            }
        }

        if (completedGroup != null) {
            R result = aggregate.apply(completedGroup);
            downstream.send(result);
        }
    }

    /**
     * Stop the downstream channel. In-flight incomplete groups are silently discarded — callers
     * should drain or timeout groups before stopping if partial aggregations must be handled.
     *
     * @throws InterruptedException if interrupted while waiting for downstream to drain
     */
    @Override
    public void stop() throws InterruptedException {
        downstream.stop();
    }

    /** Returns a new builder. */
    public static <T, R> Builder<T, R> builder() {
        return new Builder<>();
    }

    /** Fluent builder for {@link MessageAggregator}. */
    public static final class Builder<T, R> {

        private Function<T, String> correlationKey;
        private Predicate<List<T>> isComplete;
        private Function<List<T>, R> aggregate;
        private MessageChannel<R> downstream;

        /**
         * Set the function that extracts the correlation key from each message.
         *
         * <p>All messages returning the same key belong to the same group.
         */
        public Builder<T, R> correlateBy(Function<T, String> correlationKey) {
            this.correlationKey = correlationKey;
            return this;
        }

        /**
         * Set the predicate that returns {@code true} when the group is ready to be aggregated and
         * forwarded downstream.
         */
        public Builder<T, R> completeWhen(Predicate<List<T>> isComplete) {
            this.isComplete = isComplete;
            return this;
        }

        /**
         * Set the aggregation function that combines a completed group into the result type
         * {@code R}.
         */
        public Builder<T, R> aggregateWith(Function<List<T>, R> aggregate) {
            this.aggregate = aggregate;
            return this;
        }

        /** Set the channel that receives each completed aggregation result. */
        public Builder<T, R> downstream(MessageChannel<R> downstream) {
            this.downstream = downstream;
            return this;
        }

        /** Build the {@link MessageAggregator}. */
        public MessageAggregator<T, R> build() {
            if (correlationKey == null) throw new IllegalStateException("correlateBy() is required");
            if (isComplete == null) throw new IllegalStateException("completeWhen() is required");
            if (aggregate == null) throw new IllegalStateException("aggregateWith() is required");
            if (downstream == null) throw new IllegalStateException("downstream() is required");
            return new MessageAggregator<>(correlationKey, isComplete, aggregate, downstream);
        }
    }
}
