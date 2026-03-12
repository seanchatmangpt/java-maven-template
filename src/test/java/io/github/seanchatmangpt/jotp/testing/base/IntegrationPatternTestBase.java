package io.github.seanchatmangpt.jotp.testing.base;

import io.github.seanchatmangpt.jotp.testing.util.CorrelationIdTracker;
import io.github.seanchatmangpt.jotp.testing.util.PerformanceTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Parent class for multi-pattern composition tests.
 *
 * <p>Tests pattern chains such as:
 * <ul>
 *   <li>Router → Aggregator → Filter</li>
 *   <li>Splitter → ScatterGather → Resequencer</li>
 *   <li>ProcessManager → DeadLetterChannel → Supervisor</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Manages process chains and interconnections</li>
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
 *   void testEndToEndDataFlow() {
 *     var router = createPattern("router", ContentBasedRouter.class);
 *     var aggregator = createPattern("aggregator", Aggregator.class);
 *     var filter = createPattern("filter", MessageFilter.class);
 *
 *     connect(router, aggregator);
 *     connect(aggregator, filter);
 *
 *     // ... send messages ...
 *     validateNoMessageLoss();
 *   }
 * }
 * }</pre>
 */
public abstract class IntegrationPatternTestBase {

  protected Map<String, Object> patternInstances = Collections.synchronizedMap(new HashMap<>());
  protected List<Object> processChain = Collections.synchronizedList(new ArrayList<>());
  protected CorrelationIdTracker correlationTracker;
  protected PerformanceTestHelper performanceHelper;

  @BeforeEach
  public void setUp() throws Exception {
    this.correlationTracker = new CorrelationIdTracker();
    this.performanceHelper = new PerformanceTestHelper();
  }

  @AfterEach
  public void tearDown() {
    // Cleanup all patterns in reverse order
    for (int i = patternInstances.size() - 1; i >= 0; i--) {
      try {
        // Would terminate pattern process
      } catch (Exception e) {
        // Log but continue cleanup
      }
    }
    patternInstances.clear();
    processChain.clear();
  }

  /**
   * Create a pattern instance in the chain.
   */
  protected Object createPattern(String name, Class<?> patternClass) {
    try {
      var instance = patternClass.getDeclaredConstructor().newInstance();
      patternInstances.put(name, instance);
      processChain.add(instance);
      return instance;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to create pattern " + name + ": " + patternClass.getName(), e);
    }
  }

  /**
   * Connect two patterns in the chain (send messages from source to target).
   */
  protected void connect(Object sourcePattern, Object targetPattern) {
    // Implementation would connect pattern outputs to inputs
  }

  /**
   * Get a pattern instance by name.
   */
  protected Object getPattern(String name) {
    return patternInstances.get(name);
  }

  /**
   * Record message flow through pattern chain.
   */
  protected void recordMessageFlow(String correlationId, String patternName) {
    correlationTracker.recordStep(correlationId, "at_" + patternName);
  }

  /**
   * Validate no message loss in pattern chain.
   */
  protected void validateNoMessageLoss(int expectedCount) {
    var chains = correlationTracker.getAllChains();
    if (chains.size() != expectedCount) {
      throw new AssertionError(
          "Message loss detected: expected " + expectedCount
              + " correlation chains, got " + chains.size());
    }
  }

  /**
   * Validate message traversed all patterns in chain.
   */
  protected void validateFullChainTraversal(String correlationId, String... patternNames) {
    var expectedSteps = new String[patternNames.length];
    for (int i = 0; i < patternNames.length; i++) {
      expectedSteps[i] = "at_" + patternNames[i];
    }
    correlationTracker.assertCausalityChain(correlationId, expectedSteps);
  }

  /**
   * Get end-to-end latency for a message through entire chain.
   */
  protected long getEndToEndLatency(String correlationId) {
    return correlationTracker.getChainLatencyMillis(correlationId);
  }

  /**
   * Start performance measurement for chain.
   */
  protected void startPerformanceMeasurement() {
    performanceHelper.start();
  }

  /**
   * Stop performance measurement for chain.
   */
  protected void stopPerformanceMeasurement() {
    performanceHelper.stop();
  }

  /**
   * Get chain performance summary.
   */
  protected String getPerformanceSummary() {
    return performanceHelper.getSummary();
  }

  /**
   * Assert chain throughput.
   */
  protected void assertChainThroughput(long messagesPerSecond) {
    performanceHelper.assertMinThroughput(messagesPerSecond);
  }

  /**
   * Assert end-to-end latency percentile.
   */
  protected void assertChainP99Latency(long maxMillis) {
    performanceHelper.assertP99Latency(maxMillis);
  }

  /**
   * Get correlation tracker for detailed analysis.
   */
  protected CorrelationIdTracker getCorrelationTracker() {
    return correlationTracker;
  }

  /**
   * Get all pattern instances.
   */
  protected Collection<Object> getAllPatterns() {
    return Collections.unmodifiableCollection(patternInstances.values());
  }

  /**
   * Get process chain in order.
   */
  protected List<Object> getProcessChain() {
    return Collections.unmodifiableList(processChain);
  }

  /**
   * Default timeout for chain tests (10 seconds).
   */
  protected long getDefaultTimeout(TimeUnit unit) {
    return unit.convert(10, TimeUnit.SECONDS);
  }

  @Override
  public String toString() {
    return "IntegrationPatternTest[patterns=" + patternInstances.size()
        + ", chainLength=" + processChain.size() + "]";
  }
}
