# 03. Dead Letter Channel

> *"A message that cannot be delivered is not lost — it is moved to a dead-letter channel where it can be inspected, retried, or alerted on."*

## Intent

Capture messages that cannot be delivered or processed. Prevent silent data loss by making undeliverable messages visible and inspectable.

## OTP Analogy

In Erlang, unhandled messages accumulate in the process mailbox. There is no built-in dead-letter mechanism — it is the programmer's responsibility to pattern-match a catch-all clause:

```erlang
receive
    {ok, Msg} -> handle(Msg);
    Unknown   -> dead_letter:capture(Unknown)  % catch-all
end
```

JOTP's `DeadLetterChannel` provides this as a first-class channel, compatible with the `MessageChannel<T>` interface. It is typically the `otherwise` target of a `MessageRouter` or the `rejected` channel of a `MessageFilter`.

## JOTP Implementation

**Class**: `org.acme.reactive.DeadLetterChannel<T>`
**Mechanism**: `CopyOnWriteArrayList<DeadLetter<T>>` — append-only, thread-safe

Each entry is a `DeadLetter<T>` record containing the original message, a reason string, and a timestamp.

## API Reference

| Method | Description |
|---|---|
| `void send(T message)` | Capture with reason `"undeliverable"` |
| `void send(T message, String reason)` | Capture with custom reason |
| `List<DeadLetter<T>> drain()` | Atomically remove and return all entries |
| `int size()` | Current dead-letter count |
| `void stop()` | No-op (stateless lifecycle) |

## Code Example

```java
var dead = new DeadLetterChannel<Order>();
var validOrders = new PointToPointChannel<Order>(order -> fulfil(order));

// Route invalid orders to dead letter
var filter = MessageFilter.of(
    order -> order.quantity() > 0,
    validOrders,
    dead);

filter.send(new Order("A", 3));   // → validOrders
filter.send(new Order("B", 0));   // → dead ("undeliverable")
filter.send(new Order("C", -1));  // → dead ("undeliverable")

// Inspect and retry
var letters = dead.drain();
for (var letter : letters) {
    System.out.printf("Dead: %s — reason: %s%n",
        letter.message(), letter.reason());
}

assert dead.size() == 0; // drained
validOrders.stop();
```

## Integration with MessageRouter

```java
var router = MessageRouter.<Event>builder()
    .route(e -> e instanceof OrderEvent, orderChannel)
    .route(e -> e instanceof PaymentEvent, paymentChannel)
    .otherwise(new DeadLetterChannel<>())  // unrecognized events
    .build();

router.send(new UnknownEvent("legacy"));   // → dead letter
```

## Test Pattern

```java
@Test
void deadLetter_capturesUndeliverableMessages() {
    var dead = new DeadLetterChannel<String>();

    dead.send("msg-1");
    dead.send("msg-2", "validation-failed");

    assertThat(dead.size()).isEqualTo(2);

    var letters = dead.drain();
    assertThat(letters).hasSize(2);
    assertThat(letters.get(0).reason()).isEqualTo("undeliverable");
    assertThat(letters.get(1).reason()).isEqualTo("validation-failed");
    assertThat(dead.size()).isZero();  // drained
}
```

## Caveats & Trade-offs

**In-memory only**: The `DeadLetterChannel` holds messages in memory. For durable dead-letter storage across JVM restarts, integrate with a `MessageStore` or an external queue (Kafka, RabbitMQ).

**Monitoring**: Monitor `dead.size()` with `ProcTimer` or a background thread to alert on growing dead-letter queues.

**Retry**: `drain()` returns the original messages — feed them back to the original channel for retry logic.
