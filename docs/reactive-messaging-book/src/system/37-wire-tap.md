# 37. Wire Tap

> *"`sys:trace(Pid, true)` attaches a trace handler that receives a copy of every message sent to the process — the process never knows it is being observed, and a crashing trace handler does not kill the process. The Wire Tap is `sys:trace` made composable, stackable, and production-safe."*

## Intent

Intercept a copy of every message flowing through a channel and deliver it to a secondary consumer (tap) on a virtual thread, without affecting the primary channel's delivery path. Tap failures are contained, tap activation is runtime-controllable, and multiple taps can be stacked without modifying the original channel.

The Wire Tap is the system-plumbing pattern for observability, auditing, and debugging in production: attach it to any channel to see every message without changing the producer, the channel, or the primary consumer.

## OTP Analogy

Erlang's `sys` module provides `sys:trace(Pid, true)` which enables a tracing mode where every message and state transition in a `gen_server` is echoed to the trace handler:

```erlang
%% Enable trace on a running gen_server
sys:trace(my_server, true).
%% Every handle_call/handle_cast/handle_info is now echoed to the error_logger

%% Disable trace
sys:trace(my_server, false).
```

The Wire Tap mirrors this at the channel level:
- `WireTap.activate()` / `WireTap.deactivate()` ↔ `sys:trace(Pid, true/false)`
- Tap on virtual thread ↔ `error_logger` process receives trace copies
- Primary channel unaffected by tap failure ↔ gen_server unaffected by logger crash
- Stacked taps ↔ multiple trace handlers in `sys:install`

The key OTP lesson: observation must be **non-intrusive**. The traced process does not need to know about the trace handler. The Wire Tap extends this to channels: the original channel does not need to be modified or even aware of the tap. The tap is installed in the channel reference wrapper, not in the channel implementation.

**`sys:install` for custom handlers:**
```erlang
%% OTP's sys:install/2 installs a custom handler that receives every event
sys:install(Pid, {fun my_trace_handler/3, InitState}).
```

`WireTap.stacked(channel, tap1, tap2, tap3)` is the direct equivalent: install multiple handlers, each receiving a copy of every message.

## JOTP Implementation

**Class:** `WireTap<T>`
**Package:** `org.acme.eip.system`
**Key design decisions:**

1. **Implements `MessageChannel<T>`** — `WireTap<T>` wraps a primary `MessageChannel<T>` and itself implements `MessageChannel<T>`. Any code expecting a `MessageChannel<T>` can transparently receive a `WireTap<T>` — zero changes to producers.

2. **Virtual thread per tap invocation** — each `send(T)` call spawns a virtual thread to call `tap.accept(message)`. The primary channel `send` completes on the calling thread without waiting for the tap. Tap latency is completely decoupled from primary delivery latency.

3. **Tap failure containment** — the virtual thread wrapping each tap invocation catches all `Throwable` from the tap and logs it to a `Consumer<TapError<T>>` error handler (or `System.err` if none provided). A crashing tap never propagates to the primary channel or the calling thread.

4. **`activate()`/`deactivate()`** — an `AtomicBoolean` controls whether the tap fires. Deactivated taps skip the virtual thread spawn entirely — zero overhead when inactive.

5. **Stackable composition** — `WireTap.stacked(primary, tap1, tap2, tap3)` builds a chain of `WireTap` wrappers, each adding one tap. The resulting object is itself a `MessageChannel<T>`. Since each `WireTap` wraps another `MessageChannel<T>`, stacking is pure composition.

6. **Tap receives a deep copy** — for mutable message types, the builder accepts a `Function<T, T> copyFn` that deep-copies the message before passing it to the tap. This prevents the tap from seeing mutations that the primary handler applies to the message after the tap copy is made. For immutable records (the recommended Java 26 style), `copyFn` defaults to identity.

## API Reference

### `WireTap<T>` (implements `MessageChannel<T>`)

| Method | Signature | Description |
|--------|-----------|-------------|
| `send` | `void send(T message)` | Send to primary; fork tap on virtual thread if active |
| `activate` | `WireTap<T> activate()` | Enable the tap (default: enabled at construction) |
| `deactivate` | `WireTap<T> deactivate()` | Disable the tap (primary channel still receives) |
| `isActive` | `boolean isActive()` | Current tap activation state |
| `tapCount` | `long tapCount()` | Total messages delivered to this tap |
| `tapErrorCount` | `long tapErrorCount()` | Total tap failures (primary delivery unaffected) |
| `primary` | `MessageChannel<T> primary()` | The wrapped primary channel |
| `tap` | `Consumer<T> tap()` | The tap consumer |

### Factory methods

```java
// Single tap
WireTap<T> tap = WireTap.of(primaryChannel, tapConsumer);

// Single tap with custom copy function
WireTap<T> tap = WireTap.of(primaryChannel, tapConsumer, msg -> msg.deepCopy());

// Single tap with error handler
WireTap<T> tap = WireTap.of(primaryChannel, tapConsumer, err -> log.error("tap failed", err));

// Stacked taps (returns MessageChannel<T>, type is outer WireTap)
MessageChannel<T> stacked = WireTap.stacked(primaryChannel, tap1, tap2, tap3);

// Builder API for full configuration
WireTap<T> tap = WireTap.<T>builder()
    .primary(primaryChannel)
    .tap(tapConsumer)
    .copyFn(msg -> msg.deepCopy())
    .onTapError(err -> metrics.increment("tap.error"))
    .startDeactivated()
    .build();
```

### `TapError<T>` (record)

```java
record TapError<T>(T message, Throwable cause, Instant timestamp) {}
```

## Implementation Internals

```
WireTap<T> internals:
├── MessageChannel<T>  primary         (wrapped channel)
├── Consumer<T>        tap             (tap consumer)
├── AtomicBoolean      active          (enable/disable flag)
├── LongAdder          tapCount        (successful tap invocations)
├── LongAdder          tapErrorCount   (failed tap invocations)
├── Function<T,T>      copyFn          (message copy, default: identity)
└── Consumer<TapError<T>> onError      (tap failure handler)

send(T message):
├── primary.send(message)              // ALWAYS called first, on calling thread
│                                      // if primary throws, tap is NOT spawned
│
└── if active.get():
    └── Thread.ofVirtual().start(() -> {
            T copy = copyFn.apply(message)
            try:
                tap.accept(copy)
                tapCount.increment()
            catch Throwable t:
                tapErrorCount.increment()
                onError.accept(new TapError(copy, t, Instant.now()))
        })

WireTap.stacked(primary, tap1, tap2, tap3):
└── new WireTap(
        new WireTap(
            new WireTap(primary, tap1),
            tap2),
        tap3)
```

**Primary-first guarantee:** `primary.send(message)` is always called before the tap virtual thread is spawned. If the primary throws, the tap is never invoked — a failed primary delivery is not observed by taps. This mirrors `sys:trace` behavior: the trace only fires for messages that were actually delivered to the process.

**Tap ordering with stacked taps:** Each `WireTap` in a stack spawns its tap on a separate virtual thread. Taps may execute in any order relative to each other (the JVM scheduler decides). If tap execution order matters, chain them in a single `Consumer<T>` rather than stacking separate `WireTap` wrappers:
```java
Consumer<T> orderedTap = msg -> { tap1.accept(msg); tap2.accept(msg); };
var wt = WireTap.of(primary, orderedTap);
```

## Code Example

```java
import org.acme.eip.system.WireTap;
import org.acme.eip.channel.InMemoryChannel;

// --- Domain ---
record Payment(String id, double amount, String currency) {}

// --- Primary channel ---
var paymentProcessor = new InMemoryChannel<Payment>("payment-processor");

// --- Tap 1: Audit log ---
Consumer<Payment> auditTap = payment ->
    auditLog.record("PAYMENT_RECEIVED", payment.id(), payment.amount());

// --- Tap 2: Real-time metrics ---
Consumer<Payment> metricsTap = payment ->
    metrics.record("payment.amount", payment.amount(),
                   "currency", payment.currency());

// --- Tap 3: Fraud detection (async, can be slow) ---
Consumer<Payment> fraudTap = payment -> {
    var score = fraudDetector.score(payment);  // may be slow
    if (score > 0.8) alerting.fire("HIGH_FRAUD_RISK", payment.id());
};

// --- Stack all three taps on the primary channel ---
MessageChannel<Payment> observed = WireTap.stacked(
    paymentProcessor,
    auditTap,
    metricsTap,
    fraudTap
);

// --- Producers use the observed channel; no changes needed ---
// All three taps fire for every payment sent through observed
observed.send(new Payment("PAY-001", 150.00, "USD"));
observed.send(new Payment("PAY-002", 9500.00, "EUR"));
observed.send(new Payment("PAY-003", 0.01,   "USD"));

Thread.sleep(200);  // let virtual threads complete

// tap counts accessible from individual WireTap references if built manually
// (stacked factory returns MessageChannel<T> for simplicity)
```

### Activate/Deactivate for Debug Taps

```java
// Debug tap — only active when debugging is enabled
var debugTap = WireTap.<Payment>builder()
    .primary(paymentChannel)
    .tap(p -> System.out.println("[DEBUG] " + p))
    .startDeactivated()   // starts off
    .build();

// In production: debug tap adds zero overhead
debugTap.send(new Payment("PAY-001", 100.0, "USD"));   // tap skipped

// Toggle on during incident investigation
debugTap.activate();
debugTap.send(new Payment("PAY-002", 200.0, "USD"));   // [DEBUG] Payment[id=PAY-002, ...]
// Toggle off when done
debugTap.deactivate();
```

### Tap with Error Channel

```java
var tapErrorChannel = new InMemoryChannel<WireTap.TapError<Payment>>("tap-errors");

var wt = WireTap.<Payment>builder()
    .primary(paymentChannel)
    .tap(p -> unreliableAuditService.record(p))   // may throw
    .onTapError(err -> {
        tapErrorChannel.send(err);
        log.warn("Tap failed for {}: {}", err.message().id(), err.cause().getMessage());
    })
    .build();

// Tap failures go to tapErrorChannel; primary delivery is unaffected
wt.send(new Payment("PAY-X", 500.0, "GBP"));
```

### Stacked Taps with Independent Activation

```java
// Build individual WireTap references for independent control
var metricsTapRef = WireTap.of(paymentChannel, metricsTap);
var auditTapRef   = WireTap.of(metricsTapRef, auditTap);
var debugTapRef   = WireTap.<Payment>builder()
    .primary(auditTapRef)
    .tap(p -> System.out.println("[DEBUG] " + p))
    .startDeactivated()
    .build();

// Route all producers through debugTapRef
// To enable debug at runtime:
debugTapRef.activate();
// To disable:
debugTapRef.deactivate();
// Metrics and audit continue unaffected by debug toggle
```

### Wire Tap as Throughput Observer

```java
// Count messages per second through a hot channel
var messageRate = new LongAdder();
var startTime   = Instant.now();

var rateTap = WireTap.of(hotChannel, msg -> messageRate.increment());

// In a monitoring thread:
scheduler.scheduleAtFixedRate(() -> {
    long count   = messageRate.sumThenReset();
    double elapsed = Duration.between(startTime, Instant.now()).toSeconds();
    System.out.printf("Throughput: %.0f msg/s%n", count / Math.max(elapsed, 1));
}, 1, 1, TimeUnit.SECONDS);
```

## Composition

**WireTap + DurableSubscriber:**
Tap messages as they enter a durable subscriber for audit, even when the subscriber is paused:
```java
var durableSub = DurableSubscriber.<Order>builder().handler(orderService::process).build();
var tapped = WireTap.of(durableSub.asChannel(), order -> auditLog.record(order));
// All orders — including those buffered during pause — are tapped before buffering
```

**WireTap + MessageRouter:**
Tap the dead-letter output of a router to alert on unrouted messages:
```java
var deadLetterWithAlert = WireTap.of(deadLetterChannel,
    msg -> alerting.fire("UNROUTED_MESSAGE", msg));
var router = MessageRouter.<Order>builder()
    .route("normal", order -> order.amount() > 0, processingChannel)
    .deadLetter(deadLetterWithAlert)   // swap in tapped dead-letter
    .build();
```

**WireTap + PollingConsumer:**
Observe every polled message without modifying the handler:
```java
// Wrap the handler channel with a wire tap
Consumer<Task> tapAndHandle = WireTap.of(handlerChannel,
    task -> metrics.count("polled"))::send;
var consumer = PollingConsumer.<Task>builder()
    .source(db::pollNext)
    .handler(tapAndHandle)
    .build();
```

**WireTap + Resequencer:**
Tap the resequencer's downstream to verify ordering in production:
```java
var lastSeq = new AtomicLong(0);
var orderVerifyTap = WireTap.of(resequencerDownstream, msg -> {
    long seq  = msg.sequenceNo();
    long prev = lastSeq.getAndSet(seq);
    if (seq <= prev) {
        alerting.fire("OUT_OF_ORDER", "seq=" + seq + " after seq=" + prev);
    }
});
```

**WireTap + ContentEnricher:**
Observe messages before and after enrichment for before/after comparison:
```java
var beforeTap = WireTap.of(enricherInput,  msg -> log.debug("before: {}", msg));
var afterTap  = WireTap.of(enricherOutput, msg -> log.debug("after:  {}", msg));
```

## Test Pattern

```java
import org.acme.eip.system.WireTap;
import org.acme.eip.channel.CapturingChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class WireTapTest implements WithAssertions {

    record Msg(int id, String value) {}

    @Test
    void tapReceivesCopyOfEveryMessage() throws InterruptedException {
        var primary = new CapturingChannel<Msg>("primary");
        var tapped  = new CopyOnWriteArrayList<Msg>();
        var latch   = new CountDownLatch(3);

        var wt = WireTap.of(primary, msg -> { tapped.add(msg); latch.countDown(); });

        wt.send(new Msg(1, "a"));
        wt.send(new Msg(2, "b"));
        wt.send(new Msg(3, "c"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(primary.captured()).extracting(Msg::id).containsExactly(1, 2, 3);
        assertThat(tapped).extracting(Msg::id).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void tapFailure_doesNotAffectPrimaryDelivery() throws InterruptedException {
        var primary = new CapturingChannel<Msg>("primary");
        var errors  = new CopyOnWriteArrayList<WireTap.TapError<Msg>>();
        var latch   = new CountDownLatch(2);

        var wt = WireTap.<Msg>builder()
            .primary(primary)
            .tap(msg -> { throw new RuntimeException("tap exploded"); })
            .onTapError(err -> { errors.add(err); latch.countDown(); })
            .build();

        wt.send(new Msg(1, "a"));
        wt.send(new Msg(2, "b"));

        latch.await(2, TimeUnit.SECONDS);

        assertThat(primary.captured()).hasSize(2);  // primary unaffected
        assertThat(errors).hasSize(2);
        assertThat(wt.tapErrorCount()).isEqualTo(2);
    }

    @Test
    void deactivate_stopsInvokingTap() throws InterruptedException {
        var primary  = new CapturingChannel<Msg>("primary");
        var tapCount = new AtomicInteger();
        var latch    = new CountDownLatch(1);

        var wt = WireTap.of(primary, msg -> { tapCount.incrementAndGet(); latch.countDown(); });

        wt.send(new Msg(1, "a"));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(tapCount.get()).isEqualTo(1);

        wt.deactivate();
        assertThat(wt.isActive()).isFalse();

        wt.send(new Msg(2, "b"));
        wt.send(new Msg(3, "c"));
        Thread.sleep(100);

        assertThat(tapCount.get()).isEqualTo(1);   // no new taps after deactivate
        assertThat(primary.captured()).hasSize(3); // primary still receives all

        wt.activate();
        assertThat(wt.isActive()).isTrue();
    }

    @Test
    void stackedTaps_allFireForEveryMessage() throws InterruptedException {
        var primary  = new CapturingChannel<Msg>("primary");
        var tap1Msgs = new CopyOnWriteArrayList<Msg>();
        var tap2Msgs = new CopyOnWriteArrayList<Msg>();
        var tap3Msgs = new CopyOnWriteArrayList<Msg>();
        var latch    = new CountDownLatch(9);  // 3 taps x 3 messages

        var stacked = WireTap.stacked(
            primary,
            msg -> { tap1Msgs.add(msg); latch.countDown(); },
            msg -> { tap2Msgs.add(msg); latch.countDown(); },
            msg -> { tap3Msgs.add(msg); latch.countDown(); }
        );

        stacked.send(new Msg(1, "x"));
        stacked.send(new Msg(2, "y"));
        stacked.send(new Msg(3, "z"));

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(primary.captured()).hasSize(3);
        assertThat(tap1Msgs).hasSize(3);
        assertThat(tap2Msgs).hasSize(3);
        assertThat(tap3Msgs).hasSize(3);
    }

    @Test
    void tapCount_accurate() throws InterruptedException {
        var primary = new CapturingChannel<Msg>("primary");
        var latch   = new CountDownLatch(5);
        var wt      = WireTap.of(primary, msg -> latch.countDown());

        for (int i = 0; i < 5; i++) wt.send(new Msg(i, "val"));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(wt.tapCount()).isEqualTo(5);
        assertThat(wt.tapErrorCount()).isEqualTo(0);
    }

    @Test
    void primaryThrows_tapNotInvoked() throws InterruptedException {
        var tapInvoked = new AtomicInteger();
        var wt = WireTap.of(
            (MessageChannel<Msg>) msg -> { throw new RuntimeException("primary failed"); },
            msg -> tapInvoked.incrementAndGet()
        );

        assertThatThrownBy(() -> wt.send(new Msg(1, "x")))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("primary failed");

        Thread.sleep(50);
        assertThat(tapInvoked.get()).isEqualTo(0);  // tap never fired
    }

    @Test
    void concurrentSends_noDataRace() throws InterruptedException {
        var primary  = new CapturingChannel<Msg>("primary");
        var tapCount = new AtomicInteger();
        var latch    = new CountDownLatch(1000);

        var wt = WireTap.of(primary, msg -> { tapCount.incrementAndGet(); latch.countDown(); });

        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 1000; i++) {
            final int id = i;
            executor.submit(() -> wt.send(new Msg(id, "concurrent")));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(primary.captured()).hasSize(1000);
        assertThat(tapCount.get()).isEqualTo(1000);
        assertThat(wt.tapCount()).isEqualTo(1000);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- You need to observe messages flowing through a channel without modifying producers or consumers
- You want audit logging, metrics recording, or fraud detection to be completely decoupled from the primary processing path
- Tap latency must not block or slow the primary delivery path
- You need to activate/deactivate observation at runtime (debug taps, A/B test observers, temporary audit windows)

**Avoid when:**
- The tap must guarantee delivery (e.g., regulatory audit) — virtual thread failure can lose tap events. Use a durable channel for the tap instead of a bare `Consumer<T>`.
- Tap ordering relative to other taps matters and must be guaranteed — use a single ordered `Consumer` instead of stacked `WireTap` wrappers.
- The tap is computationally intensive and produces work proportional to the primary — spawning a virtual thread per message under extreme load can saturate the thread scheduler. Consider batching tap events or using a single tap consumer with a `BlockingQueue`.

**Virtual thread overhead:**
- Each active `WireTap.send()` spawns one virtual thread. For very high-throughput channels (millions of messages per second), this overhead is measurable. Profile before adding taps to hot paths.
- Virtual threads are cheap (no OS thread created), but the scheduler has finite capacity. Under extreme load (10M+ msg/s), consider a single tap consumer thread with a `LinkedBlockingQueue` instead of per-message virtual thread spawning.

**Message immutability:**
- If the message type is mutable and the tap is invoked concurrently with the primary consumer, there is a potential race on the message object. Use immutable records (Java 26 best practice) to eliminate this entirely, or provide a `copyFn` to the builder.
- The `copyFn` is called *before* spawning the tap virtual thread, on the calling thread. This adds synchronous copy overhead on the hot path. Prefer immutable records over copy functions.

**Tap vs. audit channel:**
- A `WireTap` with a bare `Consumer<T>` is appropriate for ephemeral observations (metrics, logging).
- For durable audit trails, route the tap through a `DurableSubscriber` or persistent channel:
  ```java
  var auditSub = DurableSubscriber.<T>builder()
      .handler(auditStore::record).build();
  var wt = WireTap.of(primary, auditSub::send);
  ```
  This ensures the audit record persists even if the audit writer is slow or temporarily unavailable.

**`deactivate()` is not instantaneous:**
- `deactivate()` sets an `AtomicBoolean`. Virtual threads already spawned before `deactivate()` was called will still complete. The tap may fire a few more times after `deactivate()` due to in-flight virtual threads. This is consistent with Erlang's `sys:trace(Pid, false)` — trace messages already dispatched before the flag is cleared will still arrive.
