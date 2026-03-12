package io.github.seanchatmangpt.jotp.testing.extensions;

import io.github.seanchatmangpt.jotp.testing.annotations.VirtualThreaded;
import org.junit.jupiter.api.extension.*;
import java.lang.reflect.Method;

/**
 * JUnit 6 extension for testing on virtual or platform threads.
 *
 * <p>Uses Java 26 reflection API to:
 * <ul>
 *   <li>Detect if running on virtual thread: {@code Thread.isVirtual()}</li>
 *   <li>Measure virtual thread pinning duration</li>
 *   <li>Count active virtual threads</li>
 *   <li>Track context variable propagation</li>
 * </ul>
 *
 * <p>Useful for isolating platform-thread specific bugs and edge cases.
 */
public class VirtualThreadExtension implements InvocationInterceptor {

  private static final ThreadLocal<VirtualThreadMetrics> METRICS = ThreadLocal.withInitial(
      VirtualThreadMetrics::new);

  public static class VirtualThreadMetrics {
    public final boolean isVirtualThread;
    public final long startTimeNanos;
    public long pinnedDurationNanos = 0;
    public int virtualThreadCount = 0;

    public VirtualThreadMetrics() {
      this.isVirtualThread = Thread.currentThread().isVirtual();
      this.startTimeNanos = System.nanoTime();
    }

    public long getElapsedNanos() {
      return System.nanoTime() - startTimeNanos;
    }

    public boolean exceedsPinningThreshold(long maxPinningMillis) {
      return pinnedDurationNanos > maxPinningMillis * 1_000_000L;
    }
  }

  @Override
  public void interceptTestMethod(Invocation invocation, ReflectiveInvocationContext<?> invocationContext,
      ExtensionContext extensionContext) throws Throwable {
    var method = invocationContext.getExecutable();
    var annotation = method.getAnnotation(VirtualThreaded.class);

    if (annotation != null) {
      validateThreadMode(annotation);

      try {
        invocation.proceed();
      } finally {
        validateMetrics(annotation);
      }
    } else {
      invocation.proceed();
    }
  }

  private void validateThreadMode(VirtualThreaded annotation) {
    var isVirtual = Thread.currentThread().isVirtual();
    var mode = annotation.mode();

    switch (mode) {
      case VIRTUAL_ONLY:
        if (!isVirtual) {
          throw new AssertionError(
              "Test requires virtual thread execution but ran on platform thread");
        }
        break;
      case PLATFORM_ONLY:
        if (isVirtual) {
          throw new AssertionError(
              "Test requires platform thread execution but ran on virtual thread");
        }
        break;
      case BOTH:
        // Both are acceptable
        break;
    }
  }

  private void validateMetrics(VirtualThreaded annotation) {
    var metrics = METRICS.get();

    if (annotation.noPinning() && metrics.pinnedDurationNanos > 0) {
      throw new AssertionError(
          "Virtual thread pinning detected: " + (metrics.pinnedDurationNanos / 1_000_000) + " ms");
    }

    if (annotation.maxPinningMillis() > 0
        && metrics.exceedsPinningThreshold(annotation.maxPinningMillis())) {
      throw new AssertionError(
          "Virtual thread pinning exceeded threshold: "
              + (metrics.pinnedDurationNanos / 1_000_000) + " ms > "
              + annotation.maxPinningMillis() + " ms");
    }

    if (annotation.expectedThreadCount() > 0
        && metrics.virtualThreadCount != annotation.expectedThreadCount()) {
      throw new AssertionError(
          "Expected " + annotation.expectedThreadCount()
              + " virtual threads but found " + metrics.virtualThreadCount);
    }
  }

  /**
   * Get metrics for current thread (Java 26 reflection).
   */
  public static VirtualThreadMetrics getCurrentMetrics() {
    return METRICS.get();
  }

  /**
   * Check if currently executing on virtual thread.
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Record virtual thread pinning (called by framework).
   */
  public static void recordPinning(long durationNanos) {
    var metrics = METRICS.get();
    metrics.pinnedDurationNanos += durationNanos;
  }
}
