package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * Composed Message Processor pattern example — demonstrates declarative message pipeline DSL.
 *
 * <p><strong>Scenario:</strong> A logging framework receives raw log entries and must normalize,
 * filter, and route them through multiple stages: whitespace normalization, severity filtering,
 * PII redaction, and format enrichment.
 *
 * <p><strong>Pattern demonstration:</strong>
 *
 * <ul>
 *   <li>Compose multiple transformations (routers) sequentially
 *   <li>Apply filters that reject non-matching messages (null short-circuits)
 *   <li>Chain processors together for complex pipelines
 *   <li>Use peek for side-effects (logging, metrics)
 *   <li>All pure functions; no shared state
 * </ul>
 */
public final class ComposedMessageProcessorExample {

  record LogEntry(String level, String message) {
    @Override
    public String toString() {
      return "[" + level + "] " + message;
    }
  }

  public static void main(String[] args) {
    System.out.println("===== Composed Message Processor Pattern Example =====\n");
    System.out.println("Scenario: Multi-stage log processing pipeline\n");

    // Stage 1: Normalization routers
    var normalizer =
        ComposedMessageProcessor.compose(
                msg -> msg.trim(), // Strip whitespace
                msg -> msg.replaceAll("\\s+", " ")) // Normalize spaces
            .peek(msg -> System.out.println("  [NORMALIZED] " + msg));

    // Stage 2: Severity filters
    var severityFilter =
        ComposedMessageProcessor.compose(msg -> msg) // Identity (for demo)
            .thenFilter(msg -> !msg.startsWith("[DEBUG]")) // Reject debug in prod
            .peek(msg -> System.out.println("  [SEVERITY CHECK PASS] " + msg));

    // Stage 3: PII redaction router
    var redactor =
        ComposedMessageProcessor.compose(
                msg -> msg.replaceAll("\\d{3}-\\d{2}-\\d{4}", "***-**-****"), // SSN
                msg -> msg.replaceAll("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", "[EMAIL]")) // Email
            .peek(msg -> System.out.println("  [REDACTED] " + msg));

    // Stage 4: Format enrichment router
    var enricher =
        ComposedMessageProcessor.compose(
                msg -> "[LOG] " + msg + " [" + System.currentTimeMillis() % 10000 + "]")
            .peek(msg -> System.out.println("  [ENRICHED] " + msg));

    // Combine all stages
    var completePipeline =
        normalizer
            .andThen(severityFilter)
            .andThen(redactor)
            .andThen(enricher);

    System.out.println("--- Processing Log Entries ---\n");

    // Sample log entries (mixed quality)
    List<String> rawLogs = new ArrayList<>();
    rawLogs.add("  user@example.com registered with SSN 123-45-6789  ");
    rawLogs.add("[DEBUG] Internal state: x=42");
    rawLogs.add("Payment processed for john.doe@corp.com");
    rawLogs.add("  ERROR:  Multiple    spaces    normalized  ");
    rawLogs.add("");

    List<String> processedLogs = new ArrayList<>();

    for (String rawLog : rawLogs) {
      System.out.println("INPUT: \"" + rawLog + "\"");

      String processed = completePipeline.apply(rawLog);

      if (processed != null) {
        processedLogs.add(processed);
        System.out.println("OUTPUT: \"" + processed + "\"\n");
      } else {
        System.out.println("OUTPUT: [FILTERED OUT]\n");
      }
    }

    System.out.println("\n--- Summary ---\n");
    System.out.println("Total logs processed: " + (rawLogs.size()));
    System.out.println("Logs delivered: " + processedLogs.size());
    System.out.println("Logs filtered: " + (rawLogs.size() - processedLogs.size()));

    System.out.println("\n--- Advanced: Custom Message Type Pipeline ---\n");

    // Example 2: Routing orders through a fulfillment pipeline
    record Order(String id, double amount, String status) {
      @Override
      public String toString() {
        return "Order{id='" + id + "', amount=" + amount + ", status='" + status + "'}";
      }
    }

    var orderValidator =
        ComposedMessageProcessor.<Order>compose(
                order -> new Order(order.id.toUpperCase(), order.amount, order.status))
            .thenFilter(order -> order.amount > 0) // Reject zero/negative
            .peek(order -> System.out.println("  [VALIDATED] " + order));

    var orderEnricher =
        ComposedMessageProcessor.<Order>compose(
                order -> new Order(order.id, order.amount * 1.10, "ENRICHED"))
            .peek(order -> System.out.println("  [ENRICHED] Added 10% processing fee: " + order));

    var orderRouter =
        orderValidator
            .andThen(orderEnricher);

    System.out.println("Processing orders:\n");

    var validOrder = new Order("ORD-001", 99.99, "PENDING");
    var invalidOrder = new Order("ord-002", -50.0, "PENDING");

    var result1 = orderRouter.apply(validOrder);
    System.out.println("RESULT: " + result1 + "\n");

    var result2 = orderRouter.apply(invalidOrder);
    System.out.println("RESULT: " + result2 + "\n");

    System.out.println("===== Pattern Complete =====");
  }
}
