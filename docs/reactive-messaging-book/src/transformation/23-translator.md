# 23. Message Translator

> *"Translate messages between two systems with incompatible schemas — the adapter pattern for message flows."*

## Intent

Convert messages from one format or type to another as they pass through the pipeline. Decouple producers and consumers who speak different schemas.

## OTP Analogy

A pure function in a gen_server handler that pattern-matches incoming messages and returns transformed state. No side effects, no shared state — only type-safe transformation.

```erlang
handle_cast({legacy_order, Id, Amount}, State) ->
    ModernOrder = #{id => Id, total => Amount, currency => <<"USD">>},
    downstream ! {modern_order, ModernOrder},
    {noreply, State}.
```

## JOTP Implementation

**Class**: `org.acme.reactive.MessageTransformer<T, U>`

```java
// Stateless — send() applies the transformation synchronously
@Override
public void send(T message) {
    try {
        U transformed = transform.apply(message);
        downstream.send(transformed);
    } catch (Exception e) {
        if (errorChannel != null) errorChannel.send(message);
    }
}
```

## API Reference

| Factory | Description |
|---|---|
| `MessageTransformer.of(Function<T,U> fn, MessageChannel<U> downstream)` | Transform, drop errors |
| `MessageTransformer.of(Function<T,U> fn, MessageChannel<U> downstream, MessageChannel<T> errors)` | Transform with error routing |

## Code Example

```java
record Legacy(String id, double amt) {}
record Modern(String orderId, double amount, String currency) {}

var outputs = new CopyOnWriteArrayList<Modern>();
var downstream = new PointToPointChannel<Modern>(outputs::add);

var transformer = MessageTransformer.<Legacy, Modern>of(
    legacy -> new Modern(legacy.id(), legacy.amt(), "USD"),
    downstream);

transformer.send(new Legacy("O1", 100.0));
transformer.send(new Legacy("O2", 250.0));

await().atMost(Duration.ofSeconds(2)).until(() -> outputs.size() == 2);

assertThat(outputs.get(0).orderId()).isEqualTo("O1");
assertThat(outputs.get(0).currency()).isEqualTo("USD");
downstream.stop();
```

## Error Routing

```java
var errors = new DeadLetterChannel<String>();
var downstream = new PointToPointChannel<Integer>(results::add);
var transformer = MessageTransformer.<String, Integer>of(
    Integer::parseInt,
    downstream,
    errors);

transformer.send("42");          // → 42
transformer.send("not-a-number");// → errors (NumberFormatException)
```

## Composition in a Pipeline

```java
var pipeline = MessagePipeline.<String>source()
    .transform(Integer::parseInt)      // String → Integer
    .filter(n -> n > 0)               // drop negatives
    .transform(n -> n * 2)            // Integer → Integer (double)
    .sink(sinkChannel);
```

## Test Pattern

```java
@Test
void transformer_convertsTypeAndForwardsDownstream() throws InterruptedException {
    var outputs = new CopyOnWriteArrayList<Integer>();
    var downstream = new PointToPointChannel<Integer>(outputs::add);
    var transformer = MessageTransformer.<String, Integer>of(Integer::parseInt, downstream);

    transformer.send("10");
    transformer.send("20");

    await().atMost(Duration.ofSeconds(2)).until(() -> outputs.size() == 2);

    assertThat(outputs).containsExactlyInAnyOrder(10, 20);
    downstream.stop();
}
```
