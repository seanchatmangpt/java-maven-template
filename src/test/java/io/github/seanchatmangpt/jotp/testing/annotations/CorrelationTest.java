package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Marks a test that validates message correlation ID tracking across process boundaries.
 *
 * <p>Automatically:
 * <ul>
 *   <li>Injects {@code CorrelationIdTracker} for causality validation</li>
 *   <li>Tracks request → routing → reply chains</li>
 *   <li>Validates no "orphaned" messages</li>
 *   <li>Reports causality graphs for debugging</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @CorrelationTest
 * class ProcessManagerTest {
 *   @Inject CorrelationIdTracker tracker;
 *
 *   @Test
 *   void testMultiStepWorkflowCorrelation() {
 *     var correlationId = UUID.randomUUID();
 *     send(processManager, startMsg.withCorrelationId(correlationId));
 *
 *     tracker.assertNoOrphanedMessages();
 *     tracker.assertCausalityChain(correlationId, "step1", "step2", "step3");
 *   }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface CorrelationTest {

  /**
   * Enable automatic correlation ID generation for tests.
   *
   * @return true to auto-generate correlation IDs
   */
  boolean autoGenerate() default true;

  /**
   * Report causality graphs (verbose).
   *
   * @return true to generate causality graphs on failure
   */
  boolean reportGraphs() default false;

  /**
   * Maximum expected depth in causality chain.
   *
   * @return max chain depth (0 = unlimited)
   */
  int maxChainDepth() default 0;

  /**
   * Validate distributed tracing (request, reply, trace IDs).
   *
   * @return true to validate tracing headers
   */
  boolean validateDistributedTracing() default true;
}
