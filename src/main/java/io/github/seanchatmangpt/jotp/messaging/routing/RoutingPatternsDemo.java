package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcessRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Routing Patterns Demo — demonstrates DynamicRouter and RecipientListRouter in action.
 *
 * <p>Simulates an order processing system with:
 * <ul>
 *   <li>DynamicRouter: Routes orders and payments to different services based on message type
 *   <li>RecipientListRouter: Broadcasts order events to multiple recipients (audit, notifications,
 *       analytics)
 * </ul>
 */
public class RoutingPatternsDemo {

  private static final List<String> auditLog = new CopyOnWriteArrayList<>();

  public static void main(String[] args) {
    System.out.println("=== JOTP Routing Patterns Demo ===\n");

    demonstrateDynamicRouter();
    System.out.println("\n---\n");
    demonstrateRecipientListRouter();

    System.out.println("\n=== Demo Complete ===");
  }

  /**
   * Demonstrates DynamicRouter: late-binding routing based on message type.
   */
  private static void demonstrateDynamicRouter() {
    System.out.println("Demo 1: DynamicRouter (Message-Type Based Routing)");
    System.out.println("----------------------------------------------");

    // Create service processes
    List<String> orderService = new CopyOnWriteArrayList<>();
    List<String> paymentService = new CopyOnWriteArrayList<>();
    List<String> shippingService = new CopyOnWriteArrayList<>();

    ProcRef<String, String> orderRef =
        Proc.spawn(
            "order-service",
            () -> orderService,
            (state, msg) -> {
              state.add("[ORDER] " + msg);
              System.out.println("  -> Order Service received: " + msg);
              return state;
            });

    ProcRef<String, String> paymentRef =
        Proc.spawn(
            "payment-service",
            () -> paymentService,
            (state, msg) -> {
              state.add("[PAYMENT] " + msg);
              System.out.println("  -> Payment Service received: " + msg);
              return state;
            });

    ProcRef<String, String> shippingRef =
        Proc.spawn(
            "shipping-service",
            () -> shippingService,
            (state, msg) -> {
              state.add("[SHIPPING] " + msg);
              System.out.println("  -> Shipping Service received: " + msg);
              return state;
            });

    // Create dynamic router with type-based resolver
    DynamicRouter<String> router =
        new DynamicRouter<>(msg -> {
          if (msg.startsWith("ORDER:")) return "order-service";
          if (msg.startsWith("PAYMENT:")) return "payment-service";
          if (msg.startsWith("SHIP:")) return "shipping-service";
          return "order-service"; // default
        });

    // Register handlers for logging
    router.registerHandler("order-service", msg -> System.out.println("  [BEFORE ROUTE] Order: " + msg));
    router.registerHandler("payment-service", msg -> System.out.println("  [BEFORE ROUTE] Payment: " + msg));
    router.registerHandler("shipping-service", msg -> System.out.println("  [BEFORE ROUTE] Shipping: " + msg));

    // Route various messages
    System.out.println("\nRouting messages based on type...\n");

    router.route("ORDER:12345|Customer123|$99.99");
    router.route("PAYMENT:12345|CreditCard");
    router.route("SHIP:12345|Address123");
    router.route("ORDER:67890|Customer456|$149.99");
    router.route("PAYMENT:67890|PayPal");

    try {
      Thread.sleep(100); // Give virtual threads time to process
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    System.out.println("\nMessages received:");
    System.out.println("  Order Service: " + orderService.size() + " messages");
    System.out.println("  Payment Service: " + paymentService.size() + " messages");
    System.out.println("  Shipping Service: " + shippingService.size() + " messages");

    // Cleanup
    ProcessRegistry.unregister("order-service");
    ProcessRegistry.unregister("payment-service");
    ProcessRegistry.unregister("shipping-service");
  }

  /**
   * Demonstrates RecipientListRouter: fan-out to multiple recipients.
   */
  private static void demonstrateRecipientListRouter() {
    System.out.println("Demo 2: RecipientListRouter (Fan-Out / Broadcast)");
    System.out.println("---------------------------------------------");

    // Create recipient processes
    List<String> auditMessages = new CopyOnWriteArrayList<>();
    List<String> notificationMessages = new CopyOnWriteArrayList<>();
    List<String> analyticsMessages = new CopyOnWriteArrayList<>();

    ProcRef<String, String> auditRef =
        Proc.spawn(
            "audit-service",
            () -> auditMessages,
            (state, msg) -> {
              state.add(msg);
              System.out.println("  -> Audit Service: " + msg);
              return state;
            });

    ProcRef<String, String> notificationRef =
        Proc.spawn(
            "notification-service",
            () -> notificationMessages,
            (state, msg) -> {
              state.add(msg);
              System.out.println("  -> Notification Service: " + msg);
              return state;
            });

    ProcRef<String, String> analyticsRef =
        Proc.spawn(
            "analytics-service",
            () -> analyticsMessages,
            (state, msg) -> {
              state.add(msg);
              System.out.println("  -> Analytics Service: " + msg);
              return state;
            });

    // Create recipient list router
    RecipientListRouter<String> router = new RecipientListRouter<>();
    router.addRecipient(auditRef);
    router.addRecipient(notificationRef);
    router.addRecipient(analyticsRef);

    System.out.println("\nBroadcasting order events to " + router.recipientCount() + " recipients...\n");

    // Broadcast order events to all recipients
    String event1 = "OrderCreated(id=ORD-001, amount=99.99, customer=Alice)";
    String event2 = "OrderConfirmed(id=ORD-001, timestamp=2025-03-12T10:15:00Z)";
    String event3 = "OrderShipped(id=ORD-001, carrier=FedEx, tracking=123ABC)";

    router.broadcastMessage(event1);
    router.broadcastMessage(event2);
    router.broadcastMessage(event3);

    try {
      Thread.sleep(100); // Give virtual threads time to process
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    System.out.println("\nBroadcast Results:");
    System.out.println("  Audit Service received: " + auditMessages.size() + " events");
    System.out.println("  Notification Service received: " + notificationMessages.size() + " events");
    System.out.println("  Analytics Service received: " + analyticsMessages.size() + " events");

    System.out.println("\nWaiting for all recipients to process (timeout: 5s)...");
    boolean allProcessed = router.waitForAll(Duration.ofSeconds(5));
    System.out.println("  All processed: " + allProcessed);

    // Cleanup
    ProcessRegistry.unregister("audit-service");
    ProcessRegistry.unregister("notification-service");
    ProcessRegistry.unregister("analytics-service");
  }
}
