# 5. Durable Subscriber

> *"A process that crashes and restarts should not lose messages that were in flight.
> The mailbox is the contract; durability is the promise."*
> — inspired by Joe Armstrong, *Programming Erlang*, 2nd ed.

---

## Intent

A **Durable Subscriber** is a message endpoint that guarantees delivery even when it
is temporarily unable to process messages. When the subscriber is paused — due to
back-pressure, a downstream outage, or a maintenance window — it buffers inbound
messages internally and replays them in arrival order the moment it resumes. Unlike a
plain blocking queue, the Durable Subscriber encodes pause and resume as explicit
state transitions of a supervised process, so the buffer and delivery count are
auditable, introspectable metrics that survive transient faults without losing
ordering guarantees.

---

## OTP Analogy

In Erlang/OTP the canonical idiom is a `gen_server` whose state record carries a
`paused` flag and a buffered message list. When the server is paused it accumulates
messages; on resume it flushes the list to the consumer callback before accepting
new messages again:

```erlang
-module(durable_sub).
-behaviour(gen_server).

-record(state, {paused    = false,
                buffer    = [],
                delivered = 0,
                consumer}).

%% --- paused: accumulate ---
handle_cast({deliver, Payload}, #state{paused = true} = S) ->
    {noreply, S#state{buffer = S#state.buffer ++ [Payload]}};

%% --- live: forward immediately ---
handle_cast({deliver, Payload}, #state{paused = false} = S) ->
    (S#state.consumer)(Payload),
    {noreply, S#state{delivered = S#state.delivered + 1}};

handle_cast(pause, S) ->
    {noreply, S#state{paused = true}};

handle_cast(resume, S) ->
    lists:foreach(S#state.consumer, S#state.buffer),
    Delivered = S#state.delivered + length(S#state.buffer),
    {noreply, S#state{paused    = false,
                      buffer    = [],
                      delivered = Delivered}}.
```

JOTP maps every Erlang concept to an idiomatic Java 26 equivalent:

| OTP concept | JOTP equivalent |
|---|---|
| `gen_server` state record | `State<T>` immutable record |
| `handle_cast/2` clause | pattern-matched lambda `(State<T>, Cmd<T>) -> State<T>` in `Proc` |
| `Pid ! Msg` (async send) | `proc.tell(cmd)` |
| `sys:get_state/1` | `ProcSys.getState(proc)` |
| `sys:statistics/2` | `ProcSys.statistics(proc).messagesIn()` |
| supervisor child spec | `Supervisor.oneForOne(...)` child declaration |

---

## JOTP Implementation

### Design Decisions

**1. Commands are a sealed interface, not method calls on shared state.**

The three lifecycle events — deliver, pause, resume — are modelled as variants of a
sealed `Cmd<T>` hierarchy rather than three methods that mutate shared fields under a
lock. This keeps the `Proc` state handler a **pure function** `(State, Cmd) -> State`
and eliminates every `synchronized` block, `ReentrantLock`, and `volatile` field from
the implementation. Concurrency is structural, not incidental.

```java
sealed interface Cmd<T> permits Cmd.Deliver, Cmd.Pause, Cmd.Resume {
    record Deliver<T>(T payload) implements Cmd<T> {}
    record Pause<T>()            implements Cmd<T> {}
    record Resume<T>()           implements Cmd<T> {}
}
```

**2. State is an immutable record — snapshots are free.**

`State<T>` carries exactly three fields: `paused`, `buffer`, and `delivered`. Every
transition produces a fresh `State` value; the old value is abandoned and becomes
eligible for GC. There is no "current state" field that callers can race on.
`ProcSys.getState(proc)` exploits this by returning a consistent point-in-time
snapshot without pausing the process.

**3. Buffer drains atomically on resume.**

When `Resume` arrives, the entire buffer is flushed synchronously inside the handler
lambda before control returns to the `Proc` event loop. This means all buffered
messages reach the consumer **before** any `Deliver` commands that arrived after the
`Resume` — preserving the exact ordering a live subscriber would have seen.

**4. `ProcSys` provides OTP `sys`-module semantics without reflection.**

`ProcSys.getState(proc)` is the Java equivalent of `sys:get_state(Pid)`: it asks the
process to return its current state without stopping it. `ProcSys.statistics(proc)`
counts every command (including `Pause` and `Resume`) that the process mailbox has
ever received, mirroring `sys:statistics(Pid, get)` in OTP.

### Architecture Diagram

```
 Publisher / Sender(s)
        |
        |  sub.send(T message)
        |
        v
+----------------------------------------------------+
|  DurableSubscriber<T>   implements MessageChannel  |
|                                                    |
|  send(T)  --> proc.tell( Deliver(payload) )        |
|  pause()  --> proc.tell( Pause()          )        |
|  resume() --> proc.tell( Resume()         )        |
|                                                    |
|  +----------------------------------------------+  |
|  |  Proc<State<T>, Cmd<T>>                      |  |
|  |  (virtual-thread mailbox, serialised)        |  |
|  |                                              |  |
|  |  State { paused, List<T> buffer, delivered } |  |
|  |                                              |  |
|  |  on Deliver(payload):                        |  |
|  |    paused? YES -> buffer.append(payload)     |  |
|  |            NO  -> consumer.accept(payload)   |  |
|  |                   delivered++                |  |
|  |                                              |  |
|  |  on Pause():  paused = true                  |  |
|  |                                              |  |
|  |  on Resume(): flush buffer -> consumer       |  |
|  |               buffer = []   paused = false   |  |
|  +----------------------------------------------+  |
|                                                    |
|  ProcSys.getState(proc)   <-- sys:get_state/1     |
|  ProcSys.statistics(proc) <-- sys:statistics/2    |
+----------------------------------------------------+
                    |
                    v
           Consumer<T>   (your application logic)
```

---

## API Reference

| Method | Signature | Description |
|---|---|---|
| Constructor | `DurableSubscriber(Consumer<T> consumer)` | Spawns the `Proc` virtual thread; initial state is live (not paused). |
| `send` | `void send(T message)` | `MessageChannel<T>` contract. Fire-and-forget `Deliver` command to the process mailbox. |
| `pause` | `void pause()` | Transitions the process to paused mode; subsequent deliveries are buffered. Returns immediately. |
| `resume` | `void resume()` | Flushes the buffer to the consumer in order, then returns to live mode. Returns immediately. |
| `bufferSize` | `int bufferSize()` | Synchronous state snapshot via `ProcSys.getState`. Blocks up to 2 s. |
| `deliveredCount` | `long deliveredCount()` | Total payloads forwarded to the consumer (excludes buffered items not yet delivered). |
| `receivedCount` | `long receivedCount()` | Total `Cmd` messages ever received (`Deliver` + `Pause` + `Resume`). |
| `isPaused` | `boolean isPaused()` | Snapshot of the `paused` flag. Blocks up to 2 s. |
| `peekBuffer` | `List<T> peekBuffer()` | Immutable snapshot of current buffer contents. Empty list when live. Blocks up to 2 s. |
| `proc` | `Proc<State<T>, Cmd<T>> proc()` | Exposes the raw process for supervision wiring or advanced introspection. |
| `stop` | `void stop()` | Graceful shutdown: waits for the mailbox to drain, then terminates the virtual thread. |

---

## Implementation Internals

The state machine has two logical modes — **live** and **paused** — and three
command types. The following annotated pseudo-code traces every transition:

```
INITIAL STATE
  { paused = false, buffer = [], delivered = 0 }

EVENT: Deliver(payload)
  IF state.paused:
    newBuf <- immutableCopyOf(state.buffer + [payload])
    YIELD State { paused=true, buffer=newBuf, delivered }
    -- message is preserved; consumer is NOT called

  ELSE:
    consumer.accept(payload)     -- synchronous in handler
    YIELD State { paused=false, buffer=[], delivered+1 }
    -- buffer remains empty; count incremented

EVENT: Pause()
  YIELD State { paused=true, buffer, delivered }
  -- idempotent: pausing an already-paused process is safe

EVENT: Resume()
  count <- 0
  FOR EACH p IN state.buffer:   -- in insertion order
    consumer.accept(p)
    count++
  YIELD State { paused=false, buffer=[], delivered + count }
  -- any Deliver commands that arrived after Resume in the
  -- mailbox will now execute in live mode -- correct ordering
```

Because `Proc` serialises all mailbox messages through a single virtual-thread event
loop, there are **no race conditions** between concurrent `Pause`, `Resume`, and
`Deliver` commands sent from different threads. The relative order of commands in the
mailbox is always the causal order of the corresponding `tell()` calls — identical to
Erlang's message-passing guarantee.

---

## Code Example

```java
import org.acme.DurableSubscriber;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// Demonstrates the full pause / buffer / resume lifecycle of DurableSubscriber.
public class DurableSubscriberDemo {

    public static void main(String[] args) throws InterruptedException {

        List<String> received = new CopyOnWriteArrayList<>();
        var sub = new DurableSubscriber<String>(received::add);

        // Phase 1: live delivery
        sub.send("alpha");
        sub.send("beta");
        Thread.sleep(50);   // allow virtual-thread mailbox to drain
        System.out.println("Live phase received : " + received);
        // => [alpha, beta]

        // Phase 2: pause, then send three more
        sub.pause();
        sub.send("gamma");
        sub.send("delta");
        sub.send("epsilon");
        Thread.sleep(50);

        System.out.println("Paused - delivered  : " + sub.deliveredCount()); // 2
        System.out.println("Paused - buffered   : " + sub.bufferSize());     // 3
        System.out.println("Paused - isPaused   : " + sub.isPaused());       // true
        System.out.println("Paused - peekBuffer : " + sub.peekBuffer());
        // => [gamma, delta, epsilon]
        System.out.println("Paused - consumer   : " + received);
        // => [alpha, beta]   (consumer NOT called for buffered items)

        // Phase 3: resume drains buffer in order
        sub.resume();
        Thread.sleep(50);

        System.out.println("After resume - delivered : " + sub.deliveredCount()); // 5
        System.out.println("After resume - consumer  : " + received);
        // => [alpha, beta, gamma, delta, epsilon]

        // Introspection: total Cmd count includes Pause + Resume
        long total = sub.receivedCount();
        System.out.println("Total Cmds to proc : " + total);
        // => 7  (5x Deliver + 1x Pause + 1x Resume)

        sub.stop();
    }
}
```

Expected output:

```
Live phase received : [alpha, beta]
Paused - delivered  : 2
Paused - buffered   : 3
Paused - isPaused   : true
Paused - peekBuffer : [gamma, delta, epsilon]
Paused - consumer   : [alpha, beta]
After resume - delivered : 5
After resume - consumer  : [alpha, beta, gamma, delta, epsilon]
Total Cmds to proc : 7
```

---

## Composition

### 1. Supervised Durable Subscriber

Wrap the subscriber's `Proc` in a `Supervisor` so it restarts on crashes. Use a
`ProcRef` as the stable handle — it remains valid across restarts:

```java
var supervisor = Supervisor.oneForOne(spec -> spec
    .child("orders-sub",
           () -> new DurableSubscriber<Order>(orderService::process))
);
supervisor.start();

// ProcRef survives restarts; tell() through the ref is always safe
ProcRef<DurableSubscriber.State<Order>, DurableSubscriber.Cmd<Order>> ref =
    supervisor.procRef("orders-sub");

ref.tell(new DurableSubscriber.Cmd.Deliver<>(inboundOrder));
```

If the `Proc` crashes (e.g., `orderService::process` throws), the supervisor
respawns it. Messages sent via `ref.tell()` after restart go to the new process.

### 2. Fan-out to Multiple Durable Subscribers

Route one inbound channel to N durable subscribers for parallel processing, each with
an independent pause/resume lifecycle:

```java
int shardCount = 4;
List<DurableSubscriber<Event>> shards = IntStream.range(0, shardCount)
    .mapToObj(i -> new DurableSubscriber<Event>(e -> processEvent(i, e)))
    .toList();

AtomicInteger cursor = new AtomicInteger();
MessageChannel<Event> fanOut = event ->
    shards.get(Math.floorMod(cursor.getAndIncrement(), shardCount)).send(event);

// Pause shard 2 for a rolling restart
shards.get(2).pause();
deployNewVersion(2);       // shard 2 buffers events during deployment
shards.get(2).resume();    // drains buffered events in order
```

### 3. Back-pressure Circuit Breaker

Query `bufferSize()` from a monitoring loop to shed load before the buffer becomes
unbounded:

```java
int MAX_BUFFER = 10_000;
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

scheduler.scheduleAtFixedRate(() -> {
    int buffered = sub.bufferSize();
    if (buffered > MAX_BUFFER && !sub.isPaused()) {
        upstream.pause();
        alerting.trigger("durable-sub buffer overflow", buffered);
    } else if (buffered < MAX_BUFFER / 2 && sub.isPaused()) {
        upstream.resume();
    }
}, 0, 500, TimeUnit.MILLISECONDS);
```

---

## Test Pattern

```java
import org.acme.DurableSubscriber;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurableSubscriberTest {

    @Test
    void buffersWhilePausedAndDrainsOnResumeInOrder() throws InterruptedException {
        List<Integer> received = new CopyOnWriteArrayList<>();
        var sub = new DurableSubscriber<Integer>(received::add);

        // Live delivery
        sub.send(1);
        sub.send(2);
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> received.size() == 2);
        assertThat(received).containsExactly(1, 2);

        // Pause and buffer
        sub.pause();
        sub.send(3);
        sub.send(4);
        sub.send(5);
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> sub.bufferSize() == 3);
        assertThat(received).containsExactly(1, 2);       // consumer untouched
        assertThat(sub.deliveredCount()).isEqualTo(2L);
        assertThat(sub.isPaused()).isTrue();

        // Resume drains in arrival order
        sub.resume();
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> received.size() == 5);
        assertThat(received).containsExactly(1, 2, 3, 4, 5);
        assertThat(sub.deliveredCount()).isEqualTo(5L);
        assertThat(sub.bufferSize()).isZero();
        assertThat(sub.isPaused()).isFalse();

        sub.stop();
    }

    @Test
    void peekBufferReturnsImmutableSnapshot() throws InterruptedException {
        var sub = new DurableSubscriber<String>(__ -> {});
        sub.pause();
        sub.send("x");
        sub.send("y");

        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> sub.bufferSize() == 2);

        List<String> snapshot = sub.peekBuffer();
        assertThat(snapshot).containsExactly("x", "y");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("z"));

        sub.stop();
    }

    @Test
    void receivedCountIncludesAllCommandVariants() throws InterruptedException {
        var sub = new DurableSubscriber<String>(__ -> {});
        sub.send("a");   // Deliver
        sub.pause();     // Pause
        sub.resume();    // Resume

        // 3 Cmds total
        Awaitility.await()
            .atMost(Duration.ofSeconds(1))
            .until(() -> sub.receivedCount() >= 3L);
        assertThat(sub.receivedCount()).isGreaterThanOrEqualTo(3L);

        sub.stop();
    }

    @Test
    void pausingAlreadyPausedProcessIsIdempotent() throws InterruptedException {
        var sub = new DurableSubscriber<String>(__ -> {});
        sub.pause();
        sub.pause();   // second pause must not corrupt state
        sub.send("idempotent");

        Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> sub.bufferSize() == 1);
        assertThat(sub.peekBuffer()).containsExactly("idempotent");

        sub.stop();
    }
}
```

---

## Caveats & Trade-offs

### Use When

- The downstream consumer experiences **predictable, bounded outages** (rolling
  restarts, rate-limit windows, short GC pauses) and will reliably resume within a
  known time bound.
- **Message ordering** must be preserved across the pause/resume boundary — the buffer
  drains in strict arrival order.
- You need **introspectable delivery metrics** (`deliveredCount`, `bufferSize`,
  `peekBuffer`) without bolting on a separate monitoring sidecar.
- You are already building with `Proc` and `Supervisor` and want durability to compose
  naturally in the same supervision tree.

### Avoid When

- The pause could be **unbounded** (a downstream service that has crashed and will not
  restart without human intervention). The buffer will grow without limit until the
  JVM runs out of heap. Prefer a bounded queue with overflow-to-dead-letter in that
  scenario.
- Messages must **survive a JVM restart**. The buffer lives entirely in heap memory.
  For true persistence across restarts use an external broker (Kafka, NATS JetStream,
  Pulsar) and implement the subscriber as a durable consumer group member.
- The consumer is **cheap and idempotent** — a simpler retry loop or at-least-once
  delivery queue adds less complexity and is easier to reason about.
- **Buffer items are very large** (binary payloads, large POJOs). Each item is a
  heap-allocated list element. For high-volume or large-payload scenarios prefer a
  disk-backed or off-heap queue.

### Performance Notes

- `bufferSize()`, `isPaused()`, and `peekBuffer()` each issue a `ProcSys.getState`
  call that **blocks the calling thread** up to 2 seconds. Call them from monitoring
  threads only, never from a hot producer path.
- `resume()` drains the buffer **synchronously inside the handler lambda**. For
  buffers with tens of thousands of items this can starve other mailbox messages for
  a measurable duration. Consider a chunked resume strategy (flush N, yield, flush N)
  for very large buffers.
- `receivedCount()` counts all `Cmd` variants. Use `deliveredCount()` when you need
  a precise count of payloads forwarded to the consumer; `receivedCount` will always
  be higher by at least `(pause_count + resume_count)`.
