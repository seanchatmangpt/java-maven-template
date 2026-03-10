# 18. Splitter

> *"A composite message is decomposed into its constituent parts, each part forwarded independently."*

## Intent

Break a composite message (e.g., a batch order) into individual parts and forward each part to a downstream channel for independent processing.

## OTP Analogy

In Erlang, a splitter is a function in a gen_server handler that pattern-matches a list message and sends each element individually:

```erlang
handle_cast({batch, Items}, State) ->
    lists:foreach(fun(Item) -> worker ! {item, Item} end, Items),
    {noreply, State}.
```

JOTP's `MessageSplitter<T,U>` wraps this as a composable `MessageChannel<T>`.

## JOTP Implementation

**Class**: `org.acme.reactive.MessageSplitter<T, U>`

```java
public final class MessageSplitter<T, U> implements MessageChannel<T> {
    private final Function<T, List<U>> splitter;
    private final MessageChannel<U> downstream;

    @Override
    public void send(T message) {
        splitter.apply(message).forEach(downstream::send);
    }
}
```

Stateless — `send()` applies the splitter function and fans out to downstream synchronously.

## API Reference

| Method | Description |
|---|---|
| `MessageSplitter.of(Function<T,List<U>> splitter, MessageChannel<U> downstream)` | Factory |
| `void send(T message)` | Split and forward each part |
| `void stop()` | Stop downstream channel |

## Code Example

```java
record OrderLine(String orderId, String sku, int qty) {}
record FullOrder(String orderId, List<OrderLine> lines) {}

var lineSink = new PointToPointChannel<OrderLine>(line -> processLine(line));
var splitter = MessageSplitter.<FullOrder, OrderLine>of(
    FullOrder::lines,
    lineSink);

splitter.send(new FullOrder("ord-1", List.of(
    new OrderLine("ord-1", "sku-A", 2),
    new OrderLine("ord-1", "sku-B", 1),
    new OrderLine("ord-1", "sku-C", 5)
)));
// 3 OrderLine messages delivered to lineSink

lineSink.stop();
```

## Composition: Splitter + Aggregator

A common pattern is to split, process independently, then re-aggregate:

```java
// Split → process each line → aggregate back into fulfillment order
var fulfillmentSink = new PointToPointChannel<FulfilledOrder>(order -> ship(order));
var aggregator = MessageAggregator.<FulfilledLine, FulfilledOrder>builder()
    .correlateBy(FulfilledLine::orderId)
    .completeWhen(lines -> lines.size() == 3)
    .aggregateWith(lines -> new FulfilledOrder(lines.getFirst().orderId(), lines))
    .downstream(fulfillmentSink)
    .build();

var processor = new PointToPointChannel<OrderLine>(line ->
    aggregator.send(fulfil(line)));

var splitter = MessageSplitter.<FullOrder, OrderLine>of(
    FullOrder::lines, processor);
```

## Test Pattern

```java
@Test
void splitter_decomposesBatchIntoIndividualParts() throws InterruptedException {
    var parts = new CopyOnWriteArrayList<OrderLine>();
    var downstream = new PointToPointChannel<OrderLine>(parts::add);
    var splitter = MessageSplitter.<FullOrder, OrderLine>of(
        FullOrder::lines, downstream);

    splitter.send(new FullOrder("batch-1", List.of(
        new OrderLine("batch-1", "sku-a", 1),
        new OrderLine("batch-1", "sku-b", 2),
        new OrderLine("batch-1", "sku-c", 3))));

    await().atMost(Duration.ofSeconds(2)).until(() -> parts.size() == 3);

    assertThat(parts).hasSize(3);
    assertThat(parts.stream().map(OrderLine::sku))
        .containsExactlyInAnyOrder("sku-a", "sku-b", "sku-c");

    downstream.stop();
}
```

## Caveats

**Memory**: For large batches, `splitter.apply(message)` materializes the full list before forwarding. For huge batches, consider streaming (send directly in the splitter function without intermediate list).

**Ordering**: Parts are forwarded synchronously in list order, so if the downstream channel is a `PointToPointChannel`, ordering is preserved.
