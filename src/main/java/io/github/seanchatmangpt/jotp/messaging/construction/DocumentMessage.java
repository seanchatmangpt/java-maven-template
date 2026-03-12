package io.github.seanchatmangpt.jotp.messaging.construction;

import java.io.*;
import java.util.Objects;
import java.util.UUID;

/**
 * DocumentMessage pattern — Vaughn Vernon's "Document Message" for reactive messaging.
 *
 * <p>A document message wraps an entire domain entity as a self-contained message. It's used
 * when transferring large or complex data structures across process boundaries. The document
 * is serialized to a byte array for efficient transmission and storage.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Wraps immutable domain entities (use records or sealed types)</li>
 *   <li>Serializes to byte[] for wire transmission</li>
 *   <li>Type identifier for deserialization routing</li>
 *   <li>Self-contained: no external references or links</li>
 *   <li>Supports round-trip serialization/deserialization</li>
 * </ul>
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * record Customer(String id, String name, String email) implements Serializable {}
 *
 * var customer = new Customer("cust-1", "Alice", "alice@example.com");
 * var docMsg = DocumentMessage.create("Customer", customer);
 *
 * // Serialize to wire format
 * byte[] bytes = docMsg.documentBytes();
 *
 * // Deserialize from wire format
 * var restored = DocumentMessage.deserialize(bytes, Customer.class);
 * }</pre>
 *
 * <p>Joe Armstrong: "A message is best thought of as a copy of data, not a reference."
 *
 * @param <T> document type (must be Serializable)
 */
public sealed interface DocumentMessage<T extends Serializable> extends Serializable
    permits DocumentMessage.Impl {

  /** Unique message identifier. */
  UUID messageId();

  /** System timestamp when created (milliseconds since epoch). */
  long createdAt();

  /** Document type identifier (e.g., "Customer", "Order", "Invoice"). */
  String documentType();

  /** Serialized document bytes. */
  byte[] documentBytes();

  /**
   * Create a new DocumentMessage by serializing the given domain entity.
   *
   * @param documentType type identifier (e.g., "Customer", "Order")
   * @param document the domain entity to wrap
   * @return a new DocumentMessage with random UUID and current timestamp
   * @throws IllegalArgumentException if documentType is blank or document is null
   * @throws IOException if serialization fails
   */
  static <T extends Serializable> DocumentMessage<T> create(String documentType, T document)
      throws IOException {
    Objects.requireNonNull(documentType, "documentType must not be null");
    Objects.requireNonNull(document, "document must not be null");
    if (documentType.isBlank()) {
      throw new IllegalArgumentException("documentType must not be blank");
    }

    byte[] bytes = serialize(document);
    return new Impl<>(UUID.randomUUID(), System.currentTimeMillis(), documentType, bytes);
  }

  /**
   * Deserialize the document bytes back to a domain entity.
   *
   * @param documentType the expected document type (for validation)
   * @param documentClass the class to deserialize into
   * @return the deserialized domain entity
   * @throws IOException if deserialization fails
   * @throws ClassNotFoundException if the document class cannot be found
   * @throws IllegalArgumentException if document type doesn't match
   */
  default <T extends Serializable> T document(String documentType, Class<T> documentClass)
      throws IOException, ClassNotFoundException {
    if (!this.documentType().equals(documentType)) {
      throw new IllegalArgumentException(
          "Document type mismatch: expected "
              + documentType
              + " but got "
              + this.documentType());
    }
    return deserialize(documentBytes(), documentClass);
  }

  /**
   * Serialize a Serializable object to bytes.
   *
   * @param obj the object to serialize
   * @return serialized bytes
   * @throws IOException if serialization fails
   */
  static byte[] serialize(Serializable obj) throws IOException {
    Objects.requireNonNull(obj, "object must not be null");
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(obj);
      oos.flush();
      return baos.toByteArray();
    }
  }

  /**
   * Deserialize bytes back to an object of the specified class.
   *
   * @param bytes the serialized bytes
   * @param clazz the expected class type
   * @return the deserialized object
   * @throws IOException if deserialization fails
   * @throws ClassNotFoundException if the class cannot be found
   * @throws IllegalArgumentException if bytes is empty
   */
  static <T extends Serializable> T deserialize(byte[] bytes, Class<T> clazz)
      throws IOException, ClassNotFoundException {
    Objects.requireNonNull(bytes, "bytes must not be null");
    Objects.requireNonNull(clazz, "clazz must not be null");
    if (bytes.length == 0) {
      throw new IllegalArgumentException("bytes must not be empty");
    }

    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais)) {
      Object obj = ois.readObject();
      if (!clazz.isInstance(obj)) {
        throw new ClassCastException(
            "Expected " + clazz.getName() + " but got " + obj.getClass().getName());
      }
      return clazz.cast(obj);
    }
  }

  /**
   * Implementation record for DocumentMessage.
   */
  record Impl<T extends Serializable>(
      UUID messageId, long createdAt, String documentType, byte[] documentBytes)
      implements DocumentMessage<T> {

    public Impl {
      Objects.requireNonNull(messageId, "messageId must not be null");
      Objects.requireNonNull(documentType, "documentType must not be null");
      Objects.requireNonNull(documentBytes, "documentBytes must not be null");
      if (documentType.isBlank()) {
        throw new IllegalArgumentException("documentType must not be blank");
      }
      if (documentBytes.length == 0) {
        throw new IllegalArgumentException("documentBytes must not be empty");
      }
      if (createdAt < 0) {
        throw new IllegalArgumentException("createdAt must be non-negative");
      }
    }
  }
}
