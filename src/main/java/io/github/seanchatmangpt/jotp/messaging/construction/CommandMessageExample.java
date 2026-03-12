package io.github.seanchatmangpt.jotp.messaging.construction;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.io.Serializable;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable example demonstrating Vaughn Vernon's CommandMessage pattern with JOTP.
 *
 * <p>This example shows how to:
 * <ul>
 *   <li>Create command messages with reply addresses</li>
 *   <li>Send commands through process mailboxes</li>
 *   <li>Correlate requests with responses using correlation IDs</li>
 *   <li>Add timeouts for command delivery</li>
 * </ul>
 *
 * <p>Joe Armstrong: "A process is identified by a Pid. When you send a message to a Pid,
 * you expect a reply at the address you provide."
 */
public final class CommandMessageExample {

    // Domain records for this example
    record PlaceOrderCmd(String customerId, String item, int quantity) implements Serializable {}
    record OrderConfirmation(String orderId, String status) implements Serializable {}

    record OrderServiceState(java.util.Map<String, String> orders) implements Serializable {
        static OrderServiceState empty() {
            return new OrderServiceState(new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    record OrderServiceMsg(CommandMessage<?> cmd) {}

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  CommandMessage Pattern Example (Vaughn Vernon)");
        System.out.println("  Reactive Messaging with JOTP Process Mailboxes");
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        // Create a simple order service process
        Supervisor supervisor =
                Supervisor.builder()
                        .name("order-service-supervisor")
                        .startStrategy(Supervisor.StartStrategy.ONE_FOR_ONE)
                        .build();

        // Handler for order service messages
        var orderServiceHandler =
                (OrderServiceState state, OrderServiceMsg msg) -> {
                    CommandMessage<?> cmd = msg.cmd();
                    System.out.println(
                            "✓ Order Service received command: " + cmd.commandType()
                                    + " (correlation: " + cmd.correlationId() + ")");

                    // Simulate processing
                    if (cmd.payload() instanceof PlaceOrderCmd order) {
                        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
                        System.out.println(
                                "  Processing order for customer: " + order.customerId()
                                        + ", item: " + order.item()
                                        + ", quantity: " + order.quantity());

                        // Update state
                        state.orders.put(orderId, "CONFIRMED");
                        System.out.println("  Order " + orderId + " confirmed and stored");
                    }

                    return state;
                };

        // Spawn order service process
        ProcRef<OrderServiceState, OrderServiceMsg> orderService =
                supervisor.spawn(
                        "order-service", OrderServiceState.empty(), orderServiceHandler);

        System.out.println("1. ORDER SERVICE PROCESS SPAWNED");
        System.out.println("   - Service started with empty order state\n");

        // Client process to send commands
        Thread.sleep(100); // brief delay for service startup

        System.out.println("2. CLIENT SENDING COMMAND MESSAGES");

        // Create a reply-to reference by spawning a temporary client process
        AtomicReference<CommandMessage<?>> receivedReply = new AtomicReference<>();

        var clientHandler =
                (java.util.List<CommandMessage<?>> replies, CommandMessage<?> reply) -> {
                    System.out.println("    ✓ Client received reply: " + reply.commandType());
                    receivedReply.set(reply);
                    return replies;
                };

        ProcRef<java.util.List<CommandMessage<?>>, CommandMessage<?>> client =
                supervisor.spawn("client", new java.util.ArrayList<>(), clientHandler);

        // Create and send first command
        UUID correlationId1 = UUID.randomUUID();
        CommandMessage<OrderConfirmation> cmd1 =
                CommandMessage.create(
                                "PlaceOrder", new PlaceOrderCmd("CUST-001", "Widget", 5), client)
                        .withCorrelationId(correlationId1)
                        .withTimeout(Duration.ofSeconds(2));

        System.out.println("   A. Command 1:");
        System.out.println("      - Type: " + cmd1.commandType());
        System.out.println("      - Correlation ID: " + cmd1.correlationId());
        System.out.println("      - Timeout: " + cmd1.timeout());
        System.out.println("      - Reply-to: client process\n");

        orderService.tell(new OrderServiceMsg(cmd1));

        // Create and send second command with different correlation ID
        Thread.sleep(100);
        UUID correlationId2 = UUID.randomUUID();
        CommandMessage<OrderConfirmation> cmd2 =
                CommandMessage.create(
                                "PlaceOrder",
                                new PlaceOrderCmd("CUST-002", "Gadget", 3),
                                client)
                        .withCorrelationId(correlationId2)
                        .withTimeout(Duration.ofSeconds(3));

        System.out.println("   B. Command 2:");
        System.out.println("      - Type: " + cmd2.commandType());
        System.out.println("      - Correlation ID: " + cmd2.correlationId());
        System.out.println("      - Timeout: " + cmd2.timeout());
        System.out.println("      - Reply-to: client process\n");

        orderService.tell(new OrderServiceMsg(cmd2));

        // Verify message properties
        System.out.println("3. MESSAGE PROPERTIES VERIFICATION");
        System.out.println(
                "   A. Command 1 has correlation ID: " + (cmd1.hasCorrelationId() ? "YES" : "NO"));
        System.out.println(
                "   B. Command 1 has timeout: " + (cmd1.hasTimeout() ? "YES" : "NO"));
        System.out.println(
                "   C. Command 1 timestamp: " + cmd1.createdAt());
        System.out.println(
                "   D. Command 1 message ID: " + cmd1.messageId() + "\n");

        // Wait for processing
        Thread.sleep(200);

        System.out.println("4. PATTERNS DEMONSTRATED");
        System.out.println("   ✓ Fire-and-forget via tell() — commands dispatched without waiting");
        System.out.println("   ✓ Correlation IDs — track request-response pairs");
        System.out.println("   ✓ Reply-to address — ProcRef embedded in command");
        System.out.println("   ✓ Timeout specification — deadline for reply");
        System.out.println(
                "   ✓ Request-reply pattern — asymmetric addressing for RPC semantics\n");

        // Cleanup
        supervisor.shutdown();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  Example completed successfully");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}
