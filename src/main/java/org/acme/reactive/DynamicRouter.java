package org.acme.reactive;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Dynamic Router — EIP pattern that routes messages based on a routing table that can
 * be updated at runtime without stopping the system.
 *
 * <p>Unlike {@link MessageRouter} (static routes at build time), the DynamicRouter allows
 * adding and removing routes while messages are flowing. This enables live reconfiguration,
 * A/B routing, and feature flags in message-driven systems.
 *
 * <p>Erlang/OTP analogy: a {@code gen_server} whose state includes a live routing table
 * updated via normal messages (hot code reload equivalent for routing logic).
 *
 * <p>Route evaluation uses first-match semantics. Routes are stored in a
 * {@link CopyOnWriteArrayList} for lock-free reads under concurrent message flow.
 *
 * @param <T> message type
 */
public final class DynamicRouter<T> implements MessageChannel<T> {

    private record Route<T>(Predicate<T> predicate, MessageChannel<T> channel) {}

    private final CopyOnWriteArrayList<Route<T>> routes = new CopyOnWriteArrayList<>();
    private volatile MessageChannel<T> defaultChannel;

    private static final class NoOpChannel<T> implements MessageChannel<T> {
        @Override public void send(T message) {}
        @Override public void stop() {}
    }

    /** Creates a DynamicRouter with no routes (all unmatched messages are silently dropped). */
    public DynamicRouter() {
        this.defaultChannel = new NoOpChannel<>();
    }

    /** Creates a DynamicRouter with the given fallback channel for unmatched messages. */
    public DynamicRouter(MessageChannel<T> defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    /**
     * Adds a route: messages matching {@code predicate} are forwarded to {@code channel}.
     * Route is appended to the end of the routing table (lowest priority).
     * Thread-safe — can be called while messages are flowing.
     */
    public void addRoute(Predicate<T> predicate, MessageChannel<T> channel) {
        routes.add(new Route<>(predicate, channel));
    }

    /**
     * Prepends a route, giving it highest priority (first-match semantics).
     * Thread-safe — can be called while messages are flowing.
     */
    public void prependRoute(Predicate<T> predicate, MessageChannel<T> channel) {
        routes.add(0, new Route<>(predicate, channel));
    }

    /**
     * Removes all routes targeting the given channel.
     * Thread-safe — can be called while messages are flowing.
     */
    public void removeRoutesTo(MessageChannel<T> channel) {
        routes.removeIf(r -> r.channel() == channel);
    }

    /**
     * Atomically replaces the default (fallback) channel.
     * Thread-safe — next message dispatch picks up the new default.
     */
    public void setDefault(MessageChannel<T> defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    /** Returns the current number of active routes (snapshot; may change immediately). */
    public int routeCount() {
        return routes.size();
    }

    /**
     * Dispatches {@code message} to the first matching route, or the default channel
     * if no route matches.
     *
     * <p>Reads the route snapshot atomically (CopyOnWriteArrayList iteration is consistent).
     */
    @Override
    public void send(T message) {
        List<Route<T>> snapshot = routes; // stable snapshot via COWAL
        for (Route<T> route : snapshot) {
            if (route.predicate().test(message)) {
                route.channel().send(message);
                return;
            }
        }
        defaultChannel.send(message);
    }

    /** No managed threads — no-op stop. Callers are responsible for stopping downstream channels. */
    @Override
    public void stop() throws InterruptedException {
        // DynamicRouter is stateless; downstream channels manage their own lifecycle
    }
}
