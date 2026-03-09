# How to Link Processes for Bilateral Crash Propagation

## Problem

You have two processes that must live and die together. If either one crashes,
the other must also be stopped.

## Solution

Use `ProcessLink.link(ref1, ref2)` to create a bilateral link. A crash in either
process signals the other to stop.

## Prerequisites

- `org.acme.ProcessLink`, `org.acme.Proc`, `org.acme.ProcRef` on the module path
- Java 26 with `--enable-preview`
- Understand the difference from `ProcessMonitor` (unilateral, non-killing)

---

## Step 1 — Create two processes

```java
import org.acme.Proc;
import org.acme.ProcRef;

ProcRef<String, String> producer = Proc.of("running", (state, msg) -> msg);
ProcRef<String, String> consumer = Proc.of("waiting", (state, msg) -> msg);
```

---

## Step 2 — Link the two processes

```java
import org.acme.ProcessLink;

ProcessLink.link(producer, consumer);
```

The link is symmetric. You do not need to call `link(consumer, producer)`
separately.

---

## Step 3 — Verify that a crash propagates

```java
// Stop producer abnormally (simulate crash)
producer.stop();

// Consumer should also stop within a short time
await().atMost(2, SECONDS).until(() -> !consumer.isAlive());
```

---

## Step 4 — Link inside a supervised pair

Use links for tightly coupled pairs under a `ONE_FOR_ALL` or `REST_FOR_ONE`
supervisor so that both are restarted together:

```java
import org.acme.Supervisor;

var supervisor = Supervisor.builder()
    .strategy(Supervisor.Strategy.ONE_FOR_ALL)
    .maxRestarts(5)
    .withinSeconds(10)
    .build();

var dbConn = supervisor.startChild("db-conn",
    () -> Proc.of("connected", (s, m) -> m));

var dbReader = supervisor.startChild("db-reader",
    () -> Proc.of("idle", (s, m) -> m));

ProcessLink.link(dbConn, dbReader);
```

---

## Step 5 — Trap exits to intercept the crash signal

By default, a linked process dies when its partner crashes. To intercept the
crash signal instead of dying, enable exit trapping on the process before it is
linked:

```java
ProcRef<String, String> resilient = Proc.of("running", (state, msg) -> {
    // handle ExitSignal delivered as a message
    return state;
});
resilient.trapExits(true);

ProcessLink.link(producer, resilient);
```

When `trapExits(true)` is set, an `ExitSignal` is delivered to the process
mailbox instead of killing it. See [How to trap exit signals](exit-signals.md).

---

## Step 6 — Unlink processes (if needed)

There is no `unlink` primitive in this library. To stop crash propagation, use
`ProcessMonitor` instead of `ProcessLink` when you need unilateral, non-killing
observation.

---

## Verification

```java
var a = Proc.of("alive", (s, m) -> m);
var b = Proc.of("alive", (s, m) -> m);

ProcessLink.link(a, b);

assertThat(a.isAlive()).isTrue();
assertThat(b.isAlive()).isTrue();

a.stop();

await().atMost(2, SECONDS).until(() -> !b.isAlive());
assertThat(b.isAlive()).isFalse();
```

---

## Troubleshooting

**`b` does not stop when `a` crashes.**
Confirm `ProcessLink.link(a, b)` was called after both processes were started.
Links established before a process is alive have no effect.

**Both processes keep restarting in a loop.**
If both processes are under a supervisor and linked to each other, a crash
triggers the link (killing the partner) and then the supervisor restarts both.
This is usually intentional under `ONE_FOR_ALL`. Switch to `ONE_FOR_ONE` and
remove the link if you want independent restarts.

**I want crash notification without killing the observer.**
Use `ProcessMonitor` instead. `ProcessLink` is always bilateral and always kills
the partner (unless exit trapping is enabled).
