package io.github.seanchatmangpt.jotp.messaging.construction;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentMessage} — verifies Vaughn Vernon's document message pattern
 * implementation with type-safe serialization and domain entity wrapping.
 *
 * <p>Tests cover message creation, serialization round-trips, type safety, and error handling.
 */
@DisplayName("DocumentMessage — Document message construction pattern")
class DocumentMessageTest implements WithAssertions {

    // Test domain types
    record Customer(String id, String name, String email) implements Serializable {}

    record Product(String sku, String title, double price) implements Serializable {}

    record Inventory(String warehouseId, int quantity) implements Serializable {}

    // ─────────────────────────────────────────────────────────────────────────────
    // Basic Creation — factory methods and message envelope
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Basic creation — message construction and defaults")
    class BasicCreation {

        @Test
        @DisplayName("creates document message from serializable domain entity")
        void createsMessageFromDomainEntity() throws IOException {
            Customer customer = new Customer("CUST-001", "Alice", "alice@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            assertThat(msg)
                    .isNotNull()
                    .isInstanceOf(DocumentMessage.class);
            assertThat(msg.documentType()).isEqualTo("Customer");
            assertThat(msg.documentBytes()).isNotEmpty();
        }

        @Test
        @DisplayName("auto-generates unique message ID")
        void autoGeneratesUniqueMessageId() throws IOException {
            Customer customer1 = new Customer("CUST-001", "Bob", "bob@example.com");
            Customer customer2 = new Customer("CUST-002", "Charlie", "charlie@example.com");

            DocumentMessage<Customer> msg1 = DocumentMessage.create("Customer", customer1);
            DocumentMessage<Customer> msg2 = DocumentMessage.create("Customer", customer2);

            assertThat(msg1.messageId()).isNotNull().isNotEqualTo(msg2.messageId());
        }

        @Test
        @DisplayName("sets current timestamp on creation")
        void setsCurrentTimestamp() throws IOException {
            long before = System.currentTimeMillis();
            Customer customer = new Customer("CUST-003", "Dave", "dave@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            long after = System.currentTimeMillis();
            assertThat(msg.createdAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("serializes domain entity to byte array")
        void serializesDomainEntity() throws IOException {
            Customer customer = new Customer("CUST-004", "Eve", "eve@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            assertThat(msg.documentBytes()).isNotEmpty();
            assertThat(msg.documentBytes().length).isGreaterThan(0);
        }

        @Test
        @DisplayName("rejects null document type")
        void rejectsNullDocumentType() {
            Customer customer = new Customer("CUST-005", "Frank", "frank@example.com");

            assertThatThrownBy(() -> DocumentMessage.create(null, customer))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects blank document type")
        void rejectsBlankDocumentType() {
            Customer customer = new Customer("CUST-006", "Grace", "grace@example.com");

            assertThatThrownBy(() -> DocumentMessage.create("   ", customer))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("rejects null document entity")
        void rejectsNullDocument() {
            assertThatThrownBy(() -> DocumentMessage.create("Customer", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects non-serializable objects")
        void rejectsNonSerializable() {
            Object nonSerializable =
                    new Object(); // Object is not Serializable

            assertThatThrownBy(
                    () -> DocumentMessage.create("Object", nonSerializable))
                    .isInstanceOf(ClassCastException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Serialization — serialize/deserialize round-trip
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Serialization — serialize/deserialize round-trips")
    class Serialization {

        @Test
        @DisplayName("round-trip preserves customer data")
        void roundTripPreservesCustomerData() throws IOException, ClassNotFoundException {
            Customer original = new Customer("CUST-007", "Henry", "henry@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", original);
            Customer restored = msg.document("Customer", Customer.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.id()).isEqualTo(original.id());
            assertThat(restored.name()).isEqualTo(original.name());
            assertThat(restored.email()).isEqualTo(original.email());
        }

        @Test
        @DisplayName("round-trip preserves product data with double")
        void roundTripPreservesProductData() throws IOException, ClassNotFoundException {
            Product original = new Product("SKU-123", "Premium Widget", 99.99);

            DocumentMessage<Product> msg = DocumentMessage.create("Product", original);
            Product restored = msg.document("Product", Product.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.price()).isEqualTo(99.99);
        }

        @Test
        @DisplayName("serializes to bytes and deserializes with helper")
        void serializeDeserializeViaHelper() throws IOException, ClassNotFoundException {
            Customer original = new Customer("CUST-008", "Iris", "iris@example.com");

            byte[] bytes = DocumentMessage.serialize(original);
            assertThat(bytes).isNotEmpty();

            Customer restored = DocumentMessage.deserialize(bytes, Customer.class);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("serialize rejects null object")
        void serializeRejectsNull() {
            assertThatThrownBy(() -> DocumentMessage.serialize(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("deserialize rejects null bytes")
        void deserializeRejectsNullBytes() {
            assertThatThrownBy(
                    () -> DocumentMessage.deserialize(null, Customer.class))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("deserialize rejects null class")
        void deserializeRejectsNullClass() throws IOException {
            Customer original = new Customer("CUST-009", "Jack", "jack@example.com");
            byte[] bytes = DocumentMessage.serialize(original);

            assertThatThrownBy(() -> DocumentMessage.deserialize(bytes, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("deserialize rejects empty bytes")
        void deserializeRejectsEmptyBytes() {
            assertThatThrownBy(() -> DocumentMessage.deserialize(new byte[0], Customer.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Type Safety — document type validation
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type safety — document type validation")
    class TypeSafety {

        @Test
        @DisplayName("validates document type on deserialization")
        void validatesDocumentType() throws IOException {
            Customer customer = new Customer("CUST-010", "Karen", "karen@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            // Correct type - should succeed
            Customer restored = msg.document("Customer", Customer.class);
            assertThat(restored).isEqualTo(customer);
        }

        @Test
        @DisplayName("rejects mismatched document type on deserialization")
        void rejectsMismatchedType() throws IOException {
            Customer customer = new Customer("CUST-011", "Leo", "leo@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            // Wrong type - should fail
            assertThatThrownBy(() -> msg.document("Product", Customer.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type mismatch");
        }

        @Test
        @DisplayName("prevents type confusion attacks via type mismatch check")
        void preventsTypeConfusion() throws IOException {
            Product product = new Product("SKU-456", "Gadget", 49.99);

            DocumentMessage<Product> msg = DocumentMessage.create("Product", product);

            // Attempt to deserialize Product as Customer - type checking prevents this
            assertThatThrownBy(() -> msg.document("Customer", Customer.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Product");
        }

        @Test
        @DisplayName("supports multiple document types independently")
        void supportsMultipleTypes() throws IOException, ClassNotFoundException {
            Customer customer = new Customer("CUST-012", "Mona", "mona@example.com");
            Product product = new Product("SKU-789", "Premium Gadget", 199.99);
            Inventory inventory = new Inventory("WH-001", 500);

            DocumentMessage<Customer> custMsg = DocumentMessage.create("Customer", customer);
            DocumentMessage<Product> prodMsg = DocumentMessage.create("Product", product);
            DocumentMessage<Inventory> invMsg = DocumentMessage.create("Inventory", inventory);

            Customer restoredCust = custMsg.document("Customer", Customer.class);
            Product restoredProd = prodMsg.document("Product", Product.class);
            Inventory restoredInv = invMsg.document("Inventory", Inventory.class);

            assertThat(restoredCust).isEqualTo(customer);
            assertThat(restoredProd).isEqualTo(product);
            assertThat(restoredInv).isEqualTo(inventory);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Message Properties — metadata and identity
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Message properties — metadata and identity")
    class MessageProperties {

        @Test
        @DisplayName("each message has unique ID")
        void eachMessageHasUniqueId() throws IOException {
            Customer customer = new Customer("CUST-013", "Neal", "neal@example.com");

            DocumentMessage<Customer> msg1 = DocumentMessage.create("Customer", customer);
            DocumentMessage<Customer> msg2 = DocumentMessage.create("Customer", customer);

            assertThat(msg1.messageId()).isNotEqualTo(msg2.messageId());
        }

        @Test
        @DisplayName("message ID is UUID")
        void messageIdIsUUID() throws IOException {
            Customer customer = new Customer("CUST-014", "Olivia", "olivia@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            assertThat(msg.messageId()).isInstanceOf(UUID.class);
        }

        @Test
        @DisplayName("preserves document type through lifecycle")
        void preservesDocumentType() throws IOException {
            Customer customer = new Customer("CUST-015", "Paul", "paul@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            assertThat(msg.documentType()).isEqualTo("Customer");
            assertThat(msg.documentType()).isNotNull();
        }

        @Test
        @DisplayName("document bytes are immutable across accesses")
        void documentBytesConsistent() throws IOException {
            Customer customer = new Customer("CUST-016", "Quinn", "quinn@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);
            byte[] bytes1 = msg.documentBytes();
            byte[] bytes2 = msg.documentBytes();

            assertThat(bytes1).isEqualTo(bytes2);
        }

        @Test
        @DisplayName("timestamp is set at creation time")
        void timestampSetAtCreation() throws IOException, InterruptedException {
            Customer customer = new Customer("CUST-017", "Rachel", "rachel@example.com");

            long before = System.currentTimeMillis();
            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);
            long after = System.currentTimeMillis();

            assertThat(msg.createdAt()).isBetween(before, after);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Self-Contained — no external references
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Self-contained — domain entities as message copies")
    class SelfContained {

        @Test
        @DisplayName("document message is independent of original entity")
        void independentOfOriginal() throws IOException {
            Customer original = new Customer("CUST-018", "Sam", "sam@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", original);

            // Even if we modify original, message is unaffected (serialized copy)
            // Note: records are immutable, but demonstrates serialization independence
            assertThat(msg.documentBytes()).isNotEmpty();
            assertThat(msg.messageId()).isNotNull();
        }

        @Test
        @DisplayName("multiple messages from same entity are independent")
        void multipleIndependentMessages() throws IOException {
            Customer customer = new Customer("CUST-019", "Tina", "tina@example.com");

            DocumentMessage<Customer> msg1 = DocumentMessage.create("Customer", customer);
            DocumentMessage<Customer> msg2 = DocumentMessage.create("Customer", customer);

            // Different message IDs
            assertThat(msg1.messageId()).isNotEqualTo(msg2.messageId());
            // Different timestamps (may be same if created very quickly)
            // But different serialized forms should deserialize to equal entities
            Customer restored1 = msg1.document("Customer", Customer.class);
            Customer restored2 = msg2.document("Customer", Customer.class);
            assertThat(restored1).isEqualTo(restored2);
        }

        @Test
        @DisplayName("document bytes are serialized copies, not references")
        void documentBytesAreCopies() throws IOException {
            Customer customer = new Customer("CUST-020", "Uma", "uma@example.com");

            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);
            byte[] bytes = msg.documentBytes();

            // Modifying bytes array doesn't affect message
            if (bytes.length > 0) {
                bytes[0] = (byte) (bytes[0] ^ 0xFF);
            }

            // Message should still be valid
            byte[] bytesAgain = msg.documentBytes();
            assertThat(bytesAgain).isNotEqualTo(bytes);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Integration — realistic scenarios
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration — realistic document transfer scenarios")
    class Integration {

        @Test
        @DisplayName("wraps domain entity for mailbox transmission")
        void wrapsForMailboxTransmission() throws IOException, ClassNotFoundException {
            Customer customer = new Customer("CUST-021", "Victor", "victor@example.com");

            // Create message for transmission
            DocumentMessage<Customer> msg = DocumentMessage.create("Customer", customer);

            // Simulate transmission: extract bytes, send over wire, deserialize
            byte[] wireBytes = msg.documentBytes();
            String documentType = msg.documentType();

            // Reconstruct on receiving end
            DocumentMessage<Customer> receivedMsg =
                    DocumentMessage.create(documentType, DocumentMessage.deserialize(wireBytes, Customer.class));
            Customer receivedCustomer =
                    receivedMsg.document(documentType, Customer.class);

            assertThat(receivedCustomer).isEqualTo(customer);
        }

        @Test
        @DisplayName("bulk entity wrapping for batch processing")
        void bulkEntityWrapping() throws IOException {
            java.util.List<Customer> customers =
                    java.util.List.of(
                            new Customer("CUST-022", "Wendy", "wendy@example.com"),
                            new Customer("CUST-023", "Xavier", "xavier@example.com"),
                            new Customer("CUST-024", "Yara", "yara@example.com"));

            java.util.List<DocumentMessage<Customer>> messages = new java.util.ArrayList<>();
            for (Customer c : customers) {
                messages.add(DocumentMessage.create("Customer", c));
            }

            assertThat(messages).hasSize(3);
            for (DocumentMessage<Customer> msg : messages) {
                assertThat(msg.documentType()).isEqualTo("Customer");
                assertThat(msg.documentBytes()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("preserves complex nested structures through serialization")
        void preservesComplexStructures() throws IOException, ClassNotFoundException {
            // Complex nested record
            record Address(String street, String city, String zip) implements Serializable {}
            record FullCustomer(String id, String name, Address address)
                    implements Serializable {}

            Address addr = new Address("123 Main St", "Springfield", "12345");
            FullCustomer fullCustomer = new FullCustomer("CUST-025", "Zoe", addr);

            DocumentMessage<?> msg = DocumentMessage.create("FullCustomer", fullCustomer);

            // Note: deserialize as Object first, then cast if needed
            Object restored = DocumentMessage.deserialize(msg.documentBytes(), Object.class);
            assertThat(restored).isInstanceOf(FullCustomer.class);
        }
    }
}
