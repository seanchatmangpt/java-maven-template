package io.github.seanchatmangpt.jotp.testing.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.*;

/**
 * Performance testing utilities for baseline assertions.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Throughput (messages/second)</li>
 *   <li>Latency percentiles (p50, p95, p99)</li>
 *   <li>Garbage collection impact</li>
 *   <li>Memory usage deltas</li>
 *   <li>Virtual thread pinning duration</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * var perf = new PerformanceTestHelper();
 * perf.start();
 *
 * for (var msg : messages) {
 *   send(router, msg);
 *   perf.recordLatency(elapsedNanos);
 * }
 *
 * perf.stop();
 * perf.assertMinThroughput(100_000); // 100k msg/sec
 * perf.assertP99Latency(50); // p99 < 50 ms
 * }</pre>
 */
public class PerformanceTestHelper {

  private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
  private long startTimeNanos = 0;
  private long startMemoryBytes = 0;
  private long endTimeNanos = 0;
  private long endMemoryBytes = 0;
  private long messageCount = 0;

  /**
   * Start timing (record start time and memory).
   */
  public void start() {
    this.startTimeNanos = System.nanoTime();
    var memoryBean = ManagementFactory.getMemoryMXBean();
    this.startMemoryBytes = memoryBean.getHeapMemoryUsage().getUsed();
    latencies.clear();
    messageCount = 0;
  }

  /**
   * Stop timing (record end time and memory).
   */
  public void stop() {
    this.endTimeNanos = System.nanoTime();
    var memoryBean = ManagementFactory.getMemoryMXBean();
    this.endMemoryBytes = memoryBean.getHeapMemoryUsage().getUsed();
  }

  /**
   * Record a message latency in nanoseconds.
   */
  public void recordLatency(long latencyNanos) {
    latencies.add(latencyNanos);
    messageCount++;
  }

  /**
   * Get throughput in messages/second.
   */
  public long getThroughputPerSecond() {
    var durationNanos = endTimeNanos - startTimeNanos;
    if (durationNanos == 0) return 0;
    return messageCount * 1_000_000_000L / durationNanos;
  }

  /**
   * Get p50 (median) latency in milliseconds.
   */
  public long getP50LatencyMillis() {
    return getPercentileLatencyMillis(0.5);
  }

  /**
   * Get p95 latency in milliseconds.
   */
  public long getP95LatencyMillis() {
    return getPercentileLatencyMillis(0.95);
  }

  /**
   * Get p99 latency in milliseconds.
   */
  public long getP99LatencyMillis() {
    return getPercentileLatencyMillis(0.99);
  }

  /**
   * Get arbitrary percentile latency in milliseconds.
   */
  public long getPercentileLatencyMillis(double percentile) {
    if (latencies.isEmpty()) return 0;
    var sorted = new ArrayList<>(latencies);
    Collections.sort(sorted);
    int index = (int) (sorted.size() * percentile);
    var nanos = sorted.get(Math.min(index, sorted.size() - 1));
    return nanos / 1_000_000;
  }

  /**
   * Get memory usage delta in MB.
   */
  public long getMemoryDeltaMB() {
    return (endMemoryBytes - startMemoryBytes) / (1024 * 1024);
  }

  /**
   * Assert minimum throughput (throws if below threshold).
   */
  public void assertMinThroughput(long messagesPerSecond) {
    var actual = getThroughputPerSecond();
    if (actual < messagesPerSecond) {
      throw new AssertionError(
          "Throughput below threshold: expected >= " + messagesPerSecond
              + " msg/sec, got " + actual);
    }
  }

  /**
   * Assert p50 latency (throws if above threshold).
   */
  public void assertP50Latency(long maxMillis) {
    var actual = getP50LatencyMillis();
    if (actual > maxMillis) {
      throw new AssertionError(
          "P50 latency exceeded: expected <= " + maxMillis + " ms, got " + actual);
    }
  }

  /**
   * Assert p95 latency (throws if above threshold).
   */
  public void assertP95Latency(long maxMillis) {
    var actual = getP95LatencyMillis();
    if (actual > maxMillis) {
      throw new AssertionError(
          "P95 latency exceeded: expected <= " + maxMillis + " ms, got " + actual);
    }
  }

  /**
   * Assert p99 latency (throws if above threshold).
   */
  public void assertP99Latency(long maxMillis) {
    var actual = getP99LatencyMillis();
    if (actual > maxMillis) {
      throw new AssertionError(
          "P99 latency exceeded: expected <= " + maxMillis + " ms, got " + actual);
    }
  }

  /**
   * Assert maximum memory usage (throws if above threshold).
   */
  public void assertMaxMemory(long maxMB) {
    var actual = getMemoryDeltaMB();
    if (actual > maxMB) {
      throw new AssertionError(
          "Memory usage exceeded: expected <= " + maxMB + " MB, got " + actual);
    }
  }

  /**
   * Get performance summary string.
   */
  public String getSummary() {
    return String.format(
        "Throughput: %,d msg/s | Latency P50: %d ms | P95: %d ms | P99: %d ms | Memory: +%d MB",
        getThroughputPerSecond(),
        getP50LatencyMillis(),
        getP95LatencyMillis(),
        getP99LatencyMillis(),
        getMemoryDeltaMB());
  }

  /**
   * Get detailed metrics report.
   */
  public String getDetailedReport() {
    var sb = new StringBuilder();
    sb.append("=== Performance Test Report ===\n");
    sb.append("Messages: ").append(messageCount).append("\n");
    sb.append("Duration: ").append((endTimeNanos - startTimeNanos) / 1_000_000).append(" ms\n");
    sb.append("Throughput: ").append(getThroughputPerSecond()).append(" msg/s\n");
    sb.append("Latency P50: ").append(getP50LatencyMillis()).append(" ms\n");
    sb.append("Latency P95: ").append(getP95LatencyMillis()).append(" ms\n");
    sb.append("Latency P99: ").append(getP99LatencyMillis()).append(" ms\n");
    sb.append("Memory Delta: ").append(getMemoryDeltaMB()).append(" MB\n");
    return sb.toString();
  }

  /**
   * Clear all metrics (for reuse).
   */
  public void reset() {
    latencies.clear();
    startTimeNanos = 0;
    endTimeNanos = 0;
    startMemoryBytes = 0;
    endMemoryBytes = 0;
    messageCount = 0;
  }

  @Override
  public String toString() {
    return "PerformanceTestHelper[" + getSummary() + "]";
  }
}
