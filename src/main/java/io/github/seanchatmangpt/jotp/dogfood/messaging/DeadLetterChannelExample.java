package io.github.seanchatmangpt.jotp.dogfood.messaging;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.system.DeadLetterChannel;
import java.time.Duration;

/**
 * Runnable example demonstrating Dead Letter Channel pattern with poison pill recovery.
 *
 * <p>Scenario: An order processing system receives orders to validate. Some orders
 * are "poison pills" (malformed) that cause validation to fail. The Dead Letter Channel
 * captures these failed messages for inspection while keeping the main handler alive.
 */
public final class DeadLetterChannelExample {

    /**
     * Represents an order record.
     */
    sealed interface OrderMsg permits OrderMsg.Order, OrderMsg.PoisonPill {
        record Order(String orderId, double amount) implements OrderMsg {
            Order {
                if (orderId == null || orderId.isBlank()) {
                    throw new IllegalArgumentException("orderId must not be blank");
                }
                if (amount < 0) {
                    throw new IllegalArgumentException("amount must not be negative");
                }
            }
        }

        record PoisonPill() implements OrderMsg {}
    }

    /**
     * System message for monitoring.
     */
    sealed interface SystemMsg permits SystemMsg.Report {
        record Report() implements SystemMsg {}
    }

    /**
     * Main entry point demonstrating dead letter channel recovery.
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Dead Letter Channel Example: Poison Pill Recovery ===\n");

        // Create dead letter channel for failed orders
        var dlc = DeadLetterChannel.<OrderMsg>create();

        // Create supervisor for order processing
        var supervisor =
                new Supervisor(
                        "order-supervisor",
                        Supervisor.Strategy.ONE_FOR_ONE,
                        5,
                        Duration.ofSeconds(60));

        // Process orders with automatic crash recovery
        var orderHandler =
                supervisor.supervise(
                        "order-handler",
                        dlc,
                        dlc.withCrashHandler(
                                msg -> {
                                    switch (msg) {
                                        case OrderMsg.Order order -> {
                                            // Validate and process
                                            if (order.amount() < 0) {
                                                throw new IllegalArgumentException(
                                                    "Negative amount: " + order.amount());
                                            }
                                            System.out.println(
                                                "✓ Order processed: "
                                                    + order.orderId()
                                                    + " for $"
                                                    + order.amount());
                                        }
                                        case OrderMsg.PoisonPill pill -> {
                                            // This is our poison pill — throw to trigger DLC
                                            throw new IllegalStateException(
                                                "Poison pill: intentional crash for recovery demo");
                                        }
                                    }
                                }));

        // Send valid orders
        orderHandler.tell(new OrderMsg.Order("ORD-001", 99.99));
        orderHandler.tell(new OrderMsg.Order("ORD-002", 149.50));

        // Send poison pill
        System.out.println("\n→ Sending poison pill to trigger dead letter routing...");
        orderHandler.tell(new OrderMsg.PoisonPill());

        // Give the supervisor time to process
        Thread.sleep(500);

        // Send more valid orders
        orderHandler.tell(new OrderMsg.Order("ORD-003", 49.99));

        Thread.sleep(500);

        // Inspect dead letters
        System.out.println("\n=== Dead Letter Channel Inspection ===");
        var deadLetters = dlc.drain();
        if (deadLetters.isEmpty()) {
            System.out.println("(No dead letters)");
        } else {
            System.out.println("Dead letters captured: " + deadLetters.size());
            for (var dl : deadLetters) {
                System.out.println("  Message: " + dl.message());
                System.out.println("  Reason: " + dl.reason());
                System.out.println("  Arrived: " + dl.arrivedAt());
                System.out.println();
            }
        }

        System.out.println("=== Example Complete ===");
        orderHandler.stop();
        System.out.println("Order handler stopped gracefully.");
    }
}
