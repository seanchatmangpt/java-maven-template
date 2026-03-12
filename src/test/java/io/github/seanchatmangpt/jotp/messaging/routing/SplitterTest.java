package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive tests for the Splitter pattern.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic splitting correctness (sequence numbers, correlation IDs)
 *   <li>Out-of-order arrival (sequence numbers are correct regardless of processing order)
 *   <li>Stream-based partitioning (chunking large collections)
 *   <li>Edge cases (empty lists, single item, large collections)
 *   <li>Type safety and immutability
 *   <li>Error conditions (null inputs, invalid splitters)
 * </ul>
 */
class SplitterTest {

  /** A simple test message. */
  record Message(String id, String content) {}

  @DisplayName("Basic split: one message -> multiple parts")
  @Test
  void testBasicSplit() {
    Message msg = new Message("1", "Hello World");

    List<Splitter.MessagePart<String>> parts =
        Splitter.split(msg, m -> List.of("Hello", "World"));

    assertThat(parts)
        .hasSize(2)
        .allSatisfy(
            p -> {
              assertThat(p.payload()).isNotNull();
              assertThat(p.metadata()).isNotNull();
              assertThat(p.metadata().totalParts()).isEqualTo(2);
            });

    // Verify sequence numbers are 1-indexed
    assertThat(parts.get(0).metadata().sequenceNumber()).isEqualTo(1);
    assertThat(parts.get(1).metadata().sequenceNumber()).isEqualTo(2);

    // Verify all parts have the same correlation ID
    UUID corrId = parts.get(0).metadata().correlationId();
    assertThat(parts.stream().map(p -> p.metadata().correlationId()))
        .allMatch(corrId::equals);
  }

  @DisplayName("Split result is immutable")
  @Test
  void testImmutability() {
    List<Splitter.MessagePart<String>> parts =
        Splitter.split("test", m -> List.of("a", "b", "c"));

    // Try to modify the returned list (should fail or have no effect)
    assertThatThrownBy(() -> parts.add(new Splitter.MessagePart<>("d", parts.get(0).metadata())))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @DisplayName("Correlation ID can be provided explicitly")
  @Test
  void testExplicitCorrelationId() {
    UUID corrId = UUID.randomUUID();
    List<Splitter.MessagePart<String>> parts =
        Splitter.split("test", m -> List.of("a", "b"), corrId);

    assertThat(parts)
        .allSatisfy(p -> assertThat(p.metadata().correlationId()).isEqualTo(corrId));
  }

  @DisplayName("Auto-generated correlation IDs are unique")
  @Test
  void testUniqueCorrelationIds() {
    var parts1 = Splitter.split("msg1", m -> List.of("a", "b"));
    var parts2 = Splitter.split("msg2", m -> List.of("c", "d"));

    UUID corrId1 = parts1.get(0).metadata().correlationId();
    UUID corrId2 = parts2.get(0).metadata().correlationId();

    assertThat(corrId1).isNotEqualTo(corrId2);
  }

  @DisplayName("Payloads are preserved exactly")
  @Test
  void testPayloadPreservation() {
    record Item(int id, String value) {}

    List<Item> items =
        List.of(new Item(1, "one"), new Item(2, "two"), new Item(3, "three"));

    var parts = Splitter.split("container", m -> items);

    assertThat(parts.get(0).payload()).isEqualTo(new Item(1, "one"));
    assertThat(parts.get(1).payload()).isEqualTo(new Item(2, "two"));
    assertThat(parts.get(2).payload()).isEqualTo(new Item(3, "three"));
  }

  @DisplayName("Single-part split")
  @Test
  void testSinglePartSplit() {
    var parts = Splitter.split("message", m -> List.of("single part"));

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).metadata().sequenceNumber()).isEqualTo(1);
    assertThat(parts.get(0).metadata().totalParts()).isEqualTo(1);
  }

  @DisplayName("Large split (100+ parts)")
  @Test
  void testLargeSplit() {
    int partCount = 150;
    var parts = Splitter.split(
        "message",
        m -> {
          List<Integer> result = new ArrayList<>();
          for (int i = 0; i < partCount; i++) {
            result.add(i);
          }
          return result;
        });

    assertThat(parts).hasSize(partCount);
    for (int i = 0; i < partCount; i++) {
      assertThat(parts.get(i).metadata().sequenceNumber()).isEqualTo(i + 1);
      assertThat(parts.get(i).metadata().totalParts()).isEqualTo(partCount);
    }
  }

  @DisplayName("Splitter returns empty list throws IAE")
  @Test
  void testEmptySplitterResult() {
    assertThatThrownBy(() -> Splitter.split("message", m -> List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty");
  }

  @DisplayName("Splitter returns null throws IAE")
  @Test
  void testNullSplitterResult() {
    assertThatThrownBy(() -> Splitter.split("message", m -> null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty");
  }

  @DisplayName("Null message throws NPE")
  @Test
  void testNullMessage() {
    assertThatThrownBy(() -> Splitter.split(null, m -> List.of("a")))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("message");
  }

  @DisplayName("Null splitter function throws NPE")
  @Test
  void testNullSplitterFunction() {
    assertThatThrownBy(() -> Splitter.split("message", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("splitter");
  }

  @DisplayName("Null correlation ID throws NPE")
  @Test
  void testNullCorrelationId() {
    assertThatThrownBy(() -> Splitter.split("message", m -> List.of("a"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("correlationId");
  }

  @DisplayName("Partition: split collection into chunks")
  @Test
  void testPartition() {
    record Batch(List<String> items) {}

    var batch = new Batch(List.of("a", "b", "c", "d", "e"));

    var parts = Splitter.partition(
        batch,
        Batch::items,
        2,
        items -> new Batch(items));

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).payload().items()).containsExactly("a", "b");
    assertThat(parts.get(1).payload().items()).containsExactly("c", "d");
    assertThat(parts.get(2).payload().items()).containsExactly("e");

    // All parts have same correlation ID
    UUID corrId = parts.get(0).metadata().correlationId();
    assertThat(parts.stream().map(p -> p.metadata().correlationId()))
        .allMatch(corrId::equals);
  }

  @DisplayName("Partition: chunk size equals collection size")
  @Test
  void testPartitionChunkSizeEqualsCollectionSize() {
    record Batch(List<String> items) {}

    var batch = new Batch(List.of("a", "b", "c"));
    var parts = Splitter.partition(
        batch,
        Batch::items,
        3,
        items -> new Batch(items));

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).payload().items()).containsExactly("a", "b", "c");
  }

  @DisplayName("Partition: chunk size > collection size")
  @Test
  void testPartitionChunkSizeExceedsCollectionSize() {
    record Batch(List<String> items) {}

    var batch = new Batch(List.of("a", "b"));
    var parts = Splitter.partition(
        batch,
        Batch::items,
        10,
        items -> new Batch(items));

    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).payload().items()).containsExactly("a", "b");
  }

  @DisplayName("Partition: chunk size 1 creates N parts")
  @Test
  void testPartitionChunkSizeOne() {
    record Batch(List<String> items) {}

    var batch = new Batch(List.of("a", "b", "c"));
    var parts = Splitter.partition(
        batch,
        Batch::items,
        1,
        items -> new Batch(items));

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).payload().items()).containsExactly("a");
    assertThat(parts.get(1).payload().items()).containsExactly("b");
    assertThat(parts.get(2).payload().items()).containsExactly("c");
  }

  @DisplayName("Partition: invalid chunk size <= 0")
  @Test
  void testPartitionInvalidChunkSize() {
    record Batch(List<String> items) {}

    var batch = new Batch(List.of("a", "b"));

    assertThatThrownBy(() -> Splitter.partition(
        batch,
        Batch::items,
        0,
        items -> new Batch(items)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunkSize");

    assertThatThrownBy(() -> Splitter.partition(
        batch,
        Batch::items,
        -5,
        items -> new Batch(items)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunkSize");
  }

  @DisplayName("Partition: empty collection throws IAE")
  @Test
  void testPartitionEmptyCollection() {
    record Batch(List<String> items) {}

    var batch = new Batch(List.of());

    assertThatThrownBy(() -> Splitter.partition(
        batch,
        Batch::items,
        5,
        items -> new Batch(items)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }

  @DisplayName("Partition: large collection with large chunk size")
  @Test
  void testPartitionLargeCollection() {
    record Batch(List<Integer> items) {}

    List<Integer> items = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      items.add(i);
    }
    var batch = new Batch(items);

    var parts = Splitter.partition(
        batch,
        Batch::items,
        250,
        items2 -> new Batch(items2));

    assertThat(parts).hasSize(4);
    assertThat(parts.get(0).payload().items()).hasSize(250);
    assertThat(parts.get(1).payload().items()).hasSize(250);
    assertThat(parts.get(2).payload().items()).hasSize(250);
    assertThat(parts.get(3).payload().items()).hasSize(250);
  }

  @DisplayName("MessagePart metadata: sequence out of bounds throws IAE")
  @Test
  void testPartMetadataValidation() {
    UUID corrId = UUID.randomUUID();

    assertThatThrownBy(() -> new Splitter.PartMetadata(corrId, 0, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1-indexed");

    assertThatThrownBy(() -> new Splitter.PartMetadata(corrId, 6, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1-indexed");
  }

  @DisplayName("MessagePart: describe() provides readable description")
  @Test
  void testPartDescribe() {
    var part = new Splitter.MessagePart<>(
        "payload",
        new Splitter.PartMetadata(UUID.randomUUID(), 3, 5));

    String desc = part.describe();
    assertThat(desc).contains("3/5").contains("correlation");
  }

  @DisplayName("Parametrized: split with various part counts")
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 5, 10, 50})
  void testSplitVariousPartCounts(int partCount) {
    List<Integer> parts = new ArrayList<>();
    for (int i = 0; i < partCount; i++) {
      parts.add(i);
    }

    var result = Splitter.split("message", m -> parts);

    assertThat(result).hasSize(partCount);
    assertThat(result.stream().map(p -> p.metadata().totalParts()))
        .allMatch(t -> t == partCount);
    assertThat(result.stream().map(Splitter.MessagePart::describe))
        .noneMatch(String::isBlank);
  }

  @DisplayName("Correlation ID consistency across multiple splits")
  @Test
  void testCorrelationIdPersistence() {
    UUID corrId = UUID.randomUUID();

    var parts1 = Splitter.split("msg1", m -> List.of("a", "b", "c"), corrId);
    var parts2 = Splitter.split("msg2", m -> List.of("x", "y"), corrId);

    assertThat(parts1.stream().map(p -> p.metadata().correlationId()))
        .allMatch(corrId::equals);
    assertThat(parts2.stream().map(p -> p.metadata().correlationId()))
        .allMatch(corrId::equals);

    // But sequence numbers restart for each split
    assertThat(parts1.stream().map(p -> p.metadata().sequenceNumber()))
        .containsExactly(1, 2, 3);
    assertThat(parts2.stream().map(p -> p.metadata().sequenceNumber()))
        .containsExactly(1, 2);
  }
}
