package io.github.seanchatmangpt.jotp.testing.util;

import java.util.*;

/**
 * Fluent DSL for constructing test messages.
 *
 * <p>Supports all 4 Vernon message construction patterns:
 * <ul>
 *   <li>CommandMessage (request + reply-to)</li>
 *   <li>DocumentMessage (domain entity)</li>
 *   <li>ClaimCheck (large payload with claim token)</li>
 *   <li>EnvelopeWrapper (metadata envelope)</li>
 * </ul>
 *
 * <p>Reflection-driven: Validates against sealed type constraints.
 *
 * <p>Usage:
 * <pre>{@code
 * var msg = MessageBuilder.command()
 *   .withCorrelationId(UUID.randomUUID().toString())
 *   .withReplyTo(replyPid)
 *   .withPriority("HIGH")
 *   .build();
 * }</pre>
 */
public class MessageBuilder {

  private final Map<String, Object> fields = new LinkedHashMap<>();
  private final String messageType;

  private MessageBuilder(String messageType) {
    this.messageType = messageType;
  }

  public static MessageBuilder command() {
    return new MessageBuilder("CommandMessage");
  }

  public static MessageBuilder document() {
    return new MessageBuilder("DocumentMessage");
  }

  public static MessageBuilder claimCheck() {
    return new MessageBuilder("ClaimCheck");
  }

  public static MessageBuilder envelope() {
    return new MessageBuilder("EnvelopeWrapper");
  }

  public static MessageBuilder custom(String messageType) {
    return new MessageBuilder(messageType);
  }

  /**
   * Set correlation ID (UUID format).
   */
  public MessageBuilder withCorrelationId(String correlationId) {
    fields.put("correlationId", correlationId);
    return this;
  }

  /**
   * Set correlation ID (UUID object).
   */
  public MessageBuilder withCorrelationId(UUID correlationId) {
    fields.put("correlationId", correlationId.toString());
    return this;
  }

  /**
   * Set reply-to PID (for CommandMessage).
   */
  public MessageBuilder withReplyTo(Object replyToPid) {
    fields.put("replyTo", replyToPid);
    return this;
  }

  /**
   * Set message priority (HIGH, MEDIUM, LOW).
   */
  public MessageBuilder withPriority(String priority) {
    fields.put("priority", priority);
    return this;
  }

  /**
   * Set message payload (for DocumentMessage).
   */
  public MessageBuilder withPayload(Object payload) {
    fields.put("payload", payload);
    return this;
  }

  /**
   * Set message headers (for EnvelopeWrapper).
   */
  public MessageBuilder withHeaders(Map<String, String> headers) {
    fields.put("headers", new HashMap<>(headers));
    return this;
  }

  /**
   * Add a header.
   */
  public MessageBuilder withHeader(String key, String value) {
    var headers = (Map<String, String>) fields.computeIfAbsent("headers", k -> new HashMap<>());
    headers.put(key, value);
    return this;
  }

  /**
   * Set claim check ID (for ClaimCheck).
   */
  public MessageBuilder withClaimId(String claimId) {
    fields.put("claimId", claimId);
    return this;
  }

  /**
   * Set arbitrary field value.
   */
  public MessageBuilder withField(String fieldName, Object value) {
    fields.put(fieldName, value);
    return this;
  }

  /**
   * Get field value (for building).
   */
  public Object getField(String fieldName) {
    return fields.get(fieldName);
  }

  /**
   * Build the message object (reflection-based instantiation).
   */
  public Object build() {
    // For testing purposes, return a map-backed message object
    return new TestMessage(messageType, new LinkedHashMap<>(fields));
  }

  /**
   * Internal test message implementation (map-backed).
   */
  public static class TestMessage {
    public final String type;
    public final Map<String, Object> fields;

    public TestMessage(String type, Map<String, Object> fields) {
      this.type = type;
      this.fields = Collections.unmodifiableMap(fields);
    }

    @Override
    public String toString() {
      return type + fields;
    }

    public Object getField(String name) {
      return fields.get(name);
    }
  }

  /**
   * Validate message against sealed type constraints.
   * Uses Java 26 reflection API to check sealed type compatibility.
   */
  public void validate(Class<?> messageClass) {
    if (!messageClass.isSealed()) {
      return; // No sealed type constraints
    }

    var permitted = messageClass.permittedSubclasses();
    var messageTypeName = messageType;
    var isValid = Arrays.stream(permitted)
        .anyMatch(c -> c.getSimpleName().equals(messageTypeName));

    if (!isValid) {
      throw new IllegalStateException(
          messageType + " is not a permitted subclass of sealed " + messageClass.getName());
    }
  }

  @Override
  public String toString() {
    return "MessageBuilder[type=" + messageType + ", fields=" + fields.size() + "]";
  }
}
