# Reactive Messaging Patterns in JOTP

> *"A process is the unit of concurrency. Processes share nothing, communicate only by message passing, and let crashes propagate through supervision trees."*
> — Joe Armstrong

## What This Book Covers

This book documents all **39 canonical Enterprise Integration Patterns** (Hohpe & Woolf, 2003) as implemented in **JOTP** — Java OTP, a Java 26 library that maps Erlang/OTP primitives to modern Java virtual threads, sealed interfaces, and records.

Every pattern has:
- An OTP analogy explaining the Erlang equivalent
- A JOTP implementation using `Proc<S,M>`, `EventManager<E>`, `Supervisor`, or the `org.acme.reactive` channel library
- Runnable Java 26 code examples
- JUnit 5 test patterns

## Why JOTP for Enterprise Integration?

Traditional Java EIP frameworks (Spring Integration, Apache Camel) use annotation-heavy configuration and heavyweight infrastructure. JOTP implements the same patterns in **pure Java 26** using:

| OTP Primitive | Java 26 Equivalent | EIP Role |
|---|---|---|
| `spawn/3` | `new Proc<>(initial, handler)` | Channel, Consumer, Router |
| `!` (send) | `proc.tell(msg)` | Message send |
| `gen_server:call/2` | `proc.ask(msg)` | Request-Reply |
| `gen_event` | `EventManager<E>` | Pub-Sub Channel |
| `supervisor` | `Supervisor` | Fault isolation |
| `sys:get_state` | `ProcSys.getState(proc)` | Introspection |
| Mailbox | `LinkedTransferQueue` | Message buffering |

## Pattern Count by Category

```
Message Channels        6 patterns   [01–06]
Message Construction    7 patterns   [07–13]
Message Routing         9 patterns   [14–22]
Message Transformation  6 patterns   [23–28]
Message Endpoints       8 patterns   [29–36]
System Management       3 patterns   [37–39]
─────────────────────────────────────
Total                  39 patterns
```

## The org.acme.reactive Package

All channel-level patterns are implemented as `MessageChannel<T>` implementations in `org.acme.reactive`:

```
org.acme.reactive/
├── MessageChannel<T>          (interface — the core abstraction)
├── PointToPointChannel<T>     [01] virtual thread + LinkedTransferQueue
├── PublishSubscribeChannel<T> [02] backed by EventManager<T> (gen_event)
├── DeadLetterChannel<T>       [03] in-memory dead-letter store
├── DurableSubscriber<T>       [05] Proc<State,Cmd> — pause/resume buffering
├── MessageRouter<T>           [14] static content-based routing
├── DynamicRouter<T>           [15] CopyOnWriteArrayList routing table
├── MessageFilter<T>           [16] predicate → accepted | rejected
├── MessageTransformer<T,U>    [23] pure transformation function
├── ContentEnricher<T,R,U>     [25] enrichment with external resource
├── MessageFilter<T>           [26] alias — content filtering
├── MessageAggregator<T,R>     [19] ConcurrentHashMap correlation groups
├── MessageSplitter<T,U>       [18] explode composite → parts
├── MessageDispatcher<T>       [32] competing-consumer worker pool
├── Resequencer<T,K>           [20] Proc<State,Entry> — in-order delivery
├── WireTap<T>                 [37] virtual thread tap fork
└── MessagePipeline<T>         [31] composable pipes-and-filters
```

## Quick Start

```java
// 1. Create a point-to-point channel
var orders = new PointToPointChannel<Order>(order -> process(order));

// 2. Add a wire tap for auditing
var audited = new WireTap<>(orders, order -> auditLog.append(order));

// 3. Add content-based routing in front
var router = MessageRouter.<Order>builder()
    .route(o -> o instanceof ExpressOrder, audited)
    .route(o -> o instanceof StandardOrder, orders)
    .otherwise(new DeadLetterChannel<>())
    .build();

// 4. Send messages — full EIP pipeline
router.send(new ExpressOrder("e-1", 2));
```

## Running the Tests

```bash
# All 39 patterns:
./mvnw test -Dtest="ReactiveMessaging*"

# Individual pattern groups:
./mvnw test -Dtest=ReactiveMessagingFoundationPatternsTest    # patterns 1–10
./mvnw test -Dtest=ReactiveMessagingRoutingPatternsTest       # patterns 11–19
./mvnw test -Dtest=ReactiveMessagingEndpointPatternsTest      # patterns 20–33
./mvnw test -Dtest=ReactiveMessagingSystemPatternsTest        # patterns 34–39
```
