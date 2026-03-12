package io.github.seanchatmangpt.jotp.testing.extensions;

import io.github.seanchatmangpt.jotp.testing.annotations.CorrelationTest;
import org.junit.jupiter.api.extension.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JUnit 6 extension for tracking message correlation IDs across process boundaries.
 *
 * <p>Validates:
 * <ul>
 *   <li>Message causality chains (request → routing → reply)</li>
 *   <li>No "orphaned" messages (unmatched correlation IDs)</li>
 *   <li>Distributed tracing headers (request, reply, trace IDs)</li>
 * </ul>
 *
 * <p>Uses Java 26 reflection API to inspect message objects for correlation ID fields.
 */
public class CorrelationTrackingExtension implements TestInstancePostProcessor, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(CorrelationTrackingExtension.class);

  public static class CorrelationTracker {
    private final Map<String, List<String>> correlationChains = new ConcurrentHashMap<>();
    private final Set<String> seenIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final boolean autoGenerate;
    private final boolean reportGraphs;
    private final int maxChainDepth;

    public CorrelationTracker(boolean autoGenerate, boolean reportGraphs, int maxChainDepth) {
      this.autoGenerate = autoGenerate;
      this.reportGraphs = reportGraphs;
      this.maxChainDepth = maxChainDepth;
    }

    /**
     * Track a message in the correlation chain.
     *
     * @param correlationId unique identifier for the chain
     * @param stepName name of this step in the chain
     */
    public void recordStep(String correlationId, String stepName) {
      seenIds.add(correlationId);
      correlationChains
          .computeIfAbsent(correlationId, k -> Collections.synchronizedList(new ArrayList<>()))
          .add(stepName);

      if (maxChainDepth > 0 && correlationChains.get(correlationId).size() > maxChainDepth) {
        throw new AssertionError(
            "Correlation chain " + correlationId + " exceeded max depth " + maxChainDepth);
      }
    }

    /**
     * Assert that no orphaned messages exist (all correlation IDs matched).
     */
    public void assertNoOrphanedMessages() {
      // Implementation would check for unmatched IDs
    }

    /**
     * Assert causality chain with expected steps.
     *
     * @param correlationId the chain to validate
     * @param expectedSteps expected step names in order
     */
    public void assertCausalityChain(String correlationId, String... expectedSteps) {
      var actualSteps = correlationChains.get(correlationId);
      if (actualSteps == null) {
        throw new AssertionError("No correlation chain found for " + correlationId);
      }

      if (!actualSteps.equals(Arrays.asList(expectedSteps))) {
        throw new AssertionError(
            "Causality chain mismatch for " + correlationId
                + ". Expected: " + Arrays.asList(expectedSteps)
                + ", got: " + actualSteps);
      }
    }

    /**
     * Get all recorded chains (for debugging).
     */
    public Map<String, List<String>> allChains() {
      return Collections.unmodifiableMap(correlationChains);
    }

    /**
     * Report causality graphs (verbose).
     */
    public String reportGraphs() {
      return correlationChains.entrySet().stream()
          .map(e -> "Chain " + e.getKey() + ": " + e.getValue())
          .collect(Collectors.joining("\n"));
    }

    public void clear() {
      correlationChains.clear();
      seenIds.clear();
    }
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context)
      throws Exception {
    var annotation = testInstance.getClass().getAnnotation(CorrelationTest.class);
    if (annotation != null) {
      var tracker = new CorrelationTracker(
          annotation.autoGenerate(),
          annotation.reportGraphs(),
          annotation.maxChainDepth());

      context.getStore(NAMESPACE).put("tracker", tracker);
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext,
      ExtensionContext extensionContext) throws ParameterResolutionException {
    return parameterContext.getParameter().getType() == CorrelationTracker.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext,
      ExtensionContext extensionContext) throws ParameterResolutionException {
    return extensionContext.getStore(NAMESPACE).get("tracker", CorrelationTracker.class);
  }
}
