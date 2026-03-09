# OTP Primitives — Quick Reference

**Package:** `org.acme`
**Count:** 15 primitives

---

## All 15 Primitives

| Primitive | Class(es) | Erlang/OTP Equivalent | Thread Model | Key Factory / Entry Point |
|---|---|---|---|---|
| Lightweight process | `Proc<S,M>`, `ProcRef<S,M>` | `spawn/3`, `Pid` | One virtual thread per process | `Proc.of(initialState, handler)` |
| Supervision tree | `Supervisor` | `supervisor` behaviour | Supervisor runs on its own virtual thread | `Supervisor.builder().build()` |
| Crash recovery | `CrashRecovery` | `supervisor` (simple_one_for_one) | Isolated virtual threads per attempt | `CrashRecovery.of(maxAttempts, supplier)` |
| State machine | `StateMachine<S,E,D>` | `gen_statem` | Shares caller thread for transitions | `StateMachine.builder().build()` |
| Process links | `ProcessLink` | `link/1`, `spawn_link/3` | N/A (crash-propagation mechanism) | `ProcessLink.link(a, b)` |
| Structured fan-out | `Parallel` | `pmap`, `Task` | `StructuredTaskScope` (virtual threads) | `Parallel.all(tasks)` |
| Process monitor | `ProcessMonitor`, `MonitorRef` | `monitor/2`, `demonitor/1` | N/A (DOWN-notification mechanism) | `ProcessMonitor.monitor(target)` |
| Name registry | `ProcessRegistry` | `register/2`, `whereis/1` | Thread-safe global table | `ProcessRegistry.register(name, ref)` |
| Timed messages | `ProcTimer`, `TimerHandle` | `timer:send_after/3`, `timer:send_interval/3` | ScheduledExecutorService | `ProcTimer.sendAfter(delay, target, msg)` |
| Exit signal | `ExitSignal` | `EXIT` message (trap_exit) | Delivered via mailbox | `ProcRef.trapExits(true)` |
| Process introspection | `ProcSys` | `sys` module | Non-intrusive snapshots | `ProcSys.getState(ref)` |
| Startup handshake | `ProcLib` | `proc_lib:start_link/3` | Blocks caller until `initAck()` | `ProcLib.startLink(factory)` |
| Event manager | `EventManager<E>` | `gen_event` | One virtual thread per manager | `EventManager.create()` |
| Stable PID | `ProcRef<S,M>` | `Pid` (registered name-like) | N/A (handle) | Returned by `Proc.of(...)` |
| Railway result | `Result<T,E>` | (functional, not OTP) | N/A (value type) | `Result.success(v)`, `Result.failure(e)`, `Result.of(supplier)` |

---

## Primitive Details

### Proc<S,M> / ProcRef<S,M>

- **State type `S`:** Immutable value passed to and returned from the handler on every message.
- **Message type `M`:** Any type; the handler receives each message from the mailbox in order.
- **Handler:** `BiFunction<S, M, S>` — pure; must not throw unless intentional crash.
- **Crash:** Uncaught exception from the handler terminates the virtual thread; `onCrash` consumer fires (does not fire on `stop()`).
- **trapExits:** When `true`, `ExitSignal` records are enqueued in the mailbox instead of killing the process.

### Supervisor

- **Child spec:** A `String name` + `Supplier<ProcRef<?,?>> factory` (factory is re-invoked on restart).
- **Restart counting:** Sliding window — at most `maxRestarts` restarts within `withinSeconds` seconds; exceeding the limit crashes the supervisor itself.
- **Child ordering:** Children are started in insertion order; `REST_FOR_ONE` uses this order.

### CrashRecovery

- **Isolation:** Each attempt runs in a freshly created virtual thread.
- **Return:** Returns the value from the first successful attempt.
- **Failure:** Throws the last caught exception if all attempts are exhausted.

### StateMachine<S,E,D>

- **S:** State enum or sealed type.
- **E:** Event type (each handler registered per `Class<E>`).
- **D:** Arbitrary data carried between transitions.
- **Transition sealed hierarchy:** `Stay<S,D>` | `MoveTo<S,D>` | `Stop<S,D>`.

### ProcessLink

- **Bidirectional:** Crash of either linked process kills the other.
- **Escape hatch:** `trapExits(true)` on a process converts the kill into an `ExitSignal` message.

### Parallel

- **`all`:** Fail-fast — first exception cancels remaining tasks and is rethrown.
- **`allSettled`:** Collects all results; exceptions are wrapped, not rethrown.
- **Concurrency:** All tasks run concurrently on virtual threads via `StructuredTaskScope`.

### ProcessMonitor

- **Unilateral:** Monitoring process is not killed when target exits.
- **`DOWN` delivery:** `MonitorRef.awaitDown()` blocks; when `trapExits(true)`, `DownSignal` arrives in mailbox.
- **`demonitor`:** Cancels the monitor; no `DOWN` delivered after cancellation.

### ProcessRegistry

- **Uniqueness:** `register` throws if the name is already taken.
- **Auto-deregister:** Entry is removed automatically when the registered process stops or crashes.
- **`reset()`:** Clears all registrations — intended for test isolation only.

### ProcTimer

- **`sendAfter`:** Delivers `message` to `target` once after `delay`.
- **`sendInterval`:** Delivers `message` to `target` repeatedly every `period`.
- **`cancel`:** Stops future deliveries; messages already in transit may still arrive.

### ExitSignal

- **`cause == null`:** Normal exit (process called `stop()`).
- **`cause != null`:** Abnormal exit (uncaught exception from handler).

### ProcSys

- **`suspend`:** Pauses mailbox processing; messages continue to enqueue.
- **`resume`:** Resumes mailbox processing from where it paused.
- **`statistics`:** Returns `ProcStats` (message count, uptime, queue depth).

### ProcLib

- **Blocking:** `startLink` blocks the caller until the child's virtual thread calls `initAck()`.
- **Failure:** If the child crashes before calling `initAck()`, `startLink` throws.

### EventManager<E>

- **`notify`:** Asynchronous; handler exceptions crash that handler's internal invocation but do not kill the manager.
- **`syncNotify`:** Synchronous; blocks until all handlers complete.
- **`call`:** Allows synchronous read of internal state without stopping the manager.

### Result<T,E>

- **Sealed:** `Result` is a sealed interface with two permitted records: `Success<T,E>` and `Failure<T,E>`.
- **`of(supplier)`:** Catches any `Throwable` thrown by `supplier`; wraps it as `Failure`.
- **`recover`:** Converts a `Failure` to a `Success` by applying a mapping function to the error.
- **`peek`:** Side-effect only; does not alter the `Result`.
