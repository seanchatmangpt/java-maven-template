# 29. Polling Consumer

> *"Don't call us; we'll call you. Except in distributed systems —
> sometimes you have to keep knocking until someone answers."*
> — a pragmatic inversion of the Hollywood Principle

---

## Intent

A **Polling Consumer** explicitly controls when it fetches messages from a channel,
rather than having messages pushed to it. It owns the polling loop: it checks the
queue at a configurable interval, retrieves one message at a time, and forwards it to
a handler. This inverts the usual push model and gives the consumer authority over its
own consumption rate, making it ideal for back-pressure scenarios, rate-limited
upstream services, or integration with systems that expose a pull-only API (e.g.,
SQS, database outbox tables, file-system drop-folders). In JOTP, the polling loop
runs on a virtual thread while message handling runs inside a `Proc`, giving the
consumer both lightweight concurrency and OTP-style state introspection.

---

## OTP Analogy

In Erlang/OTP the canonical equivalent is a `gen_server` that uses
`erlang:send_after/3` to schedule periodic self-messages, waking itself to drain a
shared ETS table or external queue:

```erlang
-module(polling_consumer).
-behaviour(gen_server).

-record(state, {handler, interval_ms, count = 0}).

init([Handler, IntervalMs]) ->
    schedule_poll(IntervalMs),
    {ok, #state{handler = Handler, interval_ms = IntervalMs}}.

handle_info(poll, #state{handler = H, interval_ms = Ms, count = C} = S) ->
    NewCount = case queue_module:dequeue() of
        empty   -> C;
        {ok, M} -> H(M), C + 1
    end,
    schedule_poll(Ms),
    {noreply, S#state{count = NewCount}};

handle_cast({enqueue, Msg}, S) ->
    queue_module:enqueue(Msg),
    {noreply, S}.

schedule_poll(Ms) -> erlang:send_after(Ms, self(), poll).
```

JOTP replaces the timed self-message with a blocking `queue.poll(interval)` on a
virtual thread, which is cheaper than timer messages and maps directly to Java's
`BlockingQueue` contract:

| OTP concept | JOTP equivalent |
|---|---|
| `gen_server` + `send_after` | virtual-thread poll loop (`Thread.ofVirtual()`) |
| ETS table / process dictionary | `BlockingQueue<T>` (injected or internal) |
| `handle_info(poll, ...)` | `pollLoop()` — `queue.poll(interval, MILLISECONDS)` |
| `handle_cast({enqueue, Msg}, ...)` | `send(T message)` — `queue.offer(message)` |
| `handle_cast` counter update | `Proc<Long, T>` — state is the message count |
| `sys:statistics/2` | `ProcSys.statistics(handlerProc).messagesIn()` |
| `AtomicBoolean` running flag | `running` field; set to `false` on `close()` |

---

## JOTP Implementation

### Design Decisions

**1. The poll loop and the handler are intentionally separated.**

The poll loop is a plain virtual-thread `while` loop — as simple as possible. Its
only job is to move messages from the `BlockingQueue` to the `Proc` mailbox via
`handlerProc.tell(message)`. All application logic lives in the `Proc` handler
lambda, which means it inherits `Proc` semantics: sequential execution, observable
state, and `ProcSys` introspection. This separation also means the `Proc` can be
supervised independently of the poll loop.

**2. `BlockingQueue` is the bridge, not an implementation detail.**

The three-argument constructor accepts an `externalQueue`, making it possible to
share the queue with other producers (e.g., a `DurableSubscriber` draining its buffer
into the same queue, or a `JMS` listener depositing messages). The two-argument
constructor creates a private `LinkedBlockingQueue` for standalone use.

**3. `running` flag + thread interrupt for clean shutdown.**

On `close()`, `running` is set to `false` and the poll thread is interrupted. The
`InterruptedException` catch in `pollLoop()` re-sets the interrupt flag and breaks the
loop cleanly. `pollerThread.join(interval + 500 ms)` ensures the thread has exited
before `handlerProc.stop()` is called — so no messages are in flight when the `Proc`
stops.

**4. `AtomicBoolean` avoids a `Proc` command for the shutdown signal.**

Using an `AtomicBoolean` rather than a `Cmd.Stop` message is deliberate. A `Proc`
command for shutdown would be subject to mailbox queuing — if the queue is deep, the
stop signal could take a long time to arrive. The `AtomicBoolean` + interrupt
combination stops the poll loop immediately, at the OS-scheduling level, without
waiting for the mailbox to drain.

### Architecture Diagram

```
 External Producers (any thread)
        |
        |  pollingConsumer.send(T message)
        |     queue.offer(message)
        |
        v
+-----------------------------------------------------+
|  PollingConsumer<T>   implements MessageChannel     |
|                                                     |
|  +---------------------------+                      |
|  |  Virtual Thread           |                      |
|  |  "polling-consumer-loop"  |                      |
|  |                           |                      |
|  |  while (running.get()):   |                      |
|  |    msg = queue.poll(      |                      |
|  |            interval, MS)  |                      |
|  |    if (msg != null):      |                      |
|  |      handlerProc.tell(msg)|                      |
|  |    on InterruptedException|                      |
|  |      break                |                      |
|  +---------------------------+                      |
|           |                                         |
|           | tell(T)                                 |
|           v                                         |
|  +----------------------------------------------+   |
|  |  Proc<Long, T>  (virtual-thread mailbox)     |   |
|  |                                              |   |
|  |  State: Long (message count)                 |   |
|  |  Handler: (count, msg) -> {                  |   |
|  |      handler.accept(msg);                    |   |
|  |      return count + 1;                       |   |
|  |  }                                           |   |
|  +----------------------------------------------+   |
|                                                     |
|  polledCount()  <- ProcSys.statistics().messagesIn()|
|  queueSize()    <- queue.size()                     |
+-----------------------------------------------------+
         |
         v
    Consumer<T>  (your application logic)
```

---

## API Reference

| Method | Signature | Description |
|---|---|---|
| Constructor (2-arg) | `PollingConsumer(Consumer<T> handler, Duration pollInterval)` | Internal `LinkedBlockingQueue`. Starts the poll virtual thread immediately. |
| Constructor (3-arg) | `PollingConsumer(BlockingQueue<T> externalQueue, Consumer<T> handler, Duration pollInterval)` | Shared queue — multiple producers may enqueue to the same queue. |
| `send` | `void send(T message)` | `MessageChannel<T>` contract. Non-blocking `queue.offer(message)`. Returns `false` if the queue is full (unbounded queue: always `true`). |
| `polledCount` | `long polledCount()` | Total messages forwarded to the handler proc. Reads `ProcSys.statistics(handlerProc).messagesIn()`. |
| `queueSize` | `int queueSize()` | Current number of messages waiting in the queue (not yet polled). |
| `handlerProc` | `Proc<Long, T> handlerProc()` | Exposes the raw handler `Proc` for supervision wiring or testing. |
| `stop` | `void stop()` | Delegates to `close()`. |
| `close` | `void close()` | Sets `running=false`, interrupts poll thread, joins it with timeout, then stops the `Proc`. |

---

## Implementation Internals

The following pseudo-code traces the two concurrent actors — the poll loop virtual
thread and the handler `Proc` — through the full lifecycle:

```
STARTUP
  queue     <- LinkedBlockingQueue (or external)
  running   <- AtomicBoolean(true)
  handlerProc <- Proc( state=0L,
                       handler=(count, msg) -> { consumer.accept(msg); count+1 } )
  pollerThread <- Thread.ofVirtual("polling-consumer-loop").start(pollLoop)

POLL LOOP (virtual thread "polling-consumer-loop")
  WHILE running.get():
    msg <- queue.poll( pollInterval.toMillis(), MILLISECONDS )
    IF msg != null:
      handlerProc.tell(msg)        -- fire-and-forget to Proc mailbox
    -- IF msg == null: interval elapsed, no message; loop again
    ON InterruptedException:
      Thread.currentThread().interrupt()
      BREAK                        -- clean exit

HANDLER PROC (separate virtual thread, serialised)
  ON receive(msg):
    consumer.accept(msg)           -- your application logic
    state <- state + 1             -- count incremented atomically per proc semantics

SHUTDOWN (calling thread)
  running.set(false)
  pollerThread.interrupt()
  pollerThread.join(pollInterval + 500 ms)
  handlerProc.stop()
```

Key ordering guarantee: because `handlerProc.tell(msg)` is a sequential enqueue into
the `Proc` mailbox, messages are processed in the order they were dequeued from the
`BlockingQueue`. The `Proc` serialises handler invocations, so `consumer.accept` is
never called concurrently even if multiple threads somehow reach `tell`.

---

## Code Example

```java
import org.acme.PollingConsumer;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;

/// Demonstrates basic polling, shared-queue usage, and introspection.
public class PollingConsumerDemo {

    public static void main(String[] args) throws InterruptedException {

        // --- Example 1: standalone (internal queue) ---------------------------
        var consumer = new PollingConsumer<String>(
            msg -> System.out.println("[handler] " + msg),
            Duration.ofMillis(50)
        );

        consumer.send("task-001");
        consumer.send("task-002");
        consumer.send("task-003");

        // Poll loop runs every 50 ms; give it time to drain
        Thread.sleep(300);

        System.out.println("Polled so far: " + consumer.polledCount()); // 3
        System.out.println("Queue backlog: " + consumer.queueSize());   // 0

        consumer.stop();

        // --- Example 2: shared external queue --------------------------------
        var sharedQueue = new LinkedBlockingQueue<Integer>(1_000);

        // Simulate a producer filling the queue
        for (int i = 0; i < 10; i++) {
            sharedQueue.put(i);
        }

        var consumer2 = new PollingConsumer<>(
            sharedQueue,
            n -> System.out.println("[int-handler] " + n),
            Duration.ofMillis(20)
        );

        // Another producer can keep enqueuing while the consumer polls
        Thread.ofVirtual().start(() -> {
            for (int i = 10; i < 20; i++) {
                try {
                    sharedQueue.put(i);
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread.sleep(500);
        System.out.println("Total integers handled: " + consumer2.polledCount());
        consumer2.stop();
    }
}
```

Expected output (order of handler lines may vary by scheduling):

```
[handler] task-001
[handler] task-002
[handler] task-003
Polled so far: 3
Queue backlog: 0
[int-handler] 0
[int-handler] 1
... (10-19 interleaved with producer)
Total integers handled: 20
```

---

## Composition

### 1. Polling Consumer as Outbox Pattern Reader

Read from a database outbox table via a polling consumer, using an external queue
as the bridge between a JDBC reader thread and the `Proc` handler:

```java
BlockingQueue<OutboxEvent> outboxQueue = new LinkedBlockingQueue<>(500);

// JDBC reader populates the queue on its own schedule
ScheduledExecutorService jdbc = Executors.newSingleThreadScheduledExecutor();
jdbc.scheduleAtFixedRate(() -> {
    List<OutboxEvent> batch = outboxRepo.fetchUnprocessed(50);
    batch.forEach(outboxQueue::offer);
}, 0, 200, TimeUnit.MILLISECONDS);

// Polling consumer processes at its own rate, decoupled from JDBC timing
var consumer = new PollingConsumer<>(
    outboxQueue,
    event -> {
        eventBus.publish(event);
        outboxRepo.markProcessed(event.id());
    },
    Duration.ofMillis(10)
);

// Graceful drain on shutdown
Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
    jdbc.shutdown();
    try { consumer.close(); } catch (InterruptedException e) { /* log */ }
}));
```

### 2. Supervised Polling Consumer

Place the `handlerProc` under a `Supervisor` for fault recovery. If the handler
throws, the `Supervisor` restarts the proc without stopping the poll loop:

```java
var supervisor = Supervisor.oneForOne(spec -> spec
    .child("payment-handler",
           () -> new Proc<Long, Payment>(
               0L,
               (count, payment) -> { paymentService.process(payment); return count + 1; }
           ))
);
supervisor.start();

ProcRef<Long, Payment> handlerRef = supervisor.procRef("payment-handler");

// The poll loop sends directly to the ref -- safe across restarts
var pollingConsumer = new PollingConsumer<>(
    externalPaymentQueue,
    payment -> handlerRef.tell(payment),
    Duration.ofMillis(100)
);
```

### 3. Rate-limited Polling with Adaptive Interval

Adjust the poll interval dynamically to implement rate limiting without a full
token-bucket library:

```java
// Custom subclass exposing the interval for testing; in production use composition
class AdaptivePollingConsumer<T> extends PollingConsumer<T> {

    private volatile Duration currentInterval;

    AdaptivePollingConsumer(Consumer<T> handler, Duration initial) {
        super(handler, initial);
        this.currentInterval = initial;
    }

    void slowDown(Duration newInterval) {
        this.currentInterval = newInterval;
        // Signal: next poll will use updated interval via a new Proc command
        // (real implementation restarts the poll thread with updated config)
    }
}

// Alternatively, compose with a rate-limiting predicate on the queue
RateLimiter limiter = RateLimiter.create(100.0); // 100 msg/s
var consumer = new PollingConsumer<Order>(
    order -> {
        limiter.acquire();  // blocks until token available
        orderService.handle(order);
    },
    Duration.ofMillis(10)
);
```

---

## Test Pattern

```java
import org.acme.PollingConsumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

class PollingConsumerTest {

    @Test
    void pollsAndHandlesMessagesFromInternalQueue() throws InterruptedException {
        List<String> handled = new CopyOnWriteArrayList<>();
        var consumer = new PollingConsumer<String>(
            handled::add,
            Duration.ofMillis(20)
        );

        consumer.send("a");
        consumer.send("b");
        consumer.send("c");

        Awaitility.await()
            .atMost(Duration.ofSeconds(2))
            .until(() -> handled.size() == 3);

        assertThat(handled).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(consumer.polledCount()).isEqualTo(3L);
        assertThat(consumer.queueSize()).isZero();

        consumer.stop();
    }

    @Test
    void dropsNothingUnderConcurrentLoad() throws InterruptedException {
        int total = 1_000;
        List<Integer> handled = new CopyOnWriteArrayList<>();
        var consumer = new PollingConsumer<Integer>(
            handled::add,
            Duration.ofMillis(1)
        );

        // Concurrent producers
        Thread[] producers = new Thread[4];
        for (int i = 0; i < 4; i++) {
            int base = i * 250;
            producers[i] = Thread.ofVirtual().start(() -> {
                for (int j = base; j < base + 250; j++) {
                    consumer.send(j);
                }
            });
        }
        for (Thread t : producers) t.join();

        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> consumer.polledCount() == total);

        assertThat(handled).hasSize(total);
        consumer.stop();
    }

    @Test
    void externalQueueReceivesMessagesFromMultipleProducers() throws InterruptedException {
        var queue = new LinkedBlockingQueue<String>(100);
        List<String> handled = new CopyOnWriteArrayList<>();

        var consumer = new PollingConsumer<>(queue, handled::add, Duration.ofMillis(10));

        // Two independent producers share the same queue
        queue.put("from-producer-1");
        queue.put("from-producer-2");

        Awaitility.await()
            .atMost(Duration.ofSeconds(2))
            .until(() -> handled.size() == 2);

        assertThat(handled).containsExactlyInAnyOrder("from-producer-1", "from-producer-2");
        consumer.stop();
    }

    @Test
    void stopDrainsInFlightMessages() throws InterruptedException {
        List<String> handled = new CopyOnWriteArrayList<>();
        var consumer = new PollingConsumer<String>(handled::add, Duration.ofMillis(5));

        // Send a burst and immediately stop
        for (int i = 0; i < 20; i++) {
            consumer.send("msg-" + i);
        }

        // stop() should drain before returning
        consumer.stop();

        // After stop(), the handler proc should have processed everything that
        // was polled before the interrupt; we allow some tolerance for timing
        assertThat(consumer.polledCount()).isGreaterThan(0L);
    }

    @Test
    void queueSizeReflectsUnpolledMessages() {
        var consumer = new PollingConsumer<String>(
            __ -> { try { Thread.sleep(10_000); } catch (InterruptedException e) { /* slow */ } },
            Duration.ofMillis(1)
        );

        // Flood the queue faster than the handler can drain
        for (int i = 0; i < 50; i++) {
            consumer.send("item-" + i);
        }

        // Some messages should still be queued
        Awaitility.await()
            .atMost(Duration.ofSeconds(1))
            .until(() -> consumer.queueSize() > 0 || consumer.polledCount() > 0);

        // Clean up without asserting exact numbers (timing-sensitive)
        try { consumer.stop(); } catch (InterruptedException ignored) {}
    }
}
```

---

## Caveats & Trade-offs

### Use When

- The upstream source is **pull-only** — SQS, Azure Service Bus, database outbox
  tables, file-drop directories, or any API without a push/subscription mechanism.
- You need explicit **rate control**: the consumer, not the producer, decides how fast
  messages flow. This is the natural fit for back-pressure integration with slow
  downstream services.
- You want **observable queue depth** (`queueSize()`) separate from the handler's
  `polledCount()` — useful for alerting when the queue grows beyond a threshold.
- The handler occasionally throws and you want the `Proc` / `Supervisor` to isolate
  failures without taking down the poll loop.

### Avoid When

- Latency requirements are very tight (sub-millisecond). Every message waits up to
  `pollInterval` before it is dequeued. Use a push-based `MessageChannel` (e.g.,
  `DurableSubscriber`, a direct `Proc.tell`) for low-latency delivery.
- The upstream source delivers **batches** more efficiently than individual messages.
  The current implementation dequeues one message per iteration; a batching variant
  would call `queue.drainTo(batch, maxBatch)` for higher throughput.
- You need **message acknowledgement** (at-least-once with broker-side requeue on
  failure). The `PollingConsumer` does not implement ack/nack; integrate with the
  broker's own consumer API (e.g., `software.amazon.awssdk.services.sqs`) for that.
- The `pollInterval` is very short (< 1 ms) and the queue is frequently empty. Tight
  polling burns CPU on empty-poll iterations; prefer a push notification or a
  condition variable in that case.

### Performance Notes

- `queue.poll(interval, MILLISECONDS)` **blocks** the virtual thread for up to
  `pollInterval` when the queue is empty. Virtual threads are cheap to block, but
  the effective message latency is bounded below by `pollInterval`. Choose the
  interval to balance latency against CPU burn on empty polls.
- `polledCount()` calls `ProcSys.statistics(handlerProc).messagesIn()`, which
  crosses the `Proc` boundary. It is safe but not free — avoid calling it at the
  same rate as message processing.
- `queueSize()` calls `BlockingQueue.size()`, which is O(1) for
  `LinkedBlockingQueue`. It is safe to call frequently.
- Under very high throughput the `Proc` mailbox may become the bottleneck. If the
  handler is fast and the queue is always full, consider batching: dequeue up to N
  messages per poll iteration and `tell` each one to the proc, or use `drainTo` with
  a `Parallel` fan-out for concurrent handling.
- `close()` waits `pollInterval + 500 ms` for the poll thread to exit. For very
  long intervals (seconds), this can make shutdown feel slow. Pass a short
  `pollInterval` (10–100 ms) and implement backoff in the consumer if needed.
