# 9. Event Message

> *"An Event Message notifies interested parties that something has happened. The sender doesn't know or care who listens."*

## Intent

An Event Message announces a significant occurrence in the system. Unlike a Command, the sender does not direct any specific receiver to act. Zero or many consumers may react — the publisher is decoupled from all of them.

## OTP Analogy

`gen_event` in Erlang/OTP is the native event-publishing primitive. The manager process is the event bus; handlers are the subscribers:

```erlang
%% Publisher fires an event — no knowledge of handlers
gen_event:notify(domain_bus, {user_registered, #{id => "U1", email => "a@b.com"}}).

%% Each handler receives it independently
handle_event({user_registered, User}, State) ->
    send_welcome_email(User),
    {ok, State}.
```

In JOTP, `EventManager<E>` maps directly to `gen_event`.

## JOTP Implementation

Event Messages are sealed record hierarchies published via `EventManager<E>`. The sealed interface forms the closed event schema for a bounded context; each `permits` clause is a distinct domain event.

```
EventManager<DomainEvent>
        │
        ├── UserRegistered  ──► WelcomeEmailHandler
        ├── OrderPlaced     ──► PaymentHandler, InventoryHandler
        └── PaymentFailed   ──► RetryHandler, AlertHandler
```

Key design points:
- Events are named in past tense: `OrderPlaced`, `PaymentFailed`, `UserDeactivated`.
- Events carry enough data for handlers to act without additional queries.
- Include `occurredAt` so consumers can detect out-of-order delivery.
- `notify()` is fire-and-forget; `syncNotify()` blocks until all handlers finish.

## API Reference

| Method | Description |
|--------|-------------|
| `bus.notify(event)` | Async publish to all handlers |
| `bus.syncNotify(event)` | Sync publish; caller blocks until all handlers complete |
| `bus.addHandler(handler, initArg)` | Subscribe a handler |
| `bus.deleteHandler(handler)` | Unsubscribe a handler |

## Code Example

```java
import org.acme.EventManager;

// --- Sealed domain event hierarchy ---
sealed interface DomainEvent permits UserRegistered, OrderPlaced, PaymentFailed {}

record UserRegistered(
    String  userId,
    String  email,
    Instant occurredAt
) implements DomainEvent {}

record OrderPlaced(
    String  orderId,
    String  customerId,
    double  total,
    Instant occurredAt
) implements DomainEvent {}

record PaymentFailed(
    String  paymentId,
    String  orderId,
    String  reason,
    Instant occurredAt
) implements DomainEvent {}

// --- Handlers ---
class WelcomeEmailHandler implements EventManager.Handler<DomainEvent, Void> {
    @Override public Void init(Void v) { return null; }

    @Override
    public Void handleEvent(DomainEvent event, Void state) {
        if (event instanceof UserRegistered u)
            System.out.printf("[EMAIL] Welcome %s → %s%n", u.userId(), u.email());
        return null;
    }
}

class MetricsHandler implements EventManager.Handler<DomainEvent, Map<String, Long>> {
    @Override public Map<String, Long> init(Void v) { return new HashMap<>(); }

    @Override
    public Map<String, Long> handleEvent(DomainEvent event, Map<String, Long> counts) {
        String key = event.getClass().getSimpleName();
        counts.merge(key, 1L, Long::sum);
        return counts;
    }
}

// --- Publisher ---
public class EventPublisherDemo {
    public static void main(String[] args) {
        var bus = new EventManager<DomainEvent>();
        bus.addHandler(new WelcomeEmailHandler(), null);
        bus.addHandler(new MetricsHandler(), null);

        // Publish — neither handler knows about the other
        var now = Instant.now();
        bus.notify(new UserRegistered("U-001", "alice@example.com", now));
        bus.notify(new OrderPlaced("ORD-001", "U-001", 49.99, now));
        bus.notify(new PaymentFailed("PAY-001", "ORD-001", "card declined", now));

        // Sync publish — wait for all handlers before continuing
        bus.syncNotify(new UserRegistered("U-002", "bob@example.com", Instant.now()));
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;

class EventMessageTest implements WithAssertions {

    @Test
    void multipleHandlersReceiveSameEvent() {
        var bus  = new EventManager<DomainEvent>();
        var log1 = new CopyOnWriteArrayList<DomainEvent>();
        var log2 = new CopyOnWriteArrayList<DomainEvent>();

        bus.addHandler(collectingHandler(log1), null);
        bus.addHandler(collectingHandler(log2), null);

        var event = new UserRegistered("U-T1", "t@t.com", Instant.now());
        bus.syncNotify(event);

        assertThat(log1).containsExactly(event);
        assertThat(log2).containsExactly(event);
    }

    @Test
    void eventCarriesOccurredAt() {
        var before = Instant.now();
        var event  = new OrderPlaced("ORD-T1", "U-T1", 9.99, Instant.now());
        var after  = Instant.now();

        assertThat(event.occurredAt())
            .isAfterOrEqualTo(before)
            .isBeforeOrEqualTo(after);
    }

    @Test
    void unregisteredHandlerNoLongerReceivesEvents() {
        var bus      = new EventManager<DomainEvent>();
        var received = new CopyOnWriteArrayList<DomainEvent>();
        var handler  = collectingHandler(received);

        bus.addHandler(handler, null);
        bus.syncNotify(new UserRegistered("U-T2", "x@x.com", Instant.now()));
        assertThat(received).hasSize(1);

        bus.deleteHandler(handler);
        bus.syncNotify(new UserRegistered("U-T3", "y@y.com", Instant.now()));
        assertThat(received).hasSize(1); // still 1, not 2
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Multiple consumers react to the same occurrence, possibly in different ways.
- You want zero coupling between producer and consumer.
- Building an audit log, read-model projections, or sagas driven by domain events.

**Avoid when:**
- Exactly one service must handle the message — use a Command Message on a `PointToPointChannel`.
- Reliable delivery across process restarts is required — `EventManager` is in-memory; use a durable event store or message broker for persistence.
- You need causal ordering across concurrent publishers — in-memory notify does not provide global ordering.
