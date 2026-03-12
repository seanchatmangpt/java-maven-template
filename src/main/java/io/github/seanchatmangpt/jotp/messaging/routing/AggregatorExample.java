package io.github.seanchatmangpt.jotp.messaging.routing;

import java.time.*;
import java.util.*;

/**
 * Runnable example: Reassembling order items back into a complete order.
 *
 * <p>Demonstrates the Aggregator pattern by receiving order-item messages (potentially out of
 * order) and combining them back into a complete order. Shows timeout handling and
 * out-of-order part assembly.
 */
public final class AggregatorExample {

  public record OrderItemMessage(String orderId, String sku, int quantity, double unitPrice) {}

  public record CompleteOrder(String orderId, List<OrderItemMessage> items) {
    @Override
    public String toString() {
      return String.format(
          "Order[%s with %d items]",
          orderId, items.size());
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("=== Aggregator Example: Out-of-Order Order Item Assembly ===\n");

    // Create an aggregator that expects 5 parts, times out after 30 seconds
    var aggregator =
        Aggregator.create(
            5,
            Duration.ofSeconds(30),
            items -> new CompleteOrder("ORD-2025-001", items));

    System.out.println("Aggregator created:");
    System.out.println("  Expected parts: " + aggregator.expectedPartCount());
    System.out.println("  Timeout: " + aggregator.timeout());

    // Simulate receiving order items OUT OF ORDER
    System.out.println("\nSimulating out-of-order part arrival:\n");

    // Create individual part messages (out of order)
    var partOrder = List.of(3, 1, 5, 2, 4); // sequence numbers in arrival order
    UUID correlationId = UUID.randomUUID();

    System.out.println("Correlation ID: " + correlationId + "\n");

    var parts =
        createOrderItems(
            "ORD-2025-001",
            correlationId,
            List.of(
                new OrderItemMessage("ORD-2025-001", "SKU-A", 2, 19.99),
                new OrderItemMessage("ORD-2025-001", "SKU-B", 1, 49.99),
                new OrderItemMessage("ORD-2025-001", "SKU-C", 3, 9.99),
                new OrderItemMessage("ORD-2025-001", "SKU-D", 1, 99.99),
                new OrderItemMessage("ORD-2025-001", "SKU-E", 5, 5.99)));

    // Send parts in the order: 3, 1, 5, 2, 4
    boolean assembled = false;
    for (int seqNum : partOrder) {
      Splitter.MessagePart<OrderItemMessage> part = parts.get(seqNum - 1);
      var meta = part.metadata();

      System.out.println(
          String.format(
              "Aggregating Part %d/%d: %s x%d",
              meta.sequenceNumber(),
              meta.totalParts(),
              part.payload().sku(),
              part.payload().quantity()));

      var result = aggregator.aggregate(part);

      if (result.isPresent()) {
        CompleteOrder order = result.get();
        System.out.println("  ✓ Aggregation COMPLETE! " + order);
        System.out.println("  Items in order:");
        for (int i = 0; i < order.items().size(); i++) {
          OrderItemMessage item = order.items().get(i);
          System.out.println(
              String.format(
                  "    [%d] %s x%d @ $%.2f",
                  i + 1, item.sku(), item.quantity(), item.unitPrice()));
        }
        assembled = true;
        break;
      } else {
        System.out.println("  ✗ Still waiting for remaining parts...");
      }
    }

    if (!assembled) {
      System.out.println("ERROR: Aggregation did not complete!");
    }

    // Example 2: Timeout scenario
    System.out.println("\n--- Timeout Scenario (Short Timeout for Demo) ---\n");

    var shortTimeoutAgg =
        Aggregator.create(
            3,
            Duration.ofMillis(500),
            items -> new CompleteOrder("ORD-2025-002", items));

    UUID corrId2 = UUID.randomUUID();
    var itemsPart1 =
        Splitter.split(
            new OrderItemMessage("ORD-2025-002", "SKU-X", 1, 10.0),
            msg -> List.of(msg),
            corrId2);

    // Aggregate first part
    var part1 = new Splitter.MessagePart<>(
        new OrderItemMessage("ORD-2025-002", "SKU-X", 1, 10.0),
        new Splitter.PartMetadata(corrId2, 1, 3));

    System.out.println("Aggregating Part 1/3 of order ORD-2025-002...");
    var res1 = shortTimeoutAgg.aggregate(part1);
    System.out.println("  Result: " + (res1.isPresent() ? "Complete" : "Incomplete"));

    // Wait for timeout
    System.out.println("Waiting 1 second (timeout is 500ms)...");
    Thread.sleep(1000);

    // Try to add another part after timeout
    System.out.println("Attempting to aggregate Part 2 after timeout...");
    var part2 = new Splitter.MessagePart<>(
        new OrderItemMessage("ORD-2025-002", "SKU-Y", 2, 20.0),
        new Splitter.PartMetadata(corrId2, 2, 3));

    try {
      var res2 = shortTimeoutAgg.aggregate(part2);
      System.out.println("  ERROR: Should have timed out!");
    } catch (IllegalStateException e) {
      System.out.println("  ✓ Caught expected timeout: " + e.getMessage());
    }

    System.out.println("\n=== Example Complete ===");
  }

  private static List<Splitter.MessagePart<OrderItemMessage>> createOrderItems(
      String orderId,
      UUID correlationId,
      List<OrderItemMessage> items) {
    return Splitter.split(
        items.get(0),
        _ -> items,
        correlationId);
  }
}
