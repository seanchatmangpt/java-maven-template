package io.github.seanchatmangpt.jotp.testing.util;

import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Fluent assertion API for messages in Vernon patterns.
 *
 * <p>Type-safe message assertions using sealed Result pattern and Java 26 reflection.
 *
 * <p>Usage:
 * <pre>{@code
 * assertMessage(msg)
 *   .hasCorrelationId(correlationId)
 *   .isPriority(HIGH)
 *   .hasType("CommandMessage")
 *   .assertSucceeds();
 * }</pre>
 */
public class MessageAssertions {

  private final Object message;
  private final List<String> failures = new ArrayList<>();

  private MessageAssertions(Object message) {
    this.message = message;
  }

  public static MessageAssertions assertMessage(Object message) {
    return new MessageAssertions(message);
  }

  /**
   * Assert message has a specific correlation ID.
   */
  public MessageAssertions hasCorrelationId(String expectedId) {
    var actualId = getFieldValue(message, "correlationId");
    if (!expectedId.equals(actualId)) {
      failures.add("Expected correlationId=" + expectedId + ", got=" + actualId);
    }
    return this;
  }

  /**
   * Assert message priority (HIGH, MEDIUM, LOW).
   */
  public MessageAssertions isPriority(String priority) {
    var actualPriority = getFieldValue(message, "priority");
    if (!priority.equals(actualPriority)) {
      failures.add("Expected priority=" + priority + ", got=" + actualPriority);
    }
    return this;
  }

  /**
   * Assert message type (uses sealed type name).
   */
  public MessageAssertions hasType(String expectedType) {
    var actualType = message.getClass().getSimpleName();
    if (!expectedType.equals(actualType)) {
      failures.add("Expected type=" + expectedType + ", got=" + actualType);
    }
    return this;
  }

  /**
   * Assert message is not null.
   */
  public MessageAssertions isNotNull() {
    if (message == null) {
      failures.add("Expected message to be non-null");
    }
    return this;
  }

  /**
   * Assert message field has expected value (reflection-based).
   */
  public MessageAssertions hasField(String fieldName, Object expectedValue) {
    var actualValue = getFieldValue(message, fieldName);
    if (!Objects.equals(expectedValue, actualValue)) {
      failures.add("Expected " + fieldName + "=" + expectedValue + ", got=" + actualValue);
    }
    return this;
  }

  /**
   * Assert message has a field (exists).
   */
  public MessageAssertions hasField(String fieldName) {
    if (getFieldValue(message, fieldName) == null && !hasRecordComponent(fieldName)) {
      failures.add("Expected field " + fieldName + " to exist");
    }
    return this;
  }

  /**
   * Check all assertions. Throws if any failed.
   */
  public void assertSucceeds() {
    if (!failures.isEmpty()) {
      throw new AssertionError("Message assertions failed:\n" + String.join("\n", failures));
    }
  }

  /**
   * Get summary of failures without throwing.
   */
  public String getFailureSummary() {
    return failures.isEmpty() ? "No failures" : String.join("\n", failures);
  }

  /**
   * Check if record has component (Java 26 reflection API).
   */
  private boolean hasRecordComponent(String fieldName) {
    var recordClass = message.getClass();
    if (!recordClass.isRecord()) {
      return false;
    }
    var components = recordClass.getRecordComponents();
    for (var component : components) {
      if (component.getName().equals(fieldName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get field value via reflection (supports records and regular classes).
   */
  private static Object getFieldValue(Object obj, String fieldName) {
    if (obj == null) return null;

    var clazz = obj.getClass();

    // Try record components first (Java 26)
    if (clazz.isRecord()) {
      var components = clazz.getRecordComponents();
      for (var component : components) {
        if (component.getName().equals(fieldName)) {
          try {
            return component.getAccessor().invoke(obj);
          } catch (Exception e) {
            return null;
          }
        }
      }
    }

    // Try regular field
    try {
      var field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(obj);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }
}
