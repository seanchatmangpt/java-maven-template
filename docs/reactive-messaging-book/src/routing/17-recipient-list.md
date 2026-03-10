# 17. Recipient List

> *"A Recipient List routes each incoming message to a dynamically determined list of recipients, rather than broadcasting to all or routing to one."*

## Intent

A Recipient List inspects each incoming message and forwards it to the specific subset of channels or subscribers that should receive it. Unlike a static pub-sub broadcast, the recipient list can vary per message. In JOTP, when the list is the full subscriber set, `PublishSubscribeChannel<T>` is the direct implementation.

## OTP Analogy

In Erlang, you implement a recipient list by keeping a list of Pids and sending to each:

```erlang
%% Dynamic recipient list: notify only relevant subscribers
notify_subscribers(Event, State) ->
    Relevant = [Pid || {Pid, Filter} <- State#state.subscribers,
                       Filter(Event)],
    lists:foreach(fun(Pid) -> Pid ! Event end, Relevant).
```

`gen_event` does this internally for all registered handlers. `PublishSubscribeChannel<T>` is the JOTP wrapper.

## JOTP Implementation

`PublishSubscribeChannel<T>` wraps `EventManager<T>` and implements `MessageChannel<T>`. Every call to `send(msg)` fans the message out to all currently registered subscribers via `EventManager.notify()`.

For a filtered recipient list (only some subscribers per message), combine `PublishSubscribeChannel<T>` with per-handler predicates, or use `DynamicRouter<T>` for fully dynamic routing.

```
PublishSubscribeChannel<OrderEvent>
          │
          ├── send(OrderPlaced) ──► PaymentHandler
          │                    ├──► InventoryHandler
          │                    └──► AuditHandler
          │
     addSubscriber(newHandler)   ← runtime registration
     removeSubscriber(handler)   ← runtime removal
```

Key design points:
- Subscribers are registered at runtime — the producer never knows the recipient list.
- `EventManager<T>` fault-isolates handlers: one crash does not affect others.
- A subscriber receives every message unless it filters internally or is removed.
- Use `DynamicRouter<T>` (`CopyOnWriteArrayList` of `(predicate, channel)` pairs) for per-message recipient selection.

## API Reference

| Method | Description |
|--------|-------------|
| `new PublishSubscribeChannel<>(eventManager)` | Create channel over a shared bus |
| `channel.send(msg)` | Broadcast to all current subscribers |
| `eventManager.addHandler(handler, init)` | Add a subscriber at runtime |
| `eventManager.deleteHandler(handler)` | Remove a subscriber at runtime |
| `channel.stop()` | Shut down the channel and bus |

## Code Example

```java
import org.acme.EventManager;
import org.acme.reactive.PublishSubscribeChannel;

// --- Event type ---
record PriceUpdated(String sku, double newPrice, Instant updatedAt) {}

// --- Subscriber 1: update UI cache ---
class UiCacheUpdater implements EventManager.Handler<PriceUpdated, Map<String, Double>> {
    @Override public Map<String, Double> init(Void v) { return new HashMap<>(); }

    @Override
    public Map<String, Double> handleEvent(PriceUpdated event, Map<String, Double> cache) {
        cache.put(event.sku(), event.newPrice());
        System.out.printf("[CACHE] %s → %.2f%n", event.sku(), event.newPrice());
        return cache;
    }
}

// --- Subscriber 2: write to analytics ---
class AnalyticsWriter implements EventManager.Handler<PriceUpdated, List<PriceUpdated>> {
    @Override public List<PriceUpdated> init(Void v) { return new ArrayList<>(); }

    @Override
    public List<PriceUpdated> handleEvent(PriceUpdated event, List<PriceUpdated> log) {
        log.add(event);
        System.out.printf("[ANALYTICS] logged price change for %s%n", event.sku());
        return log;
    }
}

public class RecipientListDemo {

    public static void main(String[] args) {
        var bus     = new EventManager<PriceUpdated>();
        var channel = new PublishSubscribeChannel<>(bus);

        // Register initial subscribers
        var cache     = new UiCacheUpdater();
        var analytics = new AnalyticsWriter();
        bus.addHandler(cache, null);
        bus.addHandler(analytics, null);

        // Publish — both subscribers receive it
        channel.send(new PriceUpdated("SKU-A", 34.99, Instant.now()));
        channel.send(new PriceUpdated("SKU-B", 19.99, Instant.now()));

        // Dynamic: add a third subscriber at runtime
        var auditor = new EventManager.Handler<PriceUpdated, Void>() {
            @Override public Void init(Void v) { return null; }
            @Override public Void handleEvent(PriceUpdated e, Void s) {
                System.out.printf("[AUDIT] price of %s changed to %.2f%n",
                    e.sku(), e.newPrice());
                return null;
            }
        };
        bus.addHandler(auditor, null);

        // Third subscriber receives only future events
        channel.send(new PriceUpdated("SKU-C", 9.99, Instant.now()));

        // Remove a subscriber
        bus.deleteHandler(cache);
        channel.send(new PriceUpdated("SKU-A", 32.00, Instant.now())); // cache NOT notified

        channel.stop();
    }
}
```

## Filtered Recipient List (DynamicRouter)

```java
import org.acme.reactive.DynamicRouter;

// DynamicRouter routes each message to channels whose predicate matches
DynamicRouter<PriceUpdated> router = new DynamicRouter<>();

// Add routes: (predicate, destination channel)
router.addRoute(e -> e.sku().startsWith("SKU-A"), channelA);
router.addRoute(e -> e.newPrice() > 50.0,         premiumChannel);
router.addRoute(e -> true,                         auditChannel);  // catch-all

// Send: routed to matching channels only
router.send(new PriceUpdated("SKU-A", 34.99, Instant.now()));
// → goes to channelA (matches) and auditChannel (catch-all)
// → does NOT go to premiumChannel (34.99 < 50.0)
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;

class RecipientListTest implements WithAssertions {

    @Test
    void allSubscribersReceivePublishedMessage() {
        var bus  = new EventManager<PriceUpdated>();
        var log1 = new CopyOnWriteArrayList<PriceUpdated>();
        var log2 = new CopyOnWriteArrayList<PriceUpdated>();

        bus.addHandler(collectingHandler(log1), null);
        bus.addHandler(collectingHandler(log2), null);

        var ch    = new PublishSubscribeChannel<>(bus);
        var event = new PriceUpdated("SKU-T", 5.0, Instant.now());
        bus.syncNotify(event);  // syncNotify for deterministic test

        assertThat(log1).containsExactly(event);
        assertThat(log2).containsExactly(event);
    }

    @Test
    void removedSubscriberNoLongerReceivesMessages() {
        var bus      = new EventManager<PriceUpdated>();
        var received = new CopyOnWriteArrayList<PriceUpdated>();
        var handler  = collectingHandler(received);

        bus.addHandler(handler, null);
        bus.syncNotify(new PriceUpdated("SKU-X", 1.0, Instant.now()));
        assertThat(received).hasSize(1);

        bus.deleteHandler(handler);
        bus.syncNotify(new PriceUpdated("SKU-Y", 2.0, Instant.now()));
        assertThat(received).hasSize(1); // still 1
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Multiple independent consumers need the same message stream.
- The recipient list changes at runtime (subscribers join/leave).
- You want zero coupling between the publisher and any specific subscriber.

**Avoid when:**
- You need guaranteed ordered delivery to each subscriber — `EventManager.notify()` does not serialize across handlers.
- Subscribers are remote processes — use a durable pub-sub broker (Kafka, NATS) for cross-JVM fan-out.
- The recipient list per message is a small, static subset — a `MessageRouter` with explicit routing rules is more precise.
