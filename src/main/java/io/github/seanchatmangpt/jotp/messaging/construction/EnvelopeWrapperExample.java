package io.github.seanchatmangpt.jotp.messaging.construction;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real-world example: EnvelopeWrapper pattern for distributed request tracing.
 *
 * <p>Scenario: An e-commerce order processing system with multiple microservices
 * (OrderService -> PaymentService -> InventoryService -> ShippingService). Each request
 * flows through several services; we need to trace the entire chain via correlation ID,
 * enforce deadline SLAs, and enable idempotent retries.
 *
 * <p>Benefits of EnvelopeWrapper:
 * <ul>
 *   <li><strong>Correlation ID:</strong> Single trace ID follows order through all services
 *   <li><strong>Idempotency:</strong> "idempotency-key" header prevents double-charging on retry
 *   <li><strong>SLA/Deadline:</strong> "deadline" header lets workers prioritize expiring requests
 *   <li><strong>Versioning:</strong> "api-version" header enables safe schema evolution
 *   <li><strong>Headers are extensible:</strong> New headers added without changing payload type
 * </ul>
 */
public class EnvelopeWrapperExample {

  /**
   * Domain model: an order request.
   */
  record OrderRequest(
      String orderId,
      String customerId,
      double totalAmount) {
    OrderRequest {
      if (orderId == null || orderId.isBlank()) {
        throw new IllegalArgumentException("orderId must not be blank");
      }
      if (customerId == null || customerId.isBlank()) {
        throw new IllegalArgumentException("customerId must not be blank");
      }
      if (totalAmount <= 0) {
        throw new IllegalArgumentException("totalAmount must be positive");
      }
    }
  }

  /**
   * Create an order request envelope with full tracing/SLA metadata.
   *
   * @param order the order to wrap
   * @param correlationId trace ID for this request chain
   * @param deadlineMs deadline milliseconds (for SLA enforcement)
   * @return Envelope<OrderRequest> with headers set
   */
  public static EnvelopeWrapper.Envelope<OrderRequest> createOrderEnvelope(
      OrderRequest order,
      String correlationId,
      long deadlineMs) {

    return EnvelopeWrapper.wrap(
        order,
        headers -> {
          headers.put("correlation-id", correlationId);
          headers.put("request-id", UUID.randomUUID().toString());
          headers.put("deadline", String.valueOf(deadlineMs));
          headers.put("priority", "normal");
          headers.put("api-version", "1.0");
          headers.put("source-service", "order-gateway");
          headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
        });
  }

  /**
   * Forward an order to payment service, preserving correlation ID and adding breadcrumb.
   *
   * <p>This demonstrates header propagation across service boundaries.
   *
   * @param envelope the order envelope from OrderService
   * @param targetService name of the service this is being forwarded to
   * @return new Envelope with "source-service" updated
   */
  public static EnvelopeWrapper.Envelope<OrderRequest> forwardToPaymentService(
      EnvelopeWrapper.Envelope<OrderRequest> envelope,
      String targetService) {

    // Preserve all headers, but update the source-service breadcrumb
    return envelope.withHeader("previous-service",
        envelope.getHeaderOrDefault("source-service", "unknown"))
        .withHeader("source-service", targetService);
  }

  /**
   * Check if an order envelope has expired (deadline passed).
   *
   * @param envelope the order envelope
   * @return true if current time exceeds deadline; false otherwise
   */
  public static boolean isExpired(EnvelopeWrapper.Envelope<OrderRequest> envelope) {
    String deadlineStr = envelope.getDeadline();
    if (deadlineStr == null) {
      return false; // no deadline set
    }
    try {
      long deadline = Long.parseLong(deadlineStr);
      return System.currentTimeMillis() > deadline;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Check if an order has been processed before (idempotency check).
   *
   * <p>In a real system, this would check a database of processed requests.
   * For the example, we return false (not processed).
   *
   * @param envelope the order envelope
   * @return true if this request was already processed; false otherwise
   */
  public static boolean alreadyProcessed(EnvelopeWrapper.Envelope<OrderRequest> envelope) {
    String idempotencyKey = envelope.getHeader("idempotency-key");
    // In reality: SELECT COUNT(*) FROM processed_requests WHERE key = idempotencyKey
    return idempotencyKey != null && idempotencyKey.startsWith("DUPLICATE");
  }

  /**
   * Extract metrics from an order envelope for observability.
   *
   * @param envelope the order envelope
   * @return OrderMetrics suitable for logging/monitoring
   */
  public static OrderMetrics extractMetrics(EnvelopeWrapper.Envelope<OrderRequest> envelope) {
    OrderRequest order = envelope.unwrap();
    String correlationId = envelope.getCorrelationId();
    String sourceService = envelope.getHeaderOrDefault("source-service", "unknown");
    String priority = envelope.getHeaderOrDefault("priority", "normal");

    return new OrderMetrics(
        correlationId,
        envelope.id().toString(),
        sourceService,
        priority,
        order.orderId(),
        order.customerId(),
        order.totalAmount(),
        isExpired(envelope),
        alreadyProcessed(envelope));
  }

  /**
   * Metrics extracted from an order envelope (for structured logging).
   */
  record OrderMetrics(
      String correlationId,
      String envelopeId,
      String sourceService,
      String priority,
      String orderId,
      String customerId,
      double totalAmount,
      boolean isExpired,
      boolean isDuplicate) {}

  /**
   * Add urgency flag based on amount (high-value orders get priority).
   *
   * @param envelope the order envelope
   * @return new Envelope with priority header updated
   */
  public static EnvelopeWrapper.Envelope<OrderRequest> prioritizeHighValue(
      EnvelopeWrapper.Envelope<OrderRequest> envelope) {
    OrderRequest order = envelope.unwrap();
    String newPriority = order.totalAmount() > 1000 ? "high" : "normal";
    return envelope.withHeader("priority", newPriority);
  }

  /**
   * Generate a human-readable trace path for debugging.
   *
   * <p>Example: "order-gateway -> payment-service -> inventory-service"
   *
   * @param envelope the order envelope
   * @return comma-separated service chain
   */
  public static String getTracePath(EnvelopeWrapper.Envelope<OrderRequest> envelope) {
    String current = envelope.getHeaderOrDefault("source-service", "unknown");
    String previous = envelope.getHeader("previous-service");
    if (previous != null) {
      return previous + " -> " + current;
    }
    return current;
  }

  private EnvelopeWrapperExample() {
    // example class
  }
}
