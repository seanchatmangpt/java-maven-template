package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Marks a test with performance baseline assertions.
 *
 * <p>Automatically:
 * <ul>
 *   <li>Measures throughput (messages/second)</li>
 *   <li>Measures latency percentiles (p50, p95, p99)</li>
 *   <li>Tracks GC impact and memory usage</li>
 *   <li>Reports virtual thread pinning duration</li>
 *   <li>Asserts against baseline thresholds</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @PerformanceBaseline(
 *   messagesPerSecond = 100_000,
 *   p99LatencyMillis = 50,
 *   maxMemoryMB = 256
 * )
 * class RouterPerformanceTest {
 *   @Test
 *   void testHighThroughputRouting() { ... }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface PerformanceBaseline {

  /**
   * Minimum required throughput in messages/second.
   *
   * @return threshold (0 = no throughput assertion)
   */
  long messagesPerSecond() default 0;

  /**
   * Maximum acceptable p99 latency in milliseconds.
   *
   * @return p99 latency threshold (0 = no assertion)
   */
  long p99LatencyMillis() default 0;

  /**
   * Maximum acceptable p95 latency in milliseconds.
   *
   * @return p95 latency threshold (0 = no assertion)
   */
  long p95LatencyMillis() default 0;

  /**
   * Maximum acceptable p50 (median) latency in milliseconds.
   *
   * @return p50 latency threshold (0 = no assertion)
   */
  long p50LatencyMillis() default 0;

  /**
   * Maximum memory usage in MB during test.
   *
   * @return memory threshold (0 = no limit)
   */
  int maxMemoryMB() default 0;

  /**
   * Maximum acceptable GC pause in milliseconds.
   *
   * @return gc pause threshold (0 = no assertion)
   */
  long maxGcPauseMillis() default 0;

  /**
   * Enable JFR (Java Flight Recorder) profiling.
   *
   * @return true to enable JFR
   */
  boolean enableJFR() default false;

  /**
   * Report performance metrics even if thresholds are met.
   *
   * @return true to always report
   */
  boolean reportMetrics() default false;
}
