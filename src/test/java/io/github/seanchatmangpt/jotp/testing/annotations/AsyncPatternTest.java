package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * Marks a test for asynchronous Vernon patterns with virtual thread + timeout support.
 *
 * <p>Automatically:
 * <ul>
 *   <li>Wraps test in timeout (prevents infinite hangs)</li>
 *   <li>Enables {@code ask(msg, timeout)} pattern testing</li>
 *   <li>Supports virtual thread execution</li>
 *   <li>Provides async assertion helpers</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncPatternTest(timeoutValue = 5, timeoutUnit = TimeUnit.SECONDS)
 * class RouterTest extends AsyncPatternTestBase<ContentBasedRouter> {
 *   @Test
 *   void testAsyncRouting() {
 *     var result = ask(routerPid, message, timeout);
 *     assertThat(result).isSuccess();
 *   }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@PatternTest(async = true)
public @interface AsyncPatternTest {

  /**
   * Timeout value for test execution.
   *
   * @return numeric timeout value
   */
  long timeoutValue() default 5;

  /**
   * Unit of timeout.
   *
   * @return TimeUnit (default: SECONDS)
   */
  TimeUnit timeoutUnit() default TimeUnit.SECONDS;

  /**
   * Pattern name (same as {@code @PatternTest.pattern}).
   *
   * @return pattern name
   */
  String pattern() default "";

  /**
   * Enable virtual thread execution (Java 21+).
   *
   * @return true to run on virtual threads exclusively
   */
  boolean virtualThreadOnly() default false;

  /**
   * Enable correlation ID tracking during test.
   *
   * @return true to automatically track correlation IDs
   */
  boolean trackCorrelationIds() default true;
}
