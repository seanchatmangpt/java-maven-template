# How to Trap Exit Signals

## Problem

You have a process linked to another. Instead of dying when the partner crashes,
you want to receive the crash as a mailbox message and decide what to do.

## Solution

Call `ref.trapExits(true)` on the process that should intercept crash signals.
Linked crashes are then delivered as `ExitSignal` records to the mailbox instead
of killing the process.

## Prerequisites

- `org.acme.Proc`, `org.acme.ProcRef`, `org.acme.ExitSignal`, `org.acme.ProcessLink`
  on the module path
- Java 26 with `--enable-preview`
- `trapExits` must be set before the linked process crashes

---

## Step 1 — Create a process and enable exit trapping

```java
import org.acme.Proc;
import org.acme.ExitSignal;

ProcRef<String, Object> supervisor = Proc.of("running", (state, msg) -> {
    if (msg instanceof ExitSignal sig) {
        System.err.println("Child crashed: " + sig.cause().getMessage());
        // decide: restart, escalate, or ignore
        return "recovering";
    }
    return state;
});

supervisor.trapExits(true);
```

---

## Step 2 — Link the process to a partner

```java
import org.acme.ProcessLink;

ProcRef<String, String> child = Proc.of("working", (state, msg) -> {
    if ("crash".equals(msg)) throw new RuntimeException("boom");
    return msg;
});

ProcessLink.link(supervisor, child);
```

---

## Step 3 — Trigger a crash and observe the ExitSignal

```java
child.tell("crash"); // child throws RuntimeException

// supervisor receives ExitSignal in its mailbox instead of dying
await().atMost(2, SECONDS).until(() ->
    supervisor.ask("state").get(500, MILLISECONDS).equals("recovering")
);
```

---

## Step 4 — Inspect the ExitSignal fields

```java
ProcRef<ExitSignal, Object> logger = Proc.of(null, (last, msg) -> {
    if (msg instanceof ExitSignal sig) {
        var from  = sig.from();    // ProcRef<?> that crashed
        var cause = sig.cause();   // Throwable — null for normal exit
        System.out.printf("EXIT from %s: %s%n", from, cause);
        return sig;
    }
    return last;
});

logger.trapExits(true);
```

`ExitSignal` is a record:

```java
record ExitSignal(ProcRef<?, ?> from, Throwable cause) {}
```

`cause` is `null` when the partner stopped normally (`.stop()` called).

---

## Step 5 — Distinguish normal exit from crash

```java
ProcRef<String, Object> watcher = Proc.of("ok", (state, msg) -> {
    if (msg instanceof ExitSignal sig) {
        return sig.cause() == null ? "partner-stopped-normally"
                                   : "partner-crashed: " + sig.cause();
    }
    return state;
});

watcher.trapExits(true);
ProcessLink.link(watcher, child);
```

---

## Step 6 — Re-enable propagation for specific crash types

To selectively propagate some crashes, re-throw inside the handler:

```java
ProcRef<String, Object> selective = Proc.of("running", (state, msg) -> {
    if (msg instanceof ExitSignal sig && sig.cause() instanceof OutOfMemoryError e) {
        throw e; // escalate OOM
    }
    if (msg instanceof ExitSignal sig) {
        return "recovered from: " + sig.cause().getClass().getSimpleName();
    }
    return state;
});

selective.trapExits(true);
```

---

## Verification

```java
var child    = Proc.of("ok", (s, m) -> { throw new IllegalStateException("boom"); });
var observer = Proc.of("waiting", (s, msg) -> msg instanceof ExitSignal ? "got-exit" : s);

observer.trapExits(true);
ProcessLink.link(observer, child);

child.tell("anything"); // triggers throw

await().atMost(2, SECONDS).until(() ->
    "got-exit".equals(observer.ask("state").get(500, MILLISECONDS))
);
```

---

## Troubleshooting

**Observer still dies when linked partner crashes.**
Confirm `trapExits(true)` is called before the crash occurs. Setting it after a
crash has no effect on that crash.

**`ExitSignal.cause()` is null but I expected an exception.**
The partner process called `.stop()` normally. A `null` cause always means a
clean exit.

**ExitSignal is delivered but the observer also stops.**
The observer's own message handler threw an exception while processing the
`ExitSignal`. Wrap the handler logic in a try/catch.

**I want crash notification without a bilateral link.**
Use `ProcessMonitor` instead. `ProcessMonitor` delivers DOWN notifications
without requiring a link, and the monitoring side never dies as a result.
