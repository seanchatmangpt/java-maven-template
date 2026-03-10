# 12. Correlation Identifier

> *"A Correlation Identifier links a Reply to its originating Request — essential whenever multiple requests are in flight simultaneously."*

## Intent

When multiple asynchronous requests are outstanding at the same time, a Correlation Identifier tags each request/reply pair with a unique token so the requester (or an aggregator downstream) can match replies to the original requests without relying on ordering.

## OTP Analogy

Erlang bakes this into `gen_server:call`: the `Ref` component of the `From` tuple is a unique monitor reference that correlates the reply to the exact call:

```erlang
%% gen_server:call generates a unique Ref per call
%% {self(), Ref} = From; Ref is the correlation ID
handle_call(Request, {Pid, Ref} = From, State) ->
    gen_server:reply(From, do_work(Request, State)),
    {noreply, State}.
```

In JOTP, we make correlation explicit in the message record using `UUID`.

## JOTP Implementation

Add a `UUID correlationId` field to every request and reply record. Downstream aggregators use `correlationId` as the `ConcurrentHashMap` key to join replies with their originating requests.

```
Producer
  │  OrderRequest(correlationId=UUID-1, …)
  │  OrderRequest(correlationId=UUID-2, …)
  ├──────────────────────────────────────► PricingService
  └──────────────────────────────────────► InventoryService

MessageAggregator<OrderResponse>
  receives: OrderResponse(correlationId=UUID-2, …)
  receives: OrderResponse(correlationId=UUID-1, …)
  matches each back to its group by correlationId
```

Key design points:
- Use `UUID.randomUUID()` at the request origin — never reuse IDs.
- Pass `correlationId` unchanged through every stage; transformers and enrichers must preserve it.
- `MessageAggregator<T>` uses `correlationId` as its group key.
- Include `correlationId` in logs and traces for end-to-end observability.

## API Reference

| Usage | Description |
|-------|-------------|
| `UUID.randomUUID()` | Generate a unique correlation ID |
| `record Req(UUID correlationId, …)` | Embed ID in request |
| `record Reply(UUID correlationId, …)` | Echo ID in reply |
| `aggregator.accept(correlationId, reply)` | Register reply with aggregator |
| `aggregator.getGroup(correlationId)` | Retrieve all replies for an ID |

## Code Example

```java
import org.acme.reactive.MessageAggregator;
import java.util.UUID;

// --- Correlated request and reply ---
record PriceRequest(UUID correlationId, String sku, int quantity) {}
record PriceReply(UUID correlationId, String sku, double unitPrice) {}

// --- Enriched aggregate: join price + inventory ---
record OrderQuote(UUID correlationId, double totalPrice, int availableQty) {}

// --- Pricing service (Proc-backed) ---
class PricingService {
    private static final Map<String, Double> PRICES =
        Map.of("SKU-A", 29.99, "SKU-B", 49.99);

    PriceReply price(PriceRequest req) {
        double price = PRICES.getOrDefault(req.sku(), 0.0);
        return new PriceReply(req.correlationId(), req.sku(), price);
    }
}

// --- Async pipeline with correlation ---
public class CorrelationDemo {

    public static void main(String[] args) throws Exception {
        var pricing   = new PricingService();
        var completed = new ConcurrentHashMap<UUID, PriceReply>();
        var latch     = new CountDownLatch(3);

        // Send 3 concurrent requests, each with a unique correlationId
        var requests = List.of(
            new PriceRequest(UUID.randomUUID(), "SKU-A", 2),
            new PriceRequest(UUID.randomUUID(), "SKU-B", 1),
            new PriceRequest(UUID.randomUUID(), "SKU-A", 5)
        );

        // Simulate async processing with virtual threads
        try (var scope = java.util.concurrent.StructuredTaskScope.open()) {
            for (var req : requests) {
                scope.fork(() -> {
                    var reply = pricing.price(req);
                    completed.put(reply.correlationId(), reply);
                    latch.countDown();
                    return reply;
                });
            }
            scope.join();
        }

        // Match each reply back to its originating request by correlationId
        for (var req : requests) {
            var reply = completed.get(req.correlationId());
            System.out.printf("Corr=%s  sku=%s  qty=%d  total=%.2f%n",
                req.correlationId().toString().substring(0, 8),
                req.sku(), req.quantity(),
                reply.unitPrice() * req.quantity());
        }
    }
}
```

## Aggregator Integration

```java
import org.acme.reactive.MessageAggregator;

// MessageAggregator<PriceReply> groups replies by correlationId
// When a group is complete (all expected replies received), the
// completion function fires with the full group.

record AggKey(UUID correlationId) {}

MessageAggregator<PriceReply> aggregator = new MessageAggregator<>(
    reply -> new AggKey(reply.correlationId()),  // group key extractor
    group  -> group.size() >= 2,                  // completion condition
    group  -> assembleQuote(group)                // completion action
);

// Feed replies as they arrive — order does not matter
aggregator.accept(replyA);
aggregator.accept(replyB);  // triggers completion when size >= 2
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class CorrelationIdTest implements WithAssertions {

    @Test
    void eachRequestHasUniqueCorrelationId() {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 1000; i++)
            ids.add(UUID.randomUUID());
        assertThat(ids).hasSize(1000);
    }

    @Test
    void replyPreservesCorrelationId() {
        var pricing = new PricingService();
        var req     = new PriceRequest(UUID.randomUUID(), "SKU-A", 1);
        var reply   = pricing.price(req);

        assertThat(reply.correlationId()).isEqualTo(req.correlationId());
    }

    @Test
    void concurrentRepliesMatchTheirRequests() throws Exception {
        var pricing  = new PricingService();
        var requests = List.of(
            new PriceRequest(UUID.randomUUID(), "SKU-A", 2),
            new PriceRequest(UUID.randomUUID(), "SKU-B", 3)
        );

        var replies = new ConcurrentHashMap<UUID, PriceReply>();
        try (var scope = java.util.concurrent.StructuredTaskScope.open()) {
            for (var req : requests)
                scope.fork(() -> { replies.put(req.correlationId(), pricing.price(req)); return null; });
            scope.join();
        }

        for (var req : requests)
            assertThat(replies.get(req.correlationId()).correlationId())
                .isEqualTo(req.correlationId());
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Multiple asynchronous requests are in flight simultaneously and replies arrive out of order.
- Building a scatter-gather pipeline that collects N replies into one aggregate.
- Distributed tracing — the `correlationId` maps directly to a trace/span ID.

**Avoid when:**
- Only one request is ever outstanding at a time — `proc.ask()` alone suffices.
- You have a long-running process and store correlation IDs indefinitely — implement TTL-based eviction to prevent memory leaks in aggregators.
