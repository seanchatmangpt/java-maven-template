# The MessageChannel Contract

`MessageChannel<T>` is the foundational abstraction of the `org.acme.reactive` package. Every EIP channel pattern in this book implements it.

```java
public interface MessageChannel<T> {
    /** Fire-and-forget send — never blocks the caller. Mirrors Erlang's `Pid ! Msg`. */
    void send(T message);

    /** Graceful shutdown — drain in-flight messages, then stop. Mirrors `gen_server:stop/1`. */
    void stop() throws InterruptedException;
}
```

## Design Principles

### 1. Send is Never Blocking

`send(T)` returns immediately in all implementations. The caller is never delayed by downstream processing speed. This mirrors Erlang's `!` operator, which always returns instantly regardless of the recipient's mailbox depth.

### 2. Single Responsibility

Each `MessageChannel` implementation does exactly one thing:

| Class | Responsibility |
|---|---|
| `PointToPointChannel` | Deliver to exactly one consumer |
| `PublishSubscribeChannel` | Fan-out to all subscribers |
| `MessageFilter` | Pass or reject based on predicate |
| `MessageTransformer` | Convert type A → type B |
| `DynamicRouter` | Route based on runtime-updated table |
| `WireTap` | Observe without affecting flow |

### 3. Composition

Channels compose by chaining: the output of one is the input of another.

```
Producer
    │
    ▼
[WireTap → AuditLog]
    │
    ▼
[MessageFilter: valid orders only]
    │
    ▼
[ContentEnricher: add customer name]
    │
    ▼
[DynamicRouter: express | standard]
    │          │
    ▼          ▼
[Express]  [Standard]
 Channel    Channel
```

### 4. JOTP-Backed vs. Stateless

| Category | Implementations | Internal mechanism |
|---|---|---|
| **Stateless** | `MessageFilter`, `MessageRouter`, `DynamicRouter`, `ContentEnricher`, `WireTap`, `MessageTransformer`, `MessageSplitter`, `MessageAggregator` | No thread — `send()` delegates directly |
| **JOTP-backed** | `PublishSubscribeChannel` | `EventManager<T>` (`gen_event`) |
| **Thread-backed** | `PointToPointChannel`, `MessageDispatcher` | `Thread.ofVirtual()` + `LinkedTransferQueue` |
| **Proc-backed** | `PollingConsumer`, `DurableSubscriber`, `Resequencer` | `Proc<S,M>` — full OTP fault isolation |

## Stopping Channels Correctly

Channels form a DAG. Stop in reverse-topological order (sinks before sources):

```java
// Correct teardown order:
sinkChannel.stop();       // stop sink first
enricher.stop();          // stateless — no-op
filterChannel.stop();     // stateless — no-op
sourceChannel.stop();     // stop source last
```

`stop()` on stateless channels (filter, router, enricher) is a no-op since they hold no threads. Only channels with virtual threads (`PointToPointChannel`, `MessageDispatcher`, `PollingConsumer`, `DurableSubscriber`, `Resequencer`) need explicit stop.
