# Enterprise Messaging Patterns

This document describes the Enterprise Integration Patterns (EIP) implemented in `org.acme.dogfood.messaging`, providing production-ready messaging primitives for distributed systems.

---

## Overview

The messaging patterns follow the Enterprise Integration Patterns catalog by Gregor Hohpe and Bobby Woolf, adapted for Java 26 with virtual threads, sealed types, and modern concurrency primitives.

---

## Message Bus Pattern

**Class:** `MessageBusPatterns<T>`

A centralized communication hub that decouples message producers from consumers.

### API

```java
MessageBusPatterns<T> bus = MessageBusPatterns.create();

// Subscribe to messages
bus.subscribe(message -> System.out.println("Received: " + message));

// Publish asynchronously (fire-and-forget)
bus.publish("Hello, World!");

// Publish synchronously (wait for all handlers)
bus.publishSync("Important message");

// Unsubscribe
bus.unsubscribe(handler);

// Query state
int count = bus.subscriberCount();
bus.clear();
```

### Example

```java
record OrderEvent(String orderId, String status) {}

var orderBus = MessageBusPatterns.<OrderEvent>create();

// Audit subscriber
orderBus.subscribe(event -> auditLog.add("AUDIT: " + event.orderId()));

// Notification subscriber
orderBus.subscribe(event -> {
    if ("COMPLETED".equals(event.status())) {
        notifyCustomer(event.orderId());
    }
});

// Publish events
orderBus.publishSync(new OrderEvent("ORD-001", "CREATED"));
orderBus.publishSync(new OrderEvent("ORD-001", "PROCESSING"));
orderBus.publishSync(new OrderEvent("ORD-001", "COMPLETED"));
```

### Properties

- **Decoupling:** Publishers don't know about subscribers
- **Broadcast:** All subscribers receive each message
- **Async/Sync:** Choose based on reliability requirements
- **Virtual Threads:** Each handler runs in its own virtual thread

---

## Content-Based Router Pattern

**Class:** `RouterPatterns<T>`

Routes messages to different handlers based on message content.

### API

```java
RouterPatterns<T> router = RouterPatterns.create();

// Add routes with predicates
router.route(
    msg -> msg.type().equals("ERROR"),
    msg -> errorHandler.handle(msg)
);

router.route(
    msg -> msg.type().equals("INFO"),
    msg -> infoLogger.log(msg)
);

// Fallback for unmatched messages
router.fallback(msg -> defaultHandler.handle(msg));

// Route a message
boolean routed = router.routeMessage(message);

// Round-robin across matching handlers
router.routeRoundRobin(message);

// Find all matching handlers
List<Consumer<T>> handlers = router.findMatchingHandlers(message);
```

### Example

```java
record LogMessage(String level, String message, Instant timestamp) {}

var router = RouterPatterns.<LogMessage>create();

router.route(
    msg -> "ERROR".equals(msg.level()),
    msg -> alertingService.sendAlert(msg)
);

router.route(
    msg -> "WARN".equals(msg.level()),
    msg -> metricsService.recordWarning(msg)
);

router.fallback(msg -> log.info(msg.message()));

// Route incoming messages
router.routeMessage(new LogMessage("ERROR", "Connection failed", Instant.now()));
```

### Properties

- **Conditional Routing:** Messages routed by predicates
- **Order Matters:** First matching route wins
- **Fallback:** Catch-all for unmatched messages
- **Round-Robin:** Load balance across matching handlers

---

## Publish-Subscribe Pattern

**Class:** `PubSubPatterns<T>`

Topic-based publish-subscribe messaging.

### API

```java
PubSubPatterns<T> pubsub = PubSubPatterns.create();

// Subscribe to a topic
pubsub.subscribe("orders", message -> processOrder(message));
pubsub.subscribe("notifications", message -> sendNotification(message));

// Publish to a topic
int subscribers = pubsub.publish("orders", orderEvent);

// Synchronous publish
pubsub.publishSync("orders", orderEvent);

// Unsubscribe
pubsub.unsubscribe("orders", handler);

// Query state
int count = pubsub.subscriberCount("orders");
List<String> topics = pubsub.activeTopics();
pubsub.clear();
```

### Example

```java
record StockPrice(String symbol, double price, Instant timestamp) {}

var marketData = PubSubPatterns.<StockPrice>create();

// Multiple subscribers per symbol
marketData.subscribe("AAPL", price -> tradingEngine.onPrice(price));
marketData.subscribe("AAPL", price -> analytics.record(price));
marketData.subscribe("MSFT", price -> tradingEngine.onPrice(price));

// Broadcast price updates
marketData.publish("AAPL", new StockPrice("AAPL", 175.50, Instant.now()));
marketData.publish("MSFT", new StockPrice("MSFT", 380.25, Instant.now()));
```

### Properties

- **Topic-Based:** Messages categorized by topic
- **Multi-Subscriber:** Multiple handlers per topic
- **Discovery:** Query active topics and subscriber counts

---

## Scatter-Gather Pattern

**Class:** `ScatterGatherPatterns<T, R>`

Broadcast a request to multiple handlers and aggregate responses.

### API

```java
ScatterGatherPatterns<Request, Response> scatter = ScatterGatherPatterns.create();

// Add handlers
scatter.addHandler(request -> serviceA.handle(request));
scatter.addHandler(request -> serviceB.handle(request));
scatter.addHandler(request -> serviceC.handle(request));

// Set timeout
scatter.withTimeout(Duration.ofSeconds(5));

// Scatter and gather
ScatterResult<Response> result = scatter.scatterAndGather(request);

// Access results
List<Response> successes = result.results();
List<Throwable> errors = result.errors();
boolean allSucceeded = result.allSucceeded();
double successRate = result.successRate();

// Get only successful responses
List<Response> successful = scatter.scatterAndGatherSuccessful(request);
```

### Example

```java
record PriceRequest(String productId) {}
record PriceQuote(String vendor, double price) {}

var priceGather = ScatterGatherPatterns.<PriceRequest, PriceQuote>create()
    .withTimeout(Duration.ofSeconds(2));

priceGather.addHandler(req -> amazonClient.getQuote(req));
priceGather.addHandler(req -> walmartClient.getQuote(req));
priceGather.addHandler(req -> targetClient.getQuote(req));

// Get quotes from all vendors
var result = priceGather.scatterAndGather(new PriceRequest("SKU-123"));

if (result.anySucceeded()) {
    // Find best price
    PriceQuote best = result.results().stream()
        .min(Comparator.comparing(PriceQuote::price))
        .orElseThrow();

    System.out.println("Best price: " + best.price() + " from " + best.vendor());
}
```

### Properties

- **Fan-Out:** Request sent to all handlers concurrently
- **Aggregation:** Responses collected into `ScatterResult`
- **Timeout:** Configurable deadline for all responses
- **Error Isolation:** Failed handlers don't affect others

---

## Correlation Identifier Pattern

**Class:** `CorrelationPatterns<T, R>`

Correlate requests with replies in asynchronous messaging.

### API

```java
CorrelationPatterns<Request, Reply> correlator = CorrelationPatterns.create();

// Configure
correlator.withDefaultTimeout(Duration.ofSeconds(30));
correlator.withCorrelationIdExtractor(req -> req.requestId());

// Send request and get correlation ID
String correlationId = correlator.sendRequest(request);

// Wait for reply (blocking)
Reply reply = correlator.sendAndWaitForReply(request);
Reply reply = correlator.sendAndWaitForReply(request, Duration.ofSeconds(10));

// Handle incoming reply
boolean matched = correlator.handleReply(correlationId, reply);

// Handle error
correlator.handleError(correlationId, new RuntimeException("Failed"));

// Query state
boolean pending = correlator.hasPendingReply(correlationId);
int count = correlator.pendingReplyCount();

// Cleanup expired requests
int expired = correlator.cleanupExpired();

// Generate correlation ID
String id = CorrelationPatterns.generateCorrelationId();
```

### Example

```java
record OrderRequest(String orderId, List<String> items) {}
record OrderConfirmation(String orderId, String status, Instant estimatedDelivery) {}

var orderCorrelator = CorrelationPatterns.<OrderRequest, OrderConfirmation>create()
    .withDefaultTimeout(Duration.ofSeconds(60));

// Client: Send order and wait for confirmation
OrderConfirmation confirmation = orderCorrelator.sendAndWaitForReply(
    new OrderRequest("ORD-123", List.of("ITEM-A", "ITEM-B"))
);

// Server: Process order and send reply
void onOrderProcessed(OrderConfirmation confirmation) {
    orderCorrelator.handleReply(confirmation.orderId(), confirmation);
}
```

### Properties

- **Request-Reply Matching:** Correlation IDs link requests to replies
- **Timeout Handling:** Expired requests automatically cleaned up
- **Blocking API:** `sendAndWaitForReply` blocks until reply arrives
- **Custom ID Generation:** Configure your own correlation ID strategy

---

## Pattern Selection Guide

| Pattern | Use Case |
|---------|----------|
| **Message Bus** | Decouple producers from consumers; broadcast events |
| **Content-Based Router** | Route messages based on content; filter by type |
| **Publish-Subscribe** | Topic-based messaging; multiple subscribers per topic |
| **Scatter-Gather** | Parallel requests with aggregated responses |
| **Correlation Identifier** | Async request-reply; match responses to requests |

---

## Integration with OTP Patterns

These messaging patterns integrate seamlessly with the OTP primitives:

```java
// Use Proc as a message handler
var handler = new Proc<State, Message>(initialState, (state, msg) -> {
    return switch (msg) {
        case ProcessOrder order -> {
            state.bus().publish(order);
            yield state.withOrder(order);
        }
        // ...
    };
});

// Supervised message processor
var supervisor = new Supervisor.SupervisorBuilder()
    .withStrategy(Supervisor.Strategy.ONE_FOR_ONE)
    .addChild(handler)
    .build();
```

---

## Performance Considerations

- **Virtual Threads:** All async operations use virtual threads for scalability
- **Non-Blocking:** `publish()` returns immediately; handlers run concurrently
- **Thread-Safe:** All pattern classes are thread-safe for concurrent access
- **Memory:** `CopyOnWriteArrayList` for subscriber lists (read-heavy workloads)

---

## Testing

All patterns have comprehensive test coverage in `src/test/java/org/acme/dogfood/messaging/`:

```java
@Test
void messageBus_workflow_scenario() throws Exception {
    var bus = MessageBusPatterns.<OrderEvent>create();
    List<String> audit = new ArrayList<>();

    bus.subscribe(event -> audit.add("AUDIT: " + event.orderId()));
    bus.publishSync(new OrderEvent("ORD-001", "CREATED"));

    assertThat(audit).contains("AUDIT: ORD-001");
}
```
