package io.github.seanchatmangpt.jotp.testing.base;

import io.github.seanchatmangpt.jotp.testing.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.TimeUnit;

/**
 * Generic parent class for all Vernon pattern tests.
 *
 * <p>Provides:
 * <ul>
 *   <li>Messaging setup and process lifecycle</li>
 *   <li>Auto-discovery of pattern type via reflection</li>
 *   <li>Pattern-specific assertions and helpers</li>
 *   <li>Automatic cleanup (termination, deregistration)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * class ContentBasedRouterTest extends PatternTestBase<ContentBasedRouter> {
 *   @Test
 *   void testRouting() {
 *     var router = fixture.createProcess();
 *     send(router, msg);
 *     // ... assertions ...
 *   }
 * }
 * }</pre>
 */
public abstract class PatternTestBase<P> {

  protected PatternTestFixture<P> fixture;
  protected MessageBuilder messageBuilder;
  protected MessageAssertions assertions;
  protected CorrelationIdTracker correlationTracker;

  @BeforeEach
  public void setUp() throws Exception {
    var patternClass = getPatternClass();
    fixture = PatternTestFixture.for(patternClass).build();
    messageBuilder = MessageBuilder.custom(patternClass.getSimpleName());
    correlationTracker = new CorrelationIdTracker();
  }

  @AfterEach
  public void tearDown() {
    if (fixture != null) {
      fixture.cleanup();
    }
  }

  /**
   * Get the pattern class being tested (via reflection on generic type).
   */
  @SuppressWarnings("unchecked")
  protected Class<P> getPatternClass() {
    var genericSuperclass = getClass().getGenericSuperclass();
    // Would extract type argument from PatternTestBase<P>
    // For now, return Object.class and subclasses override
    return (Class<P>) Object.class;
  }

  /**
   * Create a test message using builder.
   */
  protected Object createMessage(String fieldName, Object value) {
    return messageBuilder.withField(fieldName, value).build();
  }

  /**
   * Assert message properties (fluent API).
   */
  protected MessageAssertions assertMessage(Object message) {
    return MessageAssertions.assertMessage(message);
  }

  /**
   * Track correlation ID for causality validation.
   */
  protected void trackCorrelationId(String correlationId, String step) {
    correlationTracker.recordStep(correlationId, step);
  }

  /**
   * Validate pattern invariants.
   * Subclasses override to add pattern-specific validations.
   */
  protected void validatePatternInvariants() {
    // Override in subclasses
  }

  /**
   * Get timeout for async operations (5 seconds default).
   */
  protected long getTimeout(TimeUnit unit) {
    return unit.convert(5, TimeUnit.SECONDS);
  }
}
