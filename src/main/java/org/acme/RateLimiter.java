package org.acme;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Rate Limiter — protects services from overload using various limiting algorithms.
 *
 * <p>Rate limiting is a critical resilience pattern for * that prevents service degradation by controlling
 * the rate of incoming requests. Different algorithms provide different trade-offs between
 * fairness, memory usage, and precision.
 *
 * <p>Features:
 * <ul>
 *   <li><b>Token Bucket</b> — Smooth traffic flow with burst tolerance</li>
 *   <li><b>Sliding Window</b> — Precise request counting over a time window</li>
 *   <li><b>Per-Client</b> — Rate limiting per client identifier</li>
 *   <li><b>Per-Second/Per-Minute</b> — Convenient factories for common time windows</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Token bucket: 100 requests per second with burst capacity of 100
 * RateLimiter limiter = RateLimiter.tokenBucket(100, 100.0);
 *
 * // Sliding window: 1000 requests per minute
 * RateLimiter limiter = RateLimiter.slidingWindow(1000, Duration.ofMinutes(1));
 *
 * // Per-client rate limiting
 * RateLimiter perClient = RateLimiter.perClient(100);
 * if (perClient.tryAcquire(clientId)) {
 *     // Process request
 * }
 * }</pre>
 *
 * @see ApiGateway
 */
public interface RateLimiter {
    boolean tryAcquire();
    boolean tryAcquire(int permits);
    double currentRate();
    long availablePermits();
    void reset();

    static RateLimiter perSecond(int rate) { return tokenBucket(rate, rate); }
    static RateLimiter perMinute(int rate) { return tokenBucket(rate, rate / 60); }
    static RateLimiter tokenBucket(long capacity, double refillRate) { return new TokenBucket(capacity, refillRate); }
    static RateLimiter slidingWindow(int maxRequests, Duration window) { return new SlidingWindow(maxRequests, window); }

    final class TokenBucket implements RateLimiter {
        private final long capacity;
        private final double refillRate;
        private volatile double tokens;
        private volatile long lastRefill;

        TokenBucket(long capacity, double refillRate) {
            this.capacity = capacity; this.refillRate = refillRate;
            this.tokens = capacity; this.lastRefill = System.currentTimeMillis();
        }

        @Override public synchronized boolean tryAcquire() { return tryAcquire(1); }
        @Override public synchronized boolean tryAcquire(int permits) {
            refill();
            if (tokens >= permits) { tokens -= permits; return true; }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double elapsed = (now - lastRefill) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsed * refillRate);
            lastRefill = now;
        }

        @Override public double currentRate() { return refillRate; }
        @Override public long availablePermits() { refill(); return (long) tokens; }
        @Override public synchronized void reset() { tokens = capacity; lastRefill = System.currentTimeMillis(); }
    }

    final class SlidingWindow implements RateLimiter {
        private final int maxRequests;
        private final Duration window;
        private final ConcurrentHashMap<Long, AtomicLong> counters = new ConcurrentHashMap<>();
        private final Object lock = new Object();

        SlidingWindow(int maxRequests, Duration window) {
            this.maxRequests = maxRequests; this.window = window;
        }

        @Override public boolean tryAcquire() { return tryAcquire(1); }
        @Override public boolean tryAcquire(int permits) {
            synchronized (lock) {
                long now = System.currentTimeMillis();
                long windowStart = now - window.toMillis();
                counters.keySet().removeIf(ts -> ts < windowStart);
                long count = counters.values().stream().mapToLong(AtomicLong::get).sum();
                if (count + permits <= maxRequests) {
                    counters.computeIfAbsent(now, k -> new AtomicLong()).addAndGet(permits);
                    return true;
                }
                return false;
            }
        }

        @Override public double currentRate() {
            synchronized (lock) {
                long count = counters.values().stream().mapToLong(AtomicLong::get).sum();
                return count * 1000.0 / window.toMillis();
            }
        }
        @Override public long availablePermits() {
            synchronized (lock) {
                long count = counters.values().stream().mapToLong(AtomicLong::get).sum();
                return Math.max(0, maxRequests - count);
            }
        }
        @Override public void reset() {
            synchronized (lock) {
                counters.clear();
            }
        }
    }

    final class PerClient<K> implements RateLimiter {
        private final Function<K, RateLimiter> factory;
        private final ConcurrentHashMap<K, RateLimiter> limiters = new ConcurrentHashMap<>();

        PerClient(Function<K, RateLimiter> factory) { this.factory = factory; }

        public boolean tryAcquire(K clientKey) { return tryAcquire(clientKey, 1); }
        public boolean tryAcquire(K clientKey, int permits) {
            return limiters.computeIfAbsent(clientKey, factory).tryAcquire(permits);
        }

        @Override public boolean tryAcquire() { throw new UnsupportedOperationException("Use tryAcquire(clientKey)"); }
        @Override public boolean tryAcquire(int permits) { throw new UnsupportedOperationException("Use tryAcquire(clientKey, permits)"); }
        @Override public double currentRate() { return limiters.values().stream().mapToDouble(RateLimiter::currentRate).average().orElse(0); }
        @Override public long availablePermits() { return limiters.values().stream().mapToLong(RateLimiter::availablePermits).sum(); }
        @Override public void reset() { limiters.clear(); }
        public void reset(K clientKey) { limiters.remove(clientKey); }
    }
}
