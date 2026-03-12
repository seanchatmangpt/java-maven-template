package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Forces test execution on virtual or platform threads.
 *
 * <p>Useful for isolating platform-thread specific bugs and edge cases.
 *
 * <p>Uses Java 26 reflection API to:
 * <ul>
 *   <li>Detect if running on virtual thread: {@code Thread.isVirtual()}</li>
 *   <li>Measure virtual thread pinning duration</li>
 *   <li>Count active virtual threads</li>
 *   <li>Track context variable propagation</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @VirtualThreaded(mode = ThreadMode.VIRTUAL_ONLY)
 * class VirtualThreadRouterTest {
 *   @Test
 *   void testOnVirtualThreads() { ... }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface VirtualThreaded {

  enum ThreadMode {
    /** Run only on virtual threads */
    VIRTUAL_ONLY,
    /** Run only on platform threads */
    PLATFORM_ONLY,
    /** Run on both (parameterized test) */
    BOTH
  }

  /**
   * Thread mode.
   *
   * @return mode (default: VIRTUAL_ONLY)
   */
  ThreadMode mode() default ThreadMode.VIRTUAL_ONLY;

  /**
   * Maximum acceptable virtual thread pinning duration in milliseconds.
   *
   * @return pinning threshold (0 = no limit)
   */
  long maxPinningMillis() default 0;

  /**
   * Assert no pinning occurs (fails if any pinning detected).
   *
   * @return true to fail on pinning
   */
  boolean noPinning() default false;

  /**
   * Expected number of virtual threads (for concurrency tests).
   *
   * @return thread count (0 = no assertion)
   */
  int expectedThreadCount() default 0;

  /**
   * Track context variables across thread boundaries.
   *
   * @return true to validate context propagation
   */
  boolean validateContextPropagation() default false;
}
