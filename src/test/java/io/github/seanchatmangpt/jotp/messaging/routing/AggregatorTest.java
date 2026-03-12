package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

/**
 * Comprehensive tests for the Aggregator pattern.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic aggregation (collecting all parts into one message)
 *   <li>Out-of-order part arrival (sequence numbers handled correctly)
 *   <li>Timeout handling (incomplete aggregations are discarded)
 *   <li>Type safety and immutability
 *   <li>Multiple concurrent aggregations (different correlation IDs)
 *   <li>Duplicate part handling
 *   <li>Metadata validation
 * </ul>
 */
class AggregatorTest {

  record Item(String id, int value) {}

  record CompleteMsg(List<Item> items) {}

  private Aggregator<Item> aggregator;

  @BeforeEach
  void setup() {
    aggregator =
        Aggregator.create(
            3,
            Duration.ofSeconds(5),
            items -> new CompleteMsg(items));
  }

  @DisplayName("Basic aggregation: all parts arrive in order")
  @Test
  void testBasicAggregationInOrder() throws Exception {
    UUID corrId = UUID.randomUUID();

    var part1 = createPart(new Item("1", 10), 1, 3, corrId);
    var part2 = createPart(new Item("2", 20), 2, 3, corrId);
    var part3 = createPart(new Item("3", 30), 3, 3, corrId);

    Optional<CompleteMsg> result1 = aggregator.aggregate(part1);
    assertThat(result1).isEmpty();

    Optional<CompleteMsg> result2 = aggregator.aggregate(part2);
    assertThat(result2).isEmpty();

    Optional<CompleteMsg> result3 = aggregator.aggregate(part3);
    assertThat(result3).isPresent();
    assertThat(result3.get().items())
        .extracting(Item::id)
        .containsExactly("1", "2", "3");
  }

  @DisplayName("Aggregation: parts arrive out of order")
  @Test
  void testOutOfOrderAggregation() {
    UUID corrId = UUID.randomUUID();

    var part1 = createPart(new Item("1", 10), 1, 3, corrId);
    var part2 = createPart(new Item("2", 20), 2, 3, corrId);
    var part3 = createPart(new Item("3", 30), 3, 3, corrId);

    // Send in order: 3, 1, 2
    Optional<CompleteMsg> res3 = aggregator.aggregate(part3);
    assertThat(res3).isEmpty();

    Optional<CompleteMsg> res1 = aggregator.aggregate(part1);
    assertThat(res1).isEmpty();

    Optional<CompleteMsg> res2 = aggregator.aggregate(part2);
    assertThat(res2).isPresent();
    // Items should be in sequence order (1, 2, 3), not arrival order
    assertThat(res2.get().items())
        .extracting(Item::id)
        .containsExactly("1", "2", "3");
  }

  @DisplayName("Aggregation: single part")
  @Test
  void testSinglePartAggregation() {
    var singleAgg =
        Aggregator.create(1, Duration.ofSeconds(5), items -> new CompleteMsg(items));

    UUID corrId = UUID.randomUUID();
    var part = createPart(new Item("solo", 99), 1, 1, corrId);

    Optional<CompleteMsg> result = singleAgg.aggregate(part);
    assertThat(result).isPresent();
    assertThat(result.get().items())
        .extracting(Item::id)
        .containsExactly("solo");
  }

  @DisplayName("Aggregation: many parts (10+)")
  @Test
  void testLargeAggregation() {
    int partCount = 15;
    var largeAgg =
        Aggregator.create(
            partCount,
            Duration.ofSeconds(5),
            items -> new CompleteMsg(items));

    UUID corrId = UUID.randomUUID();
    List<Splitter.MessagePart<Item>> parts = new ArrayList<>();
    for (int i = 1; i <= partCount; i++) {
      parts.add(createPart(new Item("item-" + i, i * 10), i, partCount, corrId));
    }

    Optional<CompleteMsg> result = Optional.empty();
    for (int i = 0; i < parts.size() - 1; i++) {
      result = largeAgg.aggregate(parts.get(i));
      assertThat(result).isEmpty();
    }

    result = largeAgg.aggregate(parts.get(partCount - 1));
    assertThat(result).isPresent();
    assertThat(result.get().items()).hasSize(partCount);
  }

  @DisplayName("Multiple concurrent aggregations with different correlation IDs")
  @Test
  void testMultipleConcurrentAggregations() {
    UUID corrId1 = UUID.randomUUID();
    UUID corrId2 = UUID.randomUUID();

    // Aggregation 1: send part 1
    var part1a = createPart(new Item("1a", 10), 1, 2, corrId1);
    var res1 = aggregator.aggregate(part1a);
    assertThat(res1).isEmpty();
    assertThat(aggregator.activeCorrelations()).isEqualTo(1);

    // Aggregation 2: send part 1
    var part1b = createPart(new Item("1b", 20), 1, 2, corrId2);
    var res2 = aggregator.aggregate(part1b);
    assertThat(res2).isEmpty();
    assertThat(aggregator.activeCorrelations()).isEqualTo(2);

    // Complete aggregation 1
    var part2a = createPart(new Item("2a", 30), 2, 2, corrId1);
    var res1b = aggregator.aggregate(part2a);
    assertThat(res1b).isPresent();
    assertThat(aggregator.activeCorrelations()).isEqualTo(1);

    // Complete aggregation 2
    var part2b = createPart(new Item("2b", 40), 2, 2, corrId2);
    var res2b = aggregator.aggregate(part2b);
    assertThat(res2b).isPresent();
    assertThat(aggregator.activeCorrelations()).isEqualTo(0);
  }

  @DisplayName("Timeout: incomplete aggregation throws after timeout")
  @Test
  void testTimeoutOnIncompleteAggregation() throws Exception {
    var shortTimeoutAgg =
        Aggregator.create(
            2,
            Duration.ofMillis(200),
            items -> new CompleteMsg(items));

    UUID corrId = UUID.randomUUID();
    var part1 = createPart(new Item("1", 10), 1, 2, corrId);

    Optional<CompleteMsg> res1 = shortTimeoutAgg.aggregate(part1);
    assertThat(res1).isEmpty();
    assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(1);

    // Wait for timeout to elapse
    Thread.sleep(300);

    // Try to add part 2 after timeout
    var part2 = createPart(new Item("2", 20), 2, 2, corrId);
    assertThatThrownBy(() -> shortTimeoutAgg.aggregate(part2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");

    // Aggregation state should be cleaned up
    assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(0);
  }

  @DisplayName("Timeout: cleanup stale aggregations")
  @Test
  void testCleanupStale() throws Exception {
    var shortTimeoutAgg =
        Aggregator.create(
            2,
            Duration.ofMillis(200),
            items -> new CompleteMsg(items));

    UUID corrId1 = UUID.randomUUID();
    UUID corrId2 = UUID.randomUUID();

    var part1a = createPart(new Item("1a", 10), 1, 2, corrId1);
    var part1b = createPart(new Item("1b", 20), 1, 2, corrId2);

    shortTimeoutAgg.aggregate(part1a);
    shortTimeoutAgg.aggregate(part1b);

    assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(2);

    // Wait for timeout
    Thread.sleep(300);

    // Cleanup should remove both
    int removed = shortTimeoutAgg.cleanupStale();
    assertThat(removed).isEqualTo(2);
    assertThat(shortTimeoutAgg.activeCorrelations()).isEqualTo(0);
  }

  @DisplayName("Metadata mismatch: totalParts differs from expected")
  @Test
  void testMetadataMismatch() {
    UUID corrId = UUID.randomUUID();

    // Aggregator expects 3, but metadata says 5
    var part = createPart(new Item("1", 10), 1, 5, corrId);

    assertThatThrownBy(() -> aggregator.aggregate(part))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("totalParts");
  }

  @DisplayName("Null part throws NPE")
  @Test
  void testNullPart() {
    assertThatThrownBy(() -> aggregator.aggregate(null))
        .isInstanceOf(NullPointerException.class);
  }

  @DisplayName("Reset clears all aggregations")
  @Test
  void testReset() {
    UUID corrId1 = UUID.randomUUID();
    UUID corrId2 = UUID.randomUUID();

    aggregator.aggregate(createPart(new Item("a", 1), 1, 3, corrId1));
    aggregator.aggregate(createPart(new Item("b", 2), 1, 3, corrId2));

    assertThat(aggregator.activeCorrelations()).isEqualTo(2);

    aggregator.reset();

    assertThat(aggregator.activeCorrelations()).isEqualTo(0);
  }

  @DisplayName("Create with invalid parameters")
  @Test
  void testInvalidCreation() {
    assertThatThrownBy(
            () ->
                Aggregator.create(
                    0,
                    Duration.ofSeconds(5),
                    items -> new CompleteMsg(items)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetCount");

    assertThatThrownBy(
            () ->
                Aggregator.create(
                    -1,
                    Duration.ofSeconds(5),
                    items -> new CompleteMsg(items)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetCount");

    assertThatThrownBy(
            () ->
                Aggregator.create(
                    5,
                    null,
                    items -> new CompleteMsg(items)))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () ->
                Aggregator.create(
                    5,
                    Duration.ofSeconds(5),
                    null))
        .isInstanceOf(NullPointerException.class);
  }

  @DisplayName("Properties: expected part count and timeout")
  @Test
  void testProperties() {
    var duration = Duration.ofSeconds(10);
    var agg =
        Aggregator.create(
            7,
            duration,
            items -> new CompleteMsg(items));

    assertThat(agg.expectedPartCount()).isEqualTo(7);
    assertThat(agg.timeout()).isEqualTo(duration);
  }

  @DisplayName("Assembly happens exactly once per correlation")
  @Test
  void testAssemblyOnce() {
    var callCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    var agg =
        Aggregator.create(
            2,
            Duration.ofSeconds(5),
            items -> {
              callCounter.incrementAndGet();
              return new CompleteMsg(items);
            });

    UUID corrId = UUID.randomUUID();
    var part1 = createPart(new Item("1", 10), 1, 2, corrId);
    var part2 = createPart(new Item("2", 20), 2, 2, corrId);

    agg.aggregate(part1);
    agg.aggregate(part2);

    assertThat(callCounter.get()).isEqualTo(1);
  }

  @DisplayName("Duplicate part sequence number: overwrites previous")
  @Test
  void testDuplicatePartSequence() {
    UUID corrId = UUID.randomUUID();

    var part1a = createPart(new Item("1a", 10), 1, 2, corrId);
    var part1b = createPart(new Item("1b", 15), 1, 2, corrId); // Same sequence
    var part2 = createPart(new Item("2", 20), 2, 2, corrId);

    aggregator.aggregate(part1a);
    aggregator.aggregate(part1b); // Overwrites part1a
    Optional<CompleteMsg> result = aggregator.aggregate(part2);

    // Should contain part1b (the newer one), not part1a
    assertThat(result.get().items())
        .extracting(Item::id)
        .containsExactly("1b", "2");
  }

  @DisplayName("Active correlations increases and decreases appropriately")
  @Test
  void testActiveCorrelationTracking() {
    assertThat(aggregator.activeCorrelations()).isEqualTo(0);

    UUID corrId1 = UUID.randomUUID();
    aggregator.aggregate(createPart(new Item("1", 10), 1, 3, corrId1));
    assertThat(aggregator.activeCorrelations()).isEqualTo(1);

    UUID corrId2 = UUID.randomUUID();
    aggregator.aggregate(createPart(new Item("2", 20), 1, 3, corrId2));
    assertThat(aggregator.activeCorrelations()).isEqualTo(2);

    // Complete first aggregation
    aggregator.aggregate(createPart(new Item("1b", 11), 2, 3, corrId1));
    assertThat(aggregator.activeCorrelations()).isEqualTo(1);

    aggregator.aggregate(createPart(new Item("1c", 12), 3, 3, corrId1));
    assertThat(aggregator.activeCorrelations()).isEqualTo(1);
  }

  @DisplayName("Large aggregation with many parts")
  @Test
  void testLargeAggregationWith50Parts() {
    int partCount = 50;
    var largeAgg =
        Aggregator.create(
            partCount,
            Duration.ofSeconds(10),
            items -> new CompleteMsg(items));

    UUID corrId = UUID.randomUUID();

    // Send all parts in reverse order
    for (int i = partCount; i >= 1; i--) {
      var part = createPart(new Item("item-" + i, i), i, partCount, corrId);
      Optional<CompleteMsg> result = largeAgg.aggregate(part);

      if (i == 1) {
        // Last part (sequence 1) arrives last
        assertThat(result).isPresent();
        assertThat(result.get().items()).hasSize(partCount);
        // Verify ordering
        for (int j = 0; j < partCount; j++) {
          assertThat(result.get().items().get(j).id()).isEqualTo("item-" + (j + 1));
        }
      } else {
        assertThat(result).isEmpty();
      }
    }
  }

  @DisplayName("Timeout with zero duration")
  @Test
  void testZeroDurationTimeout() throws Exception {
    var zeroTimeoutAgg =
        Aggregator.create(
            2,
            Duration.ZERO,
            items -> new CompleteMsg(items));

    UUID corrId = UUID.randomUUID();
    var part1 = createPart(new Item("1", 10), 1, 2, corrId);

    zeroTimeoutAgg.aggregate(part1);

    // Even minimal sleep should exceed zero duration
    Thread.sleep(1);

    var part2 = createPart(new Item("2", 20), 2, 2, corrId);
    assertThatThrownBy(() -> zeroTimeoutAgg.aggregate(part2))
        .isInstanceOf(IllegalStateException.class);
  }

  // Helper method to create test parts
  private Splitter.MessagePart<Item> createPart(
      Item item, int seqNum, int total, UUID corrId) {
    return new Splitter.MessagePart<>(
        item,
        new Splitter.PartMetadata(corrId, seqNum, total));
  }
}
