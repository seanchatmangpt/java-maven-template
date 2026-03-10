package org.acme.reactive;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Content Enricher — EIP pattern that augments a message with additional data retrieved
 * from an external resource (database, service, cache) before forwarding it downstream.
 *
 * <p>Separates the concern of data acquisition from the core message flow. The enrichment
 * function receives the original message and an external data source, returning an enriched
 * message. If enrichment fails, the original message is forwarded to a dead-letter channel.
 *
 * <p>Erlang/OTP analogy: a pure function in a {@code gen_server} handler that performs
 * a side-effect-free lookup, then returns enriched state — keeping the message processing
 * pipeline deterministic and testable.
 *
 * @param <T> input message type
 * @param <R> external resource / data source type
 * @param <U> enriched output message type
 */
public final class ContentEnricher<T, R, U> implements MessageChannel<T> {

    private static final class NoOpChannel<T> implements MessageChannel<T> {
        @Override public void send(T message) {}
        @Override public void stop() {}
    }

    private final R resource;
    private final BiFunction<T, R, U> enricher;
    private final MessageChannel<U> downstream;
    private final MessageChannel<T> errorChannel;

    private ContentEnricher(
            R resource,
            BiFunction<T, R, U> enricher,
            MessageChannel<U> downstream,
            MessageChannel<T> errorChannel) {
        this.resource = resource;
        this.enricher = enricher;
        this.downstream = downstream;
        this.errorChannel = errorChannel;
    }

    /**
     * Creates a ContentEnricher that uses a simple mapping function (no external resource).
     * Failed enrichment is silently dropped.
     */
    public static <T, U> ContentEnricher<T, Void, U> of(
            Function<T, U> enricher, MessageChannel<U> downstream) {
        return new ContentEnricher<>(null, (msg, ignored) -> enricher.apply(msg), downstream, new NoOpChannel<>());
    }

    /**
     * Creates a ContentEnricher with an external resource (e.g., a lookup table, cache,
     * or database facade).
     *
     * @param resource    the external data source provided to the enrichment function
     * @param enricher    {@code (message, resource) → enrichedMessage}
     * @param downstream  channel that receives enriched messages
     */
    public static <T, R, U> ContentEnricher<T, R, U> of(
            R resource, BiFunction<T, R, U> enricher, MessageChannel<U> downstream) {
        return new ContentEnricher<>(resource, enricher, downstream, new NoOpChannel<>());
    }

    /**
     * Creates a ContentEnricher with error routing — enrichment failures send the
     * original message to {@code errorChannel} rather than dropping it.
     *
     * @param resource      external data source
     * @param enricher      {@code (message, resource) → enrichedMessage}
     * @param downstream    channel that receives enriched messages
     * @param errorChannel  channel that receives unenrichable messages on exception
     */
    public static <T, R, U> ContentEnricher<T, R, U> of(
            R resource,
            BiFunction<T, R, U> enricher,
            MessageChannel<U> downstream,
            MessageChannel<T> errorChannel) {
        return new ContentEnricher<>(resource, enricher, downstream, errorChannel);
    }

    /**
     * Enriches {@code message} using the configured enricher and forwards the result
     * downstream. On any exception, the original message is sent to the error channel.
     */
    @Override
    public void send(T message) {
        try {
            U enriched = enricher.apply(message, resource);
            downstream.send(enriched);
        } catch (Exception e) {
            errorChannel.send(message);
        }
    }

    /** No managed threads — no-op stop. Callers stop downstream channels independently. */
    @Override
    public void stop() throws InterruptedException {
        // ContentEnricher is stateless; downstream channels manage their own lifecycle
    }
}
