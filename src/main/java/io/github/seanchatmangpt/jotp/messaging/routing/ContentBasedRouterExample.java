package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example of ContentBasedRouter routing messages to different destinations based on content.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Creating a router with multiple predicates
 *   <li>Routing messages based on content inspection
 *   <li>Setting a default route for unmatched messages
 *   <li>Fire-and-forget semantics
 * </ul>
 */
public class ContentBasedRouterExample {

    record Message(String type, int priority, String payload) {}

    public static void main(String[] args) throws InterruptedException {
        // Counters for each destination
        AtomicInteger urgentCount = new AtomicInteger(0);
        AtomicInteger normalCount = new AtomicInteger(0);
        AtomicInteger lowCount = new AtomicInteger(0);
        AtomicInteger defaultCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(6); // Expect 6 messages

        // Create router
        ContentBasedRouter<Message> router = new ContentBasedRouter<>();

        // Route 1: High priority (priority >= 8)
        router.addRoute(msg -> msg.priority() >= 8, msg -> {
            System.out.println("[URGENT] " + msg);
            urgentCount.incrementAndGet();
            latch.countDown();
        });

        // Route 2: Normal priority (priority 3-7)
        router.addRoute(
                msg -> msg.priority() >= 3 && msg.priority() < 8, msg -> {
            System.out.println("[NORMAL] " + msg);
            normalCount.incrementAndGet();
            latch.countDown();
        });

        // Route 3: Low priority (priority < 3)
        router.addRoute(msg -> msg.priority() < 3, msg -> {
            System.out.println("[LOW] " + msg);
            lowCount.incrementAndGet();
            latch.countDown();
        });

        // Default route for unmatched (catches everything via predicate returning false)
        router.setDefault(msg -> {
            System.out.println("[DEFAULT] " + msg);
            defaultCount.incrementAndGet();
            latch.countDown();
        });

        // Send test messages
        System.out.println("=== Content-Based Router Example ===\n");

        router.route(new Message("order", 9, "Fix production bug"));  // URGENT
        router.route(new Message("order", 5, "Regular order"));       // NORMAL
        router.route(new Message("order", 1, "Low priority task"));   // LOW
        router.route(new Message("alert", 9, "System failure"));      // URGENT
        router.route(new Message("info", 2, "Informational"));        // LOW
        router.route(new Message("unknown", 0, "No route"));          // DEFAULT

        // Wait for processing
        latch.await();

        System.out.println("\n=== Results ===");
        System.out.println("Urgent count: " + urgentCount.get());
        System.out.println("Normal count: " + normalCount.get());
        System.out.println("Low count: " + lowCount.get());
        System.out.println("Default count: " + defaultCount.get());

        // Clean shutdown (optional in this simple example)
        router.process().stop();
    }
}
