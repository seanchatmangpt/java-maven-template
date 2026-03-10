# 25. Content Enricher

> *"A message arriving from the outside world rarely carries all the information a service needs. The Content Enricher fetches what is missing, merges it in, and forwards the complete message downstream."*

---

## Intent

Add data to a message that the sender did not or could not include — typically by looking up a reference data store, calling an external service, or applying a local function. The enriched output is a different (usually richer) type from the input. The sender is unaware of enrichment; downstream only ever sees the complete object.

---

## OTP Analogy

In Erlang/OTP a gen_server handler performs a lookup inside handle_cast before forwarding:

```erlang
-module(order_enricher).
-behaviour(gen_server).

handle_cast({enrich, Order}, #state{customer_db=DB, downstream=DS}) ->
    Customer = customer_db:lookup(DB, Order#order.customer_id),
    Enriched = Order#order{customer=Customer, enriched_at=erlang:system_time()},
    DS \! {enriched, Enriched},
    {noreply, State}.
```

| OTP concept | JOTP equivalent |
|---|---|
| External lookup in handle_cast | BiFunction<T, R, U> enricher |
| Forward to next process | downstream.send(enriched) |
| Crash on bad data | Exception -> errorChannel.send(original) |

---

## JOTP Implementation

**Class:** ContentEnricher<T, R, U>
**Mechanism:** Pure BiFunction<T, R, U> applied inside send() — stateless, no threads

### Three factory methods

1. of(Function<T,U>) — pure derivation, no external resource
2. of(R resource, BiFunction<T,R,U>, downstream) — external lookup, errors dropped
3. of(R resource, BiFunction<T,R,U>, downstream, errorChannel) — with error routing

### Architecture

```
Sender
  |
  |  send(T message)
  v
ContentEnricher<T, R, U>
  |
  |  enricher.apply(message, resource)
  |
  +--[ok]-------> downstream.send(enriched U)
  +--[throws]---> errorChannel.send(original T)
```

---

## API Reference

| Method | Description |
|---|---|
| of(Function<T,U>, MessageChannel<U>) | Pure derivation factory |
| of(R, BiFunction<T,R,U>, MessageChannel<U>) | External resource factory |
| of(R, BiFunction<T,R,U>, MessageChannel<U>, MessageChannel<T>) | With error channel |
| send(T message) | Enrich and forward; errors go to errorChannel |
| stop() | No-op — stateless |

---

## Code Example

```java
// Domain types
record OrderRequest(String orderId, String customerId, double amount) {}
record EnrichedOrder(String orderId, String customerName, String tier, double amount) {}

// Reference store
var customerDb = Map.of(
    "C-001", Map.of("name", "Acme Corp", "tier", "PLATINUM"),
    "C-002", Map.of("name", "Beta Ltd",  "tier", "GOLD")
);

// Channels
var processed = new CopyOnWriteArrayList<EnrichedOrder>();
var downstream = new PointToPointChannel<EnrichedOrder>(processed::add);
var deadLetter = new DeadLetterChannel<OrderRequest>();

// Enricher
var enricher = ContentEnricher.of(
    customerDb,
    (order, db) -> {
        var customer = db.get(order.customerId());
        if (customer == null) throw new IllegalArgumentException("Unknown: " + order.customerId());
        return new EnrichedOrder(order.orderId(), customer.get("name"), customer.get("tier"), order.amount());
    },
    downstream,
    deadLetter
);

enricher.send(new OrderRequest("ORD-001", "C-001", 9999.0));  // enriched
enricher.send(new OrderRequest("ORD-002", "UNKNOWN", 0.01)); // -> deadLetter

Thread.sleep(100);
System.out.println("Processed: " + processed.size());     // 1
System.out.println("Dead-letter: " + deadLetter.size());  // 1
downstream.stop();
```

### Pure Derivation

```java
var timestamper = ContentEnricher.of(
    (Order o) -> new TimestampedOrder(o.id(), o.amount(), Instant.now()),
    downstream
);
```

### Pipeline Chaining

```java
var withCustomer = ContentEnricher.of(customerDb,
    (req, db) -> new CustomerOrder(req, db.get(req.customerId())), stage2);

var withDiscount = ContentEnricher.of(discountService,
    (co, svc) -> new PricedOrder(co, svc.getDiscount(co.customer().tier())), stage3);
```

---

## Composition

### ContentEnricher + MessageRouter
```java
var router = MessageRouter.<EnrichedOrder>builder()
    .route("platinum", o -> "PLATINUM".equals(o.tier()), platinumQueue)
    .route("standard", o -> true, standardQueue)
    .deadLetter(deadLetter)
    .build();

// Enrich then route
var enrichAndRoute = ContentEnricher.of(customerDb,
    (req, db) -> /* ... */, router);
```

### ContentEnricher + WireTap
```java
var tapped = new WireTap<>(downstream, order ->
    auditLog.record("Enriched: " + order.orderId()));

var enricher = ContentEnricher.of(customerDb, enricherFn, tapped, deadLetter);
```

### Async Enricher (slow resource)
```java
// Run enrichment in a Proc mailbox to decouple sender from I/O latency
var enricherProc = new Proc<Void, OrderRequest>(null, (state, req) -> {
    var customer = slowCustomerService.fetch(req.customerId());
    downstream.send(new EnrichedOrder(req.orderId(), customer.name(), customer.tier(), req.amount()));
    return state;
});
MessageChannel<OrderRequest> asyncEnricher = enricherProc::tell;
```

---

## Test Pattern

```java
class ContentEnricherTest implements WithAssertions {

    record Req(String userId, double amount) {}
    record Enriched(String userId, String name, double amount) {}

    Map<String, String> db = Map.of("U-1", "Alice", "U-2", "Bob");

    @Test
    void enrichesFromResource() {
        var results = new CopyOnWriteArrayList<Enriched>();
        var enricher = ContentEnricher.of(db,
            (req, d) -> new Enriched(req.userId(), d.get(req.userId()), req.amount()),
            (MessageChannel<Enriched>) results::add);

        enricher.send(new Req("U-1", 100.0));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Alice");
    }

    @Test
    void failureRoutesToErrorChannel() {
        var errors = new DeadLetterChannel<Req>();
        var enricher = ContentEnricher.of(db,
            (req, d) -> { throw new RuntimeException("oops"); },
            (MessageChannel<Enriched>) msg -> {},
            errors);

        enricher.send(new Req("U-1", 1.0));

        assertThat(errors.size()).isEqualTo(1);
    }

    @Test
    void pureDerivation() {
        var results = new CopyOnWriteArrayList<String>();
        var enricher = ContentEnricher.of(
            (Req r) -> r.userId() + ":" + r.amount(),
            (MessageChannel<String>) results::add);

        enricher.send(new Req("U-1", 42.0));
        assertThat(results).containsExactly("U-1:42.0");
    }
}
```

---

## Caveats & Trade-offs

**Use when:**
- Downstream needs data the sender does not have (customer profiles, pricing, geolocation).
- The resource is thread-safe and fast (in-memory map, local cache).
- Input and output types are different — use the type system to prevent unenriched messages reaching downstream.

**Avoid when:**
- Resource lookup is slow (> 10ms) — use an async Proc-backed enricher to decouple latency.
- Resource is mutable and not thread-safe — protect it with a lock or wrap in a Proc.
- Enrichment is conditional — prefer a MessageRouter that routes to the enricher or bypasses it.
- Retries are needed on failure — implement retry inside the BiFunction or wrap in CrashRecovery.

**Performance:** ContentEnricher is allocation-free beyond the U object. No threads, queues, or locks. For concurrent access to the resource, prefer ConcurrentHashMap or an immutable Map.
