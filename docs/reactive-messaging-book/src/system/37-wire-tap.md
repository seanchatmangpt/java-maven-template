# 37. Wire Tap

> *"Observe the signal without disturbing the circuit."*
> — Enterprise Integration Patterns, Hohpe & Woolf

> *"sys:trace/2 lets you watch a process think without making it stutter."*
> — Erlang/OTP Design Principles

---

## Intent

A **Wire Tap** intercepts every message flowing through a `MessageChannel` and delivers a copy to a secondary observer — a logger, auditor, metrics collector, or debugger — without altering the primary message flow in any way. The tap is entirely subordinate: if it crashes, the primary channel proceeds unaware. If the tap is deactivated, the primary channel does not slow down.

In JOTP, the tap copy is dispatched on a fresh **virtual thread** so the observer can perform I/O (write to a database, publish to an audit topic, serialize to JSON) without ever blocking the sender's thread.

---

## OTP Analogy

Erlang ships `sys:trace/2`, which attaches a tracing flag to any OTP process. Once enabled, the process emits `{trace, Pid, Event, Data}` messages to a designated tracer without modifying the process logic at all.

```erlang
%% Start a gen_server named :counter
{ok, Pid} = gen_server:start_link({local, counter}, counter_server, [], []).

%% Attach a wire tap (sys trace) — process is unaware
sys:trace(counter, true).

%% Every cast/call now produces a trace message to the shell process:
%% {trace, <0.123.0>, in, {cast, increment}}
gen_server:cast(counter, increment).

%% Detach the tap — zero cost to primary process
sys:trace(counter, false).
```

The key property: `sys:trace/2` is orthogonal to the traced process. It cannot raise an exception that propagates into `counter_server`. In exactly the same spirit, `WireTap<T>` guarantees that any exception thrown by the `tap` Consumer is **caught and discarded**; the primary channel never sees it.

---

## JOTP Implementation

### Architecture

```
Sender Thread
     │
     ▼
┌──────────────────────────┐
│      WireTap<T>          │
│  ┌────────────────────┐  │
│  │  1. primary.send() │  │  ← always first, on caller's thread
│  └────────────────────┘  │
│  ┌────────────────────┐  │
│  │  2. active check   │  │  ← volatile read, near-zero cost
│  └────────────────────┘  │
│  ┌────────────────────┐  │
│  │  3. VirtualThread  │──┼──► tap.accept(message)  [isolated]
│  │     .ofVirtual()   │  │       ↳ exception swallowed
│  └────────────────────┘  │
└──────────────────────────┘
     │
     ▼
  Primary
  Channel
  (Queue,
   Proc, etc.)
```

### Design Decisions

**Primary before tap.** `primary.send(message)` is called unconditionally *before* any tap logic. This is non-negotiable: the tap is a subordinate observer, never a gatekeeper. Even if the JVM were to crash between the two calls, the primary delivery happened.

**Virtual thread per message.** Java 21+ virtual threads are cheap enough (~few KB per thread vs ~1 MB for a platform thread) that spawning one per tapped message is practical even at tens of thousands of messages per second. The alternative — a shared executor — would introduce a shared mutable resource and require lifecycle management that the stateless `WireTap` deliberately avoids.

**`volatile boolean active`.** Activation state is a single `volatile` field rather than an `AtomicBoolean` because only one transition happens at a time (control plane vs. data plane) and we need only visibility, not compare-and-swap. A `volatile` read on the hot path is a single memory barrier instruction.

**Exception isolation.** The tap `Consumer<T>` runs inside a `try/catch(Exception ignored)` block. This mirrors Erlang's process isolation: a crash in a watcher must never kill the watched. If you need to surface tap errors, wire the tap itself through a `DeadLetterChannel` or log inside the consumer before the exception escapes.

**Stateless stop().** `WireTap` holds no threads, no queues, no executors. It cannot "stop" in any meaningful sense; the virtual threads it spawns are fire-and-forget. The `stop()` contract is fulfilled vacuously. The downstream `primary` channel manages its own lifecycle.

---

## API Reference

| Method | Signature | Description |
|---|---|---|
| `WireTap(primary, tap)` | `(MessageChannel<T>, Consumer<T>)` | Constructs a tap wrapping `primary`, forwarding copies to `tap`. |
| `send(message)` | `void send(T message)` | Sends to primary (blocking), then spawns a virtual thread to call `tap.accept(message)`. |
| `activate()` | `void activate()` | Re-enables tap dispatching. Thread-safe via `volatile`. |
| `deactivate()` | `void deactivate()` | Suppresses tap dispatching without affecting primary delivery. Thread-safe via `volatile`. |
| `isActive()` | `boolean isActive()` | Returns current tap activation state. |
| `stop()` | `void stop() throws InterruptedException` | No-op; primary channel manages its own lifecycle. |

---

## Implementation Internals

The send path in pseudo-code, showing the memory-ordering guarantees:

```
send(T message):
  ① primary.send(message)          // HB: message visible to downstream consumers
  ② boolean snap = this.active      // volatile read — happens-after any prior deactivate()
  ③ if snap:
       Thread.ofVirtual()
             .name("wire-tap-")     // JVM assigns unique suffix: wire-tap-1, wire-tap-2, …
             .start(λ:
               try:
                 tap.accept(message) // may block for I/O — isolated from sender thread
               catch Exception:
                 /* intentionally swallowed — tap must be inert on failure */
             )
```

The `volatile` read at ② establishes a *happens-before* edge: if `deactivate()` was called on another thread and completed, the write to `active = false` is visible here. There is a deliberate race window: a message in flight when `deactivate()` is called *may or may not* be tapped — this is acceptable for an observability tap where occasional missed messages during shutdown are benign. If you need hard guarantees across the transition, use `compareAndSet` on an `AtomicBoolean` or introduce a lock.

---

## Code Example

```java
import org.acme.channel.MessageChannel;
import org.acme.channel.WireTap;
import org.acme.Proc;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

// A simple in-memory channel backed by a Proc mailbox
MessageChannel<String> auditLog = msg -> System.out.println("[AUDIT] " + msg);

// Build a primary channel (e.g., a Proc-backed queue)
MessageChannel<String> primary = msg -> {
    // real processing: parse, route, persist …
    System.out.println("[PRIMARY] processing: " + msg);
};

// Wrap with a wire tap
WireTap<String> tapped = new WireTap<>(primary, auditLog);

// Normal usage — both primary and audit receive "order-42"
tapped.send("order-42");

// Silence the tap for high-frequency bursts
tapped.deactivate();
tapped.send("heartbeat-1");   // only primary sees this
tapped.send("heartbeat-2");

// Re-enable for business events
tapped.activate();
tapped.send("order-43");      // both see this again

// ── Tap crash isolation demo ──────────────────────────────────────────────
MessageChannel<String> crashyTap = msg -> {
    throw new RuntimeException("simulated audit failure");
};

WireTap<String> faultTolerant = new WireTap<>(primary, crashyTap);
faultTolerant.send("order-44");  // primary still processes normally
```

### With a `Proc`-backed primary channel

```java
import org.acme.Proc;
import org.acme.channel.WireTap;

// State: List<String> accumulates processed orders
record OrderState(List<String> orders) {}
sealed interface OrderMsg permits OrderMsg.Place, OrderMsg.Query {
    record Place(String orderId) implements OrderMsg {}
    record Query() implements OrderMsg {}
}

Proc<OrderState, OrderMsg> orderProc = Proc.of(
    new OrderState(new java.util.ArrayList<>()),
    (state, msg) -> switch (msg) {
        case OrderMsg.Place p -> {
            state.orders().add(p.orderId());
            yield state;
        }
        case OrderMsg.Query q -> state;
    }
);

// Wrap the Proc's send channel with a wire tap for metrics
var metricsCollector = new java.util.concurrent.atomic.AtomicInteger(0);
MessageChannel<OrderMsg> tappedProc = new WireTap<>(
    orderProc.channel(),
    msg -> {
        if (msg instanceof OrderMsg.Place) metricsCollector.incrementAndGet();
    }
);

tappedProc.send(new OrderMsg.Place("ORD-001"));
tappedProc.send(new OrderMsg.Place("ORD-002"));
// metricsCollector.get() == 2 (eventually — tap is async)
```

---

## Composition

### 1. Stacked Wire Taps (multi-observer)

Multiple observers can be stacked by nesting `WireTap` wrappers. Each tap wraps the previous, forming a chain where every observer is isolated:

```java
MessageChannel<T> base        = /* primary channel */;
WireTap<T>        withMetrics = new WireTap<>(base,        metricsConsumer);
WireTap<T>        withAudit   = new WireTap<>(withMetrics, auditConsumer);
WireTap<T>        withTrace   = new WireTap<>(withAudit,   traceConsumer);

// Sender uses withTrace; message reaches base through three independent taps
withTrace.send(message);
```

Each tap's virtual thread is independent. A crash in `traceConsumer` does not affect `auditConsumer` and does not affect the primary `base` delivery.

### 2. Wire Tap + Dead Letter Channel

Route tap failures to a `DeadLetterChannel` instead of silently discarding them, while still preserving the primary flow:

```java
ConcurrentLinkedQueue<T> tapErrors = new ConcurrentLinkedQueue<>();

WireTap<T> safeAudit = new WireTap<>(primary, msg -> {
    try {
        auditChannel.send(msg);
    } catch (Exception e) {
        tapErrors.offer(msg);          // dead-letter without affecting primary
    }
});
```

### 3. Conditional Wire Tap (Content-Based Activation)

Combine with a predicate to tap only messages matching a business rule:

```java
// Only tap messages that are "high value" orders
WireTap<Order> highValueTap = new WireTap<>(
    primary,
    order -> {
        if (order.total().compareTo(BigDecimal.valueOf(10_000)) > 0) {
            fraudDetectionChannel.send(order);
        }
    }
);
```

---

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class WireTapTest implements WithAssertions {

    @Test
    void primaryAlwaysReceivesMessage() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        MessageChannel<String> primary = received::add;
        WireTap<String> tap = new WireTap<>(primary, msg -> {});

        tap.send("hello");

        assertThat(received).containsExactly("hello");
    }

    @Test
    void tapReceivesMessageOnVirtualThread() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var tapped = new CopyOnWriteArrayList<String>();

        MessageChannel<String> primary = msg -> {};
        WireTap<String> wire = new WireTap<>(primary, msg -> {
            tapped.add(msg);
            latch.countDown();
        });

        wire.send("event-1");
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(tapped).containsExactly("event-1");
    }

    @Test
    void tapCrashDoesNotAffectPrimary() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        MessageChannel<String> primary = received::add;
        WireTap<String> wire = new WireTap<>(primary, msg -> {
            throw new RuntimeException("tap exploded");
        });

        // Must not throw
        assertThatCode(() -> wire.send("safe")).doesNotThrowAnyException();
        assertThat(received).containsExactly("safe");
    }

    @Test
    void deactivateStopsTapWithoutAffectingPrimary() throws InterruptedException {
        var tapSeen = new CopyOnWriteArrayList<String>();
        var primarySeen = new CopyOnWriteArrayList<String>();

        WireTap<String> wire = new WireTap<>(primarySeen::add, tapSeen::add);

        wire.deactivate();
        wire.send("msg-while-inactive");

        // Give virtual thread time to run (should not run — deactivated)
        Thread.sleep(100);

        assertThat(primarySeen).containsExactly("msg-while-inactive");
        assertThat(tapSeen).isEmpty();
    }

    @Test
    void activateReenablesTapAfterDeactivation() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var tapSeen = new CopyOnWriteArrayList<String>();

        WireTap<String> wire = new WireTap<>(msg -> {}, msg -> {
            tapSeen.add(msg);
            latch.countDown();
        });

        wire.deactivate();
        wire.send("ignored");
        wire.activate();
        wire.send("observed");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(tapSeen).containsExactly("observed");
    }
}
```

---

## Caveats & Trade-offs

**Async tap — no delivery guarantee.** The tap virtual thread is fire-and-forget. If the JVM shuts down between `primary.send()` and `tap.accept()`, the tap message is lost. For hard audit requirements, consider a synchronous tap (call `tap.accept()` on the sender thread before returning) at the cost of adding tap latency to every send.

**Memory pressure at high throughput.** Each tapped message spawns a virtual thread. Virtual threads are cheap but not free: the JVM still allocates a small stack and schedules the thread on the ForkJoinPool carrier pool. Under extreme throughput (millions of messages/second), the cost accumulates. Profile before deploying in hot paths.

**Message mutability.** If `T` is a mutable object and the tap modifies it, the primary channel may observe the mutation (depending on ordering). Always use immutable records or defensively copy before tapping mutable objects.

**`volatile` race on activate/deactivate.** There is a benign race window: a message that arrives during `deactivate()` may or may not be tapped. This is intentional for an observability tap. If strict "tap exactly these messages" semantics are required, serialize access to `active` with a lock or use a higher-level routing mechanism.

**No back-pressure.** `WireTap` does not apply back-pressure to the sender. If the tap consumer is slower than the send rate, virtual threads accumulate. Use a bounded executor or a `Semaphore` in the tap consumer if this is a concern.

**Not a security boundary.** The tap sees every message. Do not use `WireTap` to route messages to untrusted consumers. For selective masking (e.g., redact PII before tapping), apply a transformation inside the tap consumer before forwarding.
