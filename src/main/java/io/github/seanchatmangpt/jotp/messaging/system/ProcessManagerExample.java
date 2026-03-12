package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.Result;
import java.util.Objects;

/**
 * Runnable example of the ProcessManager pattern.
 *
 * <p>Demonstrates a multi-step order workflow:
 * 1. Validate order (check required fields)
 * 2. Reserve inventory (allocate stock)
 * 3. Charge payment (process card)
 * 4. Ship order (create shipment)
 *
 * <p>On failure at any step, rollback is executed in reverse order.
 *
 * <p>Run with: java io.github.seanchatmangpt.jotp.messaging.system.ProcessManagerExample
 */
public class ProcessManagerExample {

  /** Order context carries state across all steps. */
  static class OrderContext {}

  /** Immutable order record. */
  record Order(String id, String item, double amount, boolean valid, boolean inStock, boolean charged) {
    Order withValid(boolean valid) {
      return new Order(id, item, amount, valid, inStock, charged);
    }

    Order withInStock(boolean inStock) {
      return new Order(id, item, amount, valid, inStock, charged);
    }

    Order withCharged(boolean charged) {
      return new Order(id, item, amount, valid, inStock, charged);
    }

    @Override
    public String toString() {
      return String.format(
          "Order{id=%s, item=%s, amount=%.2f, valid=%b, inStock=%b, charged=%b}",
          id, item, amount, valid, inStock, charged);
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("=== ProcessManager Pattern Example ===\n");

    // Create workflow manager
    var pm = new ProcessManager<OrderContext>("order-workflow");

    // Step 1: Validate order
    pm.step(
        "validate",
        (ctx, input) -> {
          var order = (Order) input;
          System.out.println("[Step 1: Validate] Processing: " + order);
          if (!order.valid) {
            throw new IllegalArgumentException("Order is invalid");
          }
          System.out.println("[Step 1: Validate] ✓ Order validated");
          return order.withValid(true);
        },
        (ctx, output) -> {
          System.out.println("[Rollback: Validate] Reversing validation");
          return null;
        });

    // Step 2: Reserve inventory
    pm.step(
        "reserve",
        (ctx, input) -> {
          var order = (Order) input;
          System.out.println("[Step 2: Reserve] Checking stock for: " + order.item);
          if (!order.inStock) {
            throw new IllegalArgumentException("Item out of stock: " + order.item);
          }
          System.out.println("[Step 2: Reserve] ✓ Inventory reserved");
          return order.withInStock(true);
        },
        (ctx, output) -> {
          var order = (Order) output;
          System.out.println("[Rollback: Reserve] Releasing inventory for: " + order.item);
          return null;
        });

    // Step 3: Charge payment
    pm.step(
        "charge",
        (ctx, input) -> {
          var order = (Order) input;
          System.out.println("[Step 3: Charge] Charging: $" + order.amount);
          // Simulate charge failure for demo
          if (order.amount > 1000) {
            throw new RuntimeException("Charge declined: amount exceeds limit");
          }
          System.out.println("[Step 3: Charge] ✓ Payment processed");
          return order.withCharged(true);
        },
        (ctx, output) -> {
          var order = (Order) output;
          System.out.println("[Rollback: Charge] Refunding payment: $" + order.amount);
          return null;
        });

    // Step 4: Ship order
    pm.step(
        "ship",
        (ctx, input) -> {
          var order = (Order) input;
          System.out.println("[Step 4: Ship] Creating shipment for order: " + order.id);
          System.out.println("[Step 4: Ship] ✓ Shipment created");
          return order;
        },
        (ctx, output) -> {
          var order = (Order) output;
          System.out.println("[Rollback: Ship] Cancelling shipment: " + order.id);
          return null;
        });

    // Test case 1: Successful workflow
    System.out.println("\n--- Test Case 1: Successful Workflow ---");
    var order1 = new Order("ORD-001", "Widget", 99.99, true, true, false);
    var result1 = pm.start(order1).join();

    System.out.println(
        "\nWorkflow result: "
            + result1.fold(
                ctx -> "✓ SUCCESS - executed steps: " + ctx.getExecutedSteps(),
                error -> "✗ FAILED - " + error));

    pm.stop();

    // Test case 2: Failure and rollback
    System.out.println("\n\n--- Test Case 2: Failure with Rollback ---");
    var pm2 = new ProcessManager<OrderContext>("order-workflow-2");

    pm2.step(
            "validate",
            (ctx, input) -> {
              var order = (Order) input;
              System.out.println("[Step 1: Validate] Processing: " + order);
              if (!order.valid) {
                throw new IllegalArgumentException("Order is invalid");
              }
              return order.withValid(true);
            },
            (ctx, output) -> {
              System.out.println("[Rollback: Validate] Reversing validation");
              return null;
            })
        .step(
            "reserve",
            (ctx, input) -> {
              var order = (Order) input;
              System.out.println("[Step 2: Reserve] Checking stock for: " + order.item);
              if (!order.inStock) {
                throw new IllegalArgumentException("Item out of stock: " + order.item);
              }
              return order.withInStock(true);
            },
            (ctx, output) -> {
              System.out.println("[Rollback: Reserve] Releasing inventory");
              return null;
            })
        .step(
            "charge",
            (ctx, input) -> {
              var order = (Order) input;
              System.out.println("[Step 3: Charge] Charging: $" + order.amount);
              throw new RuntimeException("Payment declined: insufficient funds");
            },
            (ctx, output) -> {
              System.out.println("[Rollback: Charge] Refunding payment");
              return null;
            });

    var order2 = new Order("ORD-002", "Gadget", 299.99, true, true, false);
    var result2 = pm2.start(order2).join();

    System.out.println(
        "\nWorkflow result: "
            + result2.fold(
                ctx -> "✓ SUCCESS - executed steps: " + ctx.getExecutedSteps(),
                error -> "✗ FAILED - " + error + "\n  Executed before failure: " + order2));

    pm2.stop();

    System.out.println("\n=== Example Complete ===");
  }
}
