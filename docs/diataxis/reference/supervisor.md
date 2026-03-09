# Supervisor — API Reference

**Package:** `org.acme`
**Class:** `Supervisor`
**Erlang/OTP equivalent:** `supervisor` behaviour

---

## Overview

`Supervisor` manages a set of named child processes (`ProcRef<?,?>` instances). When a child crashes, the supervisor restarts it according to the configured strategy. Restarts exceeding the sliding-window limit (`maxRestarts` within `withinSeconds`) cause the supervisor itself to terminate.

Children are tracked by `String` name. Each child is defined by a `Supplier<ProcRef<?,?>>` factory that is re-invoked on every restart.

---

## Builder API

```java
public final class Supervisor {

    public static Builder builder();

    public final class Builder {

        public Builder strategy(Strategy strategy);

        public Builder maxRestarts(int n);

        public Builder withinSeconds(int seconds);

        public Supervisor build();
    }
}
```

### Builder Methods

| Method | Parameter | Default | Description |
|---|---|---|---|
| `strategy(strategy)` | `Strategy strategy` | `ONE_FOR_ONE` | Sets the restart strategy applied when a child crashes. |
| `maxRestarts(n)` | `int n` | `3` | Maximum number of restarts permitted within the sliding window. |
| `withinSeconds(seconds)` | `int seconds` | `5` | Sliding window duration in seconds for counting restarts. |
| `build()` | — | — | Constructs and starts the `Supervisor`. Returns the ready instance. |

---

## Restart Strategies

```java
public enum Strategy {
    ONE_FOR_ONE,
    ONE_FOR_ALL,
    REST_FOR_ONE
}
```

| Strategy | Erlang/OTP | Behaviour on child crash |
|---|---|---|
| `ONE_FOR_ONE` | `one_for_one` | Only the crashed child is stopped and restarted. All other children continue running. |
| `ONE_FOR_ALL` | `one_for_all` | All children are stopped in reverse start order, then restarted in original start order. |
| `REST_FOR_ONE` | `rest_for_one` | The crashed child and all children started after it are stopped in reverse start order, then restarted in original start order. |

---

## Runtime API

```java
public final class Supervisor {

    public ProcRef<?, ?> startChild(String name, Supplier<ProcRef<?, ?>> factory);

    public void stopChild(String name);

    public boolean isRunning();

    public void shutdown();
}
```

### Runtime Methods

| Method | Parameters | Returns | Throws | Description |
|---|---|---|---|---|
| `startChild(name, factory)` | `String name` — unique child identifier; `Supplier<ProcRef<?,?>> factory` — spawns the process | `ProcRef<?,?>` | `IllegalArgumentException` if `name` is already registered | Registers and starts a new child. The factory is called immediately and again on each restart. Returns the `ProcRef` from the initial factory call. |
| `stopChild(name)` | `String name` | `void` | `IllegalArgumentException` if `name` is not found | Calls `stop()` on the named child and removes it from supervision. Does not count as a crash; does not trigger restart logic. |
| `isRunning()` | — | `boolean` | — | Returns `true` if the supervisor's internal thread is alive and the restart window has not been exceeded. |
| `shutdown()` | — | `void` | — | Stops all children in reverse start order, then stops the supervisor. Blocks until complete. |

---

## Child Spec (Implicit)

There is no explicit `ChildSpec` record. The child specification is composed of:

| Field | Java representation | Description |
|---|---|---|
| Name | `String name` passed to `startChild` | Unique identifier within this supervisor. |
| Factory | `Supplier<ProcRef<?,?>> factory` passed to `startChild` | Re-invoked on every restart. Must produce a new, live `ProcRef`. |
| Restart type | Always `PERMANENT` (implicit) | Every crash triggers a restart attempt. |

---

## Restart Window

The supervisor counts restarts using a sliding window:

- A restart is counted each time a child is restarted due to a crash.
- Restarts older than `withinSeconds` seconds are dropped from the count.
- If the count reaches `maxRestarts` within the window, the supervisor calls `shutdown()` on itself and throws a `SupervisorMaxRestartsException`.

```
Time →   0s    1s    2s    3s    4s    5s    6s
Events:  R1    R2    R3    --    R4    --    --
Window:  [R1,R2,R3]       [R2,R3,R4]
                           ^ maxRestarts=3 → supervisor crashes at R4
```

With default settings (`maxRestarts=3`, `withinSeconds=5`): up to 3 restarts per any 5-second window.

---

## ProcessMonitor — Unilateral DOWN Notifications

```java
public final class ProcessMonitor {

    public static MonitorRef monitor(ProcRef<?, ?> target);

    public static void demonitor(MonitorRef ref);
}
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `ProcessMonitor.monitor(target)` | `ProcRef<?,?> target` | `MonitorRef` | Registers interest in `target`'s exit. When `target` stops or crashes, a DOWN notification is delivered. Equivalent to `erlang:monitor(process, Pid)`. |
| `ProcessMonitor.demonitor(ref)` | `MonitorRef ref` | `void` | Cancels the monitor. No DOWN is delivered after this returns. Equivalent to `erlang:demonitor/1`. |

### MonitorRef

```java
public sealed interface MonitorRef {

    void awaitDown() throws InterruptedException;
}
```

| Method | Returns | Description |
|---|---|---|
| `awaitDown()` | `void` | Blocks the calling thread until the monitored process exits (normally or abnormally). |

When the monitoring process has `trapExits(true)` set, a `DownSignal` is additionally delivered to its mailbox:

```java
public record DownSignal(MonitorRef ref, ProcRef<?, ?> process, Throwable cause) {}
```

| Field | Description |
|---|---|
| `ref` | The `MonitorRef` that triggered this signal. |
| `process` | The process that exited. |
| `cause` | `null` for normal exit; exception for crash. |

---

## ProcessRegistry — Global Name Table

```java
public final class ProcessRegistry {

    public static void register(String name, ProcRef<?, ?> ref);

    public static Optional<ProcRef<?, ?>> whereis(String name);

    public static void unregister(String name);

    public static Set<String> registered();

    public static void reset();
}
```

| Method | Parameters | Returns | Throws | Description |
|---|---|---|---|---|
| `register(name, ref)` | `String name`, `ProcRef<?,?> ref` | `void` | `IllegalArgumentException` if `name` is already registered | Registers `ref` under `name`. Auto-deregisters when `ref` stops or crashes. Equivalent to `erlang:register/2`. |
| `whereis(name)` | `String name` | `Optional<ProcRef<?,?>>` | — | Returns the registered process, or `Optional.empty()` if not found. Equivalent to `erlang:whereis/1`. |
| `unregister(name)` | `String name` | `void` | — | Removes the registration. No-op if not found. Equivalent to `erlang:unregister/1`. |
| `registered()` | — | `Set<String>` | — | Returns an immutable snapshot of all currently registered names. Equivalent to `erlang:registered/0`. |
| `reset()` | — | `void` | — | Clears all registrations. **Test use only.** |

---

## ProcTimer — Timed Message Delivery

```java
public final class ProcTimer {

    public static TimerHandle sendAfter(
        Duration delay,
        ProcRef<?, ?> target,
        Object message
    );

    public static TimerHandle sendInterval(
        Duration period,
        ProcRef<?, ?> target,
        Object message
    );

    public static void cancel(TimerHandle handle);
}
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `sendAfter(delay, target, message)` | `Duration delay`, `ProcRef<?,?> target`, `Object message` | `TimerHandle` | Delivers `message` to `target`'s mailbox once after `delay`. Equivalent to `timer:send_after/3`. |
| `sendInterval(period, target, message)` | `Duration period`, `ProcRef<?,?> target`, `Object message` | `TimerHandle` | Delivers `message` to `target`'s mailbox repeatedly every `period`. Equivalent to `timer:send_interval/3`. |
| `cancel(handle)` | `TimerHandle handle` | `void` | Cancels the timer. Messages already enqueued in the mailbox may still be delivered. Equivalent to `timer:cancel/1`. |

### TimerHandle

```java
public sealed interface TimerHandle {
    boolean isCancelled();
}
```

---

## Supervisor Crash Propagation

```
Child crashes
      │
      ▼
Supervisor increments restart counter
      │
      ├── counter <= maxRestarts in window? ──► Restart per strategy
      │
      └── counter > maxRestarts in window? ──► Supervisor.shutdown()
                                                     │
                                                     ▼
                                               SupervisorMaxRestartsException
                                               (propagates to supervisor's parent
                                                if nested in supervision tree)
```

---

## Nested Supervisors

Supervisors can supervise other supervisors. Pass a `Supervisor`'s `ProcRef` as a child factory:

```java
var childSupervisor = Supervisor.builder()
    .strategy(Strategy.ONE_FOR_ONE)
    .build();

var root = Supervisor.builder()
    .strategy(Strategy.ONE_FOR_ALL)
    .build();

root.startChild("child-supervisor", () -> childSupervisor.supervisorRef());
```

The `supervisorRef()` method returns the `ProcRef<?,?>` backing the supervisor's internal process.
