# 16. Message Filter

> *"A Message Filter passes messages that satisfy a predicate and discards (or dead-letters) those that do not."*

## Intent

A Message Filter sits between a producer and a consumer, selectively forwarding only the messages that match a given criterion. Messages that do not match are either silently dropped or routed to a Dead Letter Channel for later inspection.

## OTP Analogy

Erlang processes implement selective receive natively — the `receive` block pattern-matches and only picks up messages that match, leaving others in the mailbox:

```erlang
%% Selective receive: only process high-priority orders
receive
    {order, #{priority := high} = Order} ->
        process_urgent(Order)
    %% low-priority orders remain in the mailbox
after 5000 -> timeout
end.
```

In JOTP, `MessageFilter<T>` externalizes this predicate so filters are composable and testable independently.

## JOTP Implementation

`MessageFilter<T>` wraps two channels: the **accepted** channel (messages passing the predicate) and the **dead-letter** channel (messages that fail). Every incoming message is routed to exactly one of them.

```
source channel
     │
MessageFilter<Order>
     │  predicate: order.total() > 100
     ├──► accepted channel  (high-value orders)
     └──► dead-letter channel (low-value orders)
```

Key design points:
- The predicate is a pure `Predicate<T>` — stateless and thread-safe.
- Rejected messages go to `DeadLetterChannel<T>` by default; inspect it for debugging or replay.
- Filters compose: chain multiple `MessageFilter` instances for multi-stage filtering.
- `MessageFilter<T>` implements `MessageChannel<T>` so it can be substituted transparently.

## API Reference

| Constructor / Method | Description |
|----------------------|-------------|
| `new MessageFilter<>(predicate, accepted, deadLetter)` | Create a filter |
| `filter.send(msg)` | Route `msg` to accepted or dead-letter |
| `filter.stop()` | Stop both downstream channels |
| `deadLetter.drain()` | Retrieve all dead-lettered messages |

## Code Example

```java
import org.acme.reactive.MessageFilter;
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.DeadLetterChannel;

// --- Domain type ---
record Order(String id, double total, String region) {}

public class MessageFilterDemo {

    public static void main(String[] args) throws InterruptedException {
        var processed  = new CopyOnWriteArrayList<Order>();
        var deadLetter = new DeadLetterChannel<Order>();

        // Accepted channel: processes high-value orders
        var accepted = new PointToPointChannel<Order>(order -> {
            System.out.printf("[ACCEPT] %s total=%.2f%n", order.id(), order.total());
            processed.add(order);
        });

        // Filter: only let through orders > 100.00
        MessageChannel<Order> filter = new MessageFilter<>(
            order -> order.total() > 100.0,
            accepted,
            deadLetter
        );

        // Send a mix of orders
        filter.send(new Order("ORD-001", 250.00, "EU"));  // accepted
        filter.send(new Order("ORD-002",  45.00, "US"));  // dead-lettered
        filter.send(new Order("ORD-003", 175.50, "APAC")); // accepted
        filter.send(new Order("ORD-004",   9.99, "EU"));  // dead-lettered

        filter.stop();

        System.out.println("Processed: " + processed.size());           // 2
        System.out.println("Dead-lettered: " + deadLetter.size());      // 2
    }
}
```

## Filter Chaining

```java
// Chain: (1) region filter → (2) value filter → accepted channel
var valueFilter = new MessageFilter<>(
    order -> order.total() > 100.0,
    accepted,
    deadLetter
);

var regionFilter = new MessageFilter<>(
    order -> Set.of("EU", "APAC").contains(order.region()),
    valueFilter,    // ← accepted by region filter goes into value filter
    deadLetter
);

// Orders must pass BOTH predicates to reach accepted channel
regionFilter.send(new Order("ORD-A", 200.0, "EU"));    // passes both
regionFilter.send(new Order("ORD-B", 200.0, "US"));    // fails region
regionFilter.send(new Order("ORD-C",  50.0, "EU"));    // fails value
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;

class MessageFilterTest implements WithAssertions {

    @Test
    void predicateMatchingMessagesReachAcceptedChannel() throws InterruptedException {
        var accepted   = new CopyOnWriteArrayList<Order>();
        var deadLetter = new DeadLetterChannel<Order>();
        var latch      = new CountDownLatch(2);

        MessageChannel<Order> filter = new MessageFilter<>(
            o -> o.total() > 100.0,
            new PointToPointChannel<>(o -> { accepted.add(o); latch.countDown(); }),
            deadLetter
        );

        filter.send(new Order("A", 200.0, "EU"));
        filter.send(new Order("B",  50.0, "EU"));
        filter.send(new Order("C", 150.0, "US"));
        filter.send(new Order("D",  10.0, "US"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(accepted).extracting(Order::id).containsExactly("A", "C");
        assertThat(deadLetter.size()).isEqualTo(2);
        filter.stop();
    }

    @Test
    void allRejectedWithAlwaysFalsePredicate() throws InterruptedException {
        var deadLetter = new DeadLetterChannel<Order>();
        MessageChannel<Order> filter = new MessageFilter<>(
            o -> false,
            new PointToPointChannel<>(o -> {}),
            deadLetter
        );

        filter.send(new Order("X", 999.0, "EU"));
        filter.stop();

        Thread.sleep(50);
        assertThat(deadLetter.size()).isEqualTo(1);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Consumers should only process a subset of messages from a shared channel.
- You want to separate noise from signal without modifying the producer.
- Rejected messages need to be captured for audit, replay, or alerting.

**Avoid when:**
- The filter predicate is stateful or expensive — move heavy logic into a dedicated `Proc` that handles the filtering as part of its state machine.
- You need to route to more than two destinations — use a `MessageRouter` (content-based routing, Pattern 15) instead.
- Dropping messages silently is acceptable — use `DeadLetterChannel` so nothing is lost.
