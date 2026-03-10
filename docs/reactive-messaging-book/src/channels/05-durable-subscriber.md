# 05. Durable Subscriber

> *"A process with pause/resume as state machine transitions: `active` → `receive Cmd` → route to handler or buffer; `paused` → `receive Cmd` → buffer unconditionally. Resuming drains the buffer before re-entering active mode. The Durable Subscriber is OTP's gen_statem expressed as a Java sealed interface and a Proc."*

## Intent

Deliver messages to a subscriber reliably, buffering them when the subscriber is paused and draining the buffer in order when the subscriber resumes, ensuring no message is lost across pause/resume cycles.

Unlike a simple `BlockingQueue`, the `DurableSubscriber` makes pausing and resuming first-class operations expressed as messages to the backing `Proc`, not as external thread-interruption or lock manipulation. This means pause/resume obey the same ordering guarantees as message delivery: a `pause()` call that arrives before message M in the `Proc` mailbox is guaranteed to be processed before M.

## OTP Analogy

In OTP, this pattern is implemented with `gen_statem` and two states: `active` and `paused`:

```erlang
%% gen_statem callback
callback_mode() -> state_functions.

active(cast, {deliver, Msg}, #state{handler=H, delivered=D}=S) ->
    H(Msg),
    {keep_state, S#state{delivered=D+1}};

active(cast, pause, #state{}=S) ->
    {next_state, paused, S};

paused(cast, {deliver, Msg}, #state{buffer=B}=S) ->
    {keep_state, S#state{buffer=[Msg|B]}};

paused(cast, resume, #state{buffer=B, handler=H, delivered=D}=S) ->
    %% Drain buffer in FIFO order (B was built in reverse)
    lists:foreach(H, lists:reverse(B)),
    NewD = D + length(B),
    {next_state, active, S#state{buffer=[], delivered=NewD}}.
```

The Java `DurableSubscriber` maps this directly:

- `active`/`paused` states → the `boolean paused` field in `State<T>`
- `Deliver/Pause/Resume` Erlang casts → `Cmd<T>` sealed interface variants
- `lists:reverse(B)` drain → drain `ArrayDeque<T>` in FIFO order
- Handler call → `Consumer<T>` invoked in the `Proc` state handler

The critical insight: `Pause` and `Resume` are **messages**, not external mutations. They sit in the same `Proc` mailbox as `Deliver` messages. The state machine processes them in strict arrival order. A `Pause` that is "in flight" when five `Deliver` messages arrive will pause *after* the five deliveries complete — not before, not during. This gives deterministic, race-free semantics that external `synchronized` flags cannot provide.

## JOTP Implementation

**Class:** `DurableSubscriber<T>`
**Package:** `org.acme.eip.channel`
**Key design decisions:**

1. **`Proc<State<T>, Cmd<T>>`** — the entire subscriber is a `Proc`. State is held inside the mailbox loop, never shared with calling threads. Pause/resume are `Cmd` messages, not `synchronized` methods.

2. **Sealed `Cmd<T>` interface** — three variants:
   - `Deliver<T>(T message)` — a message to be handled or buffered
   - `Pause<T>()` — transition to paused state
   - `Resume<T>()` — transition to active state, drain buffer

3. **`ArrayDeque<T>` buffer** — FIFO queue for buffered messages during pause. `ArrayDeque` is more memory-efficient than `LinkedList` and faster than `ArrayList` for queue operations (amortized O(1) head removal).

4. **Buffer drain on resume** — when `Resume` is processed, the entire buffer is drained synchronously within the same state handler invocation. This means a `Resume` followed immediately by `Deliver` in the mailbox will see the resumed handler process buffered messages first, then the new delivery — preserving global FIFO order.

5. **`peekBuffer()` for observability** — returns an unmodifiable snapshot of the current buffer without consuming it. Safe to call from any thread via `ProcSys.getState`.

6. **Backpressure via `maxBufferSize`** — if the buffer exceeds `maxBufferSize` while paused, new `Deliver` messages are routed to an overflow channel rather than buffered. This prevents unbounded memory growth during long pauses.

## API Reference

### `DurableSubscriber<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `send` | `void send(T message)` | Deliver a message (handler or buffer, depending on state) |
| `pause` | `void pause()` | Send `Pause` command to the Proc mailbox |
| `resume` | `void resume()` | Send `Resume` command; triggers buffer drain |
| `bufferSize` | `int bufferSize()` | Current number of buffered messages (0 when active) |
| `deliveredCount` | `long deliveredCount()` | Total messages delivered to handler (not counting buffered) |
| `isPaused` | `boolean isPaused()` | Current pause state (snapshot; may lag by one Proc cycle) |
| `peekBuffer` | `List<T> peekBuffer()` | Unmodifiable snapshot of the buffer contents in FIFO order |
| `proc` | `ProcRef<State<T>, Cmd<T>> proc()` | Raw `ProcRef` for supervisor wiring and `ProcSys` introspection |
| `awaitQuiescence` | `void awaitQuiescence(Duration timeout)` | Block until buffer is empty and Proc mailbox is drained |
| `close` | `void close()` | Terminate the backing Proc |

### `DurableSubscriber.Builder<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `handler` | `Builder<T> handler(Consumer<T> h)` | Required: message handler (called in Proc context) |
| `maxBufferSize` | `Builder<T> maxBufferSize(int n)` | Max buffer before overflow (default: unbounded) |
| `overflowChannel` | `Builder<T> overflowChannel(MessageChannel<T> ch)` | Destination for overflow messages |
| `startPaused` | `Builder<T> startPaused()` | Start in paused state (buffer immediately) |
| `onBufferChange` | `Builder<T> onBufferChange(Consumer<Integer> obs)` | Called with new buffer size on every change |
| `build` | `DurableSubscriber<T> build()` | Construct and start the backing Proc |

### `Cmd<T>` (sealed interface, internal)

```java
sealed interface Cmd<T> {
    record Deliver<T>(T message)  implements Cmd<T> {}
    record Pause<T>()             implements Cmd<T> {}
    record Resume<T>()            implements Cmd<T> {}
}
```

### `State<T>` (internal, inspectable via ProcSys)

```java
record State<T>(
    boolean          paused,
    ArrayDeque<T>    buffer,
    long             deliveredCount,
    Consumer<T>      handler,
    int              maxBufferSize,
    MessageChannel<T> overflowChannel
) {
    List<T> peekBuffer() {
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }
}
```

## Implementation Internals

```
State handler: (State<T>, Cmd<T>) -> State<T>

case Deliver(message):
    if paused:
        if buffer.size() < maxBufferSize:
            buffer.addLast(message)                 // enqueue for later
        else:
            overflowChannel.send(message)           // overflow
        return state (unchanged except buffer)
    else:
        handler.accept(message)                     // deliver immediately
        return state.withDeliveredCount(deliveredCount + 1)

case Pause():
    return state.withPaused(true)

case Resume():
    // Drain buffer in FIFO order within this state transition
    long drained = 0
    while (!buffer.isEmpty()):
        T msg = buffer.pollFirst()
        handler.accept(msg)
        drained++
    return state.withPaused(false).withDeliveredCount(deliveredCount + drained)
```

**Ordering guarantee:** All `Cmd<T>` messages are processed serially by the `Proc`'s single virtual thread. This guarantees:
- A `Pause` sent after 3 `Deliver` calls will pause *after* all 3 are handled, not before
- A `Resume` processes the buffer *before* any subsequent `Deliver` messages (those sit later in the mailbox)
- `deliveredCount` is exactly the number of handler invocations, never overcounted or undercounted

**Handler exceptions:** If the handler throws during `Deliver` or during the buffer drain on `Resume`, the exception propagates out of the `Proc` state handler, causing the `Proc` to crash. The supervisor restarts the `Proc` with the last committed state — meaning if `Resume` fails mid-drain, the buffer may have been partially consumed. For at-least-once semantics, wrap the handler in a try-catch that records the failure to a dead-letter channel before rethrowing.

## Code Example

```java
import org.acme.eip.channel.DurableSubscriber;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

// --- Domain ---
record Notification(String id, String body) {}

// --- Track delivered messages ---
var delivered = new CopyOnWriteArrayList<Notification>();

// --- Build subscriber ---
var subscriber = DurableSubscriber.<Notification>builder()
    .handler(n -> {
        System.out.printf("[HANDLER] Delivering %s: %s%n", n.id(), n.body());
        delivered.add(n);
    })
    .maxBufferSize(100)
    .onBufferChange(size -> System.out.println("[BUFFER] size=" + size))
    .build();

// --- Normal delivery ---
subscriber.send(new Notification("N1", "Welcome!"));
subscriber.send(new Notification("N2", "Your order shipped"));

// Wait for handler
Thread.sleep(50);
System.out.println("Delivered: " + subscriber.deliveredCount());  // 2
System.out.println("Buffered:  " + subscriber.bufferSize());      // 0

// --- Pause: messages accumulate in buffer ---
subscriber.pause();
System.out.println("Paused: " + subscriber.isPaused());  // true (after Proc processes Pause cmd)

subscriber.send(new Notification("N3", "New message while paused"));
subscriber.send(new Notification("N4", "Another one"));
subscriber.send(new Notification("N5", "And another"));

Thread.sleep(50);
System.out.println("Buffered during pause: " + subscriber.bufferSize());  // 3
System.out.println("Delivered count unchanged: " + subscriber.deliveredCount());  // still 2

// Inspect buffer without consuming
var buffered = subscriber.peekBuffer();
System.out.println("Buffer preview: " + buffered.stream()
    .map(Notification::id).toList());  // [N3, N4, N5]

// --- Resume: buffer drains in FIFO order ---
subscriber.resume();
subscriber.awaitQuiescence(Duration.ofSeconds(2));

System.out.println("Final delivered: " + subscriber.deliveredCount());  // 5
System.out.println("Buffer empty:    " + subscriber.bufferSize());       // 0
System.out.println("All IDs in order: " + delivered.stream()
    .map(Notification::id).toList());
// Output: [N1, N2, N3, N4, N5]  <- strict FIFO preserved across pause/resume
```

### Pause/Resume Ordering Guarantee

```java
// Demonstrate that Pause is processed in mailbox order, not immediately
subscriber.send(new Notification("A", "before pause cmd"));
subscriber.send(new Notification("B", "before pause cmd"));
subscriber.pause();   // queued AFTER A and B in mailbox
subscriber.send(new Notification("C", "after pause cmd — will be buffered"));

Thread.sleep(100);

// A and B were delivered BEFORE pause took effect
// C is in the buffer
assertThat(subscriber.deliveredCount()).isEqualTo(2);   // A and B delivered
assertThat(subscriber.bufferSize()).isEqualTo(1);       // C buffered
```

### Start Paused (Accumulate Before Any Delivery)

```java
// Useful for "collect then process" patterns
var batchSubscriber = DurableSubscriber.<Order>builder()
    .handler(orderProcessor::process)
    .startPaused()   // all sends go to buffer immediately
    .build();

// Accumulate a batch
orders.forEach(batchSubscriber::send);
System.out.println("Accumulated: " + batchSubscriber.bufferSize());

// Release at end of batch window
batchSubscriber.resume();
```

### Overflow Handling

```java
var overflow = new InMemoryChannel<Notification>("overflow");

var subscriber = DurableSubscriber.<Notification>builder()
    .handler(n -> processNotification(n))
    .maxBufferSize(50)
    .overflowChannel(overflow)
    .build();

subscriber.pause();
// Send 60 messages while paused — first 50 buffer, remaining 10 overflow
for (int i = 0; i < 60; i++) {
    subscriber.send(new Notification("N" + i, "body"));
}
assertThat(subscriber.bufferSize()).isEqualTo(50);
assertThat(overflow.size()).isEqualTo(10);
```

## Composition

**DurableSubscriber + PollingConsumer:**
A polling consumer feeds the durable subscriber; pausing the subscriber creates natural backpressure on the poll loop:
```
[DB source] -> PollingConsumer -> DurableSubscriber -> [Slow handler]
                                         |
                                   pause() during maintenance
```

**DurableSubscriber + ProcessMonitor:**
Monitor the subscriber's `Proc` to detect stalls:
```java
var monitor = ProcessMonitor.monitor(subscriber.proc());
// DOWN signal means subscriber crashed -> supervisor will restart
// Restart restores State from last committed snapshot
```

**DurableSubscriber + Supervisor:**
Wire under a `ONE_FOR_ONE` supervisor to auto-restart on handler crashes:
```java
var supervisor = Supervisor.builder()
    .child(subscriber.proc(), SupervisionStrategy.ONE_FOR_ONE)
    .maxRestarts(10, Duration.ofMinutes(1))
    .build();
```

**DurableSubscriber + ContentEnricher:**
Enrich each message before delivery, with the enriched message going to the subscriber:
```java
var enricher = ContentEnricher.<Order, UserProfile, EnrichedOrder>withResource(
    order -> userService.loadProfile(order.userId()),
    (order, profile) -> new EnrichedOrder(order, profile),
    errorChannel
);
var pipeline = enricher.andThen(subscriber::send);
```

**Stacked DurableSubscribers:**
Chain multiple subscribers where each can independently pause:
```java
// audit subscriber never pauses; processing subscriber can pause for maintenance
auditSubscriber.send(message);     // always delivered
processingSubscriber.send(message); // may buffer during maintenance window
```

## Test Pattern

```java
import org.acme.eip.channel.DurableSubscriber;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class DurableSubscriberTest implements WithAssertions {

    record Msg(int id, String text) {}

    CopyOnWriteArrayList<Msg> delivered;
    DurableSubscriber<Msg>    subscriber;

    @BeforeEach
    void setUp() {
        delivered = new CopyOnWriteArrayList<>();
        subscriber = DurableSubscriber.<Msg>builder()
            .handler(delivered::add)
            .build();
    }

    @Test
    void normalDelivery_handlerCalledImmediately() {
        subscriber.send(new Msg(1, "a"));
        subscriber.send(new Msg(2, "b"));

        subscriber.awaitQuiescence(Duration.ofSeconds(2));

        assertThat(delivered).extracting(Msg::id).containsExactly(1, 2);
        assertThat(subscriber.deliveredCount()).isEqualTo(2);
    }

    @Test
    void pauseBuffersMessages_resumeDrainsInOrder() {
        subscriber.send(new Msg(1, "before-pause"));
        subscriber.pause();
        subscriber.send(new Msg(2, "during-pause-1"));
        subscriber.send(new Msg(3, "during-pause-2"));
        subscriber.resume();
        subscriber.send(new Msg(4, "after-resume"));

        subscriber.awaitQuiescence(Duration.ofSeconds(2));

        assertThat(delivered).extracting(Msg::id)
            .containsExactly(1, 2, 3, 4);   // strict FIFO including across pause/resume
        assertThat(subscriber.deliveredCount()).isEqualTo(4);
        assertThat(subscriber.bufferSize()).isEqualTo(0);
    }

    @Test
    void pauseIsProcessedInMailboxOrder() throws InterruptedException {
        // Send 5 messages, then pause, then send 5 more
        for (int i = 1; i <= 5; i++) subscriber.send(new Msg(i, "pre"));
        subscriber.pause();
        for (int i = 6; i <= 10; i++) subscriber.send(new Msg(i, "post"));

        Thread.sleep(200);  // let Proc drain mailbox

        // First 5 delivered before pause took effect
        assertThat(subscriber.deliveredCount()).isEqualTo(5);
        // Last 5 in buffer
        assertThat(subscriber.bufferSize()).isEqualTo(5);
        assertThat(subscriber.peekBuffer())
            .extracting(Msg::id)
            .containsExactly(6, 7, 8, 9, 10);   // FIFO order in buffer
    }

    @Test
    void multiplePauseCycles_bufferAccumulatesCorrectly() {
        subscriber.send(new Msg(1, "cycle0"));

        for (int cycle = 1; cycle <= 3; cycle++) {
            subscriber.pause();
            subscriber.send(new Msg(cycle * 10 + 1, "cycle" + cycle + "-msg1"));
            subscriber.send(new Msg(cycle * 10 + 2, "cycle" + cycle + "-msg2"));
            subscriber.resume();
        }

        subscriber.awaitQuiescence(Duration.ofSeconds(3));

        // All messages delivered in order across all cycles
        assertThat(delivered).extracting(Msg::id)
            .containsExactly(1, 11, 12, 21, 22, 31, 32);
        assertThat(subscriber.deliveredCount()).isEqualTo(7);
    }

    @Test
    void startPaused_buffersAllMessagesUntilResume() {
        var sub = DurableSubscriber.<Msg>builder()
            .handler(delivered::add)
            .startPaused()
            .build();

        sub.send(new Msg(1, "a"));
        sub.send(new Msg(2, "b"));

        // Give Proc time to process — should buffer both
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(delivered).isEmpty();
        assertThat(sub.bufferSize()).isEqualTo(2);

        sub.resume();
        sub.awaitQuiescence(Duration.ofSeconds(2));

        assertThat(delivered).extracting(Msg::id).containsExactly(1, 2);
        sub.close();
    }

    @Test
    void overflow_messagesRoutedWhenBufferFull() {
        var overflowChannel = new org.acme.eip.channel.CapturingChannel<Msg>("overflow");
        var sub = DurableSubscriber.<Msg>builder()
            .handler(delivered::add)
            .maxBufferSize(3)
            .overflowChannel(overflowChannel)
            .build();

        sub.pause();
        sub.send(new Msg(1, "a"));
        sub.send(new Msg(2, "b"));
        sub.send(new Msg(3, "c"));
        sub.send(new Msg(4, "overflow1"));   // exceeds maxBufferSize
        sub.send(new Msg(5, "overflow2"));

        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        assertThat(sub.bufferSize()).isEqualTo(3);
        assertThat(overflowChannel.captured())
            .extracting(Msg::id).containsExactly(4, 5);

        sub.resume();
        sub.awaitQuiescence(Duration.ofSeconds(2));
        assertThat(delivered).extracting(Msg::id).containsExactly(1, 2, 3);
        sub.close();
    }

    @Test
    void isPaused_reflectsStateAfterProcProcessesCmd() throws InterruptedException {
        assertThat(subscriber.isPaused()).isFalse();

        subscriber.pause();
        Thread.sleep(50);   // wait for Proc to process Pause cmd
        assertThat(subscriber.isPaused()).isTrue();

        subscriber.resume();
        Thread.sleep(50);
        assertThat(subscriber.isPaused()).isFalse();
    }

    @Test
    void procSysIntrospection_exposesLiveState() throws InterruptedException {
        subscriber.pause();
        subscriber.send(new Msg(1, "buffered1"));
        subscriber.send(new Msg(2, "buffered2"));

        Thread.sleep(100);

        var state = org.acme.ProcSys.getState(subscriber.proc());
        assertThat(state.paused()).isTrue();
        assertThat(state.buffer()).hasSize(2);
        assertThat(state.deliveredCount()).isEqualTo(0);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- You need to decouple message arrival from message processing with explicit pause/resume control
- The subscriber undergoes maintenance windows where messages must be buffered, not dropped
- You require deterministic ordering guarantees across pause/resume cycles (no races between "start buffering" and "deliver next message")
- The handler is slow and you want to buffer upstream messages without losing them

**Avoid when:**
- You only need simple async delivery with no pause/resume — a plain `Proc` or `LinkedBlockingQueue` is simpler
- The pause duration is unbounded and messages are high-volume — the buffer will grow without bound (set `maxBufferSize` and `overflowChannel`)
- You need distributed, cross-JVM durability — the buffer is in-memory; JVM crash loses buffered messages. For cross-restart durability, use a persistent queue (database, Kafka) as the buffer backing store.

**`isPaused()` consistency:**
- `isPaused()` reads the `State` snapshot from the last `Proc` state handler execution. Between sending `pause()` and the `Proc` processing it, `isPaused()` still returns `false`. Do not use `isPaused()` as a synchronization primitive — use `awaitQuiescence()` after `resume()` to ensure the buffer has drained.

**Handler crash recovery:**
- If the handler throws during buffer drain (on `Resume`), the `Proc` crashes at that point. The partially-drained buffer is lost. The supervisor restarts the `Proc` with a fresh empty state — messages not yet drained are gone.
- For reliable drain-on-resume, make the handler idempotent and use a persistent backing store for the buffer. Alternatively, checkpoint delivered message IDs to an external store so the restarted handler can skip already-processed messages.

**`peekBuffer()` snapshot cost:**
- `peekBuffer()` acquires the `Proc` state snapshot (via `ProcSys.getState`) and copies the `ArrayDeque` to a `List`. For large buffers (thousands of messages), this copy is non-trivial. In production dashboards, poll `bufferSize()` (O(1)) rather than `peekBuffer()` (O(N)) for health metrics.

**Pause is not a lock:**
- Calling `pause()` does not prevent the caller's thread from sending more messages — it sends a `Pause` *command* to the `Proc` mailbox. Messages sent between the `pause()` call and the `Proc` processing the `Pause` command will be delivered normally. This is by design: it mirrors Erlang's message-passing semantics where there are no synchronous external mutations.
