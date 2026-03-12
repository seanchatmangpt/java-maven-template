package io.github.seanchatmangpt.jotp.dogfood.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Content-Based Router pattern.
 *
 * <p>Generated from {@code templates/java/messaging/content-based-router.tera}.
 *
 * <p>Routes messages to different handlers based on message content. This is a fundamental
 * enterprise integration pattern that decouples message producers from specific consumers.
 *
 * <p><strong>Pattern contracts validated:</strong>
 *
 * <ul>
 *   <li>Route by predicates evaluated in order
 *   <li>Fallback routing for unmatched messages
 *   <li>Round-robin load balancing across matching routes
 * </ul>
 *
 * @param <T> message type
 */
public final class RouterPatterns<T> {

    private final List<Route<T>> routes = new ArrayList<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    private record Route<T>(Predicate<T> condition, java.util.function.Consumer<T> handler) {}

    /**
     * Adds a route for messages matching the predicate.
     *
     * @param condition the routing condition
     * @param handler the handler for matching messages
     * @return this router for chaining
     */
    public RouterPatterns<T> route(Predicate<T> condition, java.util.function.Consumer<T> handler) {
        routes.add(new Route<>(condition, handler));
        return this;
    }

    /**
     * Adds a fallback route for messages that don't match any other route.
     *
     * @param handler the fallback handler
     * @return this router for chaining
     */
    public RouterPatterns<T> fallback(java.util.function.Consumer<T> handler) {
        routes.add(new Route<>(msg -> true, handler));
        return this;
    }

    /**
     * Routes a message to the first matching handler.
     *
     * @param message the message to route
     * @return true if the message was routed, false if no handler matched
     */
    public boolean routeMessage(T message) {
        for (var route : routes) {
            if (route.condition().test(message)) {
                route.handler().accept(message);
                return true;
            }
        }
        return false;
    }

    /**
     * Routes a message using round-robin across all matching handlers.
     *
     * @param message the message to route
     * @return true if the message was routed
     */
    public boolean routeRoundRobin(T message) {
        var matchingRoutes = routes.stream()
                .filter(r -> r.condition().test(message))
                .toList();

        if (matchingRoutes.isEmpty()) {
            return false;
        }

        var idx = Math.abs(roundRobinCounter.getAndIncrement() % matchingRoutes.size());
        matchingRoutes.get(idx).handler().accept(message);
        return true;
    }

    /**
     * Returns all handlers that would match the given message.
     *
     * @param message the message to test
     * @return list of matching handlers
     */
    public List<java.util.function.Consumer<T>> findMatchingHandlers(T message) {
        return routes.stream()
                .filter(r -> r.condition().test(message))
                .map(Route::handler)
                .toList();
    }

    /**
     * Creates a new router.
     *
     * @param <T> message type
     * @return a new router
     */
    public static <T> RouterPatterns<T> create() {
        return new RouterPatterns<>();
    }
}
