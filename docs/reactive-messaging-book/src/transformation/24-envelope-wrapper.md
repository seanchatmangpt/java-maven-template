# 24. Envelope Wrapper

> *"An Envelope Wrapper adds metadata to a message without modifying the original payload — the envelope carries routing and tracing information, the payload carries business data."*

## Intent

An Envelope Wrapper separates infrastructure concerns (correlation IDs, timestamps, source, routing keys) from business payload. Every channel in the pipeline passes the `Envelope<T>` rather than raw `T`, so metadata travels with the message without polluting the domain model.

## OTP Analogy

In Erlang, envelopes are commonly implemented as nested tuples or maps wrapping the payload:

```erlang
%% Envelope: metadata + payload
Envelope = #{
    correlation_id => <<"uuid-123">>,
    source         => <<"order_service">>,
    timestamp      => erlang:system_time(millisecond),
    payload        => Order
},
gen_server:cast(NextStage, {envelope, Envelope}).
```

In Java 26, a generic record expresses this cleanly with full type safety on the payload.

## JOTP Implementation

```java
record Envelope<T>(
    T       payload,
    UUID    correlationId,
    Instant timestamp,
    String  source
) {}
```

Every `MessageChannel<Envelope<T>>` carries typed payloads with metadata. Transformers and enrichers preserve the envelope and only modify the payload.

```
Producer
  │  Envelope<OrderCmd>(payload, corrId, now, "order-svc")
  ▼
PointToPointChannel<Envelope<OrderCmd>>
  │  (envelope passes unchanged through routing stages)
  ▼
PaymentService
  │  reads corrId for tracing, reads payload for business logic
  ▼
Envelope<PaymentResult>(result, corrId, now, "payment-svc")
```

Key design points:
- `correlationId` is generated once at the entry point and propagated unchanged.
- `source` identifies the originating service for audit and debugging.
- `timestamp` records when the envelope was created, enabling latency measurement.
- When transforming, copy the envelope with `withPayload(newPayload)` to preserve metadata.
- The envelope should be the only type flowing through infrastructure channels; domain code unpacks the payload.

## API Reference

| Method | Description |
|--------|-------------|
| `Envelope.of(payload, source)` | Factory: generate correlationId and timestamp |
| `envelope.payload()` | Extract the business payload |
| `envelope.correlationId()` | Retrieve the correlation ID |
| `envelope.withPayload(newPayload)` | Create new envelope preserving metadata |
| `envelope.timestamp()` | When the envelope was created |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;

// --- Generic envelope ---
record Envelope<T>(
    T       payload,
    UUID    correlationId,
    Instant timestamp,
    String  source
) {
    /** Factory: auto-generate correlationId and timestamp. */
    static <T> Envelope<T> of(T payload, String source) {
        return new Envelope<>(payload, UUID.randomUUID(), Instant.now(), source);
    }

    /** Create a new envelope preserving all metadata, swapping the payload. */
    <U> Envelope<U> withPayload(U newPayload) {
        return new Envelope<>(newPayload, correlationId, timestamp, source);
    }

    /** Age of the envelope in milliseconds. */
    long ageMs() {
        return Instant.now().toEpochMilli() - timestamp.toEpochMilli();
    }
}

// --- Business types ---
record OrderRequest(String orderId, double amount, String currency) {}
record PaymentResult(String orderId, boolean success, String txnId) {}

// --- Pipeline stages ---
class PaymentGateway {
    PaymentResult charge(OrderRequest req) {
        // Simulate payment processing
        return new PaymentResult(req.orderId(), true, "TXN-" + req.orderId());
    }
}

public class EnvelopeDemo {

    public static void main(String[] args) throws InterruptedException {
        var gateway = new PaymentGateway();

        // Stage 1: order service creates the envelope
        var orderRequest = new OrderRequest("ORD-001", 149.99, "USD");
        Envelope<OrderRequest> env1 = Envelope.of(orderRequest, "order-service");

        // Stage 2: payment service receives envelope, processes, re-wraps with same metadata
        var channel = new PointToPointChannel<Envelope<OrderRequest>>(env -> {
            System.out.printf("[PAYMENT] Processing %s (corrId=%s, age=%dms)%n",
                env.payload().orderId(),
                env.correlationId().toString().substring(0, 8),
                env.ageMs());

            PaymentResult result = gateway.charge(env.payload());

            // Preserve correlationId; update source; swap payload
            Envelope<PaymentResult> env2 = env.withPayload(result);
            System.out.printf("[PAYMENT] Done %s corrId=%s txn=%s%n",
                env2.payload().orderId(),
                env2.correlationId().toString().substring(0, 8),
                env2.payload().txnId());
        });

        channel.send(env1);
        channel.stop();
    }
}
```

## Multi-Stage Pipeline

```java
// Each stage preserves correlationId across the entire pipeline:
Envelope<OrderRequest>   stage1 = Envelope.of(request, "order-svc");
Envelope<PaymentResult>  stage2 = stage1.withPayload(gateway.charge(stage1.payload()));
Envelope<ShipmentResult> stage3 = stage2.withPayload(shipper.ship(stage2.payload()));

// End-to-end tracing: all three stages share the same correlationId
assert stage1.correlationId().equals(stage2.correlationId());
assert stage2.correlationId().equals(stage3.correlationId());
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class EnvelopeWrapperTest implements WithAssertions {

    @Test
    void envelopeCarriesMetadataAlongsidePayload() {
        var env = Envelope.of(new OrderRequest("O1", 10.0, "USD"), "test-svc");

        assertThat(env.payload().orderId()).isEqualTo("O1");
        assertThat(env.correlationId()).isNotNull();
        assertThat(env.timestamp()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(env.source()).isEqualTo("test-svc");
    }

    @Test
    void withPayloadPreservesCorrelationId() {
        var env1 = Envelope.of(new OrderRequest("O2", 20.0, "EUR"), "svc-a");
        var env2 = env1.withPayload(new PaymentResult("O2", true, "TXN-X"));

        assertThat(env2.correlationId()).isEqualTo(env1.correlationId());
        assertThat(env2.timestamp()).isEqualTo(env1.timestamp());
        assertThat(env2.payload().txnId()).isEqualTo("TXN-X");
    }

    @Test
    void eachEnvelopeHasUniqueCorrelationId() {
        var e1 = Envelope.of("msg1", "svc");
        var e2 = Envelope.of("msg2", "svc");
        assertThat(e1.correlationId()).isNotEqualTo(e2.correlationId());
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Messages traverse multiple pipeline stages and you need end-to-end tracing.
- Infrastructure (routing, logging, auditing) must access metadata without touching domain types.
- Correlation IDs, timestamps, and source must flow transparently through all transformations.

**Avoid when:**
- The pipeline has only one hop — the envelope overhead is unnecessary.
- You already have a tracing framework (OpenTelemetry `Span`) — prefer integrating with that rather than rolling a custom envelope.
- The envelope grows over time with ad-hoc fields — define a strict schema and resist adding to it; use `Map<String, String>` for open-ended headers instead.
