package org.acme.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Composable message processing pipeline: a linear chain of stages where each stage transforms,
 * filters, or splits messages before passing them to the next stage.
 *
 * <p>Enterprise Integration Pattern: <em>Pipes and Filters</em> (EIP §7). Erlang analog: a chain
 * of processes where each process receives from a predecessor and sends to a successor — the
 * classic OTP pipeline topology used in protocol stacks (TCP/IP layers, codec chains, SASL
 * handlers). Joe Armstrong called this the "plumber's pattern" — each pipe section knows nothing
 * about the others.
 *
 * <p>{@code MessagePipeline} provides a <em>fluent builder</em> for constructing typed pipeline
 * graphs without manually wiring channels:
 *
 * <pre>{@code
 * // Raw HTTP log line → parsed → enriched → stored
 * var sink = new PointToPointChannel<EnrichedEvent>(event -> store.save(event));
 *
 * var pipeline = MessagePipeline.<String>source()
 *     .transform(raw -> LogParser.parse(raw))         // String → ParsedLog
 *     .filter(log -> log.level().ordinal() >= WARN)   // drop DEBUG/INFO
 *     .transform(log -> Enricher.enrich(log))         // ParsedLog → EnrichedEvent
 *     .sink(sink);
 *
 * pipeline.send("2026-03-10 ERROR NullPointerException at ...");
 * }</pre>
 *
 * <p>Each call to {@link #transform}, {@link #filter}, or {@link #splitInto} appends a stage. The
 * chain is lazily assembled when {@link #sink} is called, creating one {@link PointToPointChannel}
 * per stage — each running in its own virtual thread. This mirrors OTP pipeline topologies where
 * each process is independently scheduled and crash-isolated.
 *
 * @param <A> input type entering the pipeline at this stage
 * @param <B> output type leaving the pipeline at this stage
 */
public final class MessagePipeline<A, B> {

    /**
     * Internal pipeline stage descriptor — sealed so the terminal {@link #sink} call can
     * reflectively construct the channel chain.
     */
    private sealed interface Stage<X, Y>
            permits Stage.Transform, Stage.Filter, Stage.SplitInto {

        record Transform<X, Y>(Function<X, Y> fn) implements Stage<X, Y> {}

        record Filter<X>(Predicate<X> predicate) implements Stage<X, X> {}

        record SplitInto<X, Y>(Function<X, List<Y>> splitter) implements Stage<X, Y> {}
    }

    /** The previous pipeline stage that feeds into this one. */
    private final MessagePipeline<?, A> upstream;

    /** The stage this node applies to messages from upstream. */
    private final Stage<A, B> stage;

    /** Accumulated channel references for shutdown. Populated during {@link #sink}. */
    private final List<MessageChannel<?>> channels = new ArrayList<>();

    private MessagePipeline(MessagePipeline<?, A> upstream, Stage<A, B> stage) {
        this.upstream = upstream;
        this.stage = stage;
    }

    /**
     * Create an empty source pipeline.
     *
     * <p>Equivalent to Erlang's "start of process chain" — the head of the pipeline topology.
     *
     * @param <T> the message type entering the pipeline
     * @return an identity-stage pipeline (pass-through)
     */
    public static <T> MessagePipeline<T, T> source() {
        return new MessagePipeline<>(null, new Stage.Transform<>(Function.identity()));
    }

    /**
     * Append a transformation stage.
     *
     * <p>Corresponds to an Erlang process that receives {@code A}, maps it to {@code C}, and sends
     * the result downstream.
     *
     * @param fn the mapping function
     * @param <C> the output type of this stage
     * @return a new pipeline with this stage appended
     */
    public <C> MessagePipeline<A, C> transform(Function<B, C> fn) {
        @SuppressWarnings("unchecked")
        MessagePipeline<B, C> next =
                new MessagePipeline<>((MessagePipeline<?, B>) this, new Stage.Transform<>(fn));
        return (MessagePipeline<A, C>) (Object) next;
    }

    /**
     * Append a filter stage that drops messages failing the predicate.
     *
     * <p>Corresponds to an Erlang selective-receive guard — only matching messages advance down
     * the pipeline.
     *
     * @param predicate the acceptance condition; messages failing are silently dropped
     * @return a new pipeline with this filter appended
     */
    public MessagePipeline<A, B> filter(Predicate<B> predicate) {
        @SuppressWarnings("unchecked")
        MessagePipeline<B, B> next =
                new MessagePipeline<>(
                        (MessagePipeline<?, B>) this,
                        (Stage<B, B>) new Stage.Filter<>(predicate));
        return (MessagePipeline<A, B>) (Object) next;
    }

    /**
     * Append a split stage that decomposes one {@code B} message into multiple {@code C} messages.
     *
     * <p>Equivalent to an Erlang scatter step: {@code [Next ! Part || Part <- split(Msg)]}.
     *
     * @param splitter the decomposition function
     * @param <C> the part type
     * @return a new pipeline with this split stage appended
     */
    public <C> MessagePipeline<A, C> splitInto(Function<B, List<C>> splitter) {
        @SuppressWarnings("unchecked")
        MessagePipeline<B, C> next =
                new MessagePipeline<>(
                        (MessagePipeline<?, B>) this, new Stage.SplitInto<>(splitter));
        return (MessagePipeline<A, C>) (Object) next;
    }

    /**
     * Terminate the pipeline by connecting it to a {@code sink} channel.
     *
     * <p>Constructs the channel chain (one {@link PointToPointChannel} per intermediate stage) and
     * returns a {@link MessageChannel}{@code <A>} that serves as the pipeline's entry point.
     * Producers send messages into the returned channel; they emerge at {@code sink} after all
     * stages have processed them.
     *
     * @param sink the terminal channel receiving processed messages
     * @return the pipeline entry-point channel
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public MessageChannel<A> sink(MessageChannel<B> sink) {
        // Walk the stage chain, building channels from tail to head.
        MessageChannel current = sink;

        // Collect all stages into a list (head first after reversal).
        var stages = new ArrayList<MessagePipeline<?, ?>>();
        MessagePipeline<?, ?> cursor = this;
        while (cursor != null) {
            stages.add(cursor);
            cursor = cursor.upstream;
        }
        // stages[0] = this (tail), stages[last] = source node

        // Build channel chain from tail to head (index 0..N-1, skipping source).
        for (int i = 0; i < stages.size() - 1; i++) {
            MessagePipeline<Object, Object> node = (MessagePipeline<Object, Object>) stages.get(i);
            MessageChannel<Object> downstream = (MessageChannel<Object>) current;

            MessageChannel<Object> stageChannel =
                    switch (node.stage) {
                        case Stage.Transform(var fn) ->
                                MessageTransformer.of(fn, downstream);
                        case Stage.Filter(var pred) ->
                                MessageFilter.of(pred, downstream);
                        case Stage.SplitInto(var splitter) ->
                                MessageSplitter.of(splitter, downstream);
                    };
            channels.add(stageChannel);
            current = stageChannel;
        }

        channels.add(sink);
        return (MessageChannel<A>) current;
    }

    /**
     * Stop all channels in the pipeline, waiting for each to drain.
     *
     * <p>Call this after the entry-point channel's {@link MessageChannel#stop()} to ensure the
     * full chain shuts down cleanly.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void stopAll() throws InterruptedException {
        for (MessageChannel<?> ch : channels) {
            ch.stop();
        }
    }
}
