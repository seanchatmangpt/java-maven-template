package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;

/**
 * Service registry with health tracking and discovery — extends ProcessRegistry with
 * service mesh capabilities.
 *
 * <p>Joe Armstrong: "In distributed Erlang, nodes discover each other and processes
 * can be addressed by name across nodes. The registry is the heart of location transparency."
 *
 * <p>This registry extends {@link ProcessRegistry} with:
 * <ul>
 *   <li><b>Service metadata</b> — tags, version, health status</li>
 *   <li><b>Health tracking</b> — last seen timestamp, failure count</li>
 *   <li><b>Discovery</b> — find services by tags or name pattern</li>
 *   <li><b>Lifecycle hooks</b> — callbacks on registration/unregistration</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Register a service with metadata
 * ServiceRegistry.register("telemetry-processor", proc,
 *     ServiceMetadata.builder()
 *         .version("1.0.0")
 *         .tag("processing")
 *         .tag("telemetry")
 *         .healthCheckInterval(Duration.ofSeconds(10))
 *         .build());
 *
 * // Discover services by tag
 * List<ServiceInfo> processors = ServiceRegistry.findByTag("processing");
 *
 * // Get healthy services only
 * List<ServiceInfo> healthy = ServiceRegistry.findHealthy("telemetry");
 * }</pre>
 *
 * @see ServiceRouter
 * @see LoadBalancer
 */
public final class ServiceRegistry {

    private ServiceRegistry() {}

    // ── Service metadata ───────────────────────────────────────────────────────

    /** Service metadata attached to registration. */
    public record ServiceMetadata(
            String version,
            Set<String> tags,
            Duration healthCheckInterval,
            Map<String, String> properties) {

        public static Builder builder() {
            return new Builder();
        }

        public static ServiceMetadata empty() {
            return new ServiceMetadata("0.0.0", Set.of(), Duration.ofSeconds(30), Map.of());
        }

        public static final class Builder {
            private String version = "0.0.0";
            private final Set<String> tags = new HashSet<>();
            private Duration healthCheckInterval = Duration.ofSeconds(30);
            private final Map<String, String> properties = new HashMap<>();

            public Builder version(String version) {
                this.version = version;
                return this;
            }

            public Builder tag(String tag) {
                this.tags.add(tag);
                return this;
            }

            public Builder tags(String... tags) {
                Collections.addAll(this.tags, tags);
                return this;
            }

            public Builder healthCheckInterval(Duration interval) {
                this.healthCheckInterval = interval;
                return this;
            }

            public Builder property(String key, String value) {
                this.properties.put(key, value);
                return this;
            }

            public ServiceMetadata build() {
                return new ServiceMetadata(
                        version,
                        Set.copyOf(tags),
                        healthCheckInterval,
                        Map.copyOf(properties));
            }
        }
    }

    /** Runtime service information including health status. */
    public record ServiceInfo(
            String name,
            Proc<?, ?> proc,
            ServiceMetadata metadata,
            Instant registeredAt,
            Instant lastSeen,
            long failureCount,
            ServiceStatus status) {

        /** Check if service is healthy (status is UP and recently seen). */
        public boolean isHealthy() {
            return status == ServiceStatus.UP &&
                    lastSeen.isAfter(Instant.now().minus(metadata.healthCheckInterval().multipliedBy(3)));
        }
    }

    /** Service health status. */
    public enum ServiceStatus {
        UP,
        DOWN,
        STARTING,
        STOPPING,
        UNKNOWN
    }

    // ── Registry storage ────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, ServiceInfo> REGISTRY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> TAG_INDEX = new ConcurrentHashMap<>();
    private static final List<ServiceLifecycleListener> LISTENERS = new ArrayList<>();

    /** Lifecycle listener for service registration events. */
    public interface ServiceLifecycleListener {
        default void onRegistered(ServiceInfo info) {}
        default void onUnregistered(ServiceInfo info) {}
        default void onHealthChanged(ServiceInfo info, ServiceStatus oldStatus) {}
    }

    // ── Registration API ────────────────────────────────────────────────────────

    /**
     * Register a service with metadata.
     *
     * <p>OTP: {@code application:set_env(App, Key, Value)}
     */
    public static void register(String name, Proc<?, ?> proc, ServiceMetadata metadata) {
        ServiceInfo info = new ServiceInfo(
                name, proc, metadata,
                Instant.now(), Instant.now(), 0, ServiceStatus.STARTING);

        if (REGISTRY.putIfAbsent(name, info) != null) {
            throw new IllegalStateException("Service already registered: " + name);
        }

        // Index by tags
        for (String tag : metadata.tags()) {
            TAG_INDEX.computeIfAbsent(tag, k -> new ConcurrentSkipListSet<>()).add(name);
        }

        // Auto-deregister on termination
        proc.addTerminationCallback(_ -> unregister(name));

        // Update status to UP after registration
        updateStatus(name, ServiceStatus.UP);

        // Notify listeners
        synchronized (LISTENERS) {
            for (ServiceLifecycleListener listener : LISTENERS) {
                listener.onRegistered(info);
            }
        }
    }

    /**
     * Register a service with empty metadata.
     */
    public static void register(String name, Proc<?, ?> proc) {
        register(name, proc, ServiceMetadata.empty());
    }

    /**
     * Unregister a service.
     *
     * <p>OTP: {@code unregister(Name)}
     */
    public static void unregister(String name) {
        ServiceInfo info = REGISTRY.remove(name);
        if (info == null) return;

        // Remove from tag index
        for (String tag : info.metadata().tags()) {
            Set<String> names = TAG_INDEX.get(tag);
            if (names != null) {
                names.remove(name);
            }
        }

        // Notify listeners
        synchronized (LISTENERS) {
            for (ServiceLifecycleListener listener : LISTENERS) {
                listener.onUnregistered(info);
            }
        }
    }

    // ── Lookup API ──────────────────────────────────────────────────────────────

    /**
     * Look up a service by name.
     *
     * <p>OTP: {@code whereis(Name)}
     */
    public static Optional<ServiceInfo> lookup(String name) {
        return Optional.ofNullable(REGISTRY.get(name));
    }

    /**
     * Get the process for a service name.
     */
    @SuppressWarnings("unchecked")
    public static <S, M> Optional<Proc<S, M>> getProcess(String name) {
        return lookup(name).map(info -> (Proc<S, M>) info.proc());
    }

    /**
     * Find all services with a specific tag.
     */
    public static List<ServiceInfo> findByTag(String tag) {
        Set<String> names = TAG_INDEX.get(tag);
        if (names == null || names.isEmpty()) return List.of();

        return names.stream()
                .map(REGISTRY::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Find all healthy services with a specific tag.
     */
    public static List<ServiceInfo> findHealthyByTag(String tag) {
        return findByTag(tag).stream()
                .filter(ServiceInfo::isHealthy)
                .toList();
    }

    /**
     * Find all healthy services matching a name prefix.
     */
    public static List<ServiceInfo> findHealthy(String namePrefix) {
        return REGISTRY.values().stream()
                .filter(info -> info.name().startsWith(namePrefix))
                .filter(ServiceInfo::isHealthy)
                .toList();
    }

    /**
     * Get all registered services.
     */
    public static Collection<ServiceInfo> allServices() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    /**
     * Get all service names.
     */
    public static Set<String> serviceNames() {
        return Set.copyOf(REGISTRY.keySet());
    }

    // ── Health tracking ─────────────────────────────────────────────────────────

    /**
     * Record a heartbeat for a service (updates lastSeen).
     */
    public static void heartbeat(String name) {
        REGISTRY.computeIfPresent(name, (k, info) -> new ServiceInfo(
                info.name(), info.proc(), info.metadata(),
                info.registeredAt(), Instant.now(), info.failureCount(), info.status()));
    }

    /**
     * Record a failure for a service (increments failure count).
     */
    public static void recordFailure(String name) {
        REGISTRY.computeIfPresent(name, (k, info) -> new ServiceInfo(
                info.name(), info.proc(), info.metadata(),
                info.registeredAt(), Instant.now(), info.failureCount() + 1, info.status()));
    }

    /**
     * Update service status.
     */
    public static void updateStatus(String name, ServiceStatus newStatus) {
        REGISTRY.computeIfPresent(name, (k, info) -> {
            ServiceStatus oldStatus = info.status();
            ServiceInfo newInfo = new ServiceInfo(
                    info.name(), info.proc(), info.metadata(),
                    info.registeredAt(), Instant.now(), info.failureCount(), newStatus);

            // Notify listeners of status change
            if (oldStatus != newStatus) {
                synchronized (LISTENERS) {
                    for (ServiceLifecycleListener listener : LISTENERS) {
                        listener.onHealthChanged(newInfo, oldStatus);
                    }
                }
            }
            return newInfo;
        });
    }

    // ── Listeners ───────────────────────────────────────────────────────────────

    /**
     * Add a lifecycle listener.
     */
    public static void addListener(ServiceLifecycleListener listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
    }

    /**
     * Remove a lifecycle listener.
     */
    public static void removeListener(ServiceLifecycleListener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    // ── Testing utilities ───────────────────────────────────────────────────────

    /**
     * Clear all registrations — for testing only.
     */
    public static void reset() {
        REGISTRY.clear();
        TAG_INDEX.clear();
        synchronized (LISTENERS) {
            LISTENERS.clear();
        }
    }
}
