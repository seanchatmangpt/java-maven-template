package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runnable example of the Point-to-Point Channel pattern.
 *
 * <p>Demonstrates a simple order processing system where:
 * 1. OrderGateway receives incoming orders and sends to OrderProcessor
 * 2. OrderProcessor validates and processes, sends to Warehouse
 * 3. Warehouse processes shipment
 *
 * <p>Each stage is a separate Proc (lightweight process), messages flow 1:1.
 *
 * <p>Run with: java io.github.seanchatmangpt.jotp.messaging.channels.PointToPointChannelExample
 */
public class PointToPointChannelExample {

    // Shared state: simulate a database
    static class AppState {
        Map<String, String> orders = new ConcurrentHashMap<>();

        void addOrder(String orderId, String status) {
            orders.put(orderId, status);
        }

        String getOrder(String orderId) {
            return orders.getOrDefault(orderId, "NOT_FOUND");
        }

        @Override
        public String toString() {
            return "Orders: " + orders;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Point-to-Point Channel Example ===");
        System.out.println("Demonstrating 1:1 async messaging between processes.\n");

        // Create state for each process
        var processorState = new AppState();
        var warehouseState = new AppState();

        // Create Warehouse process (final recipient)
        var warehouse = Proc.spawn(warehouseState, state -> msg -> {
            if (msg instanceof Message.CommandMsg cmd) {
                System.out.println("[Warehouse] Received order: " + cmd.payload());
                state.addOrder(cmd.commandType(), "SHIPPED");
                return state;
            }
            return state;
        });

        // Create OrderProcessor (receives from gateway, sends to warehouse)
        var processor = Proc.spawn(processorState, state -> msg -> {
            if (msg instanceof Message.EventMsg evt) {
                System.out.println("[Processor] Processing event: " + evt.eventType());
                state.addOrder(evt.eventType(), "PROCESSING");

                // Forward to warehouse
                PointToPointChannel.send(warehouse,
                    Message.command("ORDER_" + evt.eventType(), evt.payload(), null));

                return state;
            }
            return state;
        });

        // Simulate incoming orders (1:1 Point-to-Point)
        System.out.println("[Gateway] Sending orders to processor...\n");

        String[] orderIds = {"ORD-001", "ORD-002", "ORD-003"};
        for (String orderId : orderIds) {
            var order = Message.event(orderId, Map.of("amount", 99.99, "item", "Widget"));
            PointToPointChannel.send(processor, order);

            // Small delay to show sequential processing
            Thread.sleep(100);
        }

        // Wait for processing to complete
        Thread.sleep(500);

        System.out.println("\n[Processor State] " + processorState);
        System.out.println("[Warehouse State] " + warehouseState);
        System.out.println("\n=== Example Complete ===");
    }
}
