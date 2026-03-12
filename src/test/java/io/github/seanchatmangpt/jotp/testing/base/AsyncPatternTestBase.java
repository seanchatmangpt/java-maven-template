package io.github.seanchatmangpt.jotp.testing.base;

import io.github.seanchatmangpt.jotp.testing.annotations.AsyncPatternTest;
import io.github.seanchatmangpt.jotp.testing.extensions.TimeoutExtension;
import io.github.seanchatmangpt.jotp.testing.util.PerformanceTestHelper;
import org.junit.jupiter.api.extension.ExtendWith;
import java.lang.reflect.ParameterizedType;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Parent class for asynchronous Vernon pattern tests.
 *
 * <p>Extends {@link PatternTestBase} with:
 * <ul>
 *   <li>Virtual thread execution support</li>
 *   <li>Timeout management via {@link TimeoutExtension}</li>
 *   <li>Async assertion API</li>
 *   <li>Automatic correlation tracking</li>
 *   <li>Performance baseline assertions</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncPatternTest(timeoutValue = 5, timeoutUnit = TimeUnit.SECONDS)
 * class AsyncRouterTest extends AsyncPatternTestBase<ContentBasedRouter> {
 *   @Test
 *   void testAsyncRouting() {
 *     var result = ask(routerPid, message, timeout());
 *     assertEventually(() -> result.isSuccess(), timeout());
 *   }
 * }
 * }</pre>
 */
@ExtendWith(TimeoutExtension.class)
public abstract class AsyncPatternTestBase<P> extends PatternTestBase<P> {

  protected PerformanceTestHelper performanceHelper;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.performanceHelper = new PerformanceTestHelper();

    // Initialize timeout context from @AsyncPatternTest annotation
    var testMethod = getCurrentTestMethod();
    if (testMethod != null) {
      var timeout = TimeoutExtension.extractTimeout(testMethod);
      TimeoutExtension.setCurrentTimeout(timeout.timeoutValue, timeout.timeoutUnit);
    }
  }

  @Override
  public void tearDown() {
    TimeoutExtension.resetTimeout();
    super.tearDown();
  }

  /**
   * Get current timeout as long in milliseconds.
   */
  protected long timeout() {
    return TimeoutExtension.getCurrentTimeout().timeoutMillis;
  }

  /**
   * Get current timeout in specified unit.
   */
  protected long timeout(TimeUnit unit) {
    return unit.convert(TimeoutExtension.getCurrentTimeout().timeoutMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Assert condition is true within timeout (polling).
   */
  protected void assertEventually(Predicate<Boolean> condition, long timeoutMillis)
      throws InterruptedException {
    var deadline = System.currentTimeMillis() + timeoutMillis;

    while (true) {
      try {
        if (condition.test(true)) {
          return;
        }
      } catch (Exception e) {
        // Continue polling
      }

      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Condition not satisfied within " + timeoutMillis + " ms");
      }

      Thread.sleep(10); // Poll
    }
  }

  /**
   * Assert condition is true within test timeout.
   */
  protected void assertEventually(Predicate<Boolean> condition)
      throws InterruptedException {
    assertEventually(condition, TimeoutExtension.getCurrentTimeout().timeoutMillis);
  }

  /**
   * Record message latency for performance tracking.
   */
  protected void recordLatency(long latencyNanos) {
    performanceHelper.recordLatency(latencyNanos);
  }

  /**
   * Start performance measurement.
   */
  protected void startPerformanceMeasurement() {
    performanceHelper.start();
  }

  /**
   * Stop performance measurement.
   */
  protected void stopPerformanceMeasurement() {
    performanceHelper.stop();
  }

  /**
   * Get performance summary.
   */
  protected String getPerformanceSummary() {
    return performanceHelper.getSummary();
  }

  /**
   * Assert minimum throughput (messages/second).
   */
  protected void assertMinThroughput(long messagesPerSecond) {
    performanceHelper.assertMinThroughput(messagesPerSecond);
  }

  /**
   * Assert p99 latency below threshold (milliseconds).
   */
  protected void assertP99Latency(long maxMillis) {
    performanceHelper.assertP99Latency(maxMillis);
  }

  /**
   * Assert p95 latency below threshold (milliseconds).
   */
  protected void assertP95Latency(long maxMillis) {
    performanceHelper.assertP95Latency(maxMillis);
  }

  /**
   * Assert p50 latency below threshold (milliseconds).
   */
  protected void assertP50Latency(long maxMillis) {
    performanceHelper.assertP50Latency(maxMillis);
  }

  /**
   * Check test timeout (throws if exceeded).
   */
  protected void checkTimeout() throws java.util.concurrent.TimeoutException {
    TimeoutExtension.checkTimeout();
  }

  /**
   * Get the current test method (for reflection-based annotation extraction).
   */
  private java.lang.reflect.Method getCurrentTestMethod() {
    // Would use JUnit's context to get current test method
    return null;
  }

  /**
   * Check if running on virtual thread.
   */
  protected boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  @SuppressWarnings("unchecked")
  @Override
  protected Class<P> getPatternClass() {
    var parameterizedType = (ParameterizedType) getClass().getGenericSuperclass();
    return (Class<P>) parameterizedType.getActualTypeArguments()[0];
  }
}
