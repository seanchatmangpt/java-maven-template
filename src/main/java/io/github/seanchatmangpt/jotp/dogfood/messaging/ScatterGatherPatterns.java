package io.github.seanchatmangpt.jotp.dogfood.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Scatter-Gather pattern.
 *
 * <p>Generated from {@code templates/java/messaging/scatter-gather.tera}.
 *
 * <p>Implements the scatter-gather pattern where a request is broadcast to multiple handlers
 * (scatter), and responses are collected and aggregated (gather). This is useful for
 * parallel processing and aggregating results from multiple sources.
 *
 * <p><strong>Pattern contracts validated:</strong>
 *
 * <ul>
 *   <li>Fan-out to multiple handlers concurrently
 *   <li>Aggregate responses from all handlers
 *   <li>Timeout handling for slow responses
 * </ul>
 *
 * @param <T> request type
 * @param <R> response type
 */
public final class ScatterGatherPatterns<T, R> {

    private final List<Function<T, R>> handlers = new CopyOnWriteArrayList<>();
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * Adds a handler to the scatter-gather pool.
     *
     * @param handler the handler function
     * @return this instance for chaining
     */
    public ScatterGatherPatterns<T, R> addHandler(Function<T, R> handler) {
        handlers.add(handler);
        return this;
    }

    /**
     * Sets the timeout for gathering responses.
     *
     * @param timeout the timeout duration
     * @return this instance for chaining
     */
    public ScatterGatherPatterns<T, R> withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Scatter the request to all handlers and gather responses.
     *
     * <p>Returns a ScatterResult containing successful responses and any errors.
     *
     * @param request the request to scatter
     * @return the gathered results
     */
    public ScatterResult<R> scatterAndGather(T request) {
        var results = new ArrayList<R>();
        var errors = new ArrayList<Throwable>();

        if (handlers.isEmpty()) {
            return new ScatterResult<>(results, errors);
        }

        var futures = new ArrayList<CompletableFuture<R>>();

        for (var handler : handlers) {
            var future = CompletableFuture.supplyAsync(
                    () -> handler.apply(request),
                    Executors.newVirtualThreadPerTaskExecutor());
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            for (var future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        } catch (TimeoutException e) {
            errors.add(e);
            // Collect any completed results
            for (var future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    try {
                        results.add(future.get());
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.add(e);
        } catch (ExecutionException e) {
            errors.add(e);
        }

        return new ScatterResult<>(results, errors);
    }

    /**
     * Scatter the request and gather only successful responses.
     *
     * @param request the request to scatter
     * @return list of successful responses
     */
    public List<R> scatterAndGatherSuccessful(T request) {
        return scatterAndGather(request).results();
    }

    /**
     * Returns the number of registered handlers.
     *
     * @return handler count
     */
    public int handlerCount() {
        return handlers.size();
    }

    /**
     * Clears all handlers.
     */
    public void clear() {
        handlers.clear();
    }

    /**
     * Result of a scatter-gather operation.
     *
     * @param results successful responses
     * @param errors errors from failed handlers
     * @param <R> response type
     */
    public record ScatterResult<R>(List<R> results, List<Throwable> errors) {
        /**
         * Returns true if all handlers succeeded.
         */
        public boolean allSucceeded() {
            return errors.isEmpty();
        }

        /**
         * Returns true if any handler succeeded.
         */
        public boolean anySucceeded() {
            return !results.isEmpty();
        }

        /**
         * Returns the success rate as a percentage.
         */
        public double successRate() {
            var total = results.size() + errors.size();
            return total == 0 ? 0.0 : (double) results.size() / total * 100;
        }
    }

    /**
     * Creates a new scatter-gather instance.
     *
     * @param <T> request type
     * @param <R> response type
     * @return a new scatter-gather instance
     */
    public static <T, R> ScatterGatherPatterns<T, R> create() {
        return new ScatterGatherPatterns<>();
    }
}
