# 20. Resequencer

> *"A gen_server with a gb_trees of pending messages and a `next_expected` counter: insert the arriving message, then walk the tree flushing every key equal to `next_expected`, incrementing after each flush. The Resequencer is that loop, made explicit and observable."*

## Intent

Accept messages arriving in arbitrary order and re-emit them in key-ascending order to a downstream channel, buffering out-of-order arrivals until the contiguous run from the current expected key can be flushed.

The Resequencer is the inverse of the Splitter: where Splitter breaks one message into many, the Resequencer reassembles many messages into a correctly-ordered stream. It is essential anywhere an unordered transport (UDP, parallel HTTP, partitioned Kafka with no per-partition ordering guarantee across partitions) feeds an order-sensitive consumer.

## OTP Analogy

The canonical Erlang implementation is a `gen_server` with `gb_trees` (Erlang's balanced binary tree, equivalent to Java's `TreeMap`) as the buffer:

```erlang
-record(state, {
    buffer        :: gb_trees:tree(Key, Msg),
    next_expected :: Key,
    downstream    :: pid()
}).

handle_cast({deliver, Key, Msg}, #state{buffer=B, next_expected=NE, downstream=DS}=S) ->
    NewBuffer = gb_trees:insert(Key, Msg, B),
    {Flushed, FinalBuffer, FinalNE} = flush(NewBuffer, NE, DS),
    {noreply, S#state{buffer=FinalBuffer, next_expected=FinalNE}};

flush(Buffer, NE, DS) ->
    case gb_trees:lookup(NE, Buffer) of
        {value, Msg} ->
            DS ! Msg,
            flush(gb_trees:delete(NE, Buffer), NE + 1, DS);
        none ->
            {ok, Buffer, NE}
    end.
```

This is a pure state transition: the state before and after each `handle_cast` is fully determined by the message content and previous state. No side effects other than sending to downstream. The Java `Proc<State<T,K>, Entry<T,K>>` maps directly:

- `gb_trees` → `TreeMap<K, T>` (sorted by natural key order)
- `next_expected` → field in the `State` record
- `flush` loop → the `while (buffer.containsKey(nextExpected))` drain in the state handler
- `DS ! Msg` → `downstream.send(msg)`

The `Proc` wrapper provides the mailbox and virtual-thread isolation. The state handler is a pure function: `(State, Entry) -> State`. No shared mutable state, no locks.

## JOTP Implementation

**Class:** `Resequencer<T, K extends Comparable<K>>`
**Package:** `org.acme.eip.routing`
**Key design decisions:**

1. **`Proc<State<T,K>, Entry<T,K>>`** — the resequencer is a supervised process. Its state is held inside the `Proc` mailbox loop, never shared. A supervisor can restart it (losing only buffered-but-not-flushed messages — acceptable since the upstream will retransmit on reconnect).

2. **`TreeMap<K, T>`** — sorted map ensures O(log N) insert and O(1) `firstKey()` check. `nextExpected` is compared with `firstKey()` on every step to detect the flush condition cheaply.

3. **Pure state handler** — the `Proc` state handler is a `BiFunction<State<T,K>, Entry<T,K>, State<T,K>>`. It inserts the entry into the tree, flushes the contiguous run starting at `nextExpected`, and returns the new state. No I/O in the handler (downstream.send is the only side effect, and channels are designed to be non-blocking).

4. **Key supplier** — the caller provides a `Function<T, K> keyExtractor` at construction time. This decouples the resequencing logic from the message schema. Key type `K` must implement `Comparable<K>` for natural `TreeMap` ordering.

5. **Gap detection** — an optional `gapTimeout` triggers a "flush-and-skip" when a gap in the sequence is not filled within the timeout window. Without gap detection, a single dropped message would stall the resequencer forever. The timeout is implemented as a `ProcTimer` message sent to the `Proc`.

6. **`ProcSys` introspection** — since the resequencer is a `Proc`, `ProcSys.getState(proc())` returns a live snapshot of `State<T,K>` including the current buffer contents and `nextExpected`, enabling operational visibility without adding custom management endpoints.

## API Reference

### `Resequencer<T, K>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `send` | `void send(T message)` | Deliver a message; key extracted automatically |
| `resequencedCount` | `long resequencedCount()` | Total messages flushed to downstream in order |
| `pendingCount` | `int pendingCount()` | Current number of buffered (not yet flushed) messages |
| `nextExpected` | `K nextExpected()` | The key this resequencer is currently waiting for |
| `proc` | `ProcRef<State<T,K>, Entry<T,K>> proc()` | Raw `ProcRef` for supervisor wiring and `ProcSys` introspection |
| `awaitQuiescence` | `void awaitQuiescence(Duration timeout)` | Block until pending buffer is empty (useful in tests) |
| `close` | `void close()` | Terminate the backing `Proc` and release resources |

### `Resequencer.Builder<T, K>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `keyExtractor` | `Builder<T,K> keyExtractor(Function<T,K> fn)` | Required: extract sequence key from message |
| `startingKey` | `Builder<T,K> startingKey(K key)` | Initial `nextExpected` value (default: first key received if natural start; set explicitly for gapless runs) |
| `downstream` | `Builder<T,K> downstream(MessageChannel<T> ch)` | Required: channel to emit ordered messages to |
| `gapTimeout` | `Builder<T,K> gapTimeout(Duration d)` | If set, skip missing keys after this timeout |
| `onGap` | `Builder<T,K> onGap(Consumer<K> handler)` | Called when a gap timeout fires (for alerting/logging) |
| `build` | `Resequencer<T,K> build()` | Construct and start the backing `Proc` |

### `State<T, K>` (internal, inspectable via ProcSys)

```java
record State<T, K extends Comparable<K>>(
    TreeMap<K, T>   buffer,
    K               nextExpected,
    long            resequencedCount,
    MessageChannel<T> downstream
) {}
```

### `Entry<T, K>` (message type sent to Proc)

```java
sealed interface Entry<T, K> {
    record Deliver<T, K>(K key, T message) implements Entry<T, K> {}
    record GapTimeout<T, K>(K missingKey)  implements Entry<T, K> {}
}
```

## Implementation Internals

```
Resequencer construction:
├── Proc<State<T,K>, Entry<T,K>> created with initial State(emptyTreeMap, startingKey, 0, downstream)
└── Proc started on virtual thread

State handler (pure function: State × Entry -> State):
│
├── case Entry.Deliver(key, msg):
│   ├── newBuffer = state.buffer + (key -> msg)         // TreeMap.put()
│   ├── (flushedBuffer, flushedCount) = flush(newBuffer, state.nextExpected)
│   └── return State(flushedBuffer, nextExpectedAfterFlush, state.resequencedCount + flushedCount, downstream)
│
└── case Entry.GapTimeout(missingKey):
    ├── if missingKey == state.nextExpected (gap is still open):
    │   ├── skip nextExpected; advance to nextExpected + 1 (or next key in buffer)
    │   ├── call onGap(missingKey)
    │   └── run flush from new nextExpected
    └── else: gap was filled; ignore timeout (stale)

flush(buffer, nextExpected):
│
├── while buffer.containsKey(nextExpected):
│   ├── msg = buffer.remove(nextExpected)
│   ├── downstream.send(msg)          // side effect in state handler
│   ├── nextExpected = successor(nextExpected)
│   └── flushedCount++
│
└── return (buffer, nextExpected, flushedCount)

successor(K key):
├── for K = Long/Integer: key + 1
├── for K = String: look up next key in buffer's firstKey() after key
└── custom: provided via comparator + NavigableMap.higherKey()
```

**Flush loop correctness:** The flush loop terminates because:
1. Each iteration removes one entry from `buffer` — the buffer strictly shrinks
2. `nextExpected` strictly increases — no cycle is possible
3. The while condition checks `containsKey` — terminates immediately on a gap

**Memory bound:** In the worst case (all messages arrive in reverse order), the buffer grows to N entries before flushing begins. For very long sequences with adversarial ordering, set `gapTimeout` to prevent unbounded buffer growth.

## Code Example

```java
import org.acme.eip.routing.Resequencer;
import org.acme.eip.channel.CapturingChannel;
import java.time.Duration;

// --- Domain ---
record LogEntry(long sequenceNo, String level, String text) {}

// --- Downstream ---
var ordered = new CapturingChannel<LogEntry>("ordered-log");

// --- Build resequencer ---
var resequencer = Resequencer.<LogEntry, Long>builder()
    .keyExtractor(LogEntry::sequenceNo)
    .startingKey(1L)
    .downstream(ordered)
    .gapTimeout(Duration.ofSeconds(5))
    .onGap(key -> System.err.println("Gap detected at seq=" + key + ", skipping"))
    .build();

// --- Simulate out-of-order delivery (network reorder) ---
// Send seq 3, 1, 5, 2, 4  (out of order)
resequencer.send(new LogEntry(3, "INFO",  "connected"));
resequencer.send(new LogEntry(1, "INFO",  "started"));     // triggers flush of 1, then waits for 2
resequencer.send(new LogEntry(5, "ERROR", "disk full"));
resequencer.send(new LogEntry(2, "INFO",  "configured")); // triggers flush of 2, then waits for 3
resequencer.send(new LogEntry(4, "WARN",  "high cpu"));   // triggers flush of 3,4,5

resequencer.awaitQuiescence(Duration.ofSeconds(1));

// All messages emitted in sequence order
var entries = ordered.captured();
System.out.println("Received " + entries.size() + " entries in order:");
entries.forEach(e -> System.out.printf("  [%d] %s: %s%n",
    e.sequenceNo(), e.level(), e.text()));

// Output:
// Received 5 entries in order:
//   [1] INFO: started
//   [2] INFO: configured
//   [3] INFO: connected
//   [4] WARN: high cpu
//   [5] ERROR: disk full

System.out.println("Resequenced: " + resequencer.resequencedCount());  // 5
System.out.println("Pending:     " + resequencer.pendingCount());       // 0
```

### ProcSys Introspection

```java
import org.acme.ProcSys;

// Inspect the live state of the resequencer without stopping it
var snapshot = ProcSys.getState(resequencer.proc());
System.out.println("Next expected: " + snapshot.nextExpected());
System.out.println("Buffered keys: " + snapshot.buffer().keySet());
System.out.println("Resequenced:   " + snapshot.resequencedCount());

// Useful in operations dashboards: buffer size > threshold -> alert
if (snapshot.buffer().size() > 1000) {
    alerting.fire("resequencer buffer overflow risk: " + snapshot.buffer().size());
}
```

### Gap Timeout Handling

```java
// A message with seq=3 is never sent — simulating a dropped packet
resequencer.send(new LogEntry(1, "INFO", "a"));
resequencer.send(new LogEntry(2, "INFO", "b"));
// seq=3 dropped
resequencer.send(new LogEntry(4, "INFO", "d"));
resequencer.send(new LogEntry(5, "INFO", "e"));

// After gapTimeout elapses:
// onGap(3) fires
// Resequencer advances nextExpected to 4 and flushes 4, 5
// Output order: 1, 2, 4, 5  (3 skipped with alert)
```

## Composition

**Resequencer + Splitter:**
Split a batch, process items in parallel (potentially reordering), resequence before aggregation:
```
BatchMessage -> Splitter -> [parallel processor pool] -> Resequencer -> Aggregator
```

**Resequencer + Competing Consumers:**
Multiple consumers pull from a channel in parallel; resequencer restores order before the ordered downstream:
```
Channel -> [Consumer1, Consumer2, Consumer3] -> Resequencer(seqKey) -> OrderedChannel
```

**Resequencer + ProcessMonitor:**
Monitor the resequencer's `Proc` to detect stalls (proc alive but resequenced count not incrementing):
```java
var monitor = ProcessMonitor.monitor(resequencer.proc());
// If DOWN message arrives, resequencer crashed -> supervisor will restart
```

**Resequencer + ProcTimer (manual gap detection):**
For key types where gap detection requires custom logic (non-integer sequences):
```java
// Send a GapCheck message every 10 seconds to verify progress
var timer = ProcTimer.sendInterval(resequencer.proc(),
    new Entry.GapTimeout<>(resequencer.nextExpected()),
    Duration.ofSeconds(10));
```

## Test Pattern

```java
import org.acme.eip.routing.Resequencer;
import org.acme.eip.channel.CapturingChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

class ResequencerTest implements WithAssertions {

    record Packet(int seq, String data) {}

    @Test
    void reorderedMessagesAreEmittedInOrder() {
        var out = new CapturingChannel<Packet>("out");

        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(1)
            .downstream(out)
            .build();

        // Deliver out of order
        reseq.send(new Packet(3, "c"));
        reseq.send(new Packet(1, "a"));
        reseq.send(new Packet(2, "b"));

        reseq.awaitQuiescence(Duration.ofSeconds(2));

        assertThat(out.captured())
            .extracting(Packet::seq)
            .containsExactly(1, 2, 3);
        assertThat(out.captured())
            .extracting(Packet::data)
            .containsExactly("a", "b", "c");
    }

    @Test
    void messagesBeforeStartingKeyAreBufferedUntilGapFills() {
        var out = new CapturingChannel<Packet>("out");

        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(5)   // start at 5, not 1
            .downstream(out)
            .build();

        reseq.send(new Packet(7, "g"));
        reseq.send(new Packet(5, "e"));
        reseq.send(new Packet(6, "f"));

        reseq.awaitQuiescence(Duration.ofSeconds(2));

        assertThat(out.captured()).extracting(Packet::seq)
            .containsExactly(5, 6, 7);
    }

    @Test
    void alreadyOrderedMessagesPassThrough() {
        var out = new CapturingChannel<Packet>("out");
        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(1)
            .downstream(out)
            .build();

        IntStream.rangeClosed(1, 10)
                 .mapToObj(i -> new Packet(i, "msg-" + i))
                 .forEach(reseq::send);

        reseq.awaitQuiescence(Duration.ofSeconds(2));

        assertThat(out.captured()).extracting(Packet::seq)
            .containsExactlyElementsOf(
                IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toList()));
    }

    @Test
    void reverseOrderDelivery_flushesOnlyWhenFirstArrives() {
        var out = new CapturingChannel<Packet>("out");
        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(1)
            .downstream(out)
            .build();

        // Send in reverse order: 5, 4, 3, 2, 1
        for (int i = 5; i >= 1; i--) {
            reseq.send(new Packet(i, "msg-" + i));
            // Nothing should be emitted until seq=1 arrives
            if (i > 1) {
                assertThat(out.captured()).isEmpty();
                assertThat(reseq.pendingCount()).isEqualTo(6 - i);
            }
        }

        reseq.awaitQuiescence(Duration.ofSeconds(2));
        // seq=1 was last; triggers flush of all 5
        assertThat(out.captured()).extracting(Packet::seq)
            .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void pendingCountAndResequencedCountAreAccurate() {
        var out = new CapturingChannel<Packet>("out");
        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(1)
            .downstream(out)
            .build();

        reseq.send(new Packet(3, "c"));
        reseq.send(new Packet(5, "e"));
        // nextExpected=1, buffer has {3,5}, nothing flushed yet

        // Give the proc time to process both messages
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(reseq.pendingCount()).isEqualTo(2);
        assertThat(reseq.resequencedCount()).isEqualTo(0);

        reseq.send(new Packet(1, "a"));   // triggers flush of 1; waits for 2
        reseq.send(new Packet(2, "b"));   // triggers flush of 2, 3; waits for 4
        reseq.send(new Packet(4, "d"));   // triggers flush of 4, 5

        reseq.awaitQuiescence(Duration.ofSeconds(2));
        assertThat(reseq.pendingCount()).isEqualTo(0);
        assertThat(reseq.resequencedCount()).isEqualTo(5);
    }

    @Test
    void gapTimeout_skipsAndContinues() throws InterruptedException {
        var out    = new CapturingChannel<Packet>("out");
        var gapped = new java.util.concurrent.CopyOnWriteArrayList<Integer>();

        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(1)
            .downstream(out)
            .gapTimeout(Duration.ofMillis(200))   // short timeout for test
            .onGap(gapped::add)
            .build();

        reseq.send(new Packet(1, "a"));
        reseq.send(new Packet(2, "b"));
        // seq=3 is never sent (dropped)
        reseq.send(new Packet(4, "d"));
        reseq.send(new Packet(5, "e"));

        Thread.sleep(500);  // wait for gap timeout

        assertThat(out.captured()).extracting(Packet::seq)
            .containsExactly(1, 2, 4, 5);   // 3 skipped
        assertThat(gapped).containsExactly(3);
    }

    @Test
    void procSysIntrospection_exposesLiveState() {
        var out = new CapturingChannel<Packet>("out");
        var reseq = Resequencer.<Packet, Integer>builder()
            .keyExtractor(Packet::seq)
            .startingKey(1)
            .downstream(out)
            .build();

        reseq.send(new Packet(3, "c"));
        reseq.send(new Packet(2, "b"));

        // Give proc time to process
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        var state = org.acme.ProcSys.getState(reseq.proc());
        assertThat(state.nextExpected()).isEqualTo(1);
        assertThat(state.buffer()).containsKeys(2, 3);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Messages arrive from a transport that does not guarantee ordering (UDP, parallel processing, Kafka multi-partition consumers)
- The consumer downstream is order-sensitive (event sourcing, audit logs, database replay)
- You can tolerate bounded buffering — the resequencer buffers messages until the contiguous prefix is complete

**Avoid when:**
- Messages arrive mostly in order with rare inversions — a simpler "allow one-step lookahead" buffer may suffice
- The key space is sparse and gaps are common — every gap causes buffering until the gap fills or the timeout fires, consuming memory
- Throughput is so high that the `Proc` mailbox becomes a bottleneck — at extreme scale, partition by key range and run multiple resequencers in parallel

**Memory management:**
- Without `gapTimeout`, a dropped message stalls the resequencer indefinitely, buffering all subsequent messages
- Always configure `gapTimeout` in production. A reasonable default is 2-5× the expected maximum reordering window (e.g., if messages arrive out-of-order by at most 500ms, set `gapTimeout(Duration.ofSeconds(2))`)
- Monitor `pendingCount()` via `ProcSys`; alert when it exceeds your SLA-defined threshold

**Key type selection:**
- Use `Long` for sequence numbers — successor is trivially `key + 1`
- For string or UUID keys where "successor" is not defined, use a `NavigableSet<K>` for the expected-key tracking and flush by walking the sorted buffer until a gap is found
- Custom key types must implement `Comparable<K>` and define a total order consistent with message arrival intent

**Delivery guarantee:**
- The resequencer provides at-most-once delivery to downstream by default (if the `Proc` crashes between flush and delivery, the message is lost)
- For at-least-once semantics, wrap `downstream.send()` in a transactional outbox or use a durable channel implementation
