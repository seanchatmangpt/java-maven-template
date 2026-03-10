# 19. Aggregator

> *"Correlate related messages, accumulate them into a group, emit the aggregated result when the group is complete."*

## Intent

Collect a set of related messages (correlated by a key) and emit a combined result when a completion condition is satisfied.

## OTP Analogy

A stateful `gen_server` maintaining a `Map` of in-progress correlation groups. Each `handle_cast` appends to the appropriate group; when the group is complete, the server emits the result and removes the group.

```erlang
handle_cast({line, OrderId, Sku, Qty}, State) ->
    Group = maps:get(OrderId, State, []),
    NewGroup = [{Sku, Qty} | Group],
    case length(NewGroup) >= 3 of
        true  -> emit({order, OrderId, NewGroup}), {noreply, maps:remove(OrderId, State)};
        false -> {noreply, maps:put(OrderId, NewGroup, State)}
    end.
```

## JOTP Implementation

**Class**: `org.acme.reactive.MessageAggregator<T, R>`
**Mechanism**: `ConcurrentHashMap<String, List<T>>` with per-key synchronization

Three functions define aggregation behavior:
1. `correlateBy(Function<T,String>)` — extract the correlation key
2. `completeWhen(Predicate<List<T>>)` — when is the group done?
3. `aggregateWith(Function<List<T>,R>)` — how to combine the group

## API Reference

The aggregator uses a fluent builder:

```java
MessageAggregator.<T, R>builder()
    .correlateBy(message -> key)       // mandatory
    .completeWhen(group -> condition)  // mandatory
    .aggregateWith(group -> result)    // mandatory
    .downstream(channel)               // mandatory
    .build()
```

## Code Example

```java
record OrderLine(String orderId, String sku, int qty) {}
record FullOrder(String orderId, List<OrderLine> lines) {}

var results = new CopyOnWriteArrayList<FullOrder>();
var downstream = new PointToPointChannel<FullOrder>(results::add);

var aggregator = MessageAggregator.<OrderLine, FullOrder>builder()
    .correlateBy(OrderLine::orderId)
    .completeWhen(lines -> lines.size() == 3)
    .aggregateWith(lines -> new FullOrder(lines.getFirst().orderId(), List.copyOf(lines)))
    .downstream(downstream)
    .build();

aggregator.send(new OrderLine("ord-1", "sku-a", 2));
aggregator.send(new OrderLine("ord-1", "sku-b", 1));
aggregator.send(new OrderLine("ord-1", "sku-c", 5)); // triggers emission

await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 1);
assert results.getFirst().orderId().equals("ord-1");
assert results.getFirst().lines().size() == 3;

downstream.stop();
```

## Multiple Correlation Keys

```java
// Two orders being aggregated concurrently:
aggregator.send(new OrderLine("ord-A", "sku-1", 1));
aggregator.send(new OrderLine("ord-B", "sku-1", 1));
aggregator.send(new OrderLine("ord-A", "sku-2", 1)); // ord-A now complete
aggregator.send(new OrderLine("ord-B", "sku-2", 1)); // ord-B now complete
// Both FullOrder results emitted independently
```

## Test Pattern

```java
@Test
void aggregator_emitsWhenGroupIsComplete() throws InterruptedException {
    var results = new CopyOnWriteArrayList<FullOrder>();
    var downstream = new PointToPointChannel<FullOrder>(results::add);

    var aggregator = MessageAggregator.<OrderLine, FullOrder>builder()
        .correlateBy(OrderLine::orderId)
        .completeWhen(lines -> lines.size() == 3)
        .aggregateWith(lines -> new FullOrder(lines.getFirst().orderId(), List.copyOf(lines)))
        .downstream(downstream)
        .build();

    aggregator.send(new OrderLine("ord-1", "sku-a", 2));
    aggregator.send(new OrderLine("ord-1", "sku-b", 1));
    aggregator.send(new OrderLine("ord-1", "sku-c", 5));

    await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 1);
    assertThat(results.getFirst().lines()).hasSize(3);
    downstream.stop();
}
```

## Caveats

**Incomplete groups**: If a group never completes (missing messages), entries accumulate in the `ConcurrentHashMap`. Add a timeout mechanism using `ProcTimer` to expire stale groups.

**Per-key synchronization**: The aggregator synchronizes on `groups` (the map) for each incoming message to prevent race conditions on the same correlation key. Under very high throughput with many keys, consider sharding the aggregator.
