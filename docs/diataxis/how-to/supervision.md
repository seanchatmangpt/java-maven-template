# How to Configure Supervision Strategies

## Problem

You have a set of child processes that must be restarted automatically when they
crash. You need to choose how the supervisor reacts when one child fails.

## Solution

Build a `Supervisor` with the appropriate strategy, register child suppliers, and
let the supervisor manage the lifecycle.

## Prerequisites

- `org.acme.Supervisor`, `org.acme.Proc`, `org.acme.ProcRef` on the module path
- Java 26 with `--enable-preview`

---

## Step 1 — Add the dependency (module-info.java)

```java
module com.example.myapp {
    requires org.acme;
}
```

---

## Step 2 — Choose a strategy

| Strategy | When one child crashes… |
|---|---|
| `ONE_FOR_ONE` | Restart only that child |
| `ONE_FOR_ALL` | Restart all children |
| `REST_FOR_ONE` | Restart the crashed child and all children started after it |

---

## Step 3 — Build and start the supervisor

### ONE_FOR_ONE

```java
import org.acme.Supervisor;
import org.acme.Proc;

Supervisor supervisor = Supervisor.builder()
    .strategy(Supervisor.Strategy.ONE_FOR_ONE)
    .maxRestarts(5)
    .withinSeconds(10)
    .build();

// Register two independent workers
supervisor.startChild("worker-a", () -> Proc.of("idle", (state, msg) -> msg));
supervisor.startChild("worker-b", () -> Proc.of("idle", (state, msg) -> msg));
```

If `worker-a` crashes, only `worker-a` restarts. `worker-b` is unaffected.

### ONE_FOR_ALL

```java
Supervisor supervisor = Supervisor.builder()
    .strategy(Supervisor.Strategy.ONE_FOR_ALL)
    .maxRestarts(3)
    .withinSeconds(5)
    .build();

supervisor.startChild("db-conn",     () -> Proc.of("connected", (s, m) -> m));
supervisor.startChild("db-consumer", () -> Proc.of("waiting",   (s, m) -> m));
```

If `db-conn` crashes, both `db-conn` and `db-consumer` are restarted in
registration order.

### REST_FOR_ONE

```java
Supervisor supervisor = Supervisor.builder()
    .strategy(Supervisor.Strategy.REST_FOR_ONE)
    .maxRestarts(5)
    .withinSeconds(30)
    .build();

// Registration order matters
supervisor.startChild("stage-1", () -> Proc.of("running", (s, m) -> m));
supervisor.startChild("stage-2", () -> Proc.of("running", (s, m) -> m));
supervisor.startChild("stage-3", () -> Proc.of("running", (s, m) -> m));
```

If `stage-2` crashes, `stage-2` and `stage-3` restart. `stage-1` is unaffected.

---

## Step 4 — Interact with supervised children

`startChild` returns a `ProcRef` you can keep. The supervisor replaces the
underlying process on restart; the `ProcRef` handle remains valid.

```java
var ref = supervisor.startChild("counter",
    () -> Proc.of(0, (count, msg) -> count + 1));

ref.tell("inc");
ref.tell("inc");

var state = ref.ask("state").get(2, TimeUnit.SECONDS);
System.out.println("Count: " + state); // Count: 2
```

---

## Step 5 — Shut down the supervisor

```java
supervisor.shutdown();
```

All children receive a stop signal and their threads are joined before
`shutdown()` returns.

---

## Step 6 — Handle restart budget exhaustion

When the supervisor exceeds `maxRestarts` within `withinSeconds`, it stops all
children and throws a `SupervisorException`. Wrap the supervisor in a
higher-level supervisor or handle the exception:

```java
try {
    supervisor.startChild("flaky", () -> {
        throw new RuntimeException("boot failure");
    });
} catch (SupervisorException e) {
    System.err.println("Restart budget exceeded: " + e.getMessage());
    supervisor.shutdown();
}
```

---

## Verification

```java
// After a crash, the ref should still respond
var ref = supervisor.startChild("worker", () -> Proc.of(0, (s, m) -> s + 1));
ref.tell("crash"); // simulate crash — supervisor restarts the process

await().atMost(2, SECONDS).until(() ->
    ref.ask("ping").get(500, MILLISECONDS) != null
);
```

---

## Troubleshooting

**Child is not restarted.**
Check that `maxRestarts` and `withinSeconds` are large enough. The supervisor
stops all children and terminates if the budget is exceeded before the window
resets.

**`startChild` returns a stale ref after restart.**
`ProcRef` is designed to survive restarts — you do not need to call `startChild`
again. If `.ask()` times out immediately after a restart, add an
`await().atMost(...)` retry.

**`ONE_FOR_ALL` restarts children in wrong order.**
Children are restarted in the order they were registered. Reorder your
`startChild` calls to match dependency order.
