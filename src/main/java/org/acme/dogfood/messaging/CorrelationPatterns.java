package org.acme.dogfood.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Correlation Identifier pattern.
 *
 * <p>Generated from {@code templates/java/messaging/correlation-identifier.tera}.
 *
 * <p>Implements message correlation for request-reply patterns. Each request is assigned
 * a unique correlation ID, and replies are matched back to their original requests.
 * This is essential for asynchronous messaging where responses need to be correlated
 * with their requests.
 *
 * <p><strong>Pattern contracts validated:</strong>
 *
 * <ul>
 *   <li>Correlation ID assignment
 *   <li>Request-reply matching
 *   <li>Timeout handling for pending replies
 * </ul>
 *
 * @param <T> request type
 * @param <R> reply type
 */
public final class CorrelationPatterns<T, R> {

    private final Map<String, PendingReply<R>> pendingReplies = new ConcurrentHashMap<>();
    private Duration defaultTimeout = Duration.ofSeconds(30);
    private Function<T, String> correlationIdExtractor = msg -> UUID.randomUUID().toString();

    /**
     * Represents a pending reply waiting for a response.
     */
    private static class PendingReply<R> {
        final CompletableFuture<R> future = new CompletableFuture<>();
        final Instant createdAt = Instant.now();

        boolean isExpired(Duration timeout) {
            return Instant.now().isAfter(createdAt.plus(timeout));
        }
    }

    /**
     * Sets the default timeout for waiting for replies.
     *
     * @param timeout the default timeout
     * @return this instance for chaining
     */
    public CorrelationPatterns<T, R> withDefaultTimeout(Duration timeout) {
        this.defaultTimeout = timeout;
        return this;
    }

    /**
     * Sets a custom correlation ID extractor.
     *
     * @param extractor function to extract correlation ID from request
     * @return this instance for chaining
     */
    public CorrelationPatterns<T, R> withCorrelationIdExtractor(Function<T, String> extractor) {
        this.correlationIdExtractor = extractor;
        return this;
    }

    /**
     * Sends a request and returns the correlation ID.
     *
     * <p>The correlation ID can be used to match the reply when it arrives.
     *
     * @param request the request to send
     * @return the correlation ID for this request
     */
    public String sendRequest(T request) {
        var correlationId = correlationIdExtractor.apply(request);
        pendingReplies.put(correlationId, new PendingReply<>());
        return correlationId;
    }

    /**
     * Sends a request and waits for a reply.
     *
     * @param request the request to send
     * @return the reply
     * @throws TimeoutException if no reply is received within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public R sendAndWaitForReply(T request) throws TimeoutException, InterruptedException {
        return sendAndWaitForReply(request, defaultTimeout);
    }

    /**
     * Sends a request and waits for a reply with a specific timeout.
     *
     * @param request the request to send
     * @param timeout the maximum time to wait
     * @return the reply
     * @throws TimeoutException if no reply is received within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public R sendAndWaitForReply(T request, Duration timeout)
            throws TimeoutException, InterruptedException {
        var correlationId = sendRequest(request);
        try {
            return pendingReplies.get(correlationId).future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e.getCause());
        } finally {
            pendingReplies.remove(correlationId);
        }
    }

    /**
     * Handles a reply by matching it to its original request.
     *
     * @param correlationId the correlation ID from the original request
     * @param reply the reply
     * @return true if the reply was matched to a pending request
     */
    public boolean handleReply(String correlationId, R reply) {
        var pending = pendingReplies.get(correlationId);
        if (pending != null) {
            pending.future.complete(reply);
            return true;
        }
        return false;
    }

    /**
     * Handles an error reply by completing the pending request exceptionally.
     *
     * @param correlationId the correlation ID from the original request
     * @param error the error
     * @return true if the error was matched to a pending request
     */
    public boolean handleError(String correlationId, Throwable error) {
        var pending = pendingReplies.get(correlationId);
        if (pending != null) {
            pending.future.completeExceptionally(error);
            return true;
        }
        return false;
    }

    /**
     * Checks if there is a pending request for the given correlation ID.
     *
     * @param correlationId the correlation ID to check
     * @return true if there is a pending request
     */
    public boolean hasPendingReply(String correlationId) {
        var pending = pendingReplies.get(correlationId);
        return pending != null && !pending.future.isDone();
    }

    /**
     * Returns the number of pending replies.
     *
     * @return pending reply count
     */
    public int pendingReplyCount() {
        return (int) pendingReplies.values().stream()
                .filter(p -> !p.future.isDone())
                .count();
    }

    /**
     * Cleans up expired pending replies.
     *
     * @return the number of expired replies removed
     */
    public int cleanupExpired() {
        var expired = new java.util.ArrayList<String>();
        for (var entry : pendingReplies.entrySet()) {
            if (entry.getValue().isExpired(defaultTimeout)) {
                expired.add(entry.getKey());
                entry.getValue().future.completeExceptionally(
                        new TimeoutException("Reply timed out"));
            }
        }
        expired.forEach(pendingReplies::remove);
        return expired.size();
    }

    /**
     * Generates a new correlation ID.
     *
     * @return a unique correlation ID
     */
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Creates a new correlation patterns instance.
     *
     * @param <T> request type
     * @param <R> reply type
     * @return a new correlation patterns instance
     */
    public static <T, R> CorrelationPatterns<T, R> create() {
        return new CorrelationPatterns<>();
    }
}
