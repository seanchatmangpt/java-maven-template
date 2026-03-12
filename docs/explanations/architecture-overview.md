# Explanations: Architecture Overview

Understanding JOTP's design at 30,000 feet.

## What is JOTP?

**JOTP** (Joe's OTP) is a pure Java 26 implementation of Erlang/OTP's 15 architectural primitives. It proves that Java 26—with virtual threads, sealed types, structured concurrency, and pattern matching—can express all meaningful OTP patterns idiomatically, without the BEAM VM.

## The Foundation: Virtual Threads

JOTP's architecture rests on **virtual threads** (JEP 444, Java 21+). These are lightweight threads that run on a small pool of platform threads:

```
100,000+ virtual threads
         ↓
    JVM Thread Scheduler
         ↓
    8-16 platform threads (OS threads)
```

This 1000:1 virtual-to-platform ratio enables JOTP to match BEAM's lightweight concurrency model. A single machine can host millions of JOTP processes.

## Core Primitives (15 Total)

JOTP implements Joe Armstrong's 15 OTP building blocks:

### 1. **Lightweight Processes** (`Proc<S,M>`)
- State holder: `S` (any serializable type)
- Message receiver: `M` (sealed interface or union type)
- Handler: pure function `(state) → (msg) → new_state`
- Runs in a virtual thread
- OTP equivalent: `spawn/3`

```java
var proc = Proc.start(
    state -> msg -> state + 1,
    0
);
proc.send(5);
var final = proc.ask(msg -> msg, Duration.ofSeconds(1));
```

### 2. **Process References** (`ProcRef<S,M>`)
- Opaque handle to a process
- Survives supervisor restarts
- Cannot be forged or intercepted
- OTP equivalent: Pid

### 3. **Supervision Trees** (`Supervisor`)
- Hierarchical process manager
- Three restart strategies: ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE
- Automatic restart on crash
- OTP equivalent: `supervisor` behavior

### 4. **Crash Recovery** (`CrashRecovery`)
- Isolated retry mechanism
- Wraps fallible operations
- Exponential backoff (configurable)
- OTP equivalent: "let it crash" + application restarter

### 5-15. **Additional Primitives**

| Name | Java Class | OTP | Purpose |
|------|-----------|-----|---------|
| State Machine | `StateMachine<S,E,D>` | `gen_statem` | Complex state/event handling |
| Process Links | `ProcessLink` | `link/1` | Bilateral crash propagation |
| Parallel Execution | `Parallel` | `pmap` | Structured fan-out, fail-fast |
| Process Monitors | `ProcessMonitor` | `monitor/2` | Unilateral DOWN notifications |
| Process Registry | `ProcessRegistry` | `register/2` | Global name table |
| Timers | `ProcTimer` | `timer:send_after/3` | Timed message delivery |
| Exit Signals | `ExitSignal` | exit signals | Crash notifications |
| Process Introspection | `ProcSys` | `sys:*` | Runtime process inspection |
| Startup Handshake | `ProcLib` | `proc_lib` | Synchronous start guarantee |
| Event Manager | `EventManager<E>` | `gen_event` | Typed event broadcast |
| Railway Error Handling | `Result<T,E>` | `{:ok, v} \| {:error, r}` | Functional error handling |

## Design Patterns

### Pattern 1: Message-Driven State Machines

```
┌─────────────────────────────────────┐
│  Proc<State, Message>               │
│  ─────────────────────────────────  │
│  state: S (immutable)               │
│  handler: (s) → (m) → S             │
│  queue: incoming messages (FIFO)    │
└─────────────────────────────────────┘
         ↓ (async)
    Send message → Queue → Handler → New state
```

Each process is a simple loop:
1. Wait for message
2. Apply handler: `new_state = handler(current_state, msg)`
3. Update state
4. Repeat

### Pattern 2: Supervision Hierarchies

```
          RootSupervisor
         /     |      \
        /      |       \
    Worker  Worker   Worker
       |       |       |
    Proc    Proc     Proc
```

Each supervisor manages child processes/supervisors with a restart strategy.

### Pattern 3: Request-Reply (Synchronous Over Async)

```
Process A                Process B
   |                        |
   |--ask(msg)→             |
   |  (blocks)              |
   |              ←reply--   |
   |←(unblocks)            |
```

The `ask()` method wraps fire-and-forget with a reply channel and timeout.

### Pattern 4: Railway-Oriented Programming

```java
Result<T, E> = Success(T) | Failure(E)

Success(5)
  .map(x → x * 2)      // Success(10)
  .flatMap(x → divide(x, 2))  // Success(5)
  .recover(err → 0)    // Still Success(5) or Failure→Success(0)
```

Functional error handling without checked exceptions.

## Why Java 26?

JOTP requires Java 26 for these features:

| Feature | JEP | Use in JOTP |
|---------|-----|------------|
| Virtual Threads | 444 | Lightweight process implementation |
| Structured Concurrency | 453 | Task scope management, fail-fast |
| Sealed Types | 409 | Safe pattern matching for messages |
| Pattern Matching | (multi-JEP) | Exhaustive message routing |
| Records | 395 | Message definition (sealed record hierarchy) |

Previous Java versions lack one or more of these, making true OTP idioms impossible.

## Module Structure

```
jotp/
├── src/main/java/io/github/seanchatmangpt/jotp/
│   ├── Proc.java              # Core lightweight process
│   ├── Supervisor.java        # Restart strategy
│   ├── StateMachine.java      # Complex state machines
│   ├── Result.java            # Railway error handling
│   └── ... (15 classes total)
├── src/test/java/io/github/seanchatmangpt/jotp/
│   └── ... (comprehensive test suite)
├── docs/                      # Diataxis documentation
│   ├── tutorials/             # Getting started
│   ├── how-to/                # Problem-oriented guides
│   ├── explanations/          # Deep conceptual dives
│   ├── reference/             # API lookup
│   └── examples/              # Code snippets
└── templates/java/            # Code generation (72 templates)
```

## Quality Gates

JOTP enforces three "guards" during build:

| Guard | Pattern | Meaning |
|-------|---------|---------|
| H_TODO | TODO, FIXME, XXX in production code | Deferred work |
| H_MOCK | mock, stub, fake in production code | Test doubles in prod |
| H_STUB | empty/placeholder returns | Incomplete implementations |

The build fails if these patterns appear in production code, ensuring code quality.

## Comparison: BEAM vs. Java 26

| Aspect | BEAM (Erlang) | JOTP (Java 26) |
|--------|---------------|----------------|
| Processes | 250M+/machine | 1M+/machine |
| Language | Erlang/Elixir | Java (Java 26) |
| Latency | 1-10µs per message | 1-10µs per message |
| Distribution | Built-in clustering | Future (planned) |
| Hot reload | Yes | Via class loader (planned) |
| Error recovery | gen_restart | CrashRecovery + Supervisor |
| Typing | Dynamic | Static (Java types) |

## Roadmap

| Phase | Status | Description |
|-------|--------|-------------|
| Core Primitives | ✅ Complete | All 15 OTP primitives implemented |
| Documentation | 🔄 In Progress | Diataxis-based learning paths |
| Code Generation | ✅ Complete | 72 templates + RefactorEngine |
| Performance Tuning | 📋 Planned | Virtual thread optimization |
| Distributed JOTP | 📋 Planned | Multi-node message passing |

## What Makes JOTP Different

1. **Idiomatic Java** — Uses Java 26 features naturally, not fighting the language
2. **Pure Java** — No JNI, no external runtime, no BEAM dependency
3. **Static Types** — Java's type system prevents entire classes of errors
4. **Modern Concurrency** — Virtual threads, not OS threads or coroutines
5. **Zero-Cost Abstractions** — Primitives compile to simple Java code
6. **Enterprise Ready** — Integrates with Java ecosystem (Maven, logging, etc.)

## Next Steps

- **[OTP Equivalence](otp-equivalence.md)** — Formal proof of equivalence
- **[Concurrency Model](concurrency-model.md)** — How JOTP uses virtual threads
- **[Design Decisions](design-decisions.md)** — Why JOTP made specific choices
- **[Tutorial: Getting Started](../tutorials/01-getting-started.md)** — Hands-on introduction

---

**See Also:** [PhD Thesis: OTP 28 in Pure Java 26](../phd-thesis/otp-28-java26.md) — Academic foundation and benchmarks
