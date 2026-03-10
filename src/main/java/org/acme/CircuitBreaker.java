package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit Breaker — fault tolerance pattern for preventing cascade failures.
 *
 * <p>Martin Fowler: "The basic idea behind the circuit breaker is very simple.
 * You wrap a protected function call in a circuit breaker object, which monitors
 * for failures. Once the failures reach a certain threshold, the circuit breaker
 * trips, and all further calls to the circuit breaker return with an error."
 *
 * <p>Joe Armstrong: "In Erlang, we let it crash. But we also need to protect
 * downstream services. A circuit breaker is like a supervisor for remote calls."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Three states</b> — CLOSED, OPEN, HALF_OPEN with automatic transitions</li>
 *   <li><b>Failure threshold</b> — Configurable number of failures before tripping</li>
 *   <li><b>Timeout handling</b> — Per-operation timeout with automatic failure recording</li>
 *   <li><b>Reset timeout</b> — Time to wait before attempting HALF_OPEN</li>
 *   <li><b>Result-based API</b> — Returns {@link Result} with typed errors</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * CircuitBreaker breaker = CircuitBreaker.builder("payment-service")
 *     .failureThreshold(5)
 *     .timeout(Duration.ofSeconds(10))
 *     .resetTimeout(Duration.ofSeconds(30))
 *     .build();
 *
 * Result<Payment, CircuitError> result = breaker.execute(() -> paymentClient.charge(request));
 * if (result.isSuccess()) {
 *     return result.orElseThrow();
 * } else {
 *     // Handle circuit open or operation failure
 * }
 * }</pre>
 *
 * @see Result
 * @see CircuitError
 */
public final class CircuitBreaker {
    /** Circuit breaker state machine states. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Circuit breaker configuration. */
    public record Config(String name, int failureThreshold, Duration timeout, Duration resetTimeout) {}

    private final Config config;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile Instant openedAt;

    private CircuitBreaker(Config config) { this.config = config; }

    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private int failureThreshold = 5;
        private Duration timeout = Duration.ofSeconds(10);
        private Duration resetTimeout = Duration.ofSeconds(30);
        private int halfOpenRequests = 1;

        public Builder(String name) { this.name = name; }
        public Builder failureThreshold(int threshold) { this.failureThreshold = threshold; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder resetTimeout(Duration timeout) { this.resetTimeout = timeout; return this; }
        public Builder halfOpenRequests(int requests) { this.halfOpenRequests = requests; return this; }
        public CircuitBreaker build() { return new CircuitBreaker(new Config(name, failureThreshold, timeout, resetTimeout)); }
    }

    public State state() { return state.get(); }
    public Config config() { return config; }
    public void reset() { state.set(State.CLOSED); failureCount.set(0); openedAt = null; }
    public void trip() { state.set(State.OPEN); openedAt = Instant.now(); }

    @SuppressWarnings("unchecked")
    public <T> Result<T, CircuitError> execute(Supplier<T> operation) {
        tryTransitionToHalfOpen();

        if (state.get() == State.OPEN) {
            return Result.err(new CircuitError.CircuitOpen(config.name(), openedAt != null ? openedAt.plus(config.resetTimeout()) : Instant.now()));
        }

        try {
            // Execute with timeout if configured
            T result;
            if (config.timeout().toMillis() > 0) {
                CompletableFuture<T> future = CompletableFuture.supplyAsync(operation);
                result = future.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } else {
                result = operation.get();
            }
            failureCount.set(0);
            if (state.get() == State.HALF_OPEN) state.compareAndSet(State.HALF_OPEN, State.CLOSED);
            return Result.ok(result);
        } catch (TimeoutException e) {
            int failures = failureCount.incrementAndGet();
            if (failures >= config.failureThreshold()) {
                state.set(State.OPEN);
                openedAt = Instant.now();
            }
            return Result.err(new CircuitError.Timeout(config.name(), config.timeout()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new CircuitError.Failure(config.name(), e));
        } catch (Exception e) {
            int failures = failureCount.incrementAndGet();
            if (failures >= config.failureThreshold()) {
                state.set(State.OPEN);
                openedAt = Instant.now();
            }
            return Result.err(new CircuitError.Failure(config.name(), e));
        }
    }

    private void tryTransitionToHalfOpen() {
        if (state.get() == State.OPEN && openedAt != null) {
            if (Instant.now().isAfter(openedAt.plus(config.resetTimeout()))) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
            }
        }
    }

    public sealed interface CircuitError permits CircuitError.CircuitOpen, CircuitError.Failure, CircuitError.Timeout {
        record CircuitOpen(String circuitName, Instant retryAfter) implements CircuitError {}
        record Failure(String circuitName, Throwable cause) implements CircuitError {}
        record Timeout(String circuitName, Duration timeout) implements CircuitError {}
    }

    // Registry
    private static final ConcurrentHashMap<String, CircuitBreaker> REGISTRY = new ConcurrentHashMap<>();
    public static void register(CircuitBreaker breaker) { REGISTRY.put(breaker.config.name(), breaker); }
    public static CircuitBreaker get(String name) { return REGISTRY.get(name); }
}
