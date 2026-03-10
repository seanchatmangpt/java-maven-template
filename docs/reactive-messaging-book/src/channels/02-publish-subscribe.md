# 02. Publish-Subscribe Channel

> *"gen_event: one event manager, arbitrarily many handlers, each handler isolated — a crashing handler is silently removed without killing the manager."*
> — OTP Design Principles

## Intent

Broadcast every message to all registered subscribers. Each subscriber receives an independent copy. Subscriber failures are isolated and do not affect other subscribers.

## OTP Analogy

Erlang's `gen_event` behaviour is the direct model. An event manager process (`EventManager<E>` in JOTP) maintains a list of handler modules. When `gen_event:notify/2` is called, each handler receives the event and processes it independently. A crashing handler is removed; others continue.

```erlang
%% Erlang gen_event
gen_event:add_handler(MyBus, audit_handler, []),
gen_event:add_handler(MyBus, metrics_handler, []),
gen_event:notify(MyBus, {order_placed, OrderId}).
```

```java
// JOTP: PublishSubscribeChannel backed by EventManager<T>
var bus = new PublishSubscribeChannel<DomainEvent>();
bus.subscribe(event -> auditLog.record(event));    // handler 1
bus.subscribe(event -> metrics.count(event));      // handler 2
bus.send(new OrderPlaced(orderId));                // both receive it
```

## JOTP Implementation

**Class**: `org.acme.reactive.PublishSubscribeChannel<T>`
**Backed by**: `EventManager<T>` which is itself backed by `Proc<List<Handler<T>>, Event>`

```
sender ──► bus.send(event) ──► EventManager.notify(event)
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
               handler-1           handler-2           handler-3
              (isolated)          (isolated)          (isolated)
```

The `EventManager` process dispatches events to each handler in its handler list. If a handler throws, it is removed and the manager continues — fault isolation identical to `gen_event`.

## API Reference

| Method | Description |
|---|---|
| `void subscribe(Consumer<T> subscriber)` | Register a subscriber (mirrors `gen_event:add_handler`) |
| `boolean unsubscribe(Consumer<T> subscriber)` | Remove a subscriber (mirrors `gen_event:delete_handler`) |
| `void send(T message)` | Broadcast asynchronously (mirrors `gen_event:notify`) |
| `void sendSync(T message)` | Broadcast and wait for all handlers (mirrors `gen_event:sync_notify`) |
| `void stop()` | Stop the event manager (mirrors `gen_event:stop`) |

## Code Example

```java
var bus = new PublishSubscribeChannel<String>();
var auditLog = new CopyOnWriteArrayList<String>();
var metricLog = new CopyOnWriteArrayList<String>();

bus.subscribe(msg -> auditLog.add("AUDIT: " + msg));
bus.subscribe(msg -> metricLog.add("METRIC: " + msg));

bus.sendSync("order.created");   // wait for both handlers
bus.sendSync("order.shipped");

// Both handlers received both events
assert auditLog.size() == 2;
assert metricLog.size() == 2;

bus.stop();
```

## Fault Isolation Example

```java
var bus = new PublishSubscribeChannel<String>();
var goodCount = new AtomicInteger(0);

bus.subscribe(msg -> { throw new RuntimeException("crash!"); }); // bad subscriber
bus.subscribe(msg -> goodCount.incrementAndGet());               // good subscriber

bus.sendSync("event-1");  // bad handler crashes and is removed
bus.sendSync("event-2");  // only good handler runs

assert goodCount.get() == 2; // good handler received both events
bus.stop();
```

## Composition

Use with `MessageRouter` to fan-out selectively:

```java
// Route events to a pub-sub bus which broadcasts to all subscribers
var bus = new PublishSubscribeChannel<OrderEvent>();
bus.subscribe(auditService::record);
bus.subscribe(metricsService::track);
bus.subscribe(notificationService::send);

// All three subscribers get every order event
var p2p = new PointToPointChannel<Order>(order -> {
    // process order, then publish event
    bus.send(new OrderProcessed(order.id()));
});
```

## Test Pattern

```java
@Test
void pubSub_allSubscribersReceiveEveryMessage() throws InterruptedException {
    var ch = new PublishSubscribeChannel<String>();
    var received1 = new CopyOnWriteArrayList<String>();
    var received2 = new CopyOnWriteArrayList<String>();

    ch.subscribe(received1::add);
    ch.subscribe(received2::add);

    ch.sendSync("hello");
    ch.sendSync("world");

    assertThat(received1).containsExactly("hello", "world");
    assertThat(received2).containsExactly("hello", "world");
    ch.stop();
}
```

## Caveats & Trade-offs

**Use when:**
- Multiple systems must react to the same event
- Publishers should be unaware of subscribers (loose coupling)
- Subscriber failures must not affect other subscribers

**Ordering**: Within a single subscriber, events arrive in send order. Across subscribers, no cross-subscriber ordering guarantee.

**Synchronous vs. asynchronous**: `sendSync` blocks until all current handlers finish. Use `send` for fire-and-forget; use `sendSync` in tests or when you need completion guarantees.
