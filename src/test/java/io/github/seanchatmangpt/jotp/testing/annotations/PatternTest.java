package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Marks a test class or method as testing a specific Vernon messaging pattern.
 *
 * <p>Usage:
 * <pre>{@code
 * @PatternTest(pattern = "ContentBasedRouter")
 * class ContentBasedRouterTest {
 *   @Test
 *   void testRoutesByMessageContent() { ... }
 * }
 * }</pre>
 *
 * <p>The framework uses this annotation to:
 * <ul>
 *   <li>Auto-discover pattern type via reflection</li>
 *   <li>Validate pattern invariants</li>
 *   <li>Generate pattern-specific fixtures</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface PatternTest {

  /**
   * The name of the Vernon pattern being tested.
   *
   * @return pattern name (e.g., "ContentBasedRouter", "Aggregator", "DeadLetterChannel")
   */
  String pattern();

  /**
   * Pattern category for organization and filtering.
   *
   * @return category (default: auto-detect from pattern name)
   */
  String category() default "";

  /**
   * Whether this pattern is asynchronous (default: true).
   *
   * @return true if pattern operates asynchronously
   */
  boolean async() default true;

  /**
   * Optional description of what this test validates.
   *
   * @return test description
   */
  String description() default "";
}
