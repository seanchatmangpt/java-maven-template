# 30. Event-Driven Consumer

> *"An Event-Driven Consumer sleeps until a message arrives — the channel pushes the message to the consumer rather than the consumer polling for it."*

## Intent

An Event-Driven Consumer is activated by message arrival, not by a timer or polling loop. The consumer registers a handler with a channel; the channel's infrastructure delivers messages as they arrive, using the consumer's own virtual thread. This eliminates busy-waiting and reduces CPU usage to near zero when the channel is idle.

## OTP Analogy

Every Erlang process is inherently event-driven — the BEAM scheduler parks a process until a message arrives in its mailbox:

```erlang
%% Process blocks here until a message matches
loop(State) ->
    receive
        {process, Item} ->
            NewState = handle(Item, State),
            loop(NewState);
        stop ->
            ok
    end.
```

`PointToPointChannel<T>` in JOTP provides identical semantics: the consumer function is called exactly when a message arrives, on a dedicated virtual thread.

## JOTP Implementation

`PointToPointChannel<T>` maintains a `LinkedBlockingQueue<T>` and a single virtual thread that blocks on `queue.take()`. When a message is enqueued via `send(msg)`, the virtual thread wakes, invokes the `Consumer<T>` handler, then blocks again.

```
Producer ──send()──► [LinkedBlockingQueue] ──take()──► VirtualThread
                                                           │
                                                      Consumer<T>.accept(msg)
                                                           │
                                                       (blocks on take() again)
```

**Push vs. Poll:**

| | Event-Driven Consumer | Polling Consumer |
|-|----------------------|------------------|
| Trigger | Message arrival (push) | Timer / `Thread.sleep` (pull) |
| CPU when idle | Near zero | Wasted cycles |
| Latency | Immediate | Up to poll interval |
| Backpressure | Queue depth | Explicit rate limiting |

Key design points:
- One virtual thread per `PointToPointChannel` — no platform thread consumed while idle.
- The consumer function must not block indefinitely — blocking the consumer blocks the channel.
- For concurrent processing, use `MessageDispatcher<T>` (Pattern 32) which pools multiple consumer threads.
- `channel.stop()` drains the queue and shuts down the virtual thread cleanly.

## API Reference

| Method | Description |
|--------|-------------|
| `new PointToPointChannel<>(consumer)` | Create push-driven channel |
| `channel.send(msg)` | Enqueue; returns immediately (non-blocking) |
| `channel.stop()` | Drain remaining messages and shut down |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.MessageChannel;

// --- Domain type ---
record SensorReading(String sensorId, double value, Instant recordedAt) {}

// --- Event-driven consumer ---
class AlertService {
    private static final double THRESHOLD = 95.0;

    void onReading(SensorReading reading) {
        if (reading.value() > THRESHOLD) {
            System.out.printf("[ALERT] Sensor %s exceeded threshold: %.1f at %s%n",
                reading.sensorId(), reading.value(), reading.recordedAt());
        } else {
            System.out.printf("[OK] Sensor %s: %.1f%n",
                reading.sensorId(), reading.value());
        }
    }
}

public class EventDrivenConsumerDemo {

    public static void main(String[] args) throws InterruptedException {
        var alertService = new AlertService();

        // Consumer is activated by each arriving message, not by polling
        MessageChannel<SensorReading> channel =
            new PointToPointChannel<>(alertService::onReading);

        // Producer sends readings whenever they arrive
        // Channel virtual thread wakes immediately on each send
        channel.send(new SensorReading("TEMP-01", 72.3, Instant.now()));
        channel.send(new SensorReading("TEMP-02", 98.7, Instant.now()));  // alert!
        channel.send(new SensorReading("TEMP-01", 81.5, Instant.now()));
        channel.send(new SensorReading("PRESS-01", 103.2, Instant.now())); // alert!

        // Give the consumer thread time to process all messages
        Thread.sleep(100);
        channel.stop();
    }
}
```

## Contrast with Polling Consumer

```java
// POLLING CONSUMER — avoid this pattern
var queue = new LinkedBlockingQueue<SensorReading>();
Thread.ofVirtual().start(() -> {
    while (!Thread.currentThread().isInterrupted()) {
        try {
            // Polling: wakes every 100ms regardless of messages
            SensorReading reading = queue.poll(100, TimeUnit.MILLISECONDS);
            if (reading != null) alertService.onReading(reading);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
});

// EVENT-DRIVEN CONSUMER — prefer this
var channel = new PointToPointChannel<>(alertService::onReading);
// No sleep/poll loop: consumer wakes only when a message arrives
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class EventDrivenConsumerTest implements WithAssertions {

    @Test
    void consumerReceivesAllMessages() throws InterruptedException {
        var received = new CopyOnWriteArrayList<SensorReading>();
        var latch    = new CountDownLatch(3);

        MessageChannel<SensorReading> channel = new PointToPointChannel<>(msg -> {
            received.add(msg);
            latch.countDown();
        });

        channel.send(new SensorReading("S1", 10.0, Instant.now()));
        channel.send(new SensorReading("S2", 20.0, Instant.now()));
        channel.send(new SensorReading("S3", 30.0, Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(3);
        channel.stop();
    }

    @Test
    void messagesDeliveredInOrder() throws InterruptedException {
        var received = new CopyOnWriteArrayList<Double>();
        var latch    = new CountDownLatch(5);

        MessageChannel<SensorReading> channel = new PointToPointChannel<>(msg -> {
            received.add(msg.value());
            latch.countDown();
        });

        for (double v : new double[]{1.0, 2.0, 3.0, 4.0, 5.0})
            channel.send(new SensorReading("S1", v, Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0);
        channel.stop();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Message arrival times are unpredictable and polling would waste CPU.
- Low latency between message arrival and processing is required.
- You have one logical consumer per channel (single consumer semantics).

**Avoid when:**
- Multiple concurrent consumers are needed — use `MessageDispatcher<T>` (Pattern 32) for a worker pool.
- The consumer performs long-running or blocking operations — this stalls the single channel thread; offload to a separate `Proc` or thread pool.
- The producer is significantly faster than the consumer — the queue grows unboundedly; add backpressure or a rate limiter.
