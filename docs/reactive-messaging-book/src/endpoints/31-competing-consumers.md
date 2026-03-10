# 31. Competing Consumers

> *"Multiple workers race for the same message; exactly one wins. This is the OTP supervisor tree of N identical worker processes."*

## Intent

Scale message processing by having multiple consumers compete for messages from a shared queue. Each message is processed by exactly one consumer. Throughput scales with consumer count.

## OTP Analogy

In OTP, a pool of identical worker `gen_server` processes registered to the same name competes for work. In JOTP, `MessageDispatcher<T>` manages the pool:

```erlang
%% OTP worker pool via supervisor
{ok, _} = supervisor:start_child(MySup, worker_spec(1)),
{ok, _} = supervisor:start_child(MySup, worker_spec(2)),
{ok, _} = supervisor:start_child(MySup, worker_spec(3)),
%% all three workers share the same work queue
```

## JOTP Implementation

**Class**: `org.acme.reactive.MessageDispatcher<T>`
**Mechanism**: Shared `LinkedTransferQueue<T>` + N worker virtual threads racing to poll

```
sender ──► queue.add(msg) ──► LinkedTransferQueue
                                    │
             ┌──────────────────────┼──────────────────────┐
             ▼                      ▼                      ▼
        worker-0.poll()        worker-1.poll()        worker-2.poll()
        (wins this msg)        (waiting)              (waiting)
             │
             ▼
         handler(msg)
```

## API Reference

Builder API:

| Method | Description |
|---|---|
| `.worker(Consumer<T> worker)` | Add one worker thread |
| `.workers(int count, Consumer<T> worker)` | Add N identical workers |
| `.build()` | Construct dispatcher |
| `send(T message)` | Enqueue for any available worker |
| `stop()` | Signal all workers to drain and stop |
| `workerCount()` | Number of active workers |
| `queueDepth()` | Messages waiting for a worker |

## Code Example

```java
// 3 workers compete for CPU-intensive image processing tasks
var counter = new AtomicInteger(0);

var dispatcher = MessageDispatcher.<ImageJob>builder()
    .workers(3, job -> {
        // Each worker runs in its own virtual thread
        processImage(job);
        counter.incrementAndGet();
    })
    .build();

for (var job : imageJobs) {
    dispatcher.send(job);
}

// All jobs processed by exactly one of the 3 workers
await().atMost(Duration.ofSeconds(10)).until(() -> counter.get() == imageJobs.size());
dispatcher.stop();
```

## Dynamic Worker Count

Combine with a `DynamicRouter` to adjust worker count at runtime based on queue depth:

```java
var dispatcher = MessageDispatcher.<Order>builder()
    .workers(5, order -> fulfil(order))
    .build();

// Monitor queue depth and emit alert if backlog grows
var timer = new ProcTimer();
timer.sendInterval(Duration.ofSeconds(5), () -> {
    if (dispatcher.queueDepth() > 100) {
        alertService.send("dispatcher backlog: " + dispatcher.queueDepth());
    }
});
```

## Test Pattern

```java
@Test
void dispatcher_allMessagesProcessedByExactlyOneWorker() throws InterruptedException {
    var counter = new AtomicInteger(0);

    var dispatcher = MessageDispatcher.<Integer>builder()
        .workers(3, n -> counter.addAndGet(n))
        .build();

    for (int i = 1; i <= 10; i++) dispatcher.send(i);

    // Sum 1..10 = 55 — each number processed by exactly one worker
    await().atMost(Duration.ofSeconds(2)).until(() -> counter.get() == 55);

    assertThat(counter.get()).isEqualTo(55);
    dispatcher.stop();
}

@Test
void dispatcher_workerCountReflectsBuilderConfiguration() {
    var dispatcher = MessageDispatcher.<String>builder()
        .workers(4, s -> {})
        .build();
    assertThat(dispatcher.workerCount()).isEqualTo(4);
    dispatcher.stop();
}
```

## Caveats

**Ordering**: Messages are NOT delivered in order across workers. If ordering is required, use `PointToPointChannel` with a single consumer.

**Worker failures**: A worker that throws silently skips the message and continues. Wrap worker logic in try/catch for proper error handling, or use a `Supervisor`-backed design.

**vs. MessageDispatcher**: Pattern 31 (Competing Consumers) and Pattern 32 (Message Dispatcher) use the same class — the distinction is conceptual. Competing Consumers emphasizes the pull-based competition; Message Dispatcher emphasizes the routing aspect.
