# 29. Polling Consumer

> *"A gen_server with `receive after Timeout` is the ur-polling consumer: wake up every N milliseconds, check if work is available, process it, sleep again. The PollingConsumer makes the poller and the handler separate concerns — one virtual thread polls, one Proc handles — so a slow handler never starves the poll loop and a crashing handler never kills the poller."*

## Intent

Consume messages from a source at a controlled rate by periodically polling rather than being pushed, with fault isolation between the polling loop and the message handler so that handler failures are confined and observable without disrupting the poll schedule.

The PollingConsumer is the pull-side complement to the event-driven push consumer. It is the right choice when the message source cannot push (legacy databases, files, REST APIs, systems without event/webhook support) or when backpressure must be applied by controlling the poll rate rather than relying on the source to throttle.

## OTP Analogy

The canonical Erlang polling loop is:

```erlang
-record(state, {
    source   :: fun(() -> {ok, Msg} | empty),
    handler  :: fun((Msg) -> ok),
    interval :: non_neg_integer(),  %% milliseconds
    polled   :: non_neg_integer()
}).

handle_info(poll, #state{source=Src, handler=H, interval=I, polled=N}=S) ->
    NewN = case Src() of
        {ok, Msg} ->
            H(Msg),
            N + 1;
        empty ->
            N
    end,
    erlang:send_after(I, self(), poll),
    {noreply, S#state{polled=NewN}};

init(State) ->
    self() ! poll,
    {ok, State}.
```

The `receive after Timeout` version is slightly different — it avoids `send_after` overhead by embedding the timeout in the receive:

```erlang
loop(State) ->
    receive
        {stop} -> ok
    after State#state.interval ->
        NewState = poll_and_handle(State),
        loop(NewState)
    end.
```

The Java implementation separates the concerns more explicitly:

- The **poll loop** is a plain `while(running)` on a virtual thread — sleeping between polls, calling the source, enqueuing messages. It never calls the handler directly.
- The **handler** runs inside a `Proc<Long, T>` — the `Long` state is the polled count. Each enqueued message is sent to the `Proc` as a message. The `Proc` provides mailbox buffering, fault isolation, and `ProcSys` introspection for the handler.

This separation means: if the handler crashes (throws), the `Proc` supervisor can restart it without touching the poll loop. If the poll source is slow, the poll loop blocks on the source, not on the handler. The `BlockingQueue` between them is the bounded buffer that provides backpressure.

## JOTP Implementation

**Class:** `PollingConsumer<T>`
**Package:** `org.acme.eip.endpoint`
**Key design decisions:**

1. **Dual-thread architecture** — poll loop on a virtual thread, handler inside a `Proc<Long, T>`. The poll loop sends to the `Proc`'s mailbox via a `BlockingQueue` with bounded capacity (backpressure). If the `Proc` mailbox is full, the poll loop blocks — automatically throttling the source read rate.

2. **Two constructors** — internal queue (the `PollingConsumer` manages the `BlockingQueue`) and external queue (caller provides a `BlockingQueue<T>` that another producer already fills). The external queue constructor enables the `PollingConsumer` to drain a pre-filled queue, which is the pattern for JMS/AMQP bridge adapters.

3. **`Proc<Long, T>` for handler** — the handler function `Consumer<T>` is wrapped in a `Proc`. The `Proc` state is the `Long` polled count. The state handler increments the count after each successful handler invocation, so `polledCount()` is obtained from the `Proc` state (via `ProcSys.getState`) rather than a separate counter, keeping the count consistent with the `Proc`'s actual execution.

4. **Configurable poll interval** — `Duration pollInterval` controls the sleep between polls. For time-window-based polling, the interval is added *after* each poll attempt (fixed delay, not fixed rate). This avoids the "thundering herd" problem when many consumers start simultaneously.

5. **Graceful shutdown** — `close()` sets `running = false`, interrupts the poll thread, and sends a terminal poison-pill to the `Proc`. Both stop cleanly.

6. **Error channel** — optional `MessageChannel<PollingError<T>>` for handler exceptions. If a handler throws, the exception is wrapped in `PollingError` and sent to the error channel rather than crashing the `Proc`. This enables "retry later" workflows without stopping the consumer.

## API Reference

### `PollingConsumer<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `polledCount` | `long polledCount()` | Total messages processed by handler (from Proc state) |
| `pendingCount` | `int pendingCount()` | Current size of the internal queue (messages polled but not yet handled) |
| `handlerProc` | `ProcRef<Long, T> handlerProc()` | Raw `ProcRef` for supervisor wiring and `ProcSys` introspection |
| `isRunning` | `boolean isRunning()` | Whether the poll loop is active |
| `pause` | `void pause()` | Suspend polling (handler keeps draining the queue) |
| `resume` | `void resume()` | Resume polling |
| `setPollInterval` | `void setPollInterval(Duration interval)` | Change poll interval at runtime |
| `close` | `void close()` | Graceful shutdown: stop polling, drain queue, stop handler Proc |

### `PollingConsumer.Builder<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `source` | `Builder<T> source(Supplier<Optional<T>> src)` | Poll source: returns `Optional.empty()` when nothing available |
| `handler` | `Builder<T> handler(Consumer<T> h)` | Message handler (runs inside Proc) |
| `pollInterval` | `Builder<T> pollInterval(Duration d)` | Sleep between poll attempts (default: 1 second) |
| `queueCapacity` | `Builder<T> queueCapacity(int n)` | Internal queue bound (default: 256) |
| `errorChannel` | `Builder<T> errorChannel(MessageChannel<PollingError<T>> ch)` | Handler exception routing |
| `build` | `PollingConsumer<T> build()` | Start poll loop and handler Proc |

### Constructor: external queue

```java
PollingConsumer<T> consumer = PollingConsumer.draining(
    existingQueue,            // BlockingQueue<T> already being filled externally
    handler,                  // Consumer<T>
    Duration.ofMillis(100)    // poll interval for drain loop
);
```

### `PollingError<T>` (record)

```java
record PollingError<T>(T message, Throwable cause, Instant timestamp) {}
```

## Implementation Internals

```
PollingConsumer<T> internals:

[Poll thread: virtual thread]
│
└── while (running && !interrupted):
    ├── source.get()    // returns Optional<T>
    ├── if Present(msg):
    │   └── queue.put(msg)    // blocks if queue full (backpressure)
    └── sleep(pollInterval)

[Handler Proc: Proc<Long, T>]
│
└── state handler: (count, msg) ->
    ├── try:
    │   ├── handler.accept(msg)
    │   └── return count + 1
    └── catch Exception e:
        ├── if errorChannel != null: errorChannel.send(new PollingError(msg, e, now()))
        └── return count   // don't increment on failure; don't crash Proc
```

The poll loop and handler `Proc` communicate via a `LinkedBlockingQueue<T>` (bounded). The `Proc` pulls from this queue as its message source instead of the standard `Proc` mailbox — this is the "external queue" integration point. Internally, a bridge virtual thread reads from the `LinkedBlockingQueue` and sends each entry to the `Proc` mailbox, preserving the `Proc` abstraction.

**Backpressure propagation:** When the handler is slow, the `LinkedBlockingQueue` fills to its capacity. The poll loop's `queue.put()` call blocks. The source is no longer polled. The source's backpressure is propagated all the way back to the poll source without any explicit rate-limiting logic.

**ProcSys introspection:** `ProcSys.getState(consumer.handlerProc())` returns the `Long` polled count. `ProcSys.statistics(consumer.handlerProc())` returns message throughput, queue depth, and last message timestamp — the full OTP `sys:statistics` equivalent.

## Code Example

```java
import org.acme.eip.endpoint.PollingConsumer;
import org.acme.eip.channel.InMemoryChannel;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

// --- Simulated source: a database poll result queue ---
record Task(long id, String payload) {}

// Simulate a source that returns tasks one at a time
var sourceQueue = new LinkedBlockingQueue<Task>();
sourceQueue.add(new Task(1, "process-report"));
sourceQueue.add(new Task(2, "send-email"));
sourceQueue.add(new Task(3, "generate-invoice"));

// --- Error channel ---
var errorChannel = new InMemoryChannel<PollingConsumer.PollingError<Task>>("errors");

// --- Build consumer (internal queue constructor) ---
var consumer = PollingConsumer.<Task>builder()
    .source(() -> Optional.ofNullable(sourceQueue.poll()))  // poll, not blocking take
    .handler(task -> {
        System.out.printf("[HANDLER] Processing task %d: %s%n", task.id(), task.payload());
        if (task.id() == 2) throw new RuntimeException("Email service down");
    })
    .pollInterval(Duration.ofMillis(50))
    .queueCapacity(32)
    .errorChannel(errorChannel)
    .build();

// Let it run for a bit
Thread.sleep(500);

System.out.println("Polled:  " + consumer.polledCount());
System.out.println("Pending: " + consumer.pendingCount());
System.out.println("Errors:  " + errorChannel.size());

// Output:
// [HANDLER] Processing task 1: process-report
// [HANDLER] Processing task 2: send-email       <- throws, goes to errorChannel
// [HANDLER] Processing task 3: generate-invoice
// Polled:  2   (task 2 failed, not counted)
// Pending: 0
// Errors:  1

consumer.close();
```

### External Queue Constructor (JMS/AMQP Bridge)

```java
// External queue filled by JMS message listener (not managed by us)
var jmsQueue = new LinkedBlockingQueue<JmsMessage>(512);

// Start a JMS listener that fills jmsQueue
jmsSession.createConsumer(queue).setMessageListener(msg ->
    jmsQueue.offer(convertToJmsMessage(msg)));

// PollingConsumer drains the JMS queue at a controlled rate
var consumer = PollingConsumer.<JmsMessage>draining(
    jmsQueue,
    msg -> processMessage(msg),
    Duration.ofMillis(10)
);
```

### ProcSys Introspection

```java
import org.acme.ProcSys;

// Get live handler statistics
var stats = ProcSys.statistics(consumer.handlerProc());
System.out.println("Messages processed: " + stats.messagesIn());
System.out.println("Last processed:     " + stats.lastMessageTime());
System.out.println("Throughput:         " + stats.throughput() + " msg/s");

// Suspend the handler (but keep polling, filling the queue)
ProcSys.suspend(consumer.handlerProc());
System.out.println("Handler suspended");
// ... perform maintenance ...
ProcSys.resume(consumer.handlerProc());
System.out.println("Handler resumed, draining queue");
```

### Rate-Limited Polling

```java
// Start slow, ramp up poll rate based on queue depth
var consumer = PollingConsumer.<Task>builder()
    .source(taskDb::pollNextTask)
    .handler(this::processTask)
    .pollInterval(Duration.ofSeconds(1))   // start conservative
    .build();

// Adaptive: increase rate if source has more work
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(() -> {
    long depth = taskDb.pendingCount();
    if (depth > 1000) {
        consumer.setPollInterval(Duration.ofMillis(10));
    } else if (depth > 100) {
        consumer.setPollInterval(Duration.ofMillis(100));
    } else {
        consumer.setPollInterval(Duration.ofSeconds(1));
    }
}, 5, 5, TimeUnit.SECONDS);
```

## Composition

**PollingConsumer + Content-Based Router:**
Route polled messages to specialist handlers based on type:
```java
var consumer = PollingConsumer.<Message>builder()
    .source(db::pollNext)
    .handler(router::route)    // router is a MessageRouter<Message>
    .build();
```

**PollingConsumer + Resequencer:**
Source delivers out-of-order; poll then resequence before processing:
```
[DB source] -> PollingConsumer -> Resequencer -> OrderedProcessor
```

**PollingConsumer + DurableSubscriber:**
Feed a durable subscriber that can pause/resume independently:
```java
var durable = DurableSubscriber.<Task>builder()
    .handler(this::processTask)
    .build();

var consumer = PollingConsumer.<Task>builder()
    .source(taskQueue::poll)
    .handler(durable::send)   // decouple poll rate from processing rate
    .build();
```

**PollingConsumer + WireTap:**
Tap every polled message for audit without modifying the handler:
```java
var tap = WireTap.of(handlerChannel, msg -> auditLog.record(msg));
var consumer = PollingConsumer.<Task>builder()
    .source(db::pollNext)
    .handler(task -> {
        tap.send(task);
        processTask(task);
    })
    .build();
```

**PollingConsumer + Supervisor:**
Wire the handler `Proc` under a supervisor for automatic restart on crash:
```java
var supervisor = Supervisor.builder()
    .child(consumer.handlerProc(), SupervisionStrategy.ONE_FOR_ONE)
    .maxRestarts(5, Duration.ofMinutes(1))
    .build();
supervisor.start();
```

## Test Pattern

```java
import org.acme.eip.endpoint.PollingConsumer;
import org.acme.eip.channel.CapturingChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class PollingConsumerTest implements WithAssertions {

    record Item(int id, String value) {}

    @Test
    void pollsAndHandlesMessages() throws InterruptedException {
        var handled = new CopyOnWriteArrayList<Item>();
        var source  = new LinkedBlockingQueue<Item>();
        source.addAll(List.of(
            new Item(1, "a"), new Item(2, "b"), new Item(3, "c")));

        var consumer = PollingConsumer.<Item>builder()
            .source(() -> Optional.ofNullable(source.poll()))
            .handler(handled::add)
            .pollInterval(Duration.ofMillis(20))
            .build();

        // Wait until all 3 are processed
        Awaitility.await().atMost(Duration.ofSeconds(2))
            .until(() -> handled.size() == 3);

        assertThat(handled).extracting(Item::id)
            .containsExactlyInAnyOrder(1, 2, 3);
        assertThat(consumer.polledCount()).isEqualTo(3);

        consumer.close();
    }

    @Test
    void handlerExceptionRoutesToErrorChannel() throws InterruptedException {
        var errors = new CapturingChannel<PollingConsumer.PollingError<Item>>("errors");
        var source = new ArrayDeque<>(List.of(new Item(1, "good"), new Item(2, "bad"), new Item(3, "good")));
        var handled = new CopyOnWriteArrayList<Item>();

        var consumer = PollingConsumer.<Item>builder()
            .source(() -> Optional.ofNullable(source.poll()))
            .handler(item -> {
                if ("bad".equals(item.value())) throw new RuntimeException("bad item");
                handled.add(item);
            })
            .pollInterval(Duration.ofMillis(20))
            .errorChannel(errors)
            .build();

        Awaitility.await().atMost(Duration.ofSeconds(2))
            .until(() -> handled.size() == 2);

        assertThat(errors.captured()).hasSize(1);
        assertThat(errors.captured().get(0).message()).isEqualTo(new Item(2, "bad"));
        assertThat(consumer.polledCount()).isEqualTo(2);  // only successful ones counted

        consumer.close();
    }

    @Test
    void pause_stopsPollLoop_handlersKeepDraining() throws InterruptedException {
        var source  = new LinkedBlockingQueue<Item>();
        var handled = new CopyOnWriteArrayList<Item>();

        var consumer = PollingConsumer.<Item>builder()
            .source(() -> Optional.ofNullable(source.poll()))
            .handler(handled::add)
            .pollInterval(Duration.ofMillis(20))
            .build();

        // Add items, let some process
        source.add(new Item(1, "a"));
        source.add(new Item(2, "b"));

        Awaitility.await().atMost(Duration.ofSeconds(1))
            .until(() -> handled.size() == 2);

        consumer.pause();
        source.add(new Item(3, "c"));   // added during pause
        Thread.sleep(200);              // poll loop is paused, won't pick this up

        assertThat(handled).hasSize(2); // item 3 not yet handled
        assertThat(consumer.isRunning()).isFalse();

        consumer.resume();
        Awaitility.await().atMost(Duration.ofSeconds(1))
            .until(() -> handled.size() == 3);

        assertThat(handled).extracting(Item::id).contains(3);
        consumer.close();
    }

    @Test
    void externalQueue_drainsExistingMessages() throws InterruptedException {
        var externalQueue = new LinkedBlockingQueue<Item>();
        externalQueue.addAll(List.of(new Item(10, "x"), new Item(20, "y")));

        var handled = new CopyOnWriteArrayList<Item>();
        var consumer = PollingConsumer.draining(
            externalQueue, handled::add, Duration.ofMillis(20));

        Awaitility.await().atMost(Duration.ofSeconds(2))
            .until(() -> handled.size() == 2);

        assertThat(handled).extracting(Item::id).containsExactlyInAnyOrder(10, 20);
        consumer.close();
    }

    @Test
    void procSysIntrospection_exposesHandlerState() throws InterruptedException {
        var source  = new LinkedBlockingQueue<Item>();
        var handled = new CountDownLatch(3);

        var consumer = PollingConsumer.<Item>builder()
            .source(() -> Optional.ofNullable(source.poll()))
            .handler(item -> handled.countDown())
            .pollInterval(Duration.ofMillis(10))
            .build();

        source.addAll(List.of(new Item(1, "a"), new Item(2, "b"), new Item(3, "c")));
        handled.await(2, TimeUnit.SECONDS);

        var procState = org.acme.ProcSys.getState(consumer.handlerProc());
        assertThat(procState).isEqualTo(3L);  // polled count is the Proc state

        consumer.close();
    }

    @Test
    void backpressure_pollLoopBlocksWhenQueueFull() throws InterruptedException {
        var pollCount = new AtomicInteger();
        var slowHandlerLatch = new CountDownLatch(1);

        var consumer = PollingConsumer.<Item>builder()
            .source(() -> {
                pollCount.incrementAndGet();
                return Optional.of(new Item(pollCount.get(), "item-" + pollCount.get()));
            })
            .handler(item -> {
                // Very slow handler — simulates backpressure
                slowHandlerLatch.await(5, TimeUnit.SECONDS);
            })
            .pollInterval(Duration.ofMillis(1))
            .queueCapacity(4)   // tiny queue to trigger backpressure quickly
            .build();

        Thread.sleep(100);

        // pollCount should be bounded by queueCapacity, not unbounded
        assertThat(pollCount.get()).isLessThan(20);

        slowHandlerLatch.countDown();
        consumer.close();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- The message source is a legacy system with no push/event capability (JDBC, FTP, REST polling, files)
- You need to apply backpressure to the source by controlling the poll rate
- The handler is slow or unreliable and must be isolated from the poll loop
- You need operational control (pause/resume) over the ingest rate without redeploying

**Avoid when:**
- The source supports push semantics natively (Kafka consumer, JMS with MessageListener) — using a poller introduces latency and wastes CPU on empty polls
- Sub-millisecond latency is required — the poll interval adds inherent latency; use an event-driven consumer instead
- The source cannot handle concurrent polling — multiple `PollingConsumer` instances will issue concurrent polls; ensure the source is thread-safe or use a single consumer

**Poll interval tuning:**
- **Too short:** Wastes CPU on empty polls; may overwhelm the source with requests
- **Too long:** Increases latency; messages sit in the source queue longer than necessary
- Consider adaptive polling: start with a long interval, reduce it when the source returns messages, increase it on empty polls. Exponential backoff with a ceiling is a good default strategy.

**Queue capacity and memory:**
- The internal `LinkedBlockingQueue` capacity bounds memory usage. Set it to the number of messages you're willing to buffer between source and handler.
- If the handler crashes and the `Proc` restarts, the queue retains buffered messages — they will be processed by the restarted handler. This is "at-least-once" delivery within the JVM lifetime.
- For cross-restart durability, use an external durable queue (e.g., a database row with a "processing" status column) as the source.

**Poll source idempotency:**
- The poll loop may deliver the same message twice if the JVM crashes between `queue.put(msg)` and the handler acknowledging the message. Ensure the handler is idempotent, or mark messages as "in-flight" in the source before polling and "complete" after handling.

**Handler crashes:**
- With `errorChannel` configured, handler exceptions are captured and forwarded without crashing the `Proc`. Without `errorChannel`, handler exceptions are logged and the message is dropped. Never silently drop messages in production — always wire an `errorChannel`.
