# 25. Content Enricher

> *"A gen_server handler does a side-effect-free lookup — `{ok, Extra} = resource_server:fetch(Key)` — then returns the enriched state in one atomic step. No intermediate mutable state, no lock. The Content Enricher is that lookup made explicit, typed, and composable."*

## Intent

Augment an incoming message with additional data from an external resource, producing an enriched output message and routing enrichment failures to a dedicated error channel rather than crashing the pipeline.

The Content Enricher sits between a source channel and a downstream consumer. It is a pure transformation: given a message of type `T` and a resource of type `R`, it produces a message of type `U`. The resource lookup may fail; the error channel ensures the failure is observable and recoverable without stopping the enricher.

## OTP Analogy

In OTP, enrichment is a `gen_server:call` to a resource process followed by a pure state construction:

```erlang
handle_cast({enrich, Msg}, #state{resource=R, downstream=DS}=S) ->
    case resource:fetch(R, key_of(Msg)) of
        {ok, Extra} ->
            Enriched = merge(Msg, Extra),
            DS ! {enriched, Enriched},
            {noreply, S};
        {error, Reason} ->
            error_channel ! {enrichment_failed, Msg, Reason},
            {noreply, S}
    end.
```

Key properties:
1. **No state mutation** — the enricher's `gen_server` state does not change. The resource lookup is a read-only side effect.
2. **Pattern-matched error handling** — the `{error, Reason}` clause routes failures without crashing the server.
3. **Downstream decoupling** — the enricher sends to `DS` (downstream), not directly to the consumer.

The Java `ContentEnricher<T, R, U>` maps directly:
- `resource:fetch(R, key_of(Msg))` → `Function<T, R> resourceLoader`
- `merge(Msg, Extra)` → `BiFunction<T, R, U> enricher`
- `error_channel ! {enrichment_failed, ...}` → `MessageChannel<EnrichmentError<T>> errorChannel`
- `DS ! {enriched, Enriched}` → `MessageChannel<U> downstream`

The three factory methods correspond to three OTP patterns: no resource (pure function), external resource with error channel, and resource that itself returns a `Result<R,E>`.

## JOTP Implementation

**Class:** `ContentEnricher<T, R, U>`
**Package:** `org.acme.eip.transformation`
**Key design decisions:**

1. **Three factory methods** — `simple`, `withResource`, `withResult` — cover the three common enrichment patterns. The factory method names signal intent to readers and prevent misuse (e.g., using `simple` when a resource lookup can fail would force the exception to propagate unchecked).

2. **`Function<T, R> resourceLoader`** — the resource lookup is a plain `Function`. If the function throws, the enricher catches the exception and routes it to the error channel. This means the resource loader does not need to handle its own errors — the enricher is the error boundary.

3. **Error channel** — `MessageChannel<EnrichmentError<T>>` receives the original message, the exception, and a timestamp when enrichment fails. The error channel is optional in `simple()` (no resource, no errors possible) but mandatory in `withResource()` and `withResult()`.

4. **Virtual thread isolation** — each `enrich(T)` call runs synchronously on the calling thread by default. For async enrichment, `enrichAsync(T)` wraps the call in a virtual thread, enabling fan-out enrichment of batches.

5. **`Result<R, E>` variant** — `withResult` accepts a `Function<T, Result<R, E>> resourceLoader`. The `Failure` case routes to the error channel without throwing. This is the railway-oriented programming variant for callers who prefer explicit error types over exceptions.

6. **Composability** — `ContentEnricher<T, R, U>` implements `Function<T, Optional<U>>`: `Optional.empty()` on enrichment failure (after routing to error channel), `Optional.of(enriched)` on success. This allows chaining with `Stream.flatMap`, `CompletableFuture.thenApply`, and other functional composition patterns.

## API Reference

### `ContentEnricher<T, R, U>` (implements `Function<T, Optional<U>>`)

| Method | Signature | Description |
|--------|-----------|-------------|
| `enrich` | `Optional<U> enrich(T message)` | Enrich synchronously; returns empty on failure (after routing to error channel) |
| `enrichOrThrow` | `U enrichOrThrow(T message)` | Enrich synchronously; throws `EnrichmentException` on failure |
| `enrichAsync` | `CompletableFuture<Optional<U>> enrichAsync(T message)` | Enrich on a virtual thread |
| `enrichedCount` | `long enrichedCount()` | Total successful enrichments |
| `failureCount` | `long failureCount()` | Total enrichment failures routed to error channel |
| `apply` | `Optional<U> apply(T message)` | `Function<T, Optional<U>>` implementation (delegates to `enrich`) |

### Factory methods

```java
// 1. Simple: pure function, no resource, no error possible
ContentEnricher<T, Void, U> enricher = ContentEnricher.simple(
    Function<T, U> transformer
);

// 2. With external resource: resourceLoader may throw
ContentEnricher<T, R, U> enricher = ContentEnricher.withResource(
    Function<T, R>                     resourceLoader,  // may throw
    BiFunction<T, R, U>               enricher,
    MessageChannel<EnrichmentError<T>> errorChannel
);

// 3. With Result type: railway-oriented, no exceptions
ContentEnricher<T, R, U> enricher = ContentEnricher.withResult(
    Function<T, Result<R, ?>>          resourceLoader,
    BiFunction<T, R, U>               enricher,
    MessageChannel<EnrichmentError<T>> errorChannel
);
```

### Builder API (for full configuration)

```java
ContentEnricher<T, R, U> enricher = ContentEnricher.<T, R, U>builder()
    .resourceLoader(t -> externalService.lookup(t.key()))
    .enricher((msg, resource) -> new EnrichedMsg(msg, resource))
    .errorChannel(errorCh)
    .timeout(Duration.ofSeconds(5))
    .onSuccess(u -> metrics.count("enriched"))
    .onFailure(err -> log.warn("enrichment failed: {}", err))
    .build();
```

### `EnrichmentError<T>` (record)

```java
record EnrichmentError<T>(
    T         originalMessage,
    Throwable cause,
    Instant   timestamp,
    String    enricherName
) {}
```

## Implementation Internals

```
ContentEnricher<T, R, U>

enrich(T message):
│
├── [simple factory]:
│   ├── U result = transformer.apply(message)
│   ├── enrichedCount++
│   └── return Optional.of(result)
│
├── [withResource factory]:
│   ├── try:
│   │   ├── R resource = resourceLoader.apply(message)
│   │   ├── U result   = enricher.apply(message, resource)
│   │   ├── enrichedCount++
│   │   └── return Optional.of(result)
│   └── catch Throwable t:
│       ├── failureCount++
│       ├── errorChannel.send(new EnrichmentError(message, t, Instant.now(), name))
│       └── return Optional.empty()
│
└── [withResult factory]:
    ├── Result<R, ?> result = resourceLoader.apply(message)
    ├── switch result:
    │   ├── Success(R r):
    │   │   ├── U enriched = enricher.apply(message, r)
    │   │   ├── enrichedCount++
    │   │   └── return Optional.of(enriched)
    │   └── Failure(E e):
    │       ├── failureCount++
    │       ├── errorChannel.send(new EnrichmentError(message, wrap(e), now(), name))
    │       └── return Optional.empty()
```

**Thread safety:** `enrichedCount` and `failureCount` are `LongAdder` instances — safe for concurrent calls. The `resourceLoader` function is invoked on the calling thread (or a virtual thread in `enrichAsync`); no state is shared between calls.

## Code Example

### Factory 1: Simple (No Resource)

```java
import org.acme.eip.transformation.ContentEnricher;

// --- Domain ---
record RawOrder(String id, double amount) {}
record NormalizedOrder(String id, double amount, String currency, Instant receivedAt) {}

// --- Simple enricher: pure function, adds metadata ---
ContentEnricher<RawOrder, Void, NormalizedOrder> enricher = ContentEnricher.simple(
    order -> new NormalizedOrder(
        order.id(),
        order.amount(),
        "USD",
        Instant.now()
    )
);

var raw      = new RawOrder("ORD-001", 150.00);
var enriched = enricher.enrich(raw);

enriched.ifPresent(o ->
    System.out.printf("Enriched: %s %.2f %s at %s%n",
        o.id(), o.amount(), o.currency(), o.receivedAt()));
```

### Factory 2: With External Resource

```java
// --- Domain ---
record Order(String id, String customerId, double amount) {}
record CustomerProfile(String id, String name, String tier) {}
record EnrichedOrder(Order order, CustomerProfile customer) {}

// --- Error channel ---
var errorChannel = new InMemoryChannel<ContentEnricher.EnrichmentError<Order>>("enrich-errors");

// --- Enricher: look up customer profile from external service ---
ContentEnricher<Order, CustomerProfile, EnrichedOrder> enricher =
    ContentEnricher.withResource(
        order -> customerService.getProfile(order.customerId()),  // may throw
        (order, profile) -> new EnrichedOrder(order, profile),
        errorChannel
    );

// --- Enrich orders ---
var orders = List.of(
    new Order("ORD-001", "CUST-A", 100.0),
    new Order("ORD-002", "CUST-MISSING", 200.0),  // customer not found -> error
    new Order("ORD-003", "CUST-B", 300.0)
);

orders.stream()
    .map(enricher::enrich)
    .flatMap(Optional::stream)
    .forEach(enrichedOrder ->
        System.out.printf("Order %s -> customer %s (%s)%n",
            enrichedOrder.order().id(),
            enrichedOrder.customer().name(),
            enrichedOrder.customer().tier()));

System.out.println("Enriched: " + enricher.enrichedCount());  // 2
System.out.println("Failures: " + enricher.failureCount());   // 1
```

### Factory 3: With Result Type (Railway-Oriented)

```java
// --- Resource service returns Result, not throws ---
record InventoryCheck(String productId, int available, boolean inStock) {}
record OrderWithInventory(Order order, InventoryCheck inventory) {}

ContentEnricher<Order, InventoryCheck, OrderWithInventory> enricher =
    ContentEnricher.withResult(
        order -> inventoryService.check(order.id()),   // returns Result<InventoryCheck, ServiceError>
        (order, inv) -> new OrderWithInventory(order, inv),
        errorChannel
    );

var order  = new Order("ORD-004", "CUST-C", 50.0);
var result = enricher.enrich(order);
result.ifPresentOrElse(
    owi -> System.out.println("In stock: " + owi.inventory().inStock()),
    ()  -> System.out.println("Enrichment failed, see error channel")
);
```

### Async Enrichment of a Batch

```java
// Enrich 1000 orders in parallel using virtual threads
var futures = orders.stream()
    .map(enricher::enrichAsync)
    .toList();

var enriched = futures.stream()
    .map(CompletableFuture::join)
    .flatMap(Optional::stream)
    .toList();

System.out.println("Enriched " + enriched.size() + " of " + orders.size());
```

### Chained Enrichment Pipeline

```java
// Chain two enrichers: first add customer profile, then add shipping address
ContentEnricher<Order, CustomerProfile, OrderWithCustomer> step1 =
    ContentEnricher.withResource(
        order -> customerService.getProfile(order.customerId()),
        OrderWithCustomer::new,
        errorChannel
    );

ContentEnricher<OrderWithCustomer, Address, FullyEnrichedOrder> step2 =
    ContentEnricher.withResource(
        owc -> addressService.getShipping(owc.customer().id()),
        FullyEnrichedOrder::new,
        errorChannel
    );

// Compose as a pipeline
Function<Order, Optional<FullyEnrichedOrder>> pipeline =
    order -> step1.enrich(order).flatMap(step2::enrich);
```

## Composition

**ContentEnricher + MessageRouter:**
Route enriched messages by enriched field, which was not available before enrichment:
```java
enricher.enrich(order).ifPresent(enrichedOrder -> tierRouter.route(enrichedOrder));
```

**ContentEnricher + DurableSubscriber:**
Feed a durable subscriber that buffers enriched messages during maintenance:
```java
var durable = DurableSubscriber.<EnrichedOrder>builder()
    .handler(orderProcessor::process).build();
Consumer<Order> pipeline = order ->
    enricher.enrich(order).ifPresent(durable::send);
```

**ContentEnricher + WireTap:**
Observe enrichment failures via tap on the error channel:
```java
var monitoredErrorCh = WireTap.of(errorChannel,
    err -> alerting.fire("ENRICHMENT_FAILURE", err.originalMessage()));
```

**ContentEnricher + PollingConsumer:**
Enrich every polled message before downstream processing:
```java
var consumer = PollingConsumer.<Order>builder()
    .source(db::pollNextOrder)
    .handler(order -> enricher.enrich(order).ifPresent(processor::process))
    .build();
```

## Test Pattern

```java
import org.acme.eip.transformation.ContentEnricher;
import org.acme.eip.channel.CapturingChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class ContentEnricherTest implements WithAssertions {

    record Input(String id, String lookupKey) {}
    record Resource(String key, String extra) {}
    record Output(Input input, Resource resource) {}

    @Test
    void simpleEnricher_transformsWithoutResource() {
        var enricher = ContentEnricher.<Input, Void, String>simple(
            in -> in.id().toUpperCase()
        );

        var result = enricher.enrich(new Input("abc", "k1"));
        assertThat(result).contains("ABC");
        assertThat(enricher.enrichedCount()).isEqualTo(1);
        assertThat(enricher.failureCount()).isEqualTo(0);
    }

    @Test
    void withResource_successPath() {
        var errors = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");

        var enricher = ContentEnricher.<Input, Resource, Output>withResource(
            in -> new Resource(in.lookupKey(), "extra-for-" + in.lookupKey()),
            Output::new,
            errors
        );

        var result = enricher.enrich(new Input("id1", "key1"));
        assertThat(result).isPresent();
        assertThat(result.get().resource().extra()).isEqualTo("extra-for-key1");
        assertThat(enricher.enrichedCount()).isEqualTo(1);
        assertThat(errors.captured()).isEmpty();
    }

    @Test
    void withResource_failurePath_routesToErrorChannel() {
        var errors = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");

        var enricher = ContentEnricher.<Input, Resource, Output>withResource(
            in -> { throw new RuntimeException("lookup failed: " + in.lookupKey()); },
            Output::new,
            errors
        );

        var result = enricher.enrich(new Input("id-fail", "missing-key"));
        assertThat(result).isEmpty();
        assertThat(enricher.failureCount()).isEqualTo(1);
        assertThat(errors.captured()).hasSize(1);
        assertThat(errors.captured().get(0).originalMessage())
            .isEqualTo(new Input("id-fail", "missing-key"));
        assertThat(errors.captured().get(0).cause())
            .hasMessageContaining("lookup failed: missing-key");
    }

    @Test
    void withResult_successPath() {
        var errors = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");

        var enricher = ContentEnricher.<Input, Resource, Output>withResult(
            in -> org.acme.Result.success(new Resource(in.lookupKey(), "found")),
            Output::new,
            errors
        );

        var result = enricher.enrich(new Input("id2", "key2"));
        assertThat(result).isPresent();
        assertThat(result.get().resource().extra()).isEqualTo("found");
        assertThat(errors.captured()).isEmpty();
    }

    @Test
    void withResult_failurePath_routesToErrorChannel() {
        var errors = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");

        var enricher = ContentEnricher.<Input, Resource, Output>withResult(
            in -> org.acme.Result.failure(new RuntimeException("not found")),
            Output::new,
            errors
        );

        var result = enricher.enrich(new Input("id-miss", "missing"));
        assertThat(result).isEmpty();
        assertThat(enricher.failureCount()).isEqualTo(1);
        assertThat(errors.captured()).hasSize(1);
    }

    @Test
    void enrichmentFailure_doesNotAffectSubsequentMessages() {
        var errors   = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");
        var enricher = ContentEnricher.<Input, Resource, Output>withResource(
            in -> {
                if ("fail".equals(in.lookupKey())) throw new RuntimeException("boom");
                return new Resource(in.lookupKey(), "ok");
            },
            Output::new,
            errors
        );

        List.of(
            new Input("1", "ok"),
            new Input("2", "fail"),
            new Input("3", "ok")
        ).forEach(enricher::enrich);

        assertThat(enricher.enrichedCount()).isEqualTo(2);
        assertThat(enricher.failureCount()).isEqualTo(1);
        assertThat(errors.captured()).hasSize(1);
    }

    @Test
    void asyncEnrichment_allCompleteEventually() throws Exception {
        var errors   = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");
        var enricher = ContentEnricher.<Input, Resource, Output>withResource(
            in -> {
                Thread.sleep(10);  // simulate async I/O
                return new Resource(in.lookupKey(), "async-val");
            },
            Output::new,
            errors
        );

        var inputs  = List.of(new Input("a", "k1"), new Input("b", "k2"), new Input("c", "k3"));
        var futures = inputs.stream().map(enricher::enrichAsync).toList();

        var results = futures.stream()
            .map(CompletableFuture::join)
            .flatMap(Optional::stream)
            .toList();

        assertThat(results).hasSize(3);
        assertThat(enricher.enrichedCount()).isEqualTo(3);
    }

    @Test
    void asFunctionInterface_composeWithStream() {
        var errors  = new CapturingChannel<ContentEnricher.EnrichmentError<Input>>("errors");
        var enricher = ContentEnricher.<Input, String, String>withResource(
            in -> "enriched-" + in.id(),
            (in, extra) -> extra,
            errors
        );

        var results = List.of(new Input("a", "k"), new Input("b", "k"))
            .stream()
            .map(enricher)           // ContentEnricher implements Function
            .flatMap(Optional::stream)
            .toList();

        assertThat(results).containsExactly("enriched-a", "enriched-b");
    }
}
```

## Caveats & Trade-offs

**Use when:**
- A message requires data from an external system before it can be processed downstream
- The resource lookup may fail and failures must be handled gracefully (not silently dropped or exception-propagated to the producer)
- You want to keep the message schema minimal at the source and enrich at the point of need

**Avoid when:**
- The "enrichment" is actually a complex join requiring multiple messages from multiple sources — use a `Scatter-Gather` or `Aggregator` pattern instead
- The resource is always available and cannot fail (e.g., a constant map lookup) — `ContentEnricher.simple()` is fine, but even a plain `Stream.map()` is simpler
- Enrichment must be transactional with downstream processing — the enricher does not participate in transactions; use a transactional outbox pattern instead

**Latency vs. throughput:**
- Synchronous enrichment blocks the calling thread during the resource lookup. For bulk enrichment, use `enrichAsync()` to pipeline lookups across virtual threads.
- If the resource service has a batch API (`fetchAll(List<Key>)`), prefer batching over per-message calls to amortize network round-trip cost.

**Resource loader idempotency:**
- If the enricher retries on failure, the resource loader may be called multiple times for the same message. Ensure the resource loader is idempotent (read-only operations always are).

**Error channel as backpressure signal:**
- A rising `failureCount()` means the resource service is degraded. Wire a `WireTap` on the error channel to alert on-call. Consider adding a circuit breaker around the resource loader to avoid overwhelming a degrading resource service.

**Factory method choice guide:**
- `simple()` — no I/O, no errors possible. Pure function, no error channel needed.
- `withResource()` — resource lookup may throw `RuntimeException`. Error channel is mandatory.
- `withResult()` — resource service has an explicit `Result<R, E>` API. Prefer this over `withResource()` when the resource service is under your control — it makes failures explicit in the type system without relying on exception handling.
