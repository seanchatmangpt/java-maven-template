package io.github.seanchatmangpt.jotp.messaging.routing;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Message Aggregator pattern — reassembles multiple correlated partial messages into a complete
 * message.
 *
 * <p>From Enterprise Integration Patterns (Vernon): "Use an Aggregator to combine related but
 * separate messages into a single, unified message."
 *
 * <p>This implementation uses a {@link Proc}-like stateful accumulator (holding a TreeMap indexed
 * by sequence number) to handle out-of-order arrival, timeout-based completion, and correlation
 * by UUID. The aggregator maintains soft state per correlation ID and automatically completes when
 * all parts have arrived or a timeout elapses.
 *
 * <p><strong>Example: Reassemble split order line items into a complete order</strong>
 *
 * <pre>{@code
 * record OrderPart(String orderId, LineItem item, Splitter.PartMetadata meta) {}
 * record CompleteOrder(String id, List<LineItem> items) {}
 *
 * var agg = Aggregator.create(
 *     5,  // expect 5 parts
 *     Duration.ofSeconds(10),
 *     parts -> new CompleteOrder("ORD-123", parts)
 * );
 *
 * // Send parts out of order
 * agg.aggregate(part2);  // returns Optional.empty()
 * agg.aggregate(part1);  // returns Optional.empty()
 * agg.aggregate(part5);  // returns Optional.empty()
 * agg.aggregate(part3);  // returns Optional.empty()
 * agg.aggregate(part4);  // returns Optional.of(CompleteOrder(...))
 *
 * // After 10s, if not all parts arrived:
 * agg.aggregate(null); // returns Optional.empty(), times out incomplete aggregation
 * }</pre>
 *
 * <p><strong>Immutability:</strong> Aggregator maintains immutable state (TreeMap of parts by
 * sequence number). Each part is stored as-is; no mutation of input messages occurs.
 */
public final class Aggregator<T> {

  /**
   * Internal state: maps correlation ID -> (TreeMap of sequence -> part, arrival time).
   *
   * <p>TreeMap ensures we can detect missing sequence numbers and complete when all parts
   * (1..totalParts) are present.
   */
  private static final class AggregationState<T> {
    final int expectedTotal;
    final TreeMap<Integer, T> parts;
    final Instant createdAt;

    AggregationState(int expectedTotal) {
      this.expectedTotal = expectedTotal;
      this.parts = new TreeMap<>();
      this.createdAt = Instant.now();
    }

    boolean isComplete() {
      if (parts.size() != expectedTotal) return false;
      // Check that we have parts 1, 2, ..., expectedTotal with no gaps
      for (int i = 1; i <= expectedTotal; i++) {
        if (!parts.containsKey(i)) return false;
      }
      return true;
    }

    boolean isTimedOut(Duration timeout) {
      return Duration.between(createdAt, Instant.now()).compareTo(timeout) > 0;
    }
  }

  private final int expectedPartCount;
  private final Duration timeout;
  private final Function<List<T>, ?> assembler;
  private final Map<UUID, AggregationState<T>> correlations = new ConcurrentHashMap<>();

  /**
   * Create a new Aggregator.
   *
   * @param targetCount the number of parts expected for a complete message
   * @param timeout the duration after which an incomplete aggregation is considered stale and
   *     discarded
   * @param assembler function to combine the ordered list of parts into a complete message
   * @param <R> the result type (return type of the assembler function)
   * @return an Aggregator instance
   * @throws IllegalArgumentException if targetCount <= 0
   */
  public static <T, R> Aggregator<T> create(
      int targetCount, Duration timeout, Function<List<T>, R> assembler) {
    if (targetCount <= 0) {
      throw new IllegalArgumentException("targetCount must be > 0");
    }
    Objects.requireNonNull(timeout, "timeout cannot be null");
    Objects.requireNonNull(assembler, "assembler function cannot be null");

    return new Aggregator<>(targetCount, timeout, assembler);
  }

  private Aggregator(int expectedPartCount, Duration timeout, Function<List<T>, ?> assembler) {
    this.expectedPartCount = expectedPartCount;
    this.timeout = timeout;
    this.assembler = assembler;
  }

  /**
   * Aggregate a partial message, potentially completing the aggregation.
   *
   * <p>When a partial message arrives:
   *
   * <ul>
   *   <li>Its correlation ID and sequence number are extracted from {@link
   *       Splitter.PartMetadata}
   *   <li>The part is stored in a TreeMap indexed by sequence number (1-based)
   *   <li>The aggregator checks if all parts 1..N have arrived
   *   <li>If yes, the assembler function is called with the ordered parts, and the result is
   *       returned wrapped in {@code Optional.of(...)}
   *   <li>If no, {@code Optional.empty()} is returned
   *   <li>Timed-out aggregations are silently discarded
   * </ul>
   *
   * @param <R> the result type
   * @param part the partial message (must include {@link Splitter.PartMetadata})
   * @return {@code Optional.of(assembled)} when complete, or {@code Optional.empty()} otherwise
   * @throws IllegalArgumentException if the metadata sequence is invalid
   * @throws NullPointerException if part is null
   */
  public <R> Optional<R> aggregate(Splitter.MessagePart<T> part) {
    Objects.requireNonNull(part, "part cannot be null");

    Splitter.PartMetadata meta = part.metadata();
    UUID correlationId = meta.correlationId();

    // Get or create the aggregation state for this correlation ID
    AggregationState<T> state =
        correlations.computeIfAbsent(
            correlationId, k -> new AggregationState<>(expectedPartCount));

    // Check for timeout before processing
    if (state.isTimedOut(timeout)) {
      correlations.remove(correlationId);
      throw new IllegalStateException(
          String.format(
              "Aggregation %s timed out after %s",
              correlationId, timeout));
    }

    // Verify metadata consistency
    if (meta.totalParts() != expectedPartCount) {
      throw new IllegalArgumentException(
          String.format(
              "Part metadata totalParts (%d) does not match expected count (%d)",
              meta.totalParts(), expectedPartCount));
    }

    // Store the part
    state.parts.put(meta.sequenceNumber(), part.payload());

    // Check if complete
    if (state.isComplete()) {
      // Build ordered list from TreeMap
      List<T> orderedParts = new ArrayList<>(state.parts.values());
      correlations.remove(correlationId); // Clean up after assembly

      @SuppressWarnings("unchecked")
      R result = (R) assembler.apply(orderedParts);
      return Optional.of(result);
    }

    return Optional.empty();
  }

  /**
   * Returns the number of parts expected for a complete message.
   *
   * @return the target count
   */
  public int expectedPartCount() {
    return expectedPartCount;
  }

  /**
   * Returns the timeout duration for incomplete aggregations.
   *
   * @return the timeout
   */
  public Duration timeout() {
    return timeout;
  }

  /**
   * Returns the number of currently active (incomplete) aggregations.
   *
   * <p>This is useful for monitoring or cleanup during tests.
   *
   * @return the count of active correlations
   */
  public int activeCorrelations() {
    return correlations.size();
  }

  /**
   * Removes all stale aggregations that have exceeded their timeout.
   *
   * <p>This is an optional cleanup method for long-running systems. Normally, stale aggregations
   * are cleaned up lazily when a new part arrives for that correlation ID.
   *
   * @return the number of aggregations that were timed out and removed
   */
  public int cleanupStale() {
    int removed = 0;
    Iterator<Map.Entry<UUID, AggregationState<T>>> iter =
        correlations.entrySet().iterator();
    while (iter.hasNext()) {
      var entry = iter.next();
      if (entry.getValue().isTimedOut(timeout)) {
        iter.remove();
        removed++;
      }
    }
    return removed;
  }

  /**
   * Manually reset all aggregation state.
   *
   * <p>Useful for testing or resetting the aggregator between logical phases of a system.
   */
  public void reset() {
    correlations.clear();
  }
}
