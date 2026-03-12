package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcessRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for routing patterns — verifies end-to-end scenarios combining DynamicRouter
 * and RecipientListRouter with multiple concurrent processes.
 *
 * <p>Tests real-world order processing scenarios with dynamic routing and fan-out messaging.
 */
@DisplayName("Routing Patterns Integration Tests")
class RoutingPatternsIT implements WithAssertions {

  @AfterEach
  void cleanup() {
    for (String name : ProcessRegistry.registered()) {
      ProcessRegistry.unregister(name);
    }
  }

  @Nested
  @DisplayName("Order Processing Workflow")
  class OrderProcessingWorkflow {

    @Test
    @DisplayName("dynamic router routes order to correct service")
    void dynamicRouterRoutesOrderCorrectly() {
      List<String> orderProcessorMessages = new CopyOnWriteArrayList<>();

      ProcRef<String, String> orderProcessorRef =
          Proc.spawn(
              "order-processor",
              () -> orderProcessorMessages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      DynamicRouter<String> router =
          new DynamicRouter<>(msg -> msg.startsWith("order:") ? "order-processor" : "unknown");

      boolean routed = router.route("order:ORD-123|amount:99.99");

      assertThat(routed).isTrue();

      Thread.yield();

      assertThat(orderProcessorMessages).contains("order:ORD-123|amount:99.99");
    }

    @Test
    @DisplayName("dynamic router discriminates between order types")
    void discriminatesBetweenOrderTypes() {
      List<String> standardOrders = new CopyOnWriteArrayList<>();
      List<String> expressOrders = new CopyOnWriteArrayList<>();

      ProcRef<String, String> standardRef =
          Proc.spawn(
              "standard-processor",
              () -> standardOrders,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> expressRef =
          Proc.spawn(
              "express-processor",
              () -> expressOrders,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      DynamicRouter<String> router =
          new DynamicRouter<>(msg -> msg.contains("EXPRESS") ? "express-processor" : "standard-processor");

      router.route("ORDER-1|STANDARD");
      router.route("ORDER-2|EXPRESS");
      router.route("ORDER-3|STANDARD");
      router.route("ORDER-4|EXPRESS");

      Thread.yield();

      assertThat(standardOrders).hasSize(2);
      assertThat(expressOrders).hasSize(2);
    }
  }

  @Nested
  @DisplayName("Event Broadcasting")
  class EventBroadcasting {

    @Test
    @DisplayName("broadcasts order events to audit, notification, and analytics")
    void broadcastsOrderEventsToMultipleServices() {
      List<String> auditLog = new CopyOnWriteArrayList<>();
      List<String> notifications = new CopyOnWriteArrayList<>();
      List<String> analytics = new CopyOnWriteArrayList<>();

      ProcRef<String, String> auditRef =
          Proc.spawn(
              "audit",
              () -> auditLog,
              (state, msg) -> {
                state.add("[AUDIT] " + msg);
                return state;
              });

      ProcRef<String, String> notificationRef =
          Proc.spawn(
              "notification",
              () -> notifications,
              (state, msg) -> {
                state.add("[NOTIF] " + msg);
                return state;
              });

      ProcRef<String, String> analyticsRef =
          Proc.spawn(
              "analytics",
              () -> analytics,
              (state, msg) -> {
                state.add("[ANALYTICS] " + msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(auditRef);
      router.addRecipient(notificationRef);
      router.addRecipient(analyticsRef);

      String event = "OrderCreated(id=ORD-999)";
      int recipients = router.broadcastMessage(event);

      assertThat(recipients).isEqualTo(3);

      Thread.yield();

      assertThat(auditLog).hasSize(1);
      assertThat(notifications).hasSize(1);
      assertThat(analytics).hasSize(1);
    }

    @Test
    @DisplayName("broadcasts multiple events sequentially")
    void broadcastsMultipleEventsSequentially() {
      List<String> events = new CopyOnWriteArrayList<>();

      ProcRef<String, String> ref =
          Proc.spawn(
              "event-sink",
              () -> events,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(ref);

      router.broadcastMessage("Event1");
      router.broadcastMessage("Event2");
      router.broadcastMessage("Event3");
      router.broadcastMessage("Event4");

      Thread.yield();

      assertThat(events).hasSize(4).contains("Event1", "Event2", "Event3", "Event4");
    }

    @Test
    @DisplayName("broadcasts with dynamic recipient management")
    void dynamicRecipientManagement() {
      List<String> sink1 = new CopyOnWriteArrayList<>();
      List<String> sink2 = new CopyOnWriteArrayList<>();
      List<String> sink3 = new CopyOnWriteArrayList<>();

      ProcRef<String, String> ref1 =
          Proc.spawn(
              "sink-1",
              () -> sink1,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> ref2 =
          Proc.spawn(
              "sink-2",
              () -> sink2,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> ref3 =
          Proc.spawn(
              "sink-3",
              () -> sink3,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();

      // Add 2 recipients, broadcast
      router.addRecipient(ref1);
      router.addRecipient(ref2);
      router.broadcastMessage("msg-1");

      // Add 3rd recipient, broadcast
      router.addRecipient(ref3);
      router.broadcastMessage("msg-2");

      // Remove 2nd recipient, broadcast
      router.removeRecipient(ref2);
      router.broadcastMessage("msg-3");

      Thread.yield();

      assertThat(sink1).hasSize(3).contains("msg-1", "msg-2", "msg-3");
      assertThat(sink2).hasSize(2).contains("msg-1", "msg-2"); // msg-3 not received
      assertThat(sink3).hasSize(2).contains("msg-2", "msg-3");
    }
  }

  @Nested
  @DisplayName("Combined Router Scenario")
  class CombinedScenario {

    @Test
    @DisplayName("dynamic router with pre-routing handlers")
    void dynamicRouterWithHandlers() {
      List<String> handlerExecutions = new ArrayList<>();
      List<String> serviceMessages = new CopyOnWriteArrayList<>();

      ProcRef<String, String> serviceRef =
          Proc.spawn(
              "service",
              () -> serviceMessages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      DynamicRouter<String> router = new DynamicRouter<>(msg -> "service");
      router.registerHandler("service", msg -> handlerExecutions.add("intercepted: " + msg));

      router.route("message-1");
      router.route("message-2");

      Thread.yield();

      assertThat(handlerExecutions).hasSize(2);
      assertThat(serviceMessages).hasSize(2);
    }

    @Test
    @DisplayName("real-world order flow: dynamic routing + broadcasting")
    void realWorldOrderFlow() {
      // Order processor (receives via dynamic router)
      List<String> orderQueue = new CopyOnWriteArrayList<>();
      ProcRef<String, String> orderProcessorRef =
          Proc.spawn(
              "order-processor",
              () -> orderQueue,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      // Broadcast recipients
      List<String> audit = new CopyOnWriteArrayList<>();
      List<String> notification = new CopyOnWriteArrayList<>();

      ProcRef<String, String> auditRef =
          Proc.spawn(
              "audit-service",
              () -> audit,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> notificationRef =
          Proc.spawn(
              "notification-service",
              () -> notification,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      // Dynamic router routes incoming orders
      DynamicRouter<String> orderRouter =
          new DynamicRouter<>(msg -> msg.startsWith("order:") ? "order-processor" : null);

      // Broadcast router for events
      RecipientListRouter<String> eventRouter = new RecipientListRouter<>();
      eventRouter.addRecipient(auditRef);
      eventRouter.addRecipient(notificationRef);

      // Simulate order flow
      String order = "order:ORD-100|Customer123|$99.99";
      boolean routed = orderRouter.route(order);
      assertThat(routed).isTrue();

      Thread.yield();

      // After order is processed, broadcast event
      String event = "OrderProcessed(ORD-100)";
      eventRouter.broadcastMessage(event);

      Thread.yield();

      assertThat(orderQueue).contains(order);
      assertThat(audit).contains(event);
      assertThat(notification).contains(event);
    }
  }

  @Nested
  @DisplayName("Performance and Concurrency")
  class PerformanceAndConcurrency {

    @Test
    @DisplayName("handles high-volume message broadcasting")
    void highVolumeMessaging() {
      List<String> recipient1 = new CopyOnWriteArrayList<>();
      List<String> recipient2 = new CopyOnWriteArrayList<>();

      ProcRef<String, String> ref1 =
          Proc.spawn(
              "recipient-1",
              () -> recipient1,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> ref2 =
          Proc.spawn(
              "recipient-2",
              () -> recipient2,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(ref1);
      router.addRecipient(ref2);

      int messageCount = 100;
      for (int i = 0; i < messageCount; i++) {
        router.broadcastMessage("msg-" + i);
      }

      try {
        Thread.sleep(500); // Give virtual threads time
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      assertThat(recipient1).hasSize(messageCount);
      assertThat(recipient2).hasSize(messageCount);
    }

    @Test
    @DisplayName("dynamic router handles concurrent routing")
    void concurrentDynamicRouting() {
      List<String> serviceA = new CopyOnWriteArrayList<>();
      List<String> serviceB = new CopyOnWriteArrayList<>();

      ProcRef<String, String> refA =
          Proc.spawn(
              "service-a",
              () -> serviceA,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> refB =
          Proc.spawn(
              "service-b",
              () -> serviceB,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      DynamicRouter<String> router =
          new DynamicRouter<>(msg -> msg.contains("A") ? "service-a" : "service-b");

      for (int i = 0; i < 50; i++) {
        router.route("msg-A-" + i);
        router.route("msg-B-" + i);
      }

      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      assertThat(serviceA).hasSize(50);
      assertThat(serviceB).hasSize(50);
    }
  }
}
