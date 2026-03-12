package io.github.seanchatmangpt.jotp.reactive;

import java.util.function.Function;
import io.github.seanchatmangpt.jotp.Result;

/**
 * Type-safe message transformer: converts messages from type {@code A} to type {@code B} and
 * forwards the result to a downstream channel.
 *
 * <p>Enterprise Integration Pattern: <em>Message Translator</em> (EIP §8.3). Erlang analog: a
 * mapping process sitting between a producer and consumer, receiving messages of one type and
 * re-sending as another type — common in OTP protocol adapters and codec layers.
 *
 * <p>The transformer is itself a {@link MessageChannel}{@code <A>}: producers send {@code A}
 * messages in; downstream sees {@code B} messages out. Optional error handling routes translation
 * failures to a {@link DeadLetterChannel}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * record RawEvent(String json) {}
 * record DomainEvent(String type, String payload) {}
 *
 * var downstream = new PointToPointChannel<DomainEvent>(event -> handle(event));
 * var dead = new DeadLetterChannel<RawEvent>();
 *
 * var transformer = MessageTransformer.<RawEvent, DomainEvent>of(
 *     raw -> jsonParser.parse(raw.json()),
 *     downstream,
 *     dead);
 *
 * transformer.send(new RawEvent("{\"type\":\"OrderPlaced\",\"payload\":\"...\"}")); // → downstream
 * transformer.send(new RawEvent("not-valid-json"));                                 // → dead
 * }</pre>
 *
 * @param <A> input message type
 * @param <B> output message type
 */
public final class MessageTransformer<A, B> implements MessageChannel<A> {

    private final Function<A, B> transform;
    private final MessageChannel<B> downstream;
    private final MessageChannel<A> errorChannel;

    private MessageTransformer(
            Function<A, B> transform,
            MessageChannel<B> downstream,
            MessageChannel<A> errorChannel) {
        this.transform = transform;
        this.downstream = downstream;
        this.errorChannel = errorChannel;
    }

    /**
     * Create a transformer that silently drops messages when translation fails.
     *
     * @param transform the mapping function from {@code A} to {@code B}
     * @param downstream the channel for successfully transformed messages
     * @param <A> input type
     * @param <B> output type
     * @return the transformer channel
     */
    public static <A, B> MessageTransformer<A, B> of(
            Function<A, B> transform, MessageChannel<B> downstream) {
        return new MessageTransformer<>(transform, downstream, null);
    }

    /**
     * Create a transformer that routes failed transformations to an error channel.
     *
     * @param transform the mapping function; exceptions are caught and routed to {@code errorChannel}
     * @param downstream channel for successfully transformed messages
     * @param errorChannel channel for messages whose transformation threw an exception
     * @param <A> input type
     * @param <B> output type
     * @return the transformer channel
     */
    public static <A, B> MessageTransformer<A, B> of(
            Function<A, B> transform,
            MessageChannel<B> downstream,
            MessageChannel<A> errorChannel) {
        return new MessageTransformer<>(transform, downstream, errorChannel);
    }

    /**
     * Apply the transformation function to {@code message}; forward to downstream on success.
     *
     * <p>Uses {@link Result#of} to wrap the transformation — mirroring the OTP railway pattern
     * where exceptions become explicit error values rather than blowing up the call stack.
     *
     * @param message the input message to transform and forward
     */
    @Override
    public void send(A message) {
        Result<B, Exception> result = Result.of(() -> transform.apply(message));
        switch (result) {
            case Result.Ok<B, Exception>(var value) -> downstream.send(value);
            case Result.Success<B, Exception>(var value) -> downstream.send(value);
            case Result.Err<B, Exception>(var ignored) -> {
                if (errorChannel != null) errorChannel.send(message);
            }
            case Result.Failure<B, Exception>(var ignored) -> {
                if (errorChannel != null) errorChannel.send(message);
            }
        }
    }

    /**
     * Stop the downstream channel (and the error channel if present).
     *
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public void stop() throws InterruptedException {
        downstream.stop();
        if (errorChannel != null) {
            errorChannel.stop();
        }
    }
}
