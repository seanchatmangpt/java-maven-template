package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Content-Based Router — routes messages to different destinations based on message
 * content/fields.
 *
 * <p>Vernon: "A Content-Based Router examines the content of the message and routes to
 * different destinations based on data contained in the message."
 *
 * <p>Joe Armstrong influence: "Processes share nothing, communicate only by message passing."
 * The content-based routing decision is pure functional logic applied before message forwarding.
 *
 * <p><strong>Routing Pattern:</strong>
 * <ol>
 *   <li>Caller sends message to router
 *   <li>Router applies predicates in order to the message
 *   <li>First matching predicate determines the destination
 *   <li>Message is forwarded to the corresponding destination process
 *   <li>If no predicate matches, message is silently dropped
 * </ol>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * var router = new ContentBasedRouter<String>();
 * router.addRoute(msg -> msg.startsWith("URGENT:"), urgentHandler);
 * router.addRoute(msg -> msg.startsWith("LOW:"), lowPriorityHandler);
 * router.route("URGENT: Fix production bug");  // routes to urgentHandler
 * }</pre>
 *
 * @param <M> message type
 * @see DynamicRouter
 * @see ScatterGather
 */
public final class ContentBasedRouter<M> {

    /** Route entry: predicate + destination. */
    private static record Route<M>(Predicate<M> predicate, Destination<M> destination) {}

    /** Abstraction for a message destination. */
    public interface Destination<M> {
        void send(M message);
    }

    /** Router state: routes and optional default destination. */
    private record RouterState<M>(
            List<Route<M>> routes,
            Destination<M> defaultDestination) {}

    private final Proc<RouterState<M>, RoutingCommand<M>> routerProc;

    /** Sealed message hierarchy for the router. */
    sealed interface RoutingCommand<M>
            permits RoutingCommand.RouteMsg, RoutingCommand.AddRoute, RoutingCommand.SetDefault {

        record RouteMsg<M>(M message) implements RoutingCommand<M> {}

        record AddRoute<M>(Predicate<M> predicate, Destination<M> destination)
                implements RoutingCommand<M> {}

        record SetDefault<M>(Destination<M> destination) implements RoutingCommand<M> {}
    }

    /** Create a content-based router with no initial routes. */
    public ContentBasedRouter() {
        RouterState<M> initialState = new RouterState<>(new ArrayList<>(), null);
        this.routerProc =
                new Proc<>(
                        initialState,
                        (state, cmd) -> {
                            return switch (cmd) {
                                case RoutingCommand.RouteMsg<M> rm -> routeMessage(state, rm.message());
                                case RoutingCommand.AddRoute<M> ar ->
                                        addRoute(state, ar.predicate(), ar.destination());
                                case RoutingCommand.SetDefault<M> sd -> setDefault(state, sd.destination());
                            };
                        });
    }

    /**
     * Add a content-based routing rule.
     *
     * <p>Rules are evaluated in the order they were added. The first matching predicate
     * determines the destination.
     *
     * @param predicate condition to match against the message
     * @param destination process or handler to receive matching messages
     */
    public void addRoute(Predicate<M> predicate, Destination<M> destination) {
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        if (destination == null) throw new IllegalArgumentException("destination must not be null");
        routerProc.tell(new RoutingCommand.AddRoute<>(predicate, destination));
    }

    /**
     * Set a default destination for messages that don't match any route.
     *
     * @param destination default handler (can be null to drop unmatched messages)
     */
    public void setDefault(Destination<M> destination) {
        routerProc.tell(new RoutingCommand.SetDefault<>(destination));
    }

    /**
     * Route a message to the first matching destination.
     *
     * <p>If no route matches and a default destination is set, the message is sent there.
     * Otherwise, the message is dropped silently.
     *
     * @param message the message to route
     */
    public void route(M message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        routerProc.tell(new RoutingCommand.RouteMsg<>(message));
    }

    /**
     * Get the router's process reference (for monitoring or linking).
     */
    public Proc<RouterState<M>, RoutingCommand<M>> process() {
        return routerProc;
    }

    /**
     * Process a route command: find the first matching predicate and forward to destination.
     */
    private RouterState<M> routeMessage(RouterState<M> state, M message) {
        // First matching predicate wins
        for (Route<M> route : state.routes()) {
            if (route.predicate().test(message)) {
                route.destination().send(message);
                return state;
            }
        }

        // No match — try default destination
        if (state.defaultDestination() != null) {
            state.defaultDestination().send(message);
        }
        // Otherwise message is dropped silently

        return state;
    }

    /**
     * Process add route command: append a new route.
     */
    private RouterState<M> addRoute(
            RouterState<M> state, Predicate<M> predicate, Destination<M> destination) {
        var routes = new ArrayList<>(state.routes());
        routes.add(new Route<>(predicate, destination));
        return new RouterState<>(routes, state.defaultDestination());
    }

    /**
     * Process set default command: update default destination.
     */
    private RouterState<M> setDefault(RouterState<M> state, Destination<M> destination) {
        return new RouterState<>(state.routes(), destination);
    }
}
