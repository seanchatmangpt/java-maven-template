package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health checker — OTP-style health monitoring with configurable checks.
 *
 * <p>Joe Armstrong: "In Erlang, supervisors monitor processes and restart them
 * when they fail. Health checks are the proactive version — check before failure."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Liveness probes</b> — Is the service running?</li>
 *   <li><b>Readiness probes</b> — Is the service ready to accept traffic?</li>
 *   <li><b>Dependency checks</b> — Are required dependencies healthy?</li>
 *   <li><b>Custom checks</b> — Application-specific health logic</li>
 *   <li><b>Aggregated status</b> — Overall health from all checks</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * HealthChecker checker = HealthChecker.builder()
 *     .check("database", () -> database.isConnected(), Duration.ofSeconds(5))
 *     .check("kafka", () -> kafka.isHealthy(), Duration.ofSeconds(10))
 *     .check("memory", () -> Runtime.getRuntime().freeMemory() > MIN_MEMORY, Duration.ofSeconds(30))
 *     .build();
 *
 * // Get overall health
 * HealthChecker.Status status = checker.check();
 * if (status.isHealthy()) {
 *     // Service is healthy
 * }
 *
 * // Get individual check results
 * Map<String, HealthChecker.CheckResult> results = checker.checkAll();
 * }</pre>
 */
public final class HealthChecker implements Application.Infrastructure {

    /** Health status. */
    public enum HealthStatus {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }

    /** Result of a single health check. */
    public record CheckResult(
            String name,
            HealthStatus status,
            String message,
            Instant timestamp,
            Duration duration,
            Map<String, Object> details) {

        public boolean isHealthy() {
            return status == HealthStatus.HEALTHY || status == HealthStatus.DEGRADED;
        }
    }

    /** Overall health status. */
    public record Status(
            HealthStatus overall,
            Map<String, CheckResult> checks,
            Instant timestamp) {

        public boolean isHealthy() {
            return overall == HealthStatus.HEALTHY || overall == HealthStatus.DEGRADED;
        }
    }

    /** Individual health check interface. */
    @FunctionalInterface
    public interface Check {
        CheckResult check() throws Exception;
    }

    /** Simple check that returns boolean. */
    @FunctionalInterface
    public interface SimpleCheck {
        boolean check() throws Exception;
    }

    // ── Internal state ──────────────────────────────────────────────────────────

    private final String name;
    private final ConcurrentHashMap<String, Check> checks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Duration> timeouts = new ConcurrentHashMap<>();
    private final AtomicReference<Status> lastStatus = new AtomicReference<>();
    private final ScheduledExecutorService executor;
    private volatile boolean running = false;

    private HealthChecker(String name) {
        this.name = name;
        this.executor = Executors.newSingleThreadScheduledExecutor(r ->
                Thread.ofVirtual().name("health-check-" + name).unstarted(r));
    }

    // ── Factory methods ──────────────────────────────────────────────────────────

    public static HealthChecker create() {
        return new HealthChecker("default");
    }

    public static HealthChecker create(String name) {
        return new HealthChecker(name);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Health checker builder. */
    public static final class Builder {
        private String name = "default";
        private final Map<String, Check> checks = new LinkedHashMap<>();
        private final Map<String, Duration> timeouts = new LinkedHashMap<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Add a health check.
         */
        public Builder check(String name, Check check, Duration timeout) {
            this.checks.put(name, check);
            this.timeouts.put(name, timeout);
            return this;
        }

        /**
         * Add a simple boolean health check.
         */
        public Builder check(String name, SimpleCheck check, Duration timeout) {
            this.checks.put(name, () -> {
                Instant start = Instant.now();
                try {
                    boolean healthy = check.check();
                    Duration duration = Duration.between(start, Instant.now());
                    return new CheckResult(
                            name,
                            healthy ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY,
                            healthy ? "OK" : "Check failed",
                            Instant.now(),
                            duration,
                            Map.of());
                } catch (Exception e) {
                    Duration duration = Duration.between(start, Instant.now());
                    return new CheckResult(
                            name,
                            HealthStatus.UNHEALTHY,
                            e.getMessage(),
                            Instant.now(),
                            duration,
                            Map.of("error", e.getClass().getName()));
                }
            });
            this.timeouts.put(name, timeout);
            return this;
        }

        /**
         * Add a liveness check (is the service running?).
         */
        public Builder liveness(SimpleCheck check) {
            return check("liveness", check, Duration.ofSeconds(5));
        }

        /**
         * Add a readiness check (is the service ready for traffic?).
         */
        public Builder readiness(SimpleCheck check) {
            return check("readiness", check, Duration.ofSeconds(10));
        }

        public HealthChecker build() {
            HealthChecker checker = new HealthChecker(name);
            checker.checks.putAll(checks);
            checker.timeouts.putAll(timeouts);
            return checker;
        }
    }

    // ── Check registration ───────────────────────────────────────────────────────

    /**
     * Register a health check.
     */
    public void registerCheck(String name, Check check, Duration timeout) {
        checks.put(name, check);
        timeouts.put(name, timeout);
    }

    /**
     * Register a simple health check.
     */
    public void registerCheck(String name, SimpleCheck check, Duration timeout) {
        registerCheck(name, () -> {
            Instant start = Instant.now();
            boolean healthy = check.check();
            Duration duration = Duration.between(start, Instant.now());
            return new CheckResult(
                    name,
                    healthy ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY,
                    healthy ? "OK" : "Check failed",
                    Instant.now(),
                    duration,
                    Map.of());
        }, timeout);
    }

    /**
     * Remove a health check.
     */
    public void unregisterCheck(String name) {
        checks.remove(name);
        timeouts.remove(name);
    }

    // ── Health check execution ───────────────────────────────────────────────────

    /**
     * Run all health checks and return aggregated status.
     */
    public Status checkStatus() {
        Map<String, CheckResult> results = checkAll();

        HealthStatus overall = determineOverallStatus(results);
        Status status = new Status(overall, results, Instant.now());
        lastStatus.set(status);

        return status;
    }

    /**
     * Run a single health check by name.
     */
    public Optional<CheckResult> checkOne(String name) {
        Check check = checks.get(name);
        if (check == null) return Optional.empty();

        Duration timeout = timeouts.getOrDefault(name, Duration.ofSeconds(5));
        Instant start = Instant.now();

        try {
            CompletableFuture<CheckResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return check.check();
                } catch (Exception e) {
                    return new CheckResult(
                            name,
                            HealthStatus.UNHEALTHY,
                            e.getMessage(),
                            Instant.now(),
                            Duration.between(start, Instant.now()),
                            Map.of("error", e.getClass().getName()));
                }
            });

            CheckResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.of(result);
        } catch (TimeoutException e) {
            return Optional.of(new CheckResult(
                    name,
                    HealthStatus.UNHEALTHY,
                    "Check timed out after " + timeout,
                    Instant.now(),
                    timeout,
                    Map.of("timeout", true)));
        } catch (Exception e) {
            return Optional.of(new CheckResult(
                    name,
                    HealthStatus.UNHEALTHY,
                    e.getMessage(),
                    Instant.now(),
                    Duration.between(start, Instant.now()),
                    Map.of("error", e.getClass().getName())));
        }
    }

    /**
     * Run all health checks.
     */
    public Map<String, CheckResult> checkAll() {
        Map<String, CheckResult> results = new LinkedHashMap<>();

        for (String name : checks.keySet()) {
            checkOne(name).ifPresent(r -> results.put(name, r));
        }

        return results;
    }

    /**
     * Get the last known status.
     */
    public Optional<Status> lastStatus() {
        return Optional.ofNullable(lastStatus.get());
    }

    private HealthStatus determineOverallStatus(Map<String, CheckResult> results) {
        if (results.isEmpty()) return HealthStatus.UNKNOWN;

        boolean allHealthy = true;
        boolean anyUnhealthy = false;

        for (CheckResult result : results.values()) {
            if (result.status() == HealthStatus.UNHEALTHY) {
                anyUnhealthy = true;
            }
            if (result.status() != HealthStatus.HEALTHY) {
                allHealthy = false;
            }
        }

        if (allHealthy) return HealthStatus.HEALTHY;
        if (anyUnhealthy) return HealthStatus.UNHEALTHY;
        return HealthStatus.DEGRADED;
    }

    // ── Health check interface (not implementing Application.HealthCheck) ───────────────────────────────────

    public String name() {
        return name;
    }

    public boolean check() {
        Map<String, CheckResult> results = checkAll();
        return determineOverallStatus(results) != HealthStatus.UNHEALTHY;
    }

    public Duration interval() {
        return Duration.ofSeconds(30);
    }

    // ── Infrastructure lifecycle ─────────────────────────────────────────────────

    @Override
    public void onStop(Application app) {
        executor.shutdownNow();
    }
}
