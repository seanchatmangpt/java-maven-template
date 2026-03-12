package io.github.seanchatmangpt.jotp.messaging.construction;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * CommandMessage pattern — Vaughn Vernon's "Command Message" for reactive messaging.
 *
 * <p>A command message carries a request with an embedded reply-to address, enabling
 * request-reply communication over asynchronous channels. The reply address is typically
 * a {@link ProcRef} that can receive the response.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Carries a command (command type + payload)</li>
 *   <li>Embedded reply-to address for receiving responses</li>
 *   <li>Optional correlation ID for tracking request-response pairs</li>
 *   <li>Optional timeout for reply wait</li>
 *   <li>Immutable record type</li>
 * </ul>
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * record PlaceOrder(String customerId, Order details) {}
 * record OrderPlaced(String orderId) {}
 *
 * var command = CommandMessage.create(
 *     "PlaceOrder",
 *     new PlaceOrder("cust-123", order),
 *     replyTo  // ProcRef<OrderService.State, OrderService.Message>
 * ).withCorrelationId(UUID.randomUUID());
 *
 * orderService.tell(command);
 * }</pre>
 *
 * <p>Joe Armstrong: "Pattern matching on messages is essential for reliable distributed systems."
 *
 * @param <R> reply message type
 */
public sealed interface CommandMessage<R extends Serializable> extends Serializable
    permits CommandMessage.Impl {

  /** Unique message identifier. */
  UUID messageId();

  /** System timestamp when created (milliseconds since epoch). */
  long createdAt();

  /** Command type identifier. */
  String commandType();

  /** Command payload (the actual request data). */
  Serializable payload();

  /** Reply-to address: can be a {@link ProcRef}, callback, or other return channel. */
  Object replyTo();

  /** Optional correlation ID for request-response correlation. */
  UUID correlationId();

  /** Optional timeout for awaiting reply (null if no timeout). */
  Duration timeout();

  /**
   * Create a new CommandMessage with the given command type, payload, and reply address.
   *
   * @param commandType command identifier (e.g., "PlaceOrder", "UpdateAccount")
   * @param payload immutable request data (use records for type safety)
   * @param replyTo reply address (typically a {@link ProcRef})
   * @return a new CommandMessage with random UUID and current timestamp
   * @throws IllegalArgumentException if commandType is blank or payload/replyTo is null
   */
  static <R extends Serializable> CommandMessage<R> create(
      String commandType, Serializable payload, Object replyTo) {
    return new Impl<>(
        UUID.randomUUID(), System.currentTimeMillis(), commandType, payload, replyTo, null, null);
  }

  /**
   * Add a correlation ID to this message for request-response tracking.
   *
   * @param correlationId correlation ID (usually UUID.randomUUID())
   * @return a new CommandMessage with the correlation ID set
   */
  default CommandMessage<R> withCorrelationId(UUID correlationId) {
    return new Impl<>(
        messageId(),
        createdAt(),
        commandType(),
        payload(),
        replyTo(),
        correlationId,
        timeout());
  }

  /**
   * Add a timeout duration to this message.
   *
   * @param duration timeout duration (e.g., Duration.ofSeconds(5))
   * @return a new CommandMessage with the timeout set
   * @throws IllegalArgumentException if duration is null or not positive
   */
  default CommandMessage<R> withTimeout(Duration duration) {
    Objects.requireNonNull(duration, "timeout duration must not be null");
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("timeout duration must be positive");
    }
    return new Impl<>(
        messageId(),
        createdAt(),
        commandType(),
        payload(),
        replyTo(),
        correlationId(),
        duration);
  }

  /**
   * Check if this message has a correlation ID set.
   *
   * @return true if correlationId is not null
   */
  default boolean hasCorrelationId() {
    return correlationId() != null;
  }

  /**
   * Check if this message has a timeout set.
   *
   * @return true if timeout is not null
   */
  default boolean hasTimeout() {
    return timeout() != null;
  }

  /**
   * Implementation record for CommandMessage.
   */
  record Impl<R extends Serializable>(
      UUID messageId,
      long createdAt,
      String commandType,
      Serializable payload,
      Object replyTo,
      UUID correlationId,
      Duration timeout)
      implements CommandMessage<R> {

    public Impl {
      Objects.requireNonNull(messageId, "messageId must not be null");
      Objects.requireNonNull(commandType, "commandType must not be null");
      Objects.requireNonNull(payload, "payload must not be null");
      Objects.requireNonNull(replyTo, "replyTo must not be null");
      if (commandType.isBlank()) {
        throw new IllegalArgumentException("commandType must not be blank");
      }
      if (createdAt < 0) {
        throw new IllegalArgumentException("createdAt must be non-negative");
      }
    }
  }
}
