package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Auto-generates Proc/Supervisor test fixtures via Java 26 reflection API.
 *
 * <p>The framework:
 * <ul>
 *   <li>Reflects on sealed Message hierarchy to discover types</li>
 *   <li>Creates test factories for each message variant</li>
 *   <li>Automatically cleans up processes (termination, deregistration)</li>
 *   <li>Supports supervisor supervision strategies</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @ProcessFixture(ContentBasedRouter.class)
 * class RouterTest {
 *   @Inject ProcessFixture<ContentBasedRouter> fixture;
 *
 *   @Test
 *   void test() {
 *     var router = fixture.createProcess();
 *     var pid = fixture.pid();
 *     // ... test ...
 *     fixture.cleanup(); // Auto-called by extension
 *   }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
@Documented
public @interface ProcessFixture {

  /**
   * The process/pattern class to create fixtures for.
   *
   * @return class object (must have Proc-compatible constructor)
   */
  Class<?> value();

  /**
   * Number of process instances to create.
   *
   * @return instance count (default: 1)
   */
  int instances() default 1;

  /**
   * Supervision strategy (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE).
   *
   * @return strategy name
   */
  String supervisionStrategy() default "ONE_FOR_ONE";

  /**
   * Auto-cleanup after test (default: true).
   *
   * @return true to auto-terminate processes
   */
  boolean autoCleanup() default true;

  /**
   * Enable message capturing during fixture lifecycle.
   *
   * @return true to capture all messages
   */
  boolean captureMessages() default false;

  /**
   * Register process in ProcessRegistry (default: false).
   *
   * @return true to auto-register
   */
  boolean registerInRegistry() default false;

  /**
   * Registry name (if registerInRegistry = true).
   *
   * @return registered name
   */
  String registryName() default "";
}
