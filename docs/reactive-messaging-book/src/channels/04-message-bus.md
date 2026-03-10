# 4. Message Bus

> *"A Message Bus is a shared communication backbone that all components connect to, removing the need for point-to-point wiring."*

## Intent

A Message Bus provides a common messaging infrastructure through which all services communicate. Producers publish to the bus; consumers subscribe to the event types they care about, with no direct coupling between producer and consumer.

## OTP Analogy

In Erlang/OTP, `gen_event` is the canonical message bus: a single event manager process receives events from any caller and fans them out to registered handlers.

```erlang
%% Register handlers
gen_event:add_handler(order_bus, payment_handler, []),
gen_event:add_handler(order_bus, inventory_handler, []),
gen_event:add_handler(order_bus, audit_handler, []),

%% Publish (fire-and-forget)
gen_event:notify(order_bus, {order_placed, #order{id = 42}}).
```

Every handler receives `{order_placed, …}` independently. A crash in one handler does not affect the others.

## JOTP Implementation

`EventManager<E>` is the JOTP bus. It manages a list of typed handlers; `notify()` delivers the event to all of them, while `syncNotify()` waits for all handlers to finish.

```
Producer ──► EventManager<OrderEvent> ──► PaymentHandler
                                      ├──► InventoryHandler
                                      └──► AuditHandler
```

Key design points:
- Handlers are fault-isolated: an exception in one handler is caught and reported without killing others.
- `addHandler` / `deleteHandler` are safe under concurrent modification.
- `call(handler, query)` provides synchronous request/reply to a specific handler.
- Use `PublishSubscribeChannel<T>` (which wraps `EventManager`) for a `MessageChannel`-compatible API.

## API Reference

| Method | Description |
|--------|-------------|
| `addHandler(handler, initArg)` | Register a new event handler |
| `deleteHandler(handler)` | Remove a handler |
| `notify(event)` | Async fan-out to all handlers |
| `syncNotify(event)` | Sync fan-out; blocks until all handlers finish |
| `call(handler, query)` | Synchronous call to one specific handler |

## Code Example

```java
import org.acme.EventManager;

// --- Domain events (sealed hierarchy) ---
sealed interface OrderEvent permits OrderPlaced, OrderCancelled, OrderShipped {}
record OrderPlaced(String orderId, double amount)          implements OrderEvent {}
record OrderCancelled(String orderId, String reason)       implements OrderEvent {}
record OrderShipped(String orderId, String trackingId)     implements OrderEvent {}

// --- Payment handler ---
class PaymentHandler implements EventManager.Handler<OrderEvent, Void> {
    @Override public Void init(Void ignored) { return null; }

    @Override
    public Void handleEvent(OrderEvent event, Void state) {
        return switch (event) {
            case OrderPlaced p    -> { chargeCard(p.orderId(), p.amount()); yield null; }
            case OrderCancelled c -> { refund(c.orderId()); yield null; }
            case OrderShipped s   -> null; // not relevant to payments
        };
    }
    private void chargeCard(String id, double amt) { /* stripe.charge(…) */ }
    private void refund(String id)                 { /* stripe.refund(…) */ }
}

// --- Audit handler accumulates a log ---
class AuditHandler implements EventManager.Handler<OrderEvent, List<String>> {
    @Override public List<String> init(Void ignored) { return new ArrayList<>(); }

    @Override
    public List<String> handleEvent(OrderEvent event, List<String> log) {
        log.add(Instant.now() + " " + event);
        return log;
    }
}

// --- Bus wiring ---
public class OrderBusDemo {
    public static void main(String[] args) {
        var bus = new EventManager<OrderEvent>();
        bus.addHandler(new PaymentHandler(), null);
        bus.addHandler(new AuditHandler(), null);

        // Publish — both handlers receive every event
        bus.notify(new OrderPlaced("ORD-001", 149.99));
        bus.notify(new OrderShipped("ORD-001", "TRACK-XYZ"));

        // Synchronous publish — caller blocks until all handlers finish
        bus.syncNotify(new OrderCancelled("ORD-002", "customer request"));
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;

class MessageBusTest implements WithAssertions {

    @Test
    void allHandlersReceiveEveryEvent() {
        var bus       = new EventManager<OrderEvent>();
        var received1 = new CopyOnWriteArrayList<OrderEvent>();
        var received2 = new CopyOnWriteArrayList<OrderEvent>();

        bus.addHandler(collectingHandler(received1), null);
        bus.addHandler(collectingHandler(received2), null);

        bus.syncNotify(new OrderPlaced("ORD-1", 10.0));
        bus.syncNotify(new OrderPlaced("ORD-2", 20.0));

        assertThat(received1).hasSize(2);
        assertThat(received2).hasSize(2).isEqualTo(received1);
    }

    @Test
    void crashedHandlerDoesNotAffectOthers() {
        var bus     = new EventManager<OrderEvent>();
        var healthy = new CopyOnWriteArrayList<OrderEvent>();

        bus.addHandler(explodingHandler(), null);              // always throws
        bus.addHandler(collectingHandler(healthy), null);

        assertThatNoException().isThrownBy(
            () -> bus.syncNotify(new OrderPlaced("ORD-3", 5.0))
        );
        assertThat(healthy).hasSize(1);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Many loosely-coupled consumers need the same event stream.
- You want to add/remove consumers at runtime without modifying producers.
- Audit, monitoring, and domain logic all share the same events.

**Avoid when:**
- You need guaranteed delivery or persistent subscriptions — `EventManager` is in-process, in-memory only; use a durable broker (Kafka, RabbitMQ) for cross-process requirements.
- Strict ordering across handlers is required — `notify()` does not guarantee cross-handler ordering.
- You have a single consumer — a `PointToPointChannel` is simpler and faster.
