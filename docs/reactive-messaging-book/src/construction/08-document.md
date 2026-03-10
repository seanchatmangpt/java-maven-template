# 8. Document Message

> *"A Document Message transfers a chunk of data between applications. It doesn't tell the receiver what to do — it just delivers information."*

## Intent

A Document Message carries a self-contained data structure — a full representation of some entity or aggregate — without prescribing what the receiver should do with it. The receiver decides how to interpret and act on the document. This is in direct contrast to a Command Message, which dictates an operation.

## OTP Analogy

In Erlang, Document Messages are plain maps or nested tuples carrying the full data of a record:

```erlang
%% Document: the entire order is delivered; receiver decides what to do
gen_server:cast(ReportingServer,
    {order_document, #{
        id      => "ORD-42",
        items   => [{sku, "SKU-A", qty, 3}],
        total   => 149.99,
        placed  => erlang:system_time(millisecond)
    }}).
```

The `ReportingServer` might archive the order, emit metrics, or both — it is not told which.

## JOTP Implementation

Java 26 records are ideal Document Messages: they are immutable, self-describing, and carry the full state of the entity. A document record typically includes all fields of the entity snapshot, not just identifiers or deltas.

```
OrderDocument ──► PointToPointChannel<OrderDocument> ──► ReportingService
                                                      ──► ArchiveService
```

Key differences from Command Message:
- **Command**: "Reserve 3 units of SKU-A" (tells receiver what to do)
- **Document**: "Here is the complete order ORD-42 as it stood at 14:32 UTC" (delivers data, receiver decides)

Key design points:
- Documents are snapshots: include all relevant fields, not just changed ones.
- Immutability is non-negotiable — records enforce this.
- Include a `capturedAt` timestamp so receivers can determine freshness.
- Nested records compose naturally for complex documents.

## API Reference

| Usage | Description |
|-------|-------------|
| `record Doc(…)` | Define a document as an immutable record |
| `channel.send(doc)` | Deliver document to the channel |
| `doc.field()` | Accessor — no setters, guaranteed immutable |
| `new Doc(…)` | Compact constructor for validation |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;

// --- Nested document records ---
record Address(String street, String city, String country) {}

record LineItem(String sku, int quantity, double unitPrice) {
    double subtotal() { return quantity * unitPrice; }
}

// --- Full order document ---
record OrderDocument(
    String         orderId,
    String         customerId,
    Address        shippingAddress,
    List<LineItem> items,
    double         totalAmount,
    String         currency,
    Instant        capturedAt
) {
    // Compact constructor: validate invariants at construction time
    OrderDocument {
        Objects.requireNonNull(orderId,         "orderId required");
        Objects.requireNonNull(customerId,      "customerId required");
        Objects.requireNonNull(shippingAddress, "shippingAddress required");
        items = List.copyOf(items);  // defensive copy → truly immutable
        if (totalAmount < 0) throw new IllegalArgumentException("negative total");
    }
}

// --- Receivers decide independently what to do ---
class ReportingService {
    void onOrder(OrderDocument doc) {
        System.out.printf("[REPORT] Order %s: %.2f %s, %d items%n",
            doc.orderId(), doc.totalAmount(), doc.currency(), doc.items().size());
    }
}

class ArchiveService {
    private final List<OrderDocument> archive = new ArrayList<>();
    void onOrder(OrderDocument doc) { archive.add(doc); }
    List<OrderDocument> getArchive() { return List.copyOf(archive); }
}

public class DocumentMessageDemo {
    public static void main(String[] args) throws InterruptedException {
        var reporting = new ReportingService();
        var archive   = new ArchiveService();

        // Channel carries the document to the reporting service
        var reportChannel = new PointToPointChannel<>(reporting::onOrder);

        var doc = new OrderDocument(
            "ORD-001", "CUST-007",
            new Address("1 Main St", "Springfield", "US"),
            List.of(
                new LineItem("SKU-A", 2, 29.99),
                new LineItem("SKU-B", 1, 89.99)
            ),
            149.97, "USD", Instant.now()
        );

        reportChannel.send(doc);
        archive.onOrder(doc);  // same doc, different receiver, different action

        reportChannel.stop();
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class DocumentMessageTest implements WithAssertions {

    OrderDocument sampleDoc() {
        return new OrderDocument(
            "ORD-T1", "CUST-T1",
            new Address("2 Test Ave", "Testville", "GB"),
            List.of(new LineItem("SKU-X", 1, 10.0)),
            10.0, "GBP", Instant.now()
        );
    }

    @Test
    void documentIsImmutable() {
        var doc  = sampleDoc();
        var copy = doc;  // same reference — records are value objects
        assertThat(doc).isEqualTo(copy);
        // Attempting doc.items().add(…) throws UnsupportedOperationException
        assertThatThrownBy(() -> doc.items().add(new LineItem("X", 1, 1.0)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void documentCarriesAllFields() {
        var doc = sampleDoc();
        assertThat(doc.orderId()).isEqualTo("ORD-T1");
        assertThat(doc.items()).hasSize(1);
        assertThat(doc.totalAmount()).isEqualTo(10.0);
        assertThat(doc.capturedAt()).isNotNull();
    }

    @Test
    void constructorRejectsNegativeTotal() {
        assertThatThrownBy(() -> new OrderDocument(
            "ORD-BAD", "CUST-BAD",
            new Address("X", "Y", "Z"),
            List.of(), -1.0, "USD", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("negative total");
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Multiple receivers each decide independently how to handle the data.
- You are replicating state across services (read models, projections).
- The payload is a complete, self-contained snapshot rather than a delta.

**Avoid when:**
- The receiver must perform exactly one specific action — use a Command Message.
- The document is very large and bandwidth is constrained — consider Claim Check (Pattern 27) to pass a ticket instead of the full payload.
- Documents change frequently and consumers cache them — stale-document drift becomes a correctness issue.
