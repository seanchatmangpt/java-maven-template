package org.acme.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Content-based router: inspects each message and forwards it to the first matching channel.
 *
 * <p>Enterprise Integration Pattern: <em>Content-Based Router</em> (EIP §8.1). Erlang analog:
 * Erlang selective receive ({@code receive Msg when Guard -> ...}) and {@code gen_server:handle_call}
 * pattern matching — the router dispatches based on message content rather than origin.
 *
 * <p>Routes are evaluated in declaration order; the first matching predicate wins. An optional
 * <em>otherwise</em> channel (typically a {@link DeadLetterChannel}) captures unmatched messages.
 * If no otherwise channel is set and no route matches, the message is silently dropped.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * sealed interface Order permits ExpressOrder, StandardOrder {}
 *
 * var router = MessageRouter.<Order>builder()
 *     .route(o -> o instanceof ExpressOrder, expressFulfilment)
 *     .route(o -> o instanceof StandardOrder, standardFulfilment)
 *     .otherwise(deadLetterChannel)
 *     .build();
 *
 * router.send(new ExpressOrder("pkg-1"));  // → expressFulfilment
 * }</pre>
 *
 * @param <T> message type
 */
public final class MessageRouter<T> implements MessageChannel<T> {

    /** A routing rule: predicate + destination channel pair. */
    private record Route<T>(Predicate<T> predicate, MessageChannel<T> channel) {}

    private final List<Route<T>> routes;
    private final MessageChannel<T> otherwise;

    private MessageRouter(List<Route<T>> routes, MessageChannel<T> otherwise) {
        this.routes = List.copyOf(routes);
        this.otherwise = otherwise;
    }

    /**
     * Evaluate routes in order; send to the first matching channel.
     *
     * <p>Mirrors Erlang pattern matching in {@code receive}: clauses are tried top-down; the first
     * matching clause executes. Unmatched messages go to the otherwise channel or are dropped.
     *
     * @param message the message to route
     */
    @Override
    public void send(T message) {
        for (Route<T> route : routes) {
            if (route.predicate().test(message)) {
                route.channel().send(message);
                return;
            }
        }
        if (otherwise != null) {
            otherwise.send(message);
        }
    }

    /**
     * Stop all routed channels and the otherwise channel (if present).
     *
     * @throws InterruptedException if interrupted while waiting for channels to drain
     */
    @Override
    public void stop() throws InterruptedException {
        for (Route<T> route : routes) {
            route.channel().stop();
        }
        if (otherwise != null) {
            otherwise.stop();
        }
    }

    /** Returns a new builder for constructing a {@code MessageRouter}. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent builder for {@link MessageRouter}. */
    public static final class Builder<T> {

        private final List<Route<T>> routes = new ArrayList<>();
        private MessageChannel<T> otherwise;

        /**
         * Add a routing rule: if {@code predicate} matches, forward to {@code channel}.
         *
         * <p>Rules are evaluated in the order they are added — exactly as Erlang pattern clauses
         * are matched top-to-bottom.
         *
         * @param predicate the matching condition
         * @param channel the destination channel
         * @return this builder (fluent)
         */
        public Builder<T> route(Predicate<T> predicate, MessageChannel<T> channel) {
            routes.add(new Route<>(predicate, channel));
            return this;
        }

        /**
         * Set the fallback channel for messages that match no route.
         *
         * <p>Typically a {@link DeadLetterChannel}. Corresponds to OTP's "catch-all" pattern
         * clause: {@code receive _ -> handle_unknown() end}.
         *
         * @param channel the fallback channel
         * @return this builder (fluent)
         */
        public Builder<T> otherwise(MessageChannel<T> channel) {
            this.otherwise = channel;
            return this;
        }

        /** Build the immutable {@link MessageRouter}. */
        public MessageRouter<T> build() {
            return new MessageRouter<>(routes, otherwise);
        }
    }
}
