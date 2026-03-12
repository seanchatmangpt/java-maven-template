package io.github.seanchatmangpt.jotp.testing.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks message causality across process boundaries via correlation IDs.
 *
 * <p>Records:
 * <ul>
 *   <li>Request → routing → aggregation → reply chains</li>
 *   <li>Validates no "orphaned" messages</li>
 *   <li>Reports causality graphs for debugging</li>
 *   <li>Validates distributed tracing headers</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * var tracker = new CorrelationIdTracker();
 * tracker.recordStep(correlationId, "received");
 * tracker.recordStep(correlationId, "routed");
 * tracker.recordStep(correlationId, "aggregated");
 * tracker.assertCausalityChain(correlationId, "received", "routed", "aggregated");
 * }</pre>
 */
public class CorrelationIdTracker {

  private final Map<String, CorrelationChain> chains = new ConcurrentHashMap<>();
  private final Map<String, Long> correlationTimestamps = new ConcurrentHashMap<>();

  public static class CorrelationChain {
    public final String correlationId;
    public final List<ChainStep> steps = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, String> distributedTracingHeaders = new ConcurrentHashMap<>();

    public CorrelationChain(String correlationId) {
      this.correlationId = correlationId;
    }

    public void recordStep(String stepName, Object processRef) {
      steps.add(new ChainStep(stepName, processRef, System.currentTimeMillis()));
    }

    public void addTracingHeader(String headerName, String headerValue) {
      distributedTracingHeaders.put(headerName, headerValue);
    }

    public boolean isEmpty() {
      return steps.isEmpty();
    }

    public List<String> getStepNames() {
      return steps.stream().map(s -> s.stepName).collect(Collectors.toList());
    }

    @Override
    public String toString() {
      return "Chain[" + correlationId + "]: " + getStepNames();
    }
  }

  public static class ChainStep {
    public final String stepName;
    public final Object processRef;
    public final long timestamp;

    public ChainStep(String stepName, Object processRef, long timestamp) {
      this.stepName = stepName;
      this.processRef = processRef;
      this.timestamp = timestamp;
    }

    @Override
    public String toString() {
      return stepName + "@" + processRef;
    }
  }

  /**
   * Record a step in the correlation chain.
   */
  public void recordStep(String correlationId, String stepName) {
    recordStep(correlationId, stepName, null);
  }

  /**
   * Record a step with process reference.
   */
  public void recordStep(String correlationId, String stepName, Object processRef) {
    chains.computeIfAbsent(correlationId, CorrelationChain::new)
        .recordStep(stepName, processRef);
    correlationTimestamps.put(correlationId, System.currentTimeMillis());
  }

  /**
   * Add distributed tracing header (request-id, trace-id, span-id, etc.).
   */
  public void addTracingHeader(String correlationId, String headerName, String headerValue) {
    chains.computeIfAbsent(correlationId, CorrelationChain::new)
        .addTracingHeader(headerName, headerValue);
  }

  /**
   * Assert no orphaned messages (all correlation IDs matched).
   */
  public void assertNoOrphanedMessages(Set<String> expectedIds) {
    var missing = new HashSet<>(expectedIds);
    missing.removeAll(chains.keySet());

    if (!missing.isEmpty()) {
      throw new AssertionError("Orphaned correlation IDs (no chain): " + missing);
    }
  }

  /**
   * Assert causality chain with expected steps.
   */
  public void assertCausalityChain(String correlationId, String... expectedSteps) {
    var chain = chains.get(correlationId);
    if (chain == null) {
      throw new AssertionError("No correlation chain found for " + correlationId);
    }

    var actualSteps = chain.getStepNames();
    if (!actualSteps.equals(Arrays.asList(expectedSteps))) {
      throw new AssertionError(
          "Causality chain mismatch for " + correlationId
              + ".\nExpected: " + Arrays.asList(expectedSteps)
              + "\nActual:   " + actualSteps);
    }
  }

  /**
   * Assert chain contains step.
   */
  public void assertChainContainsStep(String correlationId, String stepName) {
    var chain = chains.get(correlationId);
    if (chain == null) {
      throw new AssertionError("No correlation chain found for " + correlationId);
    }

    if (!chain.getStepNames().contains(stepName)) {
      throw new AssertionError(
          "Chain " + correlationId + " does not contain step '" + stepName
              + "'. Steps: " + chain.getStepNames());
    }
  }

  /**
   * Get all recorded chains (for debugging).
   */
  public Map<String, CorrelationChain> getAllChains() {
    return Collections.unmodifiableMap(chains);
  }

  /**
   * Get chain by correlation ID.
   */
  public CorrelationChain getChain(String correlationId) {
    return chains.get(correlationId);
  }

  /**
   * Report causality graphs (verbose debugging).
   */
  public String reportGraphs() {
    return chains.entrySet().stream()
        .map(e -> e.getValue().toString())
        .collect(Collectors.joining("\n"));
  }

  /**
   * Get latency for a correlation chain (from first to last step).
   */
  public long getChainLatencyMillis(String correlationId) {
    var chain = chains.get(correlationId);
    if (chain == null || chain.steps.isEmpty()) {
      return 0;
    }

    var first = chain.steps.get(0).timestamp;
    var last = chain.steps.get(chain.steps.size() - 1).timestamp;
    return last - first;
  }

  /**
   * Clear all recorded chains.
   */
  public void clear() {
    chains.clear();
    correlationTimestamps.clear();
  }

  /**
   * Get count of correlation chains.
   */
  public int getChainCount() {
    return chains.size();
  }

  /**
   * Get count of steps for a chain.
   */
  public int getStepCount(String correlationId) {
    var chain = chains.get(correlationId);
    return chain != null ? chain.steps.size() : 0;
  }

  @Override
  public String toString() {
    return "CorrelationIdTracker[chains=" + chains.size() + ", totalSteps="
        + chains.values().stream().mapToInt(c -> c.steps.size()).sum() + "]";
  }
}
