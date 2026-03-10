# 01. Point-to-Point Channel

> *"A process with a mailbox and a single consumer thread — the purest form of message passing."*
> — Joe Armstrong

## Intent

Ensure exactly one consumer processes each message, in order. No message is delivered to more than one receiver.

## OTP Analogy

In Erlang, every process has a mailbox (a linked list of messages). Messages arrive in order and are processed one at a time by the process's receive loop. The `PointToPointChannel` is this pattern made explicit: the consumer is a function (not a process), and the mailbox is a `LinkedTransferQueue`.

```erlang
%% Erlang: a process that handles one message at a time
loop() ->
    receive
        {order, Order} -> process(Order), loop()
    end.
```

```java
// JOTP: equivalent point-to-point channel
var orders = new PointToPointChannel<Order>(order -> process(order));
orders.send(new Order("item-1", 3));
orders.send(new Order("item-2", 1));
orders.stop();
```

## JOTP Implementation

**Class**: `org.acme.reactive.PointToPointChannel<T>`
**Mechanism**: `Thread.ofVirtual()` + `LinkedTransferQueue<T>`

The channel spawns a single virtual thread on construction. That thread polls the queue every 50 ms. Producers call `send(T)` which `add()`s to the queue without blocking. The consumer thread drains the queue sequentially — exactly one message at a time.

```
Producer A ──┐
Producer B ──┼──► LinkedTransferQueue ──► consumer thread ──► handler
Producer C ──┘          (mailbox)
```

## API Reference

| Method | Description |
|---|---|
| `PointToPointChannel(Consumer<T> consumer)` | Create and start the channel |
| `void send(T message)` | Enqueue — never blocks the caller |
| `void stop()` | Drain queue then join consumer thread |
| `int queueDepth()` | Current backlog size |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;
import java.util.concurrent.CopyOnWriteArrayList;

// Create an order processing channel
var received = new CopyOnWriteArrayList<String>();
var channel = new PointToPointChannel<String>(received::add);

// Send messages from multiple threads — all processed in order
channel.send("order-1");
channel.send("order-2");
channel.send("order-3");

// Wait for processing, then stop
Thread.sleep(100);
channel.stop();
assert received.equals(List.of("order-1", "order-2", "order-3"));
```

## Composition

```java
// Chain with a filter: only positive quantities pass through
var validOrders = new PointToPointChannel<Order>(order -> fulfil(order));
var dead = new DeadLetterChannel<Order>();
var filter = MessageFilter.of(o -> o.qty() > 0, validOrders, dead);

filter.send(new Order("A", 3));   // → validOrders
filter.send(new Order("B", 0));   // → dead (qty == 0)
```

## Test Pattern

```java
@Test
void pointToPoint_deliversInOrder() throws InterruptedException {
    var received = new CopyOnWriteArrayList<String>();
    var ch = new PointToPointChannel<String>(received::add);

    ch.send("alpha");
    ch.send("beta");
    ch.send("gamma");

    await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 3);

    assertThat(received).containsExactly("alpha", "beta", "gamma");
    ch.stop();
}
```

## Caveats & Trade-offs

**Use when:**
- Message ordering is required
- Exactly-once processing semantics are needed
- One consumer is sufficient for the throughput required

**Do not use when:**
- Parallel processing is required → use `MessageDispatcher<T>`
- All subscribers must receive every message → use `PublishSubscribeChannel<T>`
- Consumer throughput is too low → consider `MessageDispatcher` with multiple workers

**Back-pressure:** The queue is unbounded. Under sustained high load, memory will grow. Monitor via `queueDepth()` and apply upstream rate limiting if needed.
