# Reference: API Overview

Complete documentation of all 15 JOTP primitives.

## Overview

JOTP's public API consists of 15 core classes implementing Joe Armstrong's OTP primitives. This reference provides signatures, behavior, and examples for each.

## Quick Navigation

| Primitive | Class | OTP Equivalent | Module | Status |
|-----------|-------|----------------|--------|--------|
| Lightweight Process | `Proc<S,M>` | `spawn/3` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Process Reference | `ProcRef<S,M>` | Pid | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Supervision | `Supervisor` | `supervisor` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Crash Recovery | `CrashRecovery` | let-it-crash | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| State Machine | `StateMachine<S,E,D>` | `gen_statem` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Process Links | `ProcessLink` | `link/1` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Parallel Execution | `Parallel` | `pmap` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Process Monitors | `ProcessMonitor` | `monitor/2` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Process Registry | `ProcessRegistry` | `register/2` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Timers | `ProcTimer` | `timer:send_after/3` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Exit Signals | `ExitSignal` | exit signals | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Process Introspection | `ProcSys` | `sys:get_state/1` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Startup Handshake | `ProcLib` | `proc_lib:start_link/3` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Event Manager | `EventManager<E>` | `gen_event` | `io.github.seanchatmangpt.jotp` | ✅ Stable |
| Railway Error Handling | `Result<T,E>` | `{:ok, V} \| {:error, R}` | `io.github.seanchatmangpt.jotp` | ✅ Stable |

## Core API Methods

### Proc<S,M> — Lightweight Process

```java
// Create a new process
static <S,M> ProcRef<S,M> start(
    Function<S, Function<M, S>> handler,
    S initialState
)

// Send a message (async, non-blocking)
void send(M message)

// Send a message and wait for response (sync, with timeout)
<R> R ask(Function<ProcRef<R, M>, M> request, Duration timeout)
    throws TimeoutException

// Query if process is still alive
boolean isAlive()

// Terminate the process
void exit(String reason)

// Enable/disable exit signal trapping
void trapExits(boolean trap)
```

**Example:**
```java
var proc = Proc.start(
    state -> msg -> state + (Integer)msg,
    0
);
proc.send(5);
int result = proc.ask(replyTo -> 10, Duration.ofSeconds(1));  // 15
```

### Supervisor — Hierarchical Restart

```java
// Create ONE_FOR_ONE supervisor (restart only crashed child)
static SupervisorBuilder oneForOne()

// Create ONE_FOR_ALL supervisor (restart all on any crash)
static SupervisorBuilder oneForAll()

// Create REST_FOR_ONE supervisor (restart failed + dependents)
static SupervisorBuilder restForOne()

// Add a child process to supervisor
SupervisorBuilder add(String name, Supplier<ProcRef<?, ?>> factory)

// Build the supervisor
ProcRef<Void, ?> build()

// Look up child by name
ProcRef<?, ?> whereis(String name)
```

**Example:**
```java
var sup = Supervisor.oneForOne()
    .add("service1", Service1::create)
    .add("service2", Service2::create)
    .build();

var service = sup.whereis("service1");
```

## Detailed API Documentation

Click below for deep dives into specific primitives:

- **[Proc<S,M> Reference](api-proc.md)** — Lightweight process detailed API
- **[Supervisor Reference](api-supervisor.md)** — Supervision tree detailed API
- **[StateMachine<S,E,D> Reference](api-statemachine.md)** — State machine detailed API

## Module Structure

```
io.github.seanchatmangpt.jotp
├── Proc<S,M>              # Lightweight process
├── ProcRef<S,M>           # Process reference
├── Supervisor             # Supervision tree
├── CrashRecovery          # Isolated retries
├── StateMachine<S,E,D>    # Complex state machines
├── ProcessLink            # Bilateral crash links
├── Parallel<T>            # Structured concurrency
├── ProcessMonitor         # Unilateral monitoring
├── ProcessRegistry        # Global name table
├── ProcTimer              # Timed delivery
├── ExitSignal             # Crash notifications
├── ProcSys                # Process introspection
├── ProcLib                # Startup handshake
├── EventManager<E>        # Event broadcast
└── Result<T,E>            # Railway error handling
```

## Import Pattern

```java
import io.github.seanchatmangpt.jotp.*;
```

All types are in the main `io.github.seanchatmangpt.jotp` package.

## Type Parameters

### Generic Types

- `<S>` — State type (any serializable Java type)
- `<M>` — Message type (sealed interface or union)
- `<E>` — Event type (for EventManager)
- `<T>` — Success type (for Result)
- `<E>` — Error type (for Result)

## Common Patterns

### Fire-and-Forget Messaging

```java
proc.send(msg);  // Returns immediately, message queued
```

### Request-Reply

```java
Response resp = proc.ask(
    replyTo -> new RequestMsg(replyTo),
    Duration.ofSeconds(5)
);
```

### Error Handling

```java
Result<Integer, String> result = Result.of(() -> risky());
result.fold(
    success -> System.out.println("OK: " + success),
    failure -> System.out.println("Error: " + failure)
);
```

## Performance Characteristics

| Operation | Time Complexity | Notes |
|-----------|-----------------|-------|
| `send()` | O(1) | Lock-free queue, amortized |
| `ask()` | O(1) + network latency | Blocks caller |
| `Supervisor.whereis()` | O(log n) | Ordered map lookup |
| Process creation | O(1) | Virtual thread overhead |
| Message processing | ~1-5µs | Per message, single-threaded handler |

## What's Next?

- **[Proc<S,M> Detailed Reference](api-proc.md)** — Full Proc API
- **[Supervisor Detailed Reference](api-supervisor.md)** — Full Supervisor API
- **[StateMachine Detailed Reference](api-statemachine.md)** — Full StateMachine API
- **[Configuration Reference](configuration.md)** — JOTP runtime settings
- **[Glossary](glossary.md)** — Key terms and definitions
- **[Troubleshooting](troubleshooting.md)** — Common issues and solutions

---

**Related:** [How-To: Create Lightweight Processes](../how-to/create-lightweight-processes.md) | [Architecture Overview](../explanations/architecture-overview.md)
