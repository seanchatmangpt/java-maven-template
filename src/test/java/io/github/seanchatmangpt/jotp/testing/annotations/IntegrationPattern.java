package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Marks a multi-pattern composition test.
 *
 * <p>Tests pattern chains such as:
 * <ul>
 *   <li>Router → Aggregator → Filter</li>
 *   <li>Splitter → ScatterGather → Resequencer</li>
 *   <li>ProcessManager → DeadLetterChannel → Supervisor</li>
 * </ul>
 *
 * <p>Automatically:
 * <ul>
 *   <li>Manages process chains and connections</li>
 *   <li>Validates data flow through pattern boundaries</li>
 *   <li>Tracks messages across all processes</li>
 *   <li>Validates end-to-end causality</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @IntegrationPattern(
 *   patterns = {"ContentBasedRouter", "Aggregator", "MessageFilter"},
 *   description = "Route, aggregate, and filter messages"
 * )
 * class RouterAggregatorFilterTest extends IntegrationPatternTestBase {
 *   @Test
 *   void testEndToEndDataFlow() { ... }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface IntegrationPattern {

  /**
   * Names of patterns in composition chain (in order).
   *
   * @return pattern names (e.g., ["Router", "Aggregator", "Filter"])
   */
  String[] patterns();

  /**
   * Description of the integration scenario.
   *
   * @return scenario description
   */
  String description() default "";

  /**
   * Validate data integrity through pattern chain.
   *
   * @return true to validate no message loss
   */
  boolean validateDataIntegrity() default true;

  /**
   * Maximum chain depth (number of hops).
   *
   * @return depth limit (0 = unlimited)
   */
  int maxChainDepth() default 0;

  /**
   * Timeout for end-to-end message completion in seconds.
   *
   * @return timeout (0 = use default)
   */
  long timeoutSeconds() default 0;

  /**
   * Enable comprehensive tracing of all messages in chain.
   *
   * @return true to trace all message paths
   */
  boolean enableFullTracing() default false;

  /**
   * Validate causality chains across all patterns.
   *
   * @return true to validate correlation IDs
   */
  boolean validateCausality() default true;

  /**
   * Expected number of output messages per input (validation).
   *
   * @return -1 = unknown, 0 = same count, >0 = specific count
   */
  int expectedOutputCount() default -1;
}
