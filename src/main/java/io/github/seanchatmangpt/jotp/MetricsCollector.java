package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Metrics collector — OTP-style telemetry for observability.
 *
 * <p>Joe Armstrong: "In production systems, you need visibility. Observability
 * is not optional — metrics tell you what's happening right now."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Counters</b> — Monotonically increasing values (requests, errors)</li>
 *   <li><b>Gauges</b> — Point-in-time values (queue depth, memory)</li>
 *   <li><b>Histograms</b> — Distribution of values (latency, size)</li>
 *   <li><b>Timers</b> — Duration measurements with percentiles</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * MetricsCollector metrics = MetricsCollector.create("telemetry-service");
 *
 * // Counter
 * metrics.counter("requests.total").increment();
 * metrics.counter("errors.total").increment();
 *
 * // Gauge
 * metrics.gauge("queue.depth", () -> queue.size());
 *
 * // Timer
 * try (MetricsCollector.Timer timer = metrics.timer("request.duration").start()) {
 *     processRequest();
 * }
 *
 * // Histogram
 * metrics.histogram("payload.size").record(payload.length);
 *
 * // Export
 * Map<String, Object> snapshot = metrics.snapshot();
 * }</pre>
 */
public final class MetricsCollector implements Application.Infrastructure {

    /** Metric type enumeration. */
    public enum MetricType { COUNTER, GAUGE, HISTOGRAM, TIMER }

    /** Metric metadata. */
    public record MetricInfo(String name, MetricType type, String description, Map<String, String> tags) {}

    // ── Metric interfaces ────────────────────────────────────────────────────────

    /** Counter metric — monotonically increasing value. */
    public interface Counter {
        void increment();
        void increment(long delta);
        long count();
        void reset();
    }

    /** Gauge metric — point-in-time value. */
    public interface Gauge {
        void set(double value);
        double value();
    }

    /** Histogram metric — distribution of values. */
    public interface Histogram {
        void record(long value);
        long count();
        double mean();
        long min();
        long max();
        double percentile(double p);
    }

    /** Timer metric — duration measurement. */
    public interface Timer {
        TimerContext start();
        void record(Duration duration);
        long count();
        Duration mean();
        Duration min();
        Duration max();
        Duration percentile(double p);
    }

    /** Timer context for try-with-resources. */
    public interface TimerContext extends AutoCloseable {
        Duration stop();
        @Override void close();
    }

    // ── Internal implementations ─────────────────────────────────────────────────

    private static final class CounterImpl implements Counter {
        private final LongAdder adder = new LongAdder();

        @Override public void increment() { adder.increment(); }
        @Override public void increment(long delta) { adder.add(delta); }
        @Override public long count() { return adder.sum(); }
        @Override public void reset() { adder.reset(); }
    }

    private static final class GaugeImpl implements Gauge {
        private volatile double value = 0.0;
        private final Supplier<Double> supplier;

        GaugeImpl(Supplier<Double> supplier) {
            this.supplier = supplier;
        }

        @Override public void set(double value) { this.value = value; }
        @Override public double value() { return supplier != null ? supplier.get() : value; }
    }

    private static final class HistogramImpl implements Histogram {
        private final LongAdder count = new LongAdder();
        private final LongAdder sum = new LongAdder();
        private volatile long min = Long.MAX_VALUE;
        private volatile long max = Long.MIN_VALUE;
        private final List<Long> samples = Collections.synchronizedList(new ArrayList<>());
        private final int maxSamples;

        HistogramImpl(int maxSamples) {
            this.maxSamples = maxSamples;
        }

        @Override
        public void record(long value) {
            count.increment();
            sum.add(value);
            min = Math.min(min, value);
            max = Math.max(max, value);

            synchronized (samples) {
                samples.add(value);
                if (samples.size() > maxSamples) {
                    samples.remove(0);
                }
            }
        }

        @Override public long count() { return count.sum(); }
        @Override public double mean() {
            long c = count.sum();
            return c > 0 ? (double) sum.sum() / c : 0;
        }
        @Override public long min() { return min == Long.MAX_VALUE ? 0 : min; }
        @Override public long max() { return max == Long.MIN_VALUE ? 0 : max; }

        @Override
        public double percentile(double p) {
            synchronized (samples) {
                if (samples.isEmpty()) return 0;
                List<Long> sorted = new ArrayList<>(samples);
                Collections.sort(sorted);
                int index = (int) (p / 100.0 * sorted.size());
                return sorted.get(Math.min(index, sorted.size() - 1));
            }
        }
    }

    private static final class TimerImpl implements Timer {
        private final HistogramImpl histogram;

        TimerImpl(int maxSamples) {
            this.histogram = new HistogramImpl(maxSamples);
        }

        @Override
        public TimerContext start() {
            Instant start = Instant.now();
            return new TimerContext() {
                private volatile Duration duration = null;

                @Override
                public Duration stop() {
                    if (duration == null) {
                        duration = Duration.between(start, Instant.now());
                        histogram.record(duration.toMillis());
                    }
                    return duration;
                }

                @Override
                public void close() {
                    stop();
                }
            };
        }

        @Override public void record(Duration duration) { histogram.record(duration.toMillis()); }
        @Override public long count() { return histogram.count(); }
        @Override public Duration mean() { return Duration.ofMillis((long) histogram.mean()); }
        @Override public Duration min() { return Duration.ofMillis(histogram.min()); }
        @Override public Duration max() { return Duration.ofMillis(histogram.max()); }
        @Override public Duration percentile(double p) { return Duration.ofMillis((long) histogram.percentile(p)); }
    }

    // ── Metrics collector state ───────────────────────────────────────────────────

    private final String name;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MetricInfo> metadata = new ConcurrentHashMap<>();
    private final int histogramMaxSamples;

    private MetricsCollector(String name, int histogramMaxSamples) {
        this.name = name;
        this.histogramMaxSamples = histogramMaxSamples;
    }

    // ── Factory methods ──────────────────────────────────────────────────────────

    public static MetricsCollector create() {
        return new MetricsCollector("metrics", 1000);
    }

    public static MetricsCollector create(String name) {
        return new MetricsCollector(name, 1000);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Metrics collector builder. */
    public static final class Builder {
        private String name = "metrics";
        private int histogramMaxSamples = 1000;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder histogramMaxSamples(int max) {
            this.histogramMaxSamples = max;
            return this;
        }

        public MetricsCollector build() {
            return new MetricsCollector(name, histogramMaxSamples);
        }
    }

    // ── Metric registration ──────────────────────────────────────────────────────

    /**
     * Get or create a counter.
     */
    public Counter counter(String name) {
        return counters.computeIfAbsent(name, k -> {
            metadata.put(k, new MetricInfo(k, MetricType.COUNTER, "", Map.of()));
            return new CounterImpl();
        });
    }

    /**
     * Get or create a counter with tags.
     */
    public Counter counter(String name, Map<String, String> tags) {
        String key = name + tags.toString();
        return counters.computeIfAbsent(key, k -> {
            metadata.put(k, new MetricInfo(name, MetricType.COUNTER, "", tags));
            return new CounterImpl();
        });
    }

    /**
     * Get or create a gauge with a supplier.
     */
    public Gauge gauge(String name, Supplier<Double> supplier) {
        return gauges.computeIfAbsent(name, k -> {
            metadata.put(k, new MetricInfo(k, MetricType.GAUGE, "", Map.of()));
            return new GaugeImpl(supplier);
        });
    }

    /**
     * Get or create a gauge without supplier (manual updates).
     */
    public Gauge gauge(String name) {
        return gauge(name, null);
    }

    /**
     * Get or create a histogram.
     */
    public Histogram histogram(String name) {
        return histograms.computeIfAbsent(name, k -> {
            metadata.put(k, new MetricInfo(k, MetricType.HISTOGRAM, "", Map.of()));
            return new HistogramImpl(histogramMaxSamples);
        });
    }

    /**
     * Get or create a timer.
     */
    public Timer timer(String name) {
        return timers.computeIfAbsent(name, k -> {
            metadata.put(k, new MetricInfo(k, MetricType.TIMER, "", Map.of()));
            return new TimerImpl(histogramMaxSamples);
        });
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────────

    /**
     * Get a snapshot of all metrics.
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();

        counters.forEach((name, counter) -> snap.put(name, counter.count()));
        gauges.forEach((name, gauge) -> snap.put(name, gauge.value()));

        histograms.forEach((name, hist) -> {
            Map<String, Object> histData = new LinkedHashMap<>();
            histData.put("count", hist.count());
            histData.put("mean", hist.mean());
            histData.put("min", hist.min());
            histData.put("max", hist.max());
            histData.put("p50", hist.percentile(50));
            histData.put("p95", hist.percentile(95));
            histData.put("p99", hist.percentile(99));
            snap.put(name, histData);
        });

        timers.forEach((name, timer) -> {
            Map<String, Object> timerData = new LinkedHashMap<>();
            timerData.put("count", timer.count());
            timerData.put("mean_ms", timer.mean().toMillis());
            timerData.put("min_ms", timer.min().toMillis());
            timerData.put("max_ms", timer.max().toMillis());
            timerData.put("p50_ms", timer.percentile(50).toMillis());
            timerData.put("p95_ms", timer.percentile(95).toMillis());
            timerData.put("p99_ms", timer.percentile(99).toMillis());
            snap.put(name, timerData);
        });

        return snap;
    }

    // ── Infrastructure lifecycle ─────────────────────────────────────────────────

    @Override
    public String name() { return name; }

    // ── Global registry ──────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, MetricsCollector> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Register a metrics collector.
     */
    public static void register(MetricsCollector collector) {
        REGISTRY.put(collector.name, collector);
    }

    /**
     * Get a registered collector by name.
     */
    public static MetricsCollector get(String name) {
        return REGISTRY.get(name);
    }

    /**
     * Get the default collector.
     */
    public static MetricsCollector getDefault() {
        return REGISTRY.computeIfAbsent("default", k -> create());
    }
}
