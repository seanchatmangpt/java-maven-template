package io.github.seanchatmangpt.jotp.testing.extensions;

import io.github.seanchatmangpt.jotp.testing.annotations.AsyncPatternTest;
import org.junit.jupiter.api.extension.*;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * JUnit 6 extension that manages global test timeouts for async patterns.
 *
 * <p>Features:
 * <ul>
 *   <li>Prevents infinite hangs in async tests</li>
 *   <li>Configurable per-test via {@code @AsyncPatternTest}</li>
 *   <li>Supports {@code ask()} timeout patterns</li>
 *   <li>Async assertion timeout management</li>
 * </ul>
 *
 * <p>Uses thread timeout mechanisms and context-local timeout tracking.
 */
public class TimeoutExtension implements TestExecutionExceptionHandler {

  private static final ThreadLocal<TimeoutContext> TIMEOUT_CONTEXT = ThreadLocal.withInitial(
      () -> new TimeoutContext(5, TimeUnit.SECONDS));

  public static class TimeoutContext {
    public final long timeoutValue;
    public final TimeUnit timeoutUnit;
    public final long timeoutMillis;
    public final long startTimeMillis;

    public TimeoutContext(long timeoutValue, TimeUnit timeoutUnit) {
      this.timeoutValue = timeoutValue;
      this.timeoutUnit = timeoutUnit;
      this.timeoutMillis = timeoutUnit.toMillis(timeoutValue);
      this.startTimeMillis = System.currentTimeMillis();
    }

    public long getRemainingMillis() {
      return timeoutMillis - (System.currentTimeMillis() - startTimeMillis);
    }

    public boolean isExpired() {
      return getRemainingMillis() <= 0;
    }
  }

  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    // Re-throw the original exception if it's a timeout
    if (throwable instanceof java.util.concurrent.TimeoutException) {
      throw new AssertionError(
          "Test exceeded timeout. See cause for details.", throwable);
    }
    throw throwable;
  }

  /**
   * Get the current timeout context for the test.
   */
  public static TimeoutContext getCurrentTimeout() {
    return TIMEOUT_CONTEXT.get();
  }

  /**
   * Set timeout for the current test.
   */
  public static void setCurrentTimeout(long value, TimeUnit unit) {
    TIMEOUT_CONTEXT.set(new TimeoutContext(value, unit));
  }

  /**
   * Reset timeout to default (5 seconds).
   */
  public static void resetTimeout() {
    TIMEOUT_CONTEXT.remove();
  }

  /**
   * Check if test has exceeded timeout and throw if so.
   */
  public static void checkTimeout() throws java.util.concurrent.TimeoutException {
    if (getCurrentTimeout().isExpired()) {
      throw new java.util.concurrent.TimeoutException(
          "Test timeout exceeded: "
              + getCurrentTimeout().timeoutValue
              + " " + getCurrentTimeout().timeoutUnit);
    }
  }

  /**
   * Extract timeout from {@code @AsyncPatternTest} annotation.
   */
  public static TimeoutContext extractTimeout(Method method) {
    var annotation = method.getAnnotation(AsyncPatternTest.class);
    if (annotation != null) {
      return new TimeoutContext(annotation.timeoutValue(), annotation.timeoutUnit());
    }
    return new TimeoutContext(5, TimeUnit.SECONDS);
  }
}
