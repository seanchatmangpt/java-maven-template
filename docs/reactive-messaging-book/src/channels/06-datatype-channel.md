# 6. Datatype Channel

> *"A Datatype Channel carries messages of a single specific type, so receivers always know what they're getting."*

## Intent

A Datatype Channel restricts all messages on a given channel to a single type. This eliminates runtime type errors, enables exhaustive pattern matching at the consumer, and makes channel contracts explicit and self-documenting.

## OTP Analogy

Erlang achieves this informally through tagged tuples and per-process protocol conventions. In practice, a dedicated `gen_server` accepting only one message shape acts as a typed channel:

```erlang
%% Producer sends only payment_event tuples on this channel
gen_server:cast(PaymentChannel, {payment_event, OrderId, Amount}).

%% Consumer knows it only ever receives payment_event tuples
handle_cast({payment_event, OrderId, Amount}, State) -> …
```

Java's type system makes the contract compile-time rather than convention-based.

## JOTP Implementation

`MessageChannel<T>` is a generic interface; the type parameter `T` is the single datatype the channel carries. Using sealed interfaces as `T` gives you a closed, exhaustive set of sub-types — the channel is typed and the message hierarchy is closed.

```
PaymentChannel : MessageChannel<PaymentEvent>
                      │
         ┌────────────┼────────────┐
  PaymentInitiated  PaymentFailed  PaymentSettled
```

Key design points:
- The compiler rejects `channel.send(wrongType)` at the call site.
- Sealed `T` + `switch` expression gives exhaustiveness checking in handlers.
- `PointToPointChannel<PaymentEvent>` is the concrete single-consumer implementation.
- `PublishSubscribeChannel<PaymentEvent>` fans out to multiple subscribers, all receiving the same `T`.

## API Reference

| Method | Description |
|--------|-------------|
| `channel.send(T msg)` | Enqueue a typed message |
| `channel.stop()` | Signal end-of-stream and shut down the channel |
| `new PointToPointChannel<>(Consumer<T>)` | Single-consumer typed channel |
| `new PublishSubscribeChannel<>(EventManager<T>)` | Multi-subscriber typed channel |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.MessageChannel;

// --- Sealed message type: the "datatype" ---
sealed interface PaymentEvent
    permits PaymentInitiated, PaymentFailed, PaymentSettled {}

record PaymentInitiated(String paymentId, double amount, String currency)
    implements PaymentEvent {}
record PaymentFailed(String paymentId, String errorCode)
    implements PaymentEvent {}
record PaymentSettled(String paymentId, Instant settledAt)
    implements PaymentEvent {}

// --- Typed channel ---
public class DatatypeChannelDemo {

    static void processPayment(PaymentEvent event) {
        // Exhaustive switch — compiler enforces all cases
        switch (event) {
            case PaymentInitiated p ->
                System.out.printf("Initiating %s for %.2f %s%n",
                    p.paymentId(), p.amount(), p.currency());
            case PaymentFailed f ->
                System.out.printf("Payment %s failed: %s%n",
                    f.paymentId(), f.errorCode());
            case PaymentSettled s ->
                System.out.printf("Payment %s settled at %s%n",
                    s.paymentId(), s.settledAt());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // Channel only accepts PaymentEvent — wrong type is a compile error
        MessageChannel<PaymentEvent> channel =
            new PointToPointChannel<>(DatatypeChannelDemo::processPayment);

        channel.send(new PaymentInitiated("PAY-001", 99.99, "USD"));
        channel.send(new PaymentFailed("PAY-002", "INSUFFICIENT_FUNDS"));
        channel.send(new PaymentSettled("PAY-001", Instant.now()));

        // channel.send(new OrderPlaced(…)); // COMPILE ERROR — wrong type

        channel.stop();
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class DatatypeChannelTest implements WithAssertions {

    @Test
    void channelDeliversOnlyDeclaredType() throws InterruptedException {
        var received = new CopyOnWriteArrayList<PaymentEvent>();
        var latch    = new CountDownLatch(3);

        MessageChannel<PaymentEvent> ch = new PointToPointChannel<>(msg -> {
            received.add(msg);
            latch.countDown();
        });

        ch.send(new PaymentInitiated("P1", 10.0, "EUR"));
        ch.send(new PaymentFailed("P2", "ERR"));
        ch.send(new PaymentSettled("P1", Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(3);
        assertThat(received.get(0)).isInstanceOf(PaymentInitiated.class);
        assertThat(received.get(1)).isInstanceOf(PaymentFailed.class);
        assertThat(received.get(2)).isInstanceOf(PaymentSettled.class);

        ch.stop();
    }

    @Test
    void exhaustiveSwitchCoversAllSubtypes() {
        // If a new PaymentEvent subtype is added and the switch below is not
        // updated, the compiler will fail — that is the whole point.
        PaymentEvent event = new PaymentSettled("P3", Instant.now());
        String result = switch (event) {
            case PaymentInitiated p -> "initiated";
            case PaymentFailed f   -> "failed";
            case PaymentSettled s  -> "settled";
        };
        assertThat(result).isEqualTo("settled");
    }
}
```

## Caveats & Trade-offs

**Use when:**
- The channel carries a well-defined, closed set of message types.
- You want compile-time guarantees instead of runtime `instanceof` checks.
- Multiple consumers exist that all need the same type contract.

**Avoid when:**
- The message taxonomy is open-ended or evolves rapidly — sealed interfaces require recompilation of all consumers when a new permit is added.
- You're bridging heterogeneous external systems where a single type hierarchy would be artificially forced.
