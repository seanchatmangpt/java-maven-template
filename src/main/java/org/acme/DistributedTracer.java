package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed tracer — W3C Trace Context compatible tracing for JOTP.
 *
 * <p>Joe Armstrong: "In distributed systems, you need to follow the request
 * across process boundaries. Distributed tracing is the distributed debugger."
 *
 * <p>Features:
 * <ul>
 *   <li><b>W3C Trace Context</b> — Compatible with OpenTelemetry, Jaeger, Zipkin</li>
 *   <li><b>Span hierarchy</b> — Parent-child relationships for nested operations</li>
 *   <li><b>Context propagation</b> — Trace context flows with messages</li>
 *   <li><b>Span events</b> — Timestamped events within spans</li>
 *   <li><b>Attribute bags</b> — Key-value attributes on spans</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * DistributedTracer tracer = DistributedTracer.create("telemetry-service");
 *
 * // Start a root span
 * Span span = tracer.spanBuilder("process-sample")
 *     .setAttribute("sample.id", sampleId)
 *     .startSpan();
 *
 * try (SpanScope scope = span.makeCurrent()) {
 *     // Do work
 *     processSample(sample);
 *
 *     // Add events
 *     span.addEvent("validation-complete");
 *
 *     // Create child span
 *     Span child = tracer.spanBuilder("persist-sample")
 *         .setParent(span)
 *         .startSpan();
 *     try {
 *         persistSample(sample);
 *     } finally {
 *         child.end();
 *     }
 * } finally {
 *     span.end();
 * }
 * }</pre>
 */
public final class DistributedTracer implements Application.Infrastructure {

    /** W3C Trace Context. */
    public record TraceContext(
            String traceId,      // 32 hex chars
            String spanId,       // 16 hex chars
            String parentSpanId, // 16 hex chars or null
            byte traceFlags,     // 0x01 = sampled
            Map<String, String> traceState) {

        private static final Random RANDOM = new Random();

        public static TraceContext create() {
            return new TraceContext(
                    generateTraceId(),
                    generateSpanId(),
                    null,
                    (byte) 1,
                    Map.of());
        }

        public static TraceContext createChild(TraceContext parent) {
            return new TraceContext(
                    parent.traceId(),
                    generateSpanId(),
                    parent.spanId(),
                    parent.traceFlags(),
                    parent.traceState());
        }

        public static String generateTraceId() {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            return bytesToHex(bytes);
        }

        public static String generateSpanId() {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            return bytesToHex(bytes);
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        public boolean isSampled() {
            return (traceFlags & 0x01) != 0;
        }

        public String toW3CHeader() {
            return traceId + "-" + spanId + "-" + (isSampled() ? "01" : "00");
        }
    }

    /** Span status codes. */
    public enum StatusCode {
        UNSET,
        OK,
        ERROR
    }

    /** Span kind. */
    public enum SpanKind {
        INTERNAL,
        SERVER,
        CLIENT,
        PRODUCER,
        CONSUMER
    }

    /** Span event. */
    public record SpanEvent(String name, Instant timestamp, Map<String, Object> attributes) {
        public static SpanEvent of(String name) {
            return new SpanEvent(name, Instant.now(), Map.of());
        }

        public static SpanEvent of(String name, Map<String, Object> attributes) {
            return new SpanEvent(name, Instant.now(), attributes);
        }
    }

    /** Span interface. */
    public interface Span {
        String name();
        TraceContext context();
        SpanKind kind();
        StatusCode status();
        String statusDescription();
        Instant startTime();
        Instant endTime();
        Duration duration();
        boolean isEnded();
        boolean isRecording();

        void setStatus(StatusCode status);
        void setStatus(StatusCode status, String description);
        void addEvent(String name);
        void addEvent(String name, Map<String, Object> attributes);
        void setAttribute(String key, String value);
        void setAttribute(String key, long value);
        void setAttribute(String key, double value);
        void setAttribute(String key, boolean value);
        void recordException(Throwable exception);
        void end();
        void end(Throwable exception);

        SpanScope makeCurrent();
    }

    /** Scope for making a span current. */
    public interface SpanScope extends AutoCloseable {
        void close();
    }

    // ── Span implementation ──────────────────────────────────────────────────────

    private static final class SpanImpl implements Span {
        private final String name;
        private final TraceContext context;
        private final SpanKind kind;
        private final Instant startTime;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<SpanEvent> events = new ConcurrentLinkedQueue<>();
        private volatile StatusCode status = StatusCode.UNSET;
        private volatile String statusDescription;
        private volatile Instant endTime;
        private volatile boolean ended = false;
        private final DistributedTracer tracer;

        SpanImpl(String name, TraceContext context, SpanKind kind, DistributedTracer tracer) {
            this.name = name;
            this.context = context;
            this.kind = kind;
            this.startTime = Instant.now();
            this.tracer = tracer;
        }

        @Override public String name() { return name; }
        @Override public TraceContext context() { return context; }
        @Override public SpanKind kind() { return kind; }
        @Override public StatusCode status() { return status; }
        @Override public String statusDescription() { return statusDescription; }
        @Override public Instant startTime() { return startTime; }
        @Override public Instant endTime() { return endTime; }
        @Override public boolean isEnded() { return ended; }
        @Override public boolean isRecording() { return !ended; }

        @Override
        public Duration duration() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end);
        }

        @Override public void setStatus(StatusCode status) { this.status = status; }
        @Override public void setStatus(StatusCode status, String description) {
            this.status = status;
            this.statusDescription = description;
        }

        @Override public void addEvent(String name) {
            events.add(SpanEvent.of(name));
        }

        @Override public void addEvent(String name, Map<String, Object> attributes) {
            events.add(SpanEvent.of(name, attributes));
        }

        @Override public void setAttribute(String key, String value) { attributes.put(key, value); }
        @Override public void setAttribute(String key, long value) { attributes.put(key, value); }
        @Override public void setAttribute(String key, double value) { attributes.put(key, value); }
        @Override public void setAttribute(String key, boolean value) { attributes.put(key, value); }

        @Override
        public void recordException(Throwable exception) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("exception.type", exception.getClass().getName());
            attrs.put("exception.message", exception.getMessage());
            events.add(new SpanEvent("exception", Instant.now(), attrs));
            setStatus(StatusCode.ERROR, exception.getMessage());
        }

        @Override
        public void end() {
            if (ended) return;
            ended = true;
            endTime = Instant.now();
            tracer.onSpanEnd(this);
        }

        @Override
        public void end(Throwable exception) {
            recordException(exception);
            end();
        }

        @Override
        public SpanScope makeCurrent() {
            SpanImpl previous = tracer.currentSpan.get();
            tracer.currentSpan.set(this);
            return () -> {
                if (previous != null) {
                    tracer.currentSpan.set(previous);
                } else {
                    tracer.currentSpan.remove();
                }
            };
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("traceId", context.traceId());
            map.put("spanId", context.spanId());
            map.put("parentSpanId", context.parentSpanId());
            map.put("kind", kind.name());
            map.put("startTime", startTime.toString());
            map.put("endTime", endTime != null ? endTime.toString() : null);
            map.put("durationMs", duration().toMillis());
            map.put("status", status.name());
            map.put("attributes", Map.copyOf(attributes));
            map.put("events", events.stream().map(e -> Map.of(
                    "name", e.name(),
                    "timestamp", e.timestamp().toString(),
                    "attributes", e.attributes()
            )).toList());
            return map;
        }
    }

    // ── Tracer state ──────────────────────────────────────────────────────────────

    private final String name;
    private final ThreadLocal<SpanImpl> currentSpan = new ThreadLocal<>();
    private final Queue<SpanImpl> completedSpans = new ConcurrentLinkedQueue<>();
    private final int maxSpans;
    private final AtomicLong spansCreated = new AtomicLong(0);
    private final AtomicLong spansEnded = new AtomicLong(0);

    private DistributedTracer(String name, int maxSpans) {
        this.name = name;
        this.maxSpans = maxSpans;
    }

    // ── Factory methods ──────────────────────────────────────────────────────────

    public static DistributedTracer create() {
        return new DistributedTracer("tracer", 10000);
    }

    public static DistributedTracer create(String name) {
        return new DistributedTracer(name, 10000);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Tracer builder. */
    public static final class Builder {
        private String name = "tracer";
        private int maxSpans = 10000;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder maxSpans(int max) {
            this.maxSpans = max;
            return this;
        }

        public DistributedTracer build() {
            return new DistributedTracer(name, maxSpans);
        }
    }

    // ── Span builder ──────────────────────────────────────────────────────────────

    public SpanBuilder spanBuilder(String spanName) {
        return new SpanBuilder(spanName, this);
    }

    /** Span builder. */
    public static final class SpanBuilder {
        private final String spanName;
        private final DistributedTracer tracer;
        private SpanKind kind = SpanKind.INTERNAL;
        private TraceContext parentContext;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        SpanBuilder(String spanName, DistributedTracer tracer) {
            this.spanName = spanName;
            this.tracer = tracer;
        }

        public SpanBuilder setKind(SpanKind kind) {
            this.kind = kind;
            return this;
        }

        public SpanBuilder setParent(Span parent) {
            this.parentContext = parent.context();
            return this;
        }

        public SpanBuilder setParent(TraceContext parent) {
            this.parentContext = parent;
            return this;
        }

        public SpanBuilder setAttribute(String key, String value) {
            attributes.put(key, value);
            return this;
        }

        public SpanBuilder setAttribute(String key, long value) {
            attributes.put(key, value);
            return this;
        }

        public SpanBuilder setAttribute(String key, double value) {
            attributes.put(key, value);
            return this;
        }

        public Span startSpan() {
            TraceContext context = parentContext != null
                    ? TraceContext.createChild(parentContext)
                    : TraceContext.create();

            SpanImpl span = new SpanImpl(spanName, context, kind, tracer);
            attributes.forEach((k, v) -> span.setAttribute(k, String.valueOf(v)));
            tracer.spansCreated.incrementAndGet();
            return span;
        }
    }

    // ── Current span ──────────────────────────────────────────────────────────────

    /**
     * Get the current span for this thread.
     */
    public Optional<Span> getCurrentSpan() {
        return Optional.ofNullable(currentSpan.get());
    }

    /**
     * Get the current trace context for propagation.
     */
    public Optional<TraceContext> getCurrentContext() {
        return getCurrentSpan().map(Span::context);
    }

    // ── Span export ───────────────────────────────────────────────────────────────

    private void onSpanEnd(SpanImpl span) {
        spansEnded.incrementAndGet();
        if (completedSpans.size() < maxSpans) {
            completedSpans.add(span);
        }
    }

    /**
     * Get completed spans (for export).
     */
    public List<Map<String, Object>> exportSpans() {
        List<Map<String, Object>> exported = new ArrayList<>();
        SpanImpl span;
        while ((span = completedSpans.poll()) != null) {
            exported.add(span.toMap());
        }
        return exported;
    }

    /**
     * Get tracer statistics.
     */
    public Map<String, Long> stats() {
        Map<String, Long> s = new LinkedHashMap<>();
        s.put("spansCreated", spansCreated.get());
        s.put("spansEnded", spansEnded.get());
        s.put("pendingSpans", spansCreated.get() - spansEnded.get());
        return s;
    }

    // ── Infrastructure lifecycle ─────────────────────────────────────────────────

    @Override
    public String name() { return name; }
}
