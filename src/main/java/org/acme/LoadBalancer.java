package org.acme;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;

/**
 * Load Balancer — distributes load across services using various selection strategies.
 *
 * <p>Load balancing is a core pattern for achieving high availability and scalability.
 * Different strategies provide different trade-offs between fairness, locality, and performance.
 *
 * <p>Features:
 * <ul>
 *   <li><b>Round Robin</b> — Even distribution across all instances (default)</li>
 *   <li><b>Random</b> — Simple random selection, good for stateless services</li>
 *   <li><b>Least Loaded</b> — Select instance with smallest mailbox (adaptive)</li>
 *   <li><b>Weighted</b> — Distribute load based on assigned weights for heterogeneous clusters</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Round robin (default)
 * LoadBalancer lb = LoadBalancer.roundRobin();
 *
 * // Weighted load balancing for heterogeneous cluster
 * Map<String, Integer> weights = Map.of("api-v1", 3, "api-v2", 1);
 * LoadBalancer weighted = LoadBalancer.weighted(weights);
 *
 * // Use with ServiceRegistry
 * ServiceRegistry.ServiceInfo service = lb.select(healthyServices);
 * }</pre>
 *
 * @see ServiceRegistry
 * @see ApiGateway
 */
@FunctionalInterface
public interface LoadBalancer {
    ServiceRegistry.ServiceInfo select(List<ServiceRegistry.ServiceInfo> services);

    static LoadBalancer roundRobin() {
        return new RoundRobin();
    }

    static LoadBalancer random() {
        return new RandomLoadBalancer();
    }

    static LoadBalancer leastLoaded() {
        return new LeastLoaded();
    }

    static LoadBalancer weighted(Map<String, Integer> weights) {
        return new Weighted(weights);
    }

    final class RoundRobin implements LoadBalancer {
        private final AtomicInteger counter = new AtomicInteger(0);
        @Override public ServiceRegistry.ServiceInfo select(List<ServiceRegistry.ServiceInfo> services) {
            if (services.isEmpty()) throw new IllegalArgumentException("No services available");
            return services.get(Math.abs(counter.getAndIncrement() % services.size()));
        }
    }

    final class RandomLoadBalancer implements LoadBalancer {
        private final Random random = new Random();
        @Override public ServiceRegistry.ServiceInfo select(List<ServiceRegistry.ServiceInfo> services) {
            if (services.isEmpty()) throw new IllegalArgumentException("No services available");
            return services.get(random.nextInt(services.size()));
        }
    }

    final class LeastLoaded implements LoadBalancer {
        @Override public ServiceRegistry.ServiceInfo select(List<ServiceRegistry.ServiceInfo> services) {
            if (services.isEmpty()) throw new IllegalArgumentException("No services available");
            return services.stream()
                    .min(java.util.Comparator.comparingInt(s -> s.proc().mailboxSize()))
                    .orElse(services.get(0));
        }
    }

    final class Weighted implements LoadBalancer {
        private final Map<String, Integer> weights;
        private final AtomicInteger counter = new AtomicInteger(0);
        private List<ServiceRegistry.ServiceInfo> expanded;

        Weighted(Map<String, Integer> weights) { this.weights = weights; }

        @Override public ServiceRegistry.ServiceInfo select(List<ServiceRegistry.ServiceInfo> services) {
            if (services.isEmpty()) throw new IllegalArgumentException("No services available");
            if (expanded == null || expanded.size() != services.size()) {
                expanded = new java.util.ArrayList<>();
                for (var s : services) {
                    int w = weights.getOrDefault(s.name(), 1);
                    for (int i = 0; i < w; i++) expanded.add(s);
                }
            }
            return expanded.get(Math.abs(counter.getAndIncrement() % expanded.size()));
        }
    }
}
