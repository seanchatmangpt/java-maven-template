package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.*;

/**
 * Runnable example: Order splitting into line items.
 *
 * <p>Demonstrates the Splitter pattern by taking a complete order with 5 line items and breaking
 * it into individual order-item messages. Each part is assigned a sequence number and correlation
 * ID for downstream reassembly.
 */
public final class SplitterExample {

  /** An immutable order with line items. */
  public record Order(String orderId, List<LineItem> items) {}

  /** A single line item. */
  public record LineItem(String sku, int quantity, BigDecimal unitPrice) {}

  /** Individual order-item message (one per line item). */
  public record OrderItemMessage(
      String orderId, String sku, int quantity, BigDecimal unitPrice) {}

  /** Use boxed BigDecimal for simplicity in this example. */
  public static final class BigDecimal implements Comparable<BigDecimal> {
    private final double value;

    public BigDecimal(double value) {
      this.value = value;
    }

    public double asDouble() {
      return value;
    }

    @Override
    public String toString() {
      return String.format("%.2f", value);
    }

    @Override
    public int compareTo(BigDecimal other) {
      return Double.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof BigDecimal bd && Double.compare(value, bd.value) == 0;
    }

    @Override
    public int hashCode() {
      return Double.hashCode(value);
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("=== Splitter Example: Order Line Item Splitting ===\n");

    // Create a complete order with 5 line items
    Order order =
        new Order(
            "ORD-2025-001",
            List.of(
                new LineItem("SKU-A", 2, new BigDecimal(19.99)),
                new LineItem("SKU-B", 1, new BigDecimal(49.99)),
                new LineItem("SKU-C", 3, new BigDecimal(9.99)),
                new LineItem("SKU-D", 1, new BigDecimal(99.99)),
                new LineItem("SKU-E", 5, new BigDecimal(5.99))));

    System.out.println("Original order: " + order.orderId());
    System.out.println("Line items: " + order.items().size());
    order.items()
        .forEach(
            item ->
                System.out.println(
                    "  - " + item.sku() + " x" + item.quantity() + " @ $" + item.unitPrice()));

    // Split the order into individual order-item messages
    var parts =
        Splitter.split(
            order,
            o ->
                o.items().stream()
                    .map(
                        item ->
                            new OrderItemMessage(
                                o.orderId(), item.sku(), item.quantity(), item.unitPrice()))
                    .toList());

    System.out.println("\n--- Split Result ---");
    System.out.println("Total parts: " + parts.size());
    System.out.println("Correlation ID: " + parts.get(0).metadata().correlationId());

    // Simulate sending parts to different systems
    System.out.println("\nRoute parts to downstream systems:\n");

    for (Splitter.MessagePart<OrderItemMessage> part : parts) {
      var msg = part.payload();
      var meta = part.metadata();
      System.out.println(
          String.format(
              "[%s] Part %d/%d: Order %s, Item %s x%d @ $%s",
              meta.correlationId(),
              meta.sequenceNumber(),
              meta.totalParts(),
              msg.orderId(),
              msg.sku(),
              msg.quantity(),
              msg.unitPrice()));

      // Simulate routing logic
      String destination = determineDestination(msg.sku());
      System.out.println("  -> Route to: " + destination);
    }

    // Example: Use partition for batch splitting (group items into batches of 2)
    System.out.println("\n--- Partition Example (batch by 2) ---");

    record OrderBatch(String orderId, List<LineItem> items) {}

    var batches =
        Splitter.partition(
            order,
            OrderBatch::items,
            2,
            items -> new OrderBatch(order.orderId(), items));

    System.out.println("Batch partitions: " + batches.size());
    batches.forEach(
        batch -> {
          var msg = batch.payload();
          var meta = batch.metadata();
          System.out.println(
              String.format(
                  "Batch %d/%d: %d items",
                  meta.sequenceNumber(), meta.totalParts(), msg.items().size()));
          msg.items()
              .forEach(
                  item ->
                      System.out.println(
                          "  - " + item.sku() + " x" + item.quantity()));
        });

    System.out.println("\n=== Example Complete ===");
  }

  private static String determineDestination(String sku) {
    if (sku.compareTo("SKU-C") < 0) return "InventorySystem-A";
    if (sku.compareTo("SKU-E") <= 0) return "InventorySystem-B";
    return "InventorySystem-Default";
  }
}
