# How to Register Processes Globally

## Problem

You need to look up a running process by name from anywhere in the application
without passing `ProcRef` handles through every call site.

## Solution

Use `ProcessRegistry` to register a `ProcRef` under a string name, then retrieve
it with `whereis`.

## Prerequisites

- `org.acme.ProcessRegistry`, `org.acme.Proc`, `org.acme.ProcRef` on the module path
- Java 26 with `--enable-preview`
- Call `ProcessRegistry.reset()` in test `@BeforeEach` / `@AfterEach` to avoid
  cross-test leakage

---

## Step 1 — Register a process

```java
import org.acme.Proc;
import org.acme.ProcessRegistry;

var ref = Proc.of("idle", (state, msg) -> msg);
ProcessRegistry.register("my-worker", ref);
```

A name can only be registered once. Registering a name that is already taken
throws `IllegalStateException`.

---

## Step 2 — Look up a process by name

```java
import java.util.Optional;
import org.acme.ProcRef;

Optional<ProcRef<String, String>> found = ProcessRegistry.whereis("my-worker");

found.ifPresent(worker -> worker.tell("hello"));
```

`whereis` returns `Optional.empty()` when no process is registered under that
name.

---

## Step 3 — Send a message without holding the ref

```java
ProcessRegistry.whereis("my-worker")
    .orElseThrow(() -> new IllegalStateException("my-worker not found"))
    .tell("process-this");
```

---

## Step 4 — Unregister a process manually

```java
ProcessRegistry.unregister("my-worker");
```

After unregistering, `whereis("my-worker")` returns `Optional.empty()`.
Stopped processes are deregistered automatically — you rarely need to call
`unregister` explicitly in production code.

---

## Step 5 — List all registered names

```java
import java.util.Set;

Set<String> names = ProcessRegistry.registered();
System.out.println("Active processes: " + names);
```

---

## Step 6 — Combine with Supervisor for auto-reregistration

When a supervisor restarts a child, the old `ProcRef` handle is reused, so the
registry entry remains valid automatically:

```java
import org.acme.Supervisor;

var supervisor = Supervisor.builder()
    .strategy(Supervisor.Strategy.ONE_FOR_ONE)
    .maxRestarts(5)
    .withinSeconds(10)
    .build();

var ref = supervisor.startChild("counter",
    () -> Proc.of(0, (count, msg) -> count + 1));

ProcessRegistry.register("counter", ref);

// After a crash and restart, this still works:
ProcessRegistry.whereis("counter").orElseThrow().tell("inc");
```

---

## Verification

```java
var ref = Proc.of("alive", (s, m) -> m);
ProcessRegistry.register("test-proc", ref);

assertThat(ProcessRegistry.whereis("test-proc")).isPresent();
assertThat(ProcessRegistry.registered()).contains("test-proc");

ref.stop();
// After stop, the process deregisters itself
await().atMost(1, SECONDS).until(() ->
    ProcessRegistry.whereis("test-proc").isEmpty()
);
```

---

## Troubleshooting

**`IllegalStateException` on `register`.**
A process is already registered under that name. Call `unregister` first or
choose a unique name.

**`whereis` returns empty after a crash.**
If the process stopped without a supervisor, it deregisters itself. Either
restart it manually and re-register, or use a `Supervisor` so the `ProcRef`
handle (and its registry entry) stays alive through restarts.

**Registry entries leak between tests.**
Call `ProcessRegistry.reset()` in `@BeforeEach` and `@AfterEach`:

```java
@BeforeEach
@AfterEach
void resetRegistry() {
    ProcessRegistry.reset();
}
```
