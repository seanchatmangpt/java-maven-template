package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.*;
import java.util.function.Function;

/**
 * Message Splitter pattern — breaks one message into multiple correlated parts.
 *
 * <p>From Enterprise Integration Patterns (Vernon): "Use a Splitter to break out the composite
 * message into a series of individual messages, each containing data related to one item."
 *
 * <p>This utility provides immutable, stream-based message splitting with UUID correlation for
 * reassembly. Each part is assigned a sequence number and correlation ID, enabling downstream
 * components (like {@link Aggregator}) to match related messages.
 *
 * <p><strong>Example:</strong> An order containing 5 line items:
 *
 * <pre>{@code
 * record Order(String id, List<LineItem> items) {}
 * record LineItem(String sku, int qty) {}
 *
 * var order = new Order("ORD-123", List.of(...));
 * var correlationId = UUID.randomUUID();
 * var parts = Splitter.split(
 *     order,
 *     o -> o.items().stream()
 *          .map(LineItem::toMessage)
 *          .toList(),
 *     correlationId
 * );
 * parts.forEach(p -> send(p.toSystems()));
 * }</pre>
 *
 * <p><strong>Immutability guarantee:</strong> All parts are immutable records; the splitter makes
 * no modifications to the input message.
 */
public final class Splitter {

  private Splitter() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Metadata for a split message part — includes correlation ID, sequence number, and total parts.
   *
   * @param correlationId UUID linking all parts from the same split operation
   * @param sequenceNumber 1-indexed position of this part in the complete message
   * @param totalParts total number of parts this message was split into
   */
  public record PartMetadata(UUID correlationId, int sequenceNumber, int totalParts) {

    public PartMetadata {
      if (sequenceNumber < 1 || sequenceNumber > totalParts) {
        throw new IllegalArgumentException(
            "sequenceNumber must be between 1 and totalParts (1-indexed)");
      }
      if (totalParts < 1) {
        throw new IllegalArgumentException("totalParts must be >= 1");
      }
      if (correlationId == null) {
        throw new IllegalArgumentException("correlationId cannot be null");
      }
    }
  }

  /**
   * A single part of a split message, including its payload and routing metadata.
   *
   * @param <T> the message part type
   */
  public record MessagePart<T>(T payload, PartMetadata metadata) {

    public MessagePart {
      if (payload == null) throw new IllegalArgumentException("payload cannot be null");
      if (metadata == null) throw new IllegalArgumentException("metadata cannot be null");
    }

    /**
     * Returns a human-readable string identifying this part in the context of all parts.
     *
     * @return string like "Part 2/5 (correlation: uuid...)"
     */
    public String describe() {
      return String.format(
          "Part %d/%d (correlation: %s)",
          metadata.sequenceNumber(), metadata.totalParts(), metadata.correlationId());
    }
  }

  /**
   * Split a message into multiple parts using the given splitter function.
   *
   * <p>The splitter function receives the input message and returns a list of parts. Each part
   * automatically receives a {@link PartMetadata} with a unique correlation ID and sequence
   * number.
   *
   * @param <T> the message type
   * @param message the message to split
   * @param splitter function that returns a list of message parts from the input
   * @return list of {@link MessagePart} records with correlation IDs and sequence numbers
   * @throws IllegalArgumentException if the splitter returns an empty list or null
   */
  public static <T> List<MessagePart<T>> split(
      T message, Function<T, List<T>> splitter) {
    return split(message, splitter, UUID.randomUUID());
  }

  /**
   * Split a message into multiple parts using the given splitter function and correlation ID.
   *
   * <p>This overload allows callers to specify a pre-determined correlation ID (e.g., from an
   * existing context or for testing). The correlation ID is the same for all resulting parts,
   * enabling reassembly downstream.
   *
   * @param <T> the message type
   * @param message the message to split
   * @param splitter function that returns a list of message parts from the input
   * @param correlationId UUID to link all resulting parts together
   * @return list of {@link MessagePart} records with the specified correlation ID
   * @throws IllegalArgumentException if the splitter returns an empty list or null
   */
  public static <T> List<MessagePart<T>> split(
      T message, Function<T, List<T>> splitter, UUID correlationId) {
    Objects.requireNonNull(message, "message cannot be null");
    Objects.requireNonNull(splitter, "splitter function cannot be null");
    Objects.requireNonNull(correlationId, "correlationId cannot be null");

    List<T> parts = splitter.apply(message);

    if (parts == null || parts.isEmpty()) {
      throw new IllegalArgumentException("splitter must return a non-empty list of parts");
    }

    int totalParts = parts.size();
    List<MessagePart<T>> result = new ArrayList<>(totalParts);

    for (int i = 0; i < parts.size(); i++) {
      T part = parts.get(i);
      int sequenceNumber = i + 1; // 1-indexed
      PartMetadata metadata = new PartMetadata(correlationId, sequenceNumber, totalParts);
      result.add(new MessagePart<>(part, metadata));
    }

    return Collections.unmodifiableList(result);
  }

  /**
   * Convenience method: partition a list message into chunks of a maximum size.
   *
   * <p>This is a common pattern where a message contains a collection, and we want to split it
   * into batches for downstream processing (e.g., batch ingestion into a database).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * record Batch<T>(List<T> items) {}
   * var largeBatch = new Batch<>(List.of(...1000 items...));
   * var parts = Splitter.partition(largeBatch, Batch::items, 100, Batch::new);
   * }</pre>
   *
   * @param <T> the item type within the collection
   * @param <C> the container/message type (e.g., a list wrapper record)
   * @param message the container message
   * @param extractor function to extract the collection from the container
   * @param chunkSize maximum size of each partition
   * @param constructor function to wrap each partition back into a container
   * @return list of {@link MessagePart} records, each wrapping a chunk
   * @throws IllegalArgumentException if chunkSize <= 0
   */
  public static <T, C> List<MessagePart<C>> partition(
      C message,
      Function<C, List<T>> extractor,
      int chunkSize,
      Function<List<T>, C> constructor) {
    Objects.requireNonNull(message, "message cannot be null");
    Objects.requireNonNull(extractor, "extractor cannot be null");
    Objects.requireNonNull(constructor, "constructor cannot be null");

    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be > 0");
    }

    List<T> items = extractor.apply(message);

    if (items.isEmpty()) {
      throw new IllegalArgumentException("items list cannot be empty");
    }

    return split(
        message,
        msg -> {
          List<List<T>> chunks = new ArrayList<>();
          List<T> allItems = extractor.apply(msg);
          for (int i = 0; i < allItems.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, allItems.size());
            chunks.add(new ArrayList<>(allItems.subList(i, end)));
          }
          return chunks.stream().map(constructor).toList();
        });
  }
}
