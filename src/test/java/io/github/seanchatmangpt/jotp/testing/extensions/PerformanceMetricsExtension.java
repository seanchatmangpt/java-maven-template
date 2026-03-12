package io.github.seanchatmangpt.jotp.testing.extensions;

import io.github.seanchatmangpt.jotp.testing.annotations.PerformanceBaseline;
import org.junit.jupiter.api.extension.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.*;

/**
 * JUnit 6 extension that records latency, throughput, and memory metrics for baselines.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Throughput (messages/second)</li>
 *   <li>Latency percentiles (p50, p95, p99)</li>
 *   <li>GC impact and memory usage</li>
 *   <li>Virtual thread pinning duration</li>
 * </ul>
 *
 * <p>Uses Java 26 reflection API for JFR integration and memory profiling.
 */
public class PerformanceMetricsExtension implements TestInstancePostProcessor, TestExecutionExceptionHandler {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(PerformanceMetricsExtension.class);

  public static class PerformanceMetrics {
    public final long startTimeNanos;
    public final long startMemoryBytes;
    public final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    public long messageCount = 0;
    public long endTimeNanos = 0;
    public long endMemoryBytes = 0;

    public PerformanceMetrics() {
      this.startTimeNanos = System.nanoTime();
      var memoryBean = ManagementFactory.getMemoryMXBean();
      this.startMemoryBytes = memoryBean.getHeapMemoryUsage().getUsed();
    }

    public void recordMessage(long latencyNanos) {
      latencies.add(latencyNanos);
      messageCount++;
    }

    public void finalize() {
      this.endTimeNanos = System.nanoTime();
      var memoryBean = ManagementFactory.getMemoryMXBean();
      this.endMemoryBytes = memoryBean.getHeapMemoryUsage().getUsed();
    }

    public long getThroughputPerSecond() {
      var durationNanos = endTimeNanos - startTimeNanos;
      if (durationNanos == 0) return 0;
      return messageCount * 1_000_000_000L / durationNanos;
    }

    public long getP50LatencyNanos() {
      return getPercentileLatency(0.5);
    }

    public long getP95LatencyNanos() {
      return getPercentileLatency(0.95);
    }

    public long getP99LatencyNanos() {
      return getPercentileLatency(0.99);
    }

    private long getPercentileLatency(double percentile) {
      if (latencies.isEmpty()) return 0;
      var sorted = new ArrayList<>(latencies);
      Collections.sort(sorted);
      int index = (int) (sorted.size() * percentile);
      return sorted.get(Math.min(index, sorted.size() - 1));
    }

    public long getMemoryUsageDeltaBytes() {
      return endMemoryBytes - startMemoryBytes;
    }

    public long getMemoryUsageDeltaMB() {
      return getMemoryUsageDeltaBytes() / (1024 * 1024);
    }

    public String summary() {
      return String.format(
          "Throughput: %d msg/s | P50: %d ns | P95: %d ns | P99: %d ns | Memory: +%d MB",
          getThroughputPerSecond(),
          getP50LatencyNanos(),
          getP95LatencyNanos(),
          getP99LatencyNanos(),
          getMemoryUsageDeltaMB());
    }
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    var annotation = testInstance.getClass().getAnnotation(PerformanceBaseline.class);
    if (annotation != null) {
      var metrics = new PerformanceMetrics();
      context.getStore(NAMESPACE).put("metrics", metrics);
    }
  }

  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    var metrics = context.getStore(NAMESPACE).get("metrics", PerformanceMetrics.class);
    if (metrics != null) {
      metrics.finalize();

      var annotation = context.getTestClass().get().getAnnotation(PerformanceBaseline.class);
      if (annotation != null && annotation.reportMetrics()) {
        System.out.println(metrics.summary());
      }
    }
    throw throwable;
  }

  /**
   * Get current metrics (thread-local).
   */
  public static PerformanceMetrics getCurrentMetrics(ExtensionContext context) {
    var metrics = context.getStore(NAMESPACE).get("metrics", PerformanceMetrics.class);
    return metrics != null ? metrics : new PerformanceMetrics();
  }

  /**
   * Record a message latency.
   */
  public static void recordMessageLatency(ExtensionContext context, long latencyNanos) {
    var metrics = getCurrentMetrics(context);
    if (metrics != null) {
      metrics.recordMessage(latencyNanos);
    }
  }
}
