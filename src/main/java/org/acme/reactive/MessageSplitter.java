package org.acme.reactive;

import java.util.List;
import java.util.function.Function;

/**
 * Message splitter: decomposes one composite message into a sequence of part messages, forwarding
 * each part to a downstream channel.
 *
 * <p>Enterprise Integration Pattern: <em>Splitter</em> (EIP §9.1). Erlang analog: the canonical
 * OTP scatter pattern — a coordinator process receives a batch and fans out individual work units
 * to a pool of worker processes via {@code [Pid ! Part || Part <- Parts]}. Complementary to
 * {@link MessageAggregator} which re-collects the scattered parts.
 *
 * <p>Usage — scatter-gather with splitter and aggregator:
 *
 * <pre>{@code
 * record ShipmentBatch(String batchId, List<Package> packages) {}
 * record Package(String batchId, String trackingId, String destination) {}
 * record LabelledPackage(String trackingId, String label) {}
 *
 * // Gather phase: re-aggregate labelled packages back into a batch
 * var labelled = MessageAggregator.<LabelledPackage, List<LabelledPackage>>builder()
 *     .correlateBy(p -> p.trackingId().substring(0, 8)) // batch prefix
 *     .completeWhen(parts -> parts.size() == 3)
 *     .aggregateWith(parts -> List.copyOf(parts))
 *     .downstream(labelPrinter)
 *     .build();
 *
 * // Transform + label individual packages
 * var labeller = new PointToPointChannel<Package>(pkg -> {
 *     labelled.send(new LabelledPackage(pkg.trackingId(), generateLabel(pkg)));
 * });
 *
 * // Scatter phase: split the batch into individual packages
 * var splitter = MessageSplitter.<ShipmentBatch, Package>of(
 *     batch -> batch.packages(),
 *     labeller);
 *
 * splitter.send(new ShipmentBatch("batch-1", List.of(pkg1, pkg2, pkg3)));
 * }</pre>
 *
 * @param <T> composite input message type
 * @param <R> individual part message type
 */
public final class MessageSplitter<T, R> implements MessageChannel<T> {

    private final Function<T, List<R>> split;
    private final MessageChannel<R> downstream;

    private MessageSplitter(Function<T, List<R>> split, MessageChannel<R> downstream) {
        this.split = split;
        this.downstream = downstream;
    }

    /**
     * Create a splitter.
     *
     * @param split function decomposing the composite message into its parts
     * @param downstream channel receiving each individual part
     * @param <T> composite input type
     * @param <R> part output type
     * @return the splitter channel
     */
    public static <T, R> MessageSplitter<T, R> of(
            Function<T, List<R>> split, MessageChannel<R> downstream) {
        return new MessageSplitter<>(split, downstream);
    }

    /**
     * Decompose {@code message} and forward each part to downstream.
     *
     * <p>Parts are sent in the order returned by the split function. All sends are non-blocking —
     * identical to Erlang list comprehension sends which return immediately after enqueueing.
     *
     * @param message the composite message to split
     */
    @Override
    public void send(T message) {
        List<R> parts = split.apply(message);
        for (R part : parts) {
            downstream.send(part);
        }
    }

    /**
     * Stop the downstream channel.
     *
     * @throws InterruptedException if interrupted while waiting for downstream to drain
     */
    @Override
    public void stop() throws InterruptedException {
        downstream.stop();
    }
}
