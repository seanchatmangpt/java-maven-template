# JOTP Concurrency Model

JOTP (Java OTP) maps Joe Armstrong's nine Erlang/OTP primitives to Java 26 constructs. Understanding this mapping is essential for reading the pattern implementations in this book.

## The Fifteen Primitives

| JOTP Class | OTP Equivalent | Description |
|---|---|---|
| `Proc<S,M>` | `spawn/3` | Lightweight process: virtual-thread mailbox + pure state handler |
| `ProcRef<S,M>` | `Pid` | Stable handle that survives supervisor restarts |
| `Supervisor` | `supervisor` | ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE trees |
| `CrashRecovery` | `let it crash` | Supervised retry via isolated virtual threads |
| `StateMachine<S,E,D>` | `gen_statem` | State/event/data separation |
| `ProcessLink` | `link/1` | Bilateral crash propagation |
| `Parallel` | `pmap` | Structured fan-out (StructuredTaskScope) |
| `ProcessMonitor` | `monitor/2` | Unilateral DOWN notifications |
| `ProcessRegistry` | `global:register_name` | Global name table |
| `ProcTimer` | `timer:send_after/3` | Timed message delivery |
| `ExitSignal` | `EXIT` message | Exit signal when trapping exits |
| `ProcSys` | `sys` module | `get_state`, `suspend`, `resume`, `statistics` |
| `ProcLib` | `proc_lib` | Startup handshake: `start_link` blocks until `initAck()` |
| `EventManager<E>` | `gen_event` | Typed event manager with fault-isolated handlers |
| `Proc.trapExits` / `.ask` | `process_flag(trap_exit)` / `gen_server:call` | Exit trapping + timed calls |

## Core Process Lifecycle

```
new Proc<>(initial, handler)
    │
    ▼ spawns virtual thread
┌───────────────────────────────────────┐
│  Proc virtual thread                  │
│                                       │
│  state = initial                      │
│  loop:                                │
│    msg = mailbox.poll(50ms)           │
│    state = handler.apply(state, msg)  │
│    if ask: reply with new state       │
└───────────────────────────────────────┘
    │
    ▼ proc.tell(msg)     → mailbox.add(envelope)
    ▼ proc.ask(msg)      → mailbox.add(envelope+future) → future<S>
    ▼ proc.stop()        → thread.interrupt() + thread.join()
```

## Why Virtual Threads for Messaging?

Java 26 virtual threads are scheduled by the JVM onto a small pool of platform threads. This gives:

- **Low overhead**: ~1 KB heap per virtual thread vs. ~512 KB for platform threads
- **Blocking I/O**: virtual threads block cheaply — no async/callback hell
- **OTP semantics**: one virtual thread = one Erlang process, one mailbox

```java
// Erlang: spawn(fun() -> loop() end)
// JOTP:
var proc = new Proc<>(initialState, handler);

// Erlang: Pid ! Message
// JOTP:
proc.tell(message);

// Erlang: gen_server:call(Pid, Request)
// JOTP:
S newState = proc.ask(request).get(2, TimeUnit.SECONDS);
```

## Fault Isolation

The handler is a pure function `(S state, M msg) → S nextState`. If it throws, the Proc records `lastError` and terminates — exactly as an Erlang process terminates with a non-normal exit reason. A `Supervisor` restarts it.

```java
// "Let it crash" — the Supervisor handles recovery
var sup = new Supervisor("my-sup", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(1));
var channel = sup.supervise("worker", initialState, handler);
channel.tell(problematicMessage); // crashes → supervisor restarts worker
```

## The sys Module

`ProcSys` provides runtime introspection without stopping the process:

```java
// OTP: sys:get_state(Pid)
S state = ProcSys.getState(proc).get();

// OTP: sys:statistics(Pid, get)
ProcSys.Stats stats = ProcSys.statistics(proc);
System.out.println(stats.messagesIn() + " msgs processed");

// OTP: sys:suspend(Pid) / sys:resume(Pid)
ProcSys.suspend(proc);
// ... hot code swap ...
ProcSys.resume(proc);
```
