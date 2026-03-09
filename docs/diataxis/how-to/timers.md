# How to Schedule Timed Messages with ProcTimer

## Problem

You need a process to receive a message after a delay, or on a repeating
interval, without blocking its mailbox loop.

## Solution

Use `ProcTimer.sendAfter` for one-shot delivery or `ProcTimer.sendInterval` for
recurring delivery. Both return a `TimerHandle` you can cancel.

## Prerequisites

- `org.acme.ProcTimer`, `org.acme.Proc`, `org.acme.ProcRef` on the module path
- Java 26 with `--enable-preview`

---

## Step 1 — Send a message once after a delay

```java
import org.acme.Proc;
import org.acme.ProcTimer;
import org.acme.ProcTimer.TimerHandle;
import java.time.Duration;

ProcRef<String, String> ref = Proc.of("idle", (state, msg) -> {
    if ("tick".equals(msg)) {
        return "ticked";
    }
    return state;
});

TimerHandle handle = ProcTimer.sendAfter(Duration.ofMillis(500), ref, "tick");
```

The message `"tick"` is delivered to `ref`'s mailbox approximately 500 ms after
the call.

---

## Step 2 — Send a message on a fixed interval

```java
TimerHandle intervalHandle = ProcTimer.sendInterval(Duration.ofSeconds(1), ref, "heartbeat");
```

`"heartbeat"` is delivered every second until cancelled or the process stops.

---

## Step 3 — Cancel a timer

```java
ProcTimer.cancel(handle);
```

If the timer has already fired, `cancel` is a no-op. It is safe to call
`cancel` multiple times.

---

## Step 4 — Use timers for process timeouts

Pattern: a process sends itself a timeout message and reacts if no other message
arrived first.

```java
import org.acme.Proc;
import org.acme.ProcRef;

ProcRef<String, Object> server = Proc.of("waiting", (state, msg) -> switch (msg) {
    case "request" -> {
        // real work
        yield "done";
    }
    case "timeout" -> {
        System.err.println("Request timed out");
        yield "idle";
    }
    default -> state;
});

TimerHandle timeout = ProcTimer.sendAfter(Duration.ofSeconds(5), server, "timeout");
server.tell("request");
ProcTimer.cancel(timeout); // cancel if request handled first
```

---

## Step 5 — Implement a recurring heartbeat

```java
ProcRef<Integer, String> counter = Proc.of(0, (count, msg) -> {
    if ("tick".equals(msg)) {
        System.out.println("Heartbeat #" + (count + 1));
        return count + 1;
    }
    return count;
});

TimerHandle heartbeat = ProcTimer.sendInterval(Duration.ofSeconds(1), counter, "tick");

// Later, when done:
ProcTimer.cancel(heartbeat);
counter.stop();
```

---

## Step 6 — Handle timer messages in a StateMachine

```java
import org.acme.StateMachine;
import org.acme.StateMachine.Transition;

enum State { IDLE, ACTIVE }
sealed interface Event permits Event.Start, Event.Timeout {
    record Start() implements Event {}
    record Timeout() implements Event {}
}

var sm = StateMachine.<State, Event, Void>builder()
    .state(State.IDLE)
    .on(Event.Start.class,   (s, e, d) -> Transition.moveTo(State.ACTIVE))
    .on(Event.Timeout.class, (s, e, d) -> Transition.moveTo(State.IDLE))
    .build();
```

Then wrap the state machine in a `Proc` and deliver timed events:

```java
ProcRef<State, Event> smProc = Proc.of(State.IDLE, (state, event) ->
    sm.process(state, event));

ProcTimer.sendAfter(Duration.ofSeconds(10), smProc, new Event.Timeout());
```

---

## Verification

```java
var latch = new java.util.concurrent.CountDownLatch(1);

ProcRef<String, String> ref = Proc.of("waiting", (state, msg) -> {
    if ("fired".equals(msg)) latch.countDown();
    return msg;
});

ProcTimer.sendAfter(Duration.ofMillis(200), ref, "fired");

assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
```

---

## Troubleshooting

**Timer fires later than expected.**
`ProcTimer` uses a virtual-thread scheduler; delivery is best-effort and subject
to JVM scheduling. Do not rely on sub-millisecond precision.

**`cancel` does not prevent message delivery.**
If the timer fires before `cancel` is called, the message is already enqueued.
Design message handlers to be idempotent or include a generation counter to
ignore stale timer messages.

**Process stops but interval timer keeps firing.**
`ProcTimer.sendInterval` fires unconditionally. Cancel all interval handles
before stopping the target process to avoid delivering messages to a dead
mailbox.
