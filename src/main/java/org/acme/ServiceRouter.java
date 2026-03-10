package org.acme;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Content-based and predicate-based service router — EIP Content-Based Router for services.
 *
 * <p>Enterprise Integration Pattern: "Content-Based Router routes messages to different
 * services based on message content, without the sender needing to know which service
 * will handle the message."
 *
 * <p>This router enables:
 * <ul>
 *   <li><b>Predicate routing</b> — Route based on message predicates</li>
 *   <li><b>Content extraction</b> — Extract routing key from message</li>
 *   <li><b>Fallback routing</b> — Default route when no predicate matches</li>
 *   <li><b>Round-robin load balancing</b> — Distribute across matching services</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * ServiceRouter<TelemetryMsg> router = ServiceRouter.<TelemetryMsg>builder()
 *     .route("high-priority", msg -> msg.priority() > 100, "priority-processor")
 *     .route("standard", msg -> true, "standard-processor")
 *     .fallback("fallback-processor")
 *     .build();
 *
 * // Route a message
 * Optional<ProcRef<State, TelemetryMsg>> target = router.route(message);
 * if (target.isPresent()) {
 *     target.get().tell(message);
 * }
 * }</pre>
 *
 * @see ServiceRegistry
 * @see LoadBalancer
 */
public final class ServiceRouter<M> {

    /** A routing rule with predicate and target service name. */
    public record Route<M>(
            String name,
            Predicate<M> predicate,
            String serviceName,
            int priority) {}

    // ── Router state ────────────────────────────────────────────────────────────

    private final List<Route<M>> routes;
    private final String fallbackService;
    private final LoadBalancer loadBalancer;
    private final ConcurrentHashMap<String, AtomicCounter> counters = new ConcurrentHashMap<>();

    private ServiceRouter(List<Route<M>> routes, String fallbackService, LoadBalancer loadBalancer) {
        // Sort by priority (higher first)
        this.routes = routes.stream()
                .sorted(Comparator.comparingInt((Route<M> r) -> r.priority()).reversed())
                .toList();
        this.fallbackService = fallbackService;
        this.loadBalancer = loadBalancer != null ? loadBalancer : LoadBalancer.roundRobin();
    }

    // ── Routing API ─────────────────────────────────────────────────────────────

    /**
     * Route a message to the first matching service.
     *
     * @param message the message to route
     * @return the service to handle the message, or empty if no route matches
     */
    public Optional<ProcRef<?, M>> route(M message) {
        // Find first matching route
        for (Route<M> route : routes) {
            if (route.predicate().test(message)) {
                return findService(route.serviceName());
            }
        }

        // Fallback
        if (fallbackService != null) {
            return findService(fallbackService);
        }

        return Optional.empty();
    }

    /**
     * Route a message and send it to the matching service.
     *
     * @param message the message to route and send
     * @return true if routed successfully, false otherwise
     */
    public boolean routeAndSend(M message) {
        Optional<ProcRef<?, M>> target = route(message);
        target.ifPresent(ref -> ref.tell(message));
        return target.isPresent();
    }

    /**
     * Find all services that match a message (for multicast routing).
     */
    public List<String> matchingRoutes(M message) {
        List<String> matches = new ArrayList<>();
        for (Route<M> route : routes) {
            if (route.predicate().test(message)) {
                matches.add(route.serviceName());
            }
        }
        if (matches.isEmpty() && fallbackService != null) {
            matches.add(fallbackService);
        }
        return matches;
    }

    /**
     * Get all configured routes.
     */
    public List<Route<M>> routes() {
        return routes;
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<ProcRef<?, M>> findService(String serviceName) {
        // First check ServiceRegistry for healthy instances
        List<ServiceRegistry.ServiceInfo> healthy = ServiceRegistry.findHealthy(serviceName);
        if (!healthy.isEmpty()) {
            // Use load balancer to pick one
            ServiceRegistry.ServiceInfo selected = loadBalancer.select(healthy);
            return Optional.of(new ProcRef(selected.proc()));
        }

        // Fall back to direct ProcessRegistry lookup
        Proc<?, M> proc = (Proc<?, M>) ProcessRegistry.<Object, M>whereis(serviceName).orElse(null);
        return Optional.ofNullable(proc).map(ProcRef::new);
    }

    // ── Builder ─────────────────────────────────────────────────────────────────

    /**
     * Create a new router builder.
     */
    public static <M> Builder<M> builder() {
        return new Builder<>();
    }

    /** Router builder with fluent API. */
    public static final class Builder<M> {
        private final List<Route<M>> routes = new ArrayList<>();
        private String fallbackService;
        private LoadBalancer loadBalancer;

        /**
         * Add a routing rule.
         *
         * @param name route name for identification
         * @param predicate condition for this route
         * @param serviceName target service name
         */
        public Builder<M> route(String name, Predicate<M> predicate, String serviceName) {
            routes.add(new Route<>(name, predicate, serviceName, 0));
            return this;
        }

        /**
         * Add a routing rule with priority.
         *
         * @param name route name for identification
         * @param predicate condition for this route
         * @param serviceName target service name
         * @param priority higher priority routes are checked first
         */
        public Builder<M> route(String name, Predicate<M> predicate, String serviceName, int priority) {
            routes.add(new Route<>(name, predicate, serviceName, priority));
            return this;
        }

        /**
         * Add a routing rule with content extractor.
         *
         * @param name route name
         * @param extractor function to extract routing key from message
         * @param key the key to match
         * @param serviceName target service name
         */
        public <K> Builder<M> routeByKey(String name, Function<M, K> extractor, K key, String serviceName) {
            return route(name, msg -> Objects.equals(extractor.apply(msg), key), serviceName);
        }

        /**
         * Set fallback service for unmatched messages.
         */
        public Builder<M> fallback(String serviceName) {
            this.fallbackService = serviceName;
            return this;
        }

        /**
         * Set load balancer for multi-instance services.
         */
        public Builder<M> loadBalancer(LoadBalancer loadBalancer) {
            this.loadBalancer = loadBalancer;
            return this;
        }

        /**
         * Build the router.
         */
        public ServiceRouter<M> build() {
            return new ServiceRouter<>(routes, fallbackService, loadBalancer);
        }
    }

    // ── Simple atomic counter for round-robin ───────────────────────────────────

    private static final class AtomicCounter {
        private int value = 0;

        synchronized int incrementAndGet(int modulo) {
            value = (value + 1) % modulo;
            return value;
        }
    }
}
