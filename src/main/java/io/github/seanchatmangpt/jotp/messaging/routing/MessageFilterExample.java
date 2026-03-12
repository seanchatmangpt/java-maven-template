package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example of MessageFilter filtering messages and forwarding matching ones.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Creating a filter with a predicate
 *   <li>Chaining filters in sequence
 *   <li>Fire-and-forget filtering semantics
 *   <li>Statistics tracking (received, forwarded, dropped)
 * </ul>
 */
public class MessageFilterExample {

    record Message(String id, int value, String category) {}

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Message Filter Example ===\n");

        // Counters for tracking message flow
        AtomicInteger finalDelivered = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3); // Expect 3 final messages

        // Create a final destination (end of filter chain)
        MessageFilter.Destination<Message> finalDestination = msg -> {
            System.out.println("[FINAL] Delivered: " + msg);
            finalDelivered.incrementAndGet();
            latch.countDown();
        };

        // Create filter chain:
        // 1. First filter: passes only value > 5 (drops low values)
        // 2. Second filter: passes only category "IMPORTANT" (drops others)

        // Filter 2: Category check
        MessageFilter<Message> categoryFilter =
                MessageFilter.create(msg -> msg.category().equals("IMPORTANT"), finalDestination);

        // Filter 1: Value check
        MessageFilter<Message> valueFilter =
                MessageFilter.create(
                        msg -> msg.value() > 5,
                        categoryFilter::filter);

        // Send test messages
        System.out.println("Processing messages through filter chain:\n");

        valueFilter.filter(new Message("msg1", 10, "IMPORTANT"));    // PASS (value=10, cat=IMPORTANT)
        valueFilter.filter(new Message("msg2", 3, "IMPORTANT"));     // DROP (value=3 <= 5)
        valueFilter.filter(new Message("msg3", 8, "SPAM"));          // DROP (category != IMPORTANT)
        valueFilter.filter(new Message("msg4", 6, "IMPORTANT"));     // PASS (value=6, cat=IMPORTANT)
        valueFilter.filter(new Message("msg5", 9, "IMPORTANT"));     // PASS (value=9, cat=IMPORTANT)
        valueFilter.filter(new Message("msg6", 2, "SPAM"));          // DROP (value=2 <= 5)

        // Wait for final deliveries
        latch.await();

        System.out.println("\n=== Results ===");
        System.out.println("Total messages sent to value filter: 6");
        System.out.println("Messages passing value filter (value > 5): 4");
        System.out.println("Messages passing category filter (IMPORTANT): 3");
        System.out.println("Final delivered count: " + finalDelivered.get());

        // Clean shutdown
        valueFilter.process().stop();
        categoryFilter.process().stop();
    }
}
