# How to Fan Out with Parallel (Structured Concurrency)

## Problem

You have a list of independent tasks that must all run concurrently, and you
need either all results or an immediate failure if any task fails.

## Solution

Use `Parallel.all(List<Supplier<T>>)`. It runs all suppliers concurrently on
virtual threads using `StructuredTaskScope`, returns a `List<T>` of results in
submission order, and throws on the first failure.

## Prerequisites

- `org.acme.Parallel` on the module path
- Java 26 with `--enable-preview`
- All suppliers must be thread-safe; results are collected in order

---

## Step 1 — Run a fixed list of tasks in parallel

```java
import org.acme.Parallel;
import java.util.List;

List<String> results = Parallel.all(List.of(
    () -> fetchUser(1),
    () -> fetchUser(2),
    () -> fetchUser(3)
));
// results.get(0) corresponds to fetchUser(1), etc.
```

---

## Step 2 — Build the supplier list dynamically

```java
import java.util.function.Supplier;

List<Integer> ids = List.of(10, 20, 30, 40);

List<Supplier<String>> tasks = ids.stream()
    .<Supplier<String>>map(id -> () -> fetchUser(id))
    .toList();

List<String> users = Parallel.all(tasks);
```

---

## Step 3 — Handle the fail-fast exception

`Parallel.all` throws the first exception that occurs, cancelling all in-flight
tasks:

```java
try {
    List<String> results = Parallel.all(List.of(
        () -> "ok",
        () -> { throw new RuntimeException("service down"); },
        () -> "ok"
    ));
} catch (RuntimeException e) {
    System.err.println("Fan-out failed: " + e.getMessage());
}
```

The failed task's exception is propagated directly; other tasks are cancelled
via the `StructuredTaskScope` shutdown mechanism.

---

## Step 4 — Combine Parallel with Result for partial failure tolerance

If you want to collect results even when some tasks fail, wrap each supplier
with `Result.of`:

```java
import org.acme.Parallel;
import org.acme.Result;

List<Result<String, Exception>> outcomes = Parallel.all(List.of(
    () -> Result.of(() -> fetchUser(1)),
    () -> Result.of(() -> fetchUser(2)),
    () -> Result.of(() -> { throw new RuntimeException("timeout"); })
));

long successes = outcomes.stream().filter(Result::isSuccess).count();
long failures  = outcomes.stream().filter(Result::isFailure).count();
System.out.printf("%d succeeded, %d failed%n", successes, failures);
```

---

## Step 5 — Limit concurrency with batching

`Parallel.all` runs all tasks simultaneously. For large lists, batch manually:

```java
import java.util.ArrayList;

var allIds   = List.of(/* many ids */);
var allUsers = new ArrayList<String>();

// Process in batches of 10
for (int i = 0; i < allIds.size(); i += 10) {
    var batch = allIds.subList(i, Math.min(i + 10, allIds.size()));
    var tasks = batch.stream()
        .<Supplier<String>>map(id -> () -> fetchUser(id))
        .toList();
    allUsers.addAll(Parallel.all(tasks));
}
```

---

## Step 6 — Use Parallel inside a Proc handler

`Proc` handlers run on virtual threads, so calling `Parallel.all` inside a
handler is safe:

```java
ProcRef<List<String>, List<Integer>> fetcher = Proc.of(
    List.of(),
    (state, ids) -> Parallel.all(
        ids.stream()
           .<Supplier<String>>map(id -> () -> fetchUser(id))
           .toList()
    )
);

var results = fetcher.ask(List.of(1, 2, 3)).get(5, TimeUnit.SECONDS);
```

---

## Verification

```java
var results = Parallel.all(List.of(
    () -> 1,
    () -> 2,
    () -> 3
));

assertThat(results).containsExactly(1, 2, 3);
```

Fail-fast test:

```java
assertThatThrownBy(() ->
    Parallel.all(List.of(
        () -> "a",
        () -> { throw new IllegalStateException("oops"); }
    ))
).isInstanceOf(IllegalStateException.class)
 .hasMessage("oops");
```

---

## Troubleshooting

**Results are out of order.**
`Parallel.all` preserves submission order — results are aligned with the input
`List` positions. If order appears wrong, check the index mapping in your
supplier construction.

**One slow task blocks the whole result.**
All tasks must complete (or one must fail) before `Parallel.all` returns.
Add a timeout by running `Parallel.all` in a virtual thread with its own
deadline:

```java
var future = Thread.ofVirtual().start(() -> Parallel.all(tasks));
future.join(Duration.ofSeconds(10));
```

**`StructuredTaskScope` is closed before tasks finish.**
Do not pass suppliers that capture and use an externally closed scope. Keep all
concurrency inside the `Parallel.all` call.
