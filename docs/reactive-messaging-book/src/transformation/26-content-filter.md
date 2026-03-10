# 26. Content Filter

> *"A Content Filter removes unneeded data from a message, passing only the relevant fields to the downstream consumer."*

## Intent

A Content Filter reduces a large, rich message to a smaller, focused projection. This protects consumers from information overload, reduces data transfer, and enforces the principle that a consumer only sees what it needs. In JOTP, `MessageTransformer<T,U>` with a projection function is the idiomatic implementation.

## OTP Analogy

In Erlang, content filtering is typically a simple map projection:

```erlang
%% Full customer record
Full = #{id => "C1", name => "Alice", email => "a@b.com",
         dob => "1990-01-01", address => #{...}, credit_score => 780, ...}.

%% Content filter: project to summary
Summary = maps:with([id, name, email], Full).
```

`MessageTransformer<T,U>` codifies this as a typed, composable function.

## JOTP Implementation

`MessageTransformer<T,U>` wraps a pure `Function<T,U>`. It implements `MessageChannel<T>` on its input side, applies the function to each message, and forwards the result to a downstream `MessageChannel<U>`.

```
PointToPointChannel<CustomerRecord>
         │
MessageTransformer<CustomerRecord, CustomerSummary>
    Function: record → only {id, name, email}
         │
PointToPointChannel<CustomerSummary>
         │
MarketingService (sees only id, name, email — not credit score, DOB, etc.)
```

Key design points:
- The projection function must be pure and stateless — `MessageTransformer` calls it on the channel's virtual thread.
- Compose multiple `MessageTransformer` instances for multi-step reduction.
- The output type `U` is typically a smaller record with fewer fields.
- Content filtering is the inverse of Content Enrichment (Pattern 25) — enrich adds fields, filter removes them.

## API Reference

| Class / Method | Description |
|----------------|-------------|
| `new MessageTransformer<>(fn, downstream)` | Create transformer with projection function |
| `transformer.send(msg)` | Apply `fn` and forward result downstream |
| `transformer.stop()` | Stop the downstream channel |

## Code Example

```java
import org.acme.reactive.MessageTransformer;
import org.acme.reactive.PointToPointChannel;

// --- Full internal record (PII-rich) ---
record CustomerRecord(
    String id,
    String name,
    String email,
    String dateOfBirth,       // PII
    int    creditScore,       // sensitive
    String taxId,             // sensitive
    Address billingAddress,   // PII
    Instant createdAt,
    Instant lastLoginAt
) {}

// --- Slim projection for marketing ---
record CustomerSummary(String id, String name, String email) {}

// --- Even slimmer projection for analytics ---
record CustomerMetrics(String id, Instant createdAt, Instant lastLoginAt) {}

record Address(String street, String city, String country) {}

public class ContentFilterDemo {

    public static void main(String[] args) throws InterruptedException {
        // Downstream consumers
        var marketingChannel = new PointToPointChannel<CustomerSummary>(summary ->
            System.out.printf("[MARKETING] id=%s name=%s email=%s%n",
                summary.id(), summary.name(), summary.email())
        );

        var analyticsChannel = new PointToPointChannel<CustomerMetrics>(metrics ->
            System.out.printf("[ANALYTICS] id=%s created=%s lastLogin=%s%n",
                metrics.id(), metrics.createdAt(), metrics.lastLoginAt())
        );

        // Content filters (projection functions)
        MessageChannel<CustomerRecord> marketingFilter = new MessageTransformer<>(
            full -> new CustomerSummary(full.id(), full.name(), full.email()),
            marketingChannel
        );

        MessageChannel<CustomerRecord> analyticsFilter = new MessageTransformer<>(
            full -> new CustomerMetrics(full.id(), full.createdAt(), full.lastLoginAt()),
            analyticsChannel
        );

        // Source: full customer records
        var customers = List.of(
            new CustomerRecord("C1", "Alice", "alice@example.com",
                "1990-05-12", 780, "TAX-001",
                new Address("1 Main", "London", "GB"),
                Instant.parse("2023-01-15T10:00:00Z"),
                Instant.parse("2024-03-01T08:30:00Z")),
            new CustomerRecord("C2", "Bob", "bob@example.com",
                "1985-08-22", 640, "TAX-002",
                new Address("2 Oak Ave", "Berlin", "DE"),
                Instant.parse("2022-06-01T09:00:00Z"),
                Instant.parse("2024-02-28T14:00:00Z"))
        );

        for (var customer : customers) {
            marketingFilter.send(customer);  // sees only id, name, email
            analyticsFilter.send(customer); // sees only id, timestamps
        }

        marketingFilter.stop();
        analyticsFilter.stop();
    }
}
```

## Composing Filters

```java
// Two-stage reduction: Full → Summary → Minimal
var minimalChannel = new PointToPointChannel<String>(s ->
    System.out.println("[MINIMAL] " + s)
);

// Stage 2: CustomerSummary → just the email
MessageChannel<CustomerSummary> stage2 = new MessageTransformer<>(
    summary -> summary.email(),
    minimalChannel
);

// Stage 1: CustomerRecord → CustomerSummary
MessageChannel<CustomerRecord> stage1 = new MessageTransformer<>(
    full -> new CustomerSummary(full.id(), full.name(), full.email()),
    stage2
);

// Send a full record — it flows through both reductions
stage1.send(customer);
// Downstream sees only the email string
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class ContentFilterTest implements WithAssertions {

    CustomerRecord fullRecord() {
        return new CustomerRecord("C-T1", "Tester", "t@test.com",
            "2000-01-01", 700, "TAX-T", new Address("1 Test", "X", "Y"),
            Instant.now(), Instant.now());
    }

    @Test
    void projectionDropsSensitiveFields() throws InterruptedException {
        var summaries = new CopyOnWriteArrayList<CustomerSummary>();
        var latch     = new CountDownLatch(1);

        MessageChannel<CustomerRecord> filter = new MessageTransformer<>(
            f -> new CustomerSummary(f.id(), f.name(), f.email()),
            new PointToPointChannel<>(s -> { summaries.add(s); latch.countDown(); })
        );

        filter.send(fullRecord());
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        var summary = summaries.get(0);
        assertThat(summary.id()).isEqualTo("C-T1");
        assertThat(summary.name()).isEqualTo("Tester");
        assertThat(summary.email()).isEqualTo("t@test.com");
        // creditScore, taxId, dateOfBirth are NOT accessible on CustomerSummary
        filter.stop();
    }

    @Test
    void transformerIsStateless() throws InterruptedException {
        var results = new CopyOnWriteArrayList<CustomerSummary>();
        var latch   = new CountDownLatch(2);

        MessageChannel<CustomerRecord> filter = new MessageTransformer<>(
            f -> new CustomerSummary(f.id(), f.name(), f.email()),
            new PointToPointChannel<>(s -> { results.add(s); latch.countDown(); })
        );

        filter.send(fullRecord());
        filter.send(new CustomerRecord("C-T2", "Other", "o@o.com",
            "1999-12-31", 500, "TAX-T2", new Address("2", "Y", "Z"),
            Instant.now(), Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(results).extracting(CustomerSummary::id)
                           .containsExactly("C-T1", "C-T2");
        filter.stop();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Consumers must not see sensitive fields (PII, financial data, credentials).
- Downstream services have smaller data contracts and don't need the full payload.
- Network bandwidth is a concern and the full record is significantly larger than the projection.

**Avoid when:**
- The downstream service legitimately needs more fields than the projection provides — over-filtering leads to N+1 lookups.
- The projection function is expensive or involves I/O — use a `ContentEnricher` instead.
- You are simply renaming fields — a direct `MessageTransformer` is fine, but document the mapping so it is not mistaken for a genuine filter.
