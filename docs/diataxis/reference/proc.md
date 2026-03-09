# Proc<S,M> and ProcRef<S,M> — API Reference

**Package:** `org.acme`
**Classes:** `Proc<S,M>`, `ProcRef<S,M>`
**Erlang/OTP equivalent:** `spawn/3`, `Pid`, `gen_server`

---

## Overview

`Proc<S,M>` is a factory for lightweight processes backed by a single virtual thread. Each process holds an immutable state value of type `S` and receives messages of type `M` from a mailbox. The handler `BiFunction<S, M, S>` is applied to each message in arrival order.

`ProcRef<S,M>` is the opaque handle returned by `Proc.of(...)`. It is the only way to interact with a running process. `ProcRef` references remain valid across supervisor restarts (the internal process pointer is updated transparently).

---

## Proc — Factory Methods

```java
public final class Proc<S, M> {

    public static <S, M> ProcRef<S, M> of(
        S initialState,
        BiFunction<S, M, S> handler
    );

    public static <S, M> ProcRef<S, M> of(
        S initialState,
        BiFunction<S, M, S> handler,
        Consumer<Throwable> onCrash
    );
}
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `Proc.of(initialState, handler)` | `S initialState` — initial state value; `BiFunction<S,M,S> handler` — pure state transition | `ProcRef<S,M>` | Spawns a new virtual thread running the mailbox loop. No crash callback. |
| `Proc.of(initialState, handler, onCrash)` | Same as above plus `Consumer<Throwable> onCrash` — called if handler throws | `ProcRef<S,M>` | Same as above. `onCrash` fires when the handler throws; does **not** fire on `stop()`. |

### Constraints

- `initialState` must not be `null`.
- `handler` must not be `null`.
- `handler` should be a pure function; side effects are permitted but must be thread-safe.
- An uncaught exception from `handler` terminates the process and fires `onCrash`.

---

## ProcRef<S,M> — Instance Methods

```java
public sealed interface ProcRef<S, M> {

    void tell(M message);

    CompletableFuture<S> ask(M message);

    CompletableFuture<S> ask(M message, Duration timeout);

    void stop();

    void trapExits(boolean trap);

    boolean isAlive();
}
```

### Method Reference

| Method | Parameters | Returns | Throws | Description |
|---|---|---|---|---|
| `tell(message)` | `M message` | `void` | — | Enqueues `message` in the mailbox. Returns immediately without waiting for processing. Equivalent to `gen_server:cast`. |
| `ask(message)` | `M message` | `CompletableFuture<S>` | — | Enqueues `message` and returns a future that completes with the state value after the handler returns. Equivalent to `gen_server:call` without timeout. |
| `ask(message, timeout)` | `M message`, `Duration timeout` | `CompletableFuture<S>` | `TimeoutException` (via future) | Same as `ask(message)` but the future completes exceptionally with `TimeoutException` if the handler does not respond within `timeout`. |
| `stop()` | — | `void` | — | Sends a stop sentinel to the mailbox. The process exits cleanly after draining messages up to the sentinel. Does **not** invoke `onCrash`. |
| `trapExits(boolean)` | `boolean trap` | `void` | — | When `true`, exit signals from linked processes are converted to `ExitSignal` records delivered to the mailbox instead of killing this process. Equivalent to `process_flag(trap_exit, true)`. |
| `isAlive()` | — | `boolean` | — | Returns `true` if the backing virtual thread is still running. Returns `false` after `stop()` completes or after a crash. |

---

## ExitSignal — Delivered when trapExits is true

```java
public record ExitSignal(ProcRef<?, ?> from, Throwable cause) {}
```

| Field | Type | Description |
|---|---|---|
| `from` | `ProcRef<?,?>` | The process that exited or crashed. |
| `cause` | `Throwable` | `null` for normal exit (`stop()` was called); non-null for abnormal exit (handler threw). |

`ExitSignal` is enqueued in the receiving process's mailbox. The handler receives it as type `M`; the handler's `M` type must accommodate `ExitSignal` (e.g., via a sealed message hierarchy).

---

## ProcSys — Process Introspection

```java
public final class ProcSys {

    public static <S> S getState(ProcRef<S, ?> ref);

    public static void suspend(ProcRef<?, ?> ref);

    public static void resume(ProcRef<?, ?> ref);

    public static ProcStats statistics(ProcRef<?, ?> ref);
}
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `getState(ref)` | `ProcRef<S,?> ref` | `S` | Returns a snapshot of the current state. Blocks briefly to read the state safely. Equivalent to `sys:get_state/1`. |
| `suspend(ref)` | `ProcRef<?,?> ref` | `void` | Pauses mailbox processing. Incoming messages continue to enqueue. Equivalent to `sys:suspend/1`. |
| `resume(ref)` | `ProcRef<?,?> ref` | `void` | Resumes suspended mailbox processing. Equivalent to `sys:resume/1`. |
| `statistics(ref)` | `ProcRef<?,?> ref` | `ProcStats` | Returns runtime statistics. Equivalent to `sys:statistics/1`. |

### ProcStats

```java
public record ProcStats(
    long messagesProcessed,
    long queueDepth,
    Duration uptime
) {}
```

| Field | Type | Description |
|---|---|---|
| `messagesProcessed` | `long` | Total messages handled since process start. |
| `queueDepth` | `long` | Messages currently in the mailbox queue. |
| `uptime` | `Duration` | Elapsed time since process was spawned. |

---

## ProcLib — Startup Handshake

```java
public final class ProcLib {

    public static <S, M> ProcRef<S, M> startLink(Supplier<ProcRef<S, M>> factory);

    public void initAck();
}
```

| Method | Parameters | Returns | Throws | Description |
|---|---|---|---|---|
| `ProcLib.startLink(factory)` | `Supplier<ProcRef<S,M>> factory` — supplier that spawns and initializes the process | `ProcRef<S,M>` | `RuntimeException` if child crashes before `initAck()` | Blocks the calling thread until the child's virtual thread calls `initAck()`. Equivalent to `proc_lib:start_link/3`. |
| `initAck()` | — | `void` | — | Called inside the child process to signal successful initialization. Unblocks the `startLink` caller. |

---

## ProcessLink — Bilateral Crash Propagation

```java
public final class ProcessLink {

    public static void link(ProcRef<?, ?> a, ProcRef<?, ?> b);

    public static void unlink(ProcRef<?, ?> a, ProcRef<?, ?> b);
}
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `link(a, b)` | Two `ProcRef<?,?>` handles | `void` | Establishes a bidirectional link. If either process crashes, the other receives an exit signal (killing it unless `trapExits(true)` is set). Equivalent to `link/1`. |
| `unlink(a, b)` | Two `ProcRef<?,?>` handles | `void` | Removes the bidirectional link. Future crashes do not propagate. Equivalent to `unlink/1`. |

---

## Lifecycle State Machine

```
                  Proc.of(...)
                       │
                       ▼
                  ┌─────────┐
                  │ RUNNING  │◄─────────────────┐
                  └────┬────┘                   │ (supervisor restart)
                       │                        │
          ┌────────────┼────────────┐           │
          │            │            │           │
       tell()        ask()       stop()         │
          │            │            │           │
          ▼            ▼            ▼           │
    [mailbox]     [mailbox]   [stop sentinel]   │
          │            │            │           │
          └────────────┴────────────┘           │
                       │                        │
               handler executes                 │
                       │                        │
              ┌────────┴────────┐               │
              │                 │               │
          success            throws             │
              │                 │               │
              │        onCrash() fires          │
              │                 │               │
              ▼                 ▼               │
          RUNNING           CRASHED ────────────┘
                                │   (if supervised)
                                ▼
                            TERMINATED
```

---

## Concurrency Notes

- Each `Proc` mailbox is a `LinkedBlockingQueue<M>` consumed by exactly one virtual thread.
- `tell()` is non-blocking and thread-safe from any number of callers.
- `ask()` is thread-safe; multiple concurrent `ask()` calls are serialized via the mailbox.
- `trapExits(boolean)` is thread-safe; takes effect for the next exit signal processed.
- `isAlive()` reads a volatile flag; result may be stale by the time it is used.
