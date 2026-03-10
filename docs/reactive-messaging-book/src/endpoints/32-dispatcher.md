# 32. Message Dispatcher

> *"A Message Dispatcher distributes messages from one channel to a pool of competing consumers, enabling concurrent processing while maintaining channel ordering guarantees at the pool level."*

## Intent

A single `PointToPointChannel` processes messages sequentially. When processing is slow or CPU-intensive, a Message Dispatcher fans work out to a pool of worker `Proc` instances — competing consumers — so messages are processed in parallel. The dispatcher ensures each message goes to exactly one worker (competing, not broadcasting).

## OTP Analogy

In Erlang, worker pools are implemented by spawning N worker processes and routing work to them with a dispatcher (often a `gen_server` that round-robins or picks the least busy worker):

```erlang
%% Dispatcher: round-robin over N workers
dispatch(Msg, State = #state{workers = Workers, idx = Idx}) ->
    Worker = lists:nth((Idx rem length(Workers)) + 1, Workers),
    gen_server:cast(Worker, {process, Msg}),
    State#state{idx = Idx + 1}.
```

`MessageDispatcher<T>` in JOTP implements this with a virtual-thread worker pool.

## JOTP Implementation

`MessageDispatcher<T>` maintains a fixed pool of `Proc<WorkerState, T>` workers. Incoming messages are distributed round-robin (or using a hash for ordered processing of related messages). Each worker processes its assigned messages sequentially; the pool processes messages in parallel.

```
PointToPointChannel<Task>
         │
MessageDispatcher<Task>    (pool of N workers)
    │
    ├──► Proc<WorkerState, Task>  [Worker-1]
    ├──► Proc<WorkerState, Task>  [Worker-2]
    ├──► Proc<WorkerState, Task>  [Worker-3]
    └──► Proc<WorkerState, Task>  [Worker-N]
```

Key design points:
- Each `Proc` worker has its own mailbox and virtual thread — no shared mutable state between workers.
- Round-robin dispatch ensures even load distribution for uniform-cost tasks.
- For affinity-based routing (same customer always to the same worker), hash the message key: `workerIndex = key.hashCode() % poolSize`.
- `MessageDispatcher<T>` implements `MessageChannel<T>` — drop-in replacement for `PointToPointChannel<T>`.

## API Reference

| Method | Description |
|--------|-------------|
| `new MessageDispatcher<>(poolSize, workerFactory)` | Create a dispatcher with N workers |
| `dispatcher.send(msg)` | Route to next worker (round-robin) |
| `dispatcher.stop()` | Stop all workers |
| `dispatcher.poolSize()` | Number of workers |

## Code Example

```java
import org.acme.Proc;
import org.acme.reactive.MessageDispatcher;
import org.acme.reactive.MessageChannel;

// --- Task type ---
record ImageTask(String imageId, String url, String operation) {}

// --- Worker state ---
record WorkerState(int workerId, int processedCount) {
    WorkerState increment() {
        return new WorkerState(workerId, processedCount + 1);
    }
}

// --- Worker handler ---
class ImageWorker {
    static WorkerState handle(WorkerState state, ImageTask task) {
        System.out.printf("[Worker-%d] Processing %s (%s) — total=%d%n",
            state.workerId(), task.imageId(), task.operation(),
            state.processedCount() + 1);
        simulateWork(task.operation());
        return state.increment();
    }

    static void simulateWork(String op) {
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

public class DispatcherDemo {

    public static void main(String[] args) throws InterruptedException {
        int poolSize = 4;

        // Create dispatcher with 4 workers
        MessageChannel<ImageTask> dispatcher = new MessageDispatcher<>(
            poolSize,
            workerId -> Proc.of(
                new WorkerState(workerId, 0),
                ImageWorker::handle
            )
        );

        // Send 12 tasks — distributed round-robin across 4 workers
        // Each worker processes 3 tasks in parallel with others
        for (int i = 1; i <= 12; i++) {
            String op = (i % 3 == 0) ? "resize" : (i % 3 == 1) ? "crop" : "compress";
            dispatcher.send(new ImageTask("IMG-%03d".formatted(i),
                "https://cdn.example.com/img%d".formatted(i), op));
        }

        Thread.sleep(500);  // let workers finish
        dispatcher.stop();
    }
}
```

## Affinity Routing (Ordered Per Key)

```java
// For messages that must be processed in order per key (e.g., per customer),
// route by hash so the same key always goes to the same worker.
class AffinityDispatcher<T> {
    private final List<MessageChannel<T>> workers;

    AffinityDispatcher(int poolSize, IntFunction<MessageChannel<T>> factory) {
        workers = IntStream.range(0, poolSize)
                           .mapToObj(factory::apply)
                           .toList();
    }

    void send(T msg, int key) {
        int idx = Math.abs(key) % workers.size();
        workers.get(idx).send(msg);
    }
}

// Usage: same orderId always goes to the same worker → in-order per order
affinityDispatcher.send(task, task.orderId().hashCode());
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class MessageDispatcherTest implements WithAssertions {

    @Test
    void allTasksAreProcessed() throws InterruptedException {
        int taskCount = 20;
        var latch     = new CountDownLatch(taskCount);
        var processed = new AtomicInteger(0);

        MessageChannel<ImageTask> dispatcher = new MessageDispatcher<>(
            4,
            id -> Proc.of(
                new WorkerState(id, 0),
                (state, task) -> { processed.incrementAndGet(); latch.countDown(); return state.increment(); }
            )
        );

        for (int i = 1; i <= taskCount; i++)
            dispatcher.send(new ImageTask("IMG-%d".formatted(i), "url", "resize"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(processed.get()).isEqualTo(taskCount);
        dispatcher.stop();
    }

    @Test
    void concurrentProcessingIsFasterThanSequential() throws InterruptedException {
        int taskCount = 8;
        var latch     = new CountDownLatch(taskCount);

        MessageChannel<ImageTask> dispatcher = new MessageDispatcher<>(
            4,  // 4 workers, each task takes 50ms → parallel time ~100ms
            id -> Proc.of(
                new WorkerState(id, 0),
                (state, task) -> {
                    ImageWorker.simulateWork("resize");
                    latch.countDown();
                    return state.increment();
                }
            )
        );

        long start = System.currentTimeMillis();
        for (int i = 0; i < taskCount; i++)
            dispatcher.send(new ImageTask("T%d".formatted(i), "url", "resize"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        long elapsed = System.currentTimeMillis() - start;

        // 8 tasks × 50ms sequential = 400ms; 4 workers → ~100ms parallel
        assertThat(elapsed).isLessThan(300);
        dispatcher.stop();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Processing each message is slow (CPU-bound or I/O-bound) and sequential processing is too slow.
- Message order across the pool is not required (order within a single worker is preserved).
- You need elastic scaling — increase `poolSize` without changing producers or consumers.

**Avoid when:**
- Global message ordering is required — a dispatcher breaks total order across the pool.
- Workers share mutable state — use a dedicated `Proc` with a message-passing design instead.
- The task processing time is trivial — dispatcher overhead is not worth it for sub-microsecond work.
