package org.acme;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Query dispatcher — CQRS query-side dispatcher.
 *
 * <p>CQRS Pattern: "The query side handles all read operations, optimized
 * for fast data retrieval without the complexity of the write model."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Query routing</b> — Route queries to handlers by type</li>
 *   <li><b>Caching</b> — Optional caching of query results</li>
 *   <li><b>Batch queries</b> — Execute multiple queries in parallel</li>
 *   <li><b>Projections</b> — Query against read model projections</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * QueryDispatcher dispatcher = QueryDispatcher.create()
 *     .register(GetOrderById.class, this::handleGetOrderById)
 *     .register(SearchOrders.class, this::handleSearchOrders)
 *     .cache(Duration.ofMinutes(5));
 *
 * // Execute a query
 * Optional<Order> order = dispatcher.query(new GetOrderById(orderId));
 * }</pre>
 */
public final class QueryDispatcher {

    /** Query marker interface. */
    public interface Query<T> {
        /** Cache key for this query. */
        default String cacheKey() { return toString(); }
    }

    /** Query handler function. */
    @FunctionalInterface
    public interface Handler<Q extends Query<T>, T> {
        T handle(Q query);
    }

    /** Cache entry. */
    private record CacheEntry(Object value, Instant expiry) {
        boolean isExpired() { return Instant.now().isAfter(expiry); }
    }

    // ─-- Dispatcher state ────────────────────────────────────────────────────────

    private final ConcurrentHashMap<Class<?>, Handler<?, ?>> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong queriesExecuted = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    private volatile java.time.Duration cacheTtl = java.time.Duration.ZERO;
    private volatile boolean cachingEnabled = false;

    private QueryDispatcher() {}

    // ── Factory ──────────────────────────────────────────────────────────────────

    public static QueryDispatcher create() {
        return new QueryDispatcher();
    }

    // ── Configuration ────────────────────────────────────────────────────────────

    /**
     * Enable caching with specified TTL.
     */
    public QueryDispatcher cache(java.time.Duration ttl) {
        this.cacheTtl = ttl;
        this.cachingEnabled = !ttl.isZero() && !ttl.isNegative();
        return this;
    }

    // ── Registration ─────────────────────────────────────────────────────────────

    /**
     * Register a handler for a query type.
     */
    public <Q extends Query<T>, T> QueryDispatcher register(Class<Q> queryType, Handler<Q, T> handler) {
        handlers.put(queryType, handler);
        return this;
    }

    /**
     * Register a handler using a simple function.
     */
    @SuppressWarnings("unchecked")
    public <Q extends Query<T>, T> QueryDispatcher registerFunc(Class<Q> queryType, Function<Q, T> handler) {
        Handler<Q, T> h = query -> handler.apply(query);
        handlers.put(queryType, h);
        return this;
    }

    // ── Query execution ──────────────────────────────────────────────────────────

    /**
     * Execute a query and return the result.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> query(Query<T> query) {
        queriesExecuted.incrementAndGet();

        // Check cache
        if (cachingEnabled) {
            String key = query.cacheKey();
            CacheEntry cached = cache.get(key);
            if (cached != null && !cached.isExpired()) {
                cacheHits.incrementAndGet();
                return Optional.ofNullable((T) cached.value());
            }
            cacheMisses.incrementAndGet();
        }

        // Find handler
        Handler<Query<T>, T> handler = (Handler<Query<T>, T>) handlers.get(query.getClass());
        if (handler == null) {
            return Optional.empty();
        }

        // Execute query
        try {
            T result = handler.handle(query);

            // Cache result
            if (cachingEnabled && result != null) {
                cache.put(query.cacheKey(), new CacheEntry(result, Instant.now().plus(cacheTtl)));
            }

            return Optional.ofNullable(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Execute a query asynchronously.
     */
    public <T> CompletableFuture<Optional<T>> queryAsync(Query<T> query) {
        return CompletableFuture.supplyAsync(() -> query(query));
    }

    /**
     * Execute multiple queries in parallel.
     */
    @SuppressWarnings("unchecked")
    public <T> Map<Query<T>, Optional<T>> queryAll(Collection<Query<T>> queries) {
        Map<Query<T>, CompletableFuture<Optional<T>>> futures = new LinkedHashMap<>();

        for (Query<T> query : queries) {
            futures.put(query, queryAsync(query));
        }

        Map<Query<T>, Optional<T>> results = new LinkedHashMap<>();
        futures.forEach((query, future) -> {
            try {
                results.put(query, future.get());
            } catch (Exception e) {
                results.put(query, Optional.empty());
            }
        });

        return results;
    }

    // ── Cache management ──────────────────────────────────────────────────────────

    /**
     * Clear the query cache.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Evict expired cache entries.
     */
    public void evictExpired() {
        cache.values().removeIf(CacheEntry::isExpired);
    }

    // ── Statistics ───────────────────────────────────────────────────────────────

    public Map<String, Long> stats() {
        Map<String, Long> s = new LinkedHashMap<>();
        s.put("queries", queriesExecuted.get());
        s.put("cacheHits", cacheHits.get());
        s.put("cacheMisses", cacheMisses.get());
        s.put("handlers", (long) handlers.size());
        s.put("cacheSize", (long) cache.size());
        return s;
    }
}
