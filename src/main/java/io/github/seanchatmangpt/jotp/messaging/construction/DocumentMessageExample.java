package io.github.seanchatmangpt.jotp.messaging.construction;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runnable example demonstrating Vaughn Vernon's DocumentMessage pattern with JOTP.
 *
 * <p>This example shows how to:
 * <ul>
 *   <li>Wrap domain entities as self-contained messages</li>
 *   <li>Serialize complex data structures to bytes</li>
 *   <li>Deserialize documents back to typed objects</li>
 *   <li>Transfer domain objects across process boundaries</li>
 * </ul>
 *
 * <p>Joe Armstrong: "A message is best thought of as a copy of data, not a reference."
 */
public final class DocumentMessageExample {

    // Domain records for this example
    record Customer(String id, String name, String email) implements Serializable {}
    record Order(String orderId, String customerId, String product, double amount)
            implements Serializable {}

    record DocumentStoreState(
            java.util.Map<String, byte[]> documents,
            List<String> receivedDocuments)
            implements Serializable {
        static DocumentStoreState empty() {
            return new DocumentStoreState(
                    new java.util.concurrent.ConcurrentHashMap<>(), new ArrayList<>());
        }
    }

    record DocumentStoreMsg<T extends Serializable>(DocumentMessage<T> docMsg) {}

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  DocumentMessage Pattern Example (Vaughn Vernon)");
        System.out.println("  Domain Entity Transfer with JOTP Process Mailboxes");
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        // Create a document store process
        Supervisor supervisor =
                Supervisor.builder()
                        .name("document-store-supervisor")
                        .startStrategy(Supervisor.StartStrategy.ONE_FOR_ONE)
                        .build();

        // Handler for document store messages
        var storeHandler =
                (DocumentStoreState state, DocumentStoreMsg<?> msg) -> {
                    DocumentMessage<?> docMsg = msg.docMsg();
                    System.out.println(
                            "✓ Document Store received: "
                                    + docMsg.documentType()
                                    + " (ID: " + docMsg.messageId() + ")");

                    // Store the serialized document
                    String docKey = docMsg.documentType() + "-" + UUID.randomUUID().toString().substring(0, 8);
                    state.documents.put(docKey, docMsg.documentBytes());
                    state.receivedDocuments.add(docKey);

                    System.out.println(
                            "  Stored as: " + docKey
                                    + " (" + docMsg.documentBytes().length + " bytes)");

                    return state;
                };

        // Spawn document store process
        ProcRef<DocumentStoreState, DocumentStoreMsg<?>> documentStore =
                supervisor.spawn("document-store", DocumentStoreState.empty(), storeHandler);

        System.out.println("1. DOCUMENT STORE PROCESS SPAWNED");
        System.out.println("   - Service ready to receive serialized domain entities\n");

        // Create domain entities
        System.out.println("2. CREATING DOMAIN ENTITIES");

        Customer customer1 = new Customer("CUST-001", "Alice Johnson", "alice@example.com");
        System.out.println(
                "   A. Customer: " + customer1.name() + " (" + customer1.email() + ")");

        Order order1 = new Order("ORD-001", customer1.id(), "Premium Widget", 199.99);
        System.out.println(
                "   B. Order: " + order1.product() + " ($" + order1.amount() + ")\n");

        // Create and send document messages
        System.out.println("3. WRAPPING ENTITIES AS DOCUMENT MESSAGES");

        DocumentMessage<Customer> customerDocMsg = DocumentMessage.create("Customer", customer1);
        System.out.println("   A. Customer document:");
        System.out.println("      - Message ID: " + customerDocMsg.messageId());
        System.out.println("      - Type: " + customerDocMsg.documentType());
        System.out.println("      - Size: " + customerDocMsg.documentBytes().length + " bytes");
        System.out.println("      - Created: " + customerDocMsg.createdAt() + "\n");

        DocumentMessage<Order> orderDocMsg = DocumentMessage.create("Order", order1);
        System.out.println("   B. Order document:");
        System.out.println("      - Message ID: " + orderDocMsg.messageId());
        System.out.println("      - Type: " + orderDocMsg.documentType());
        System.out.println("      - Size: " + orderDocMsg.documentBytes().length + " bytes");
        System.out.println("      - Created: " + orderDocMsg.createdAt() + "\n");

        // Send documents to store
        System.out.println("4. SENDING DOCUMENT MESSAGES TO PROCESS MAILBOX");
        documentStore.tell(new DocumentStoreMsg<>(customerDocMsg));
        Thread.sleep(50);
        documentStore.tell(new DocumentStoreMsg<>(orderDocMsg));

        Thread.sleep(100);

        // Deserialize documents round-trip
        System.out.println("5. ROUND-TRIP SERIALIZATION TEST");

        Customer restoredCustomer = customerDocMsg.document("Customer", Customer.class);
        System.out.println("   A. Deserialized customer:");
        System.out.println("      - ID: " + restoredCustomer.id());
        System.out.println("      - Name: " + restoredCustomer.name());
        System.out.println("      - Email: " + restoredCustomer.email());
        System.out.println("      - Matches original: " + customer1.equals(restoredCustomer) + "\n");

        Order restoredOrder = orderDocMsg.document("Order", Order.class);
        System.out.println("   B. Deserialized order:");
        System.out.println("      - Order ID: " + restoredOrder.orderId());
        System.out.println("      - Product: " + restoredOrder.product());
        System.out.println("      - Amount: $" + restoredOrder.amount());
        System.out.println("      - Matches original: " + order1.equals(restoredOrder) + "\n");

        // Create additional entities
        System.out.println("6. BULK DOCUMENT MESSAGE CREATION");

        List<Customer> customers = new ArrayList<>();
        for (int i = 2; i <= 4; i++) {
            customers.add(
                    new Customer(
                            "CUST-" + String.format("%03d", i),
                            "Customer " + i,
                            "customer" + i + "@example.com"));
        }

        for (Customer c : customers) {
            DocumentMessage<Customer> docMsg = DocumentMessage.create("Customer", c);
            documentStore.tell(new DocumentStoreMsg<>(docMsg));
            System.out.println("   ✓ Sent customer: " + c.name());
            Thread.sleep(25);
        }

        Thread.sleep(100);
        System.out.println("   Total: " + customers.size() + " additional documents sent\n");

        // Verify message properties
        System.out.println("7. DOCUMENT MESSAGE PROPERTIES");
        System.out.println("   A. Customer document type: " + customerDocMsg.documentType());
        System.out.println("   B. Order document type: " + orderDocMsg.documentType());
        System.out.println(
                "   C. Byte serialization is independent of runtime class loading");
        System.out.println("   D. Each document has unique message ID for tracking\n");

        // Cleanup
        supervisor.shutdown();

        System.out.println("8. PATTERNS DEMONSTRATED");
        System.out.println("   ✓ Self-contained messages — no external references");
        System.out.println("   ✓ Type-safe serialization — domain objects as bytes");
        System.out.println("   ✓ Deserialization with type checking — safe round-tripping");
        System.out.println("   ✓ Cross-boundary transfer — domain entities through mailboxes");
        System.out.println("   ✓ Message envelope — document type + serialized data\n");

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  Example completed successfully");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}
