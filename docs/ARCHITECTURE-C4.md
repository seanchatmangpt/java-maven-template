# JOTP Architecture - C4 Model Documentation

## Overview

This document provides a comprehensive visual architecture of **JOTP** (`io.github.seanchatmangpt.jotp`) — a complete Java 26 implementation of Erlang/OTP. The architecture is documented using the **C4 Model**, which describes software systems at four levels of abstraction:

1. **System Context** — JOTP in the broader Java ecosystem
2. **Containers** — Major logical groupings of the 15 OTP primitives
3. **Components** — Detailed interactions between primitives
4. **Code** — Individual classes and relationships

---

## Table of Contents

1. [C4 Level 1: System Context](#c4-level-1-system-context)
2. [C4 Level 2: Containers](#c4-level-2-containers)
3. [C4 Level 3: Components](#c4-level-3-components)
4. [C4 Level 4: Class Details](#c4-level-4-class-details)
5. [Sequence & Flow Diagrams](#sequence--flow-diagrams)
6. [Process Behavior Diagrams](#process-behavior-diagrams)
7. [System Integration Diagrams](#system-integration-diagrams)
8. [Architecture Overview Diagrams](#architecture-overview-diagrams)
9. [Erlang/OTP Comparison](#erlang-otp-comparison)
10. [The 15 OTP Primitives Reference](#the-15-otp-primitives)
11. [Common Patterns & Use Cases](#common-patterns--use-cases)
12. [Integration & References](#integration--references)

---

## C4 Level 1: System Context

**Diagram:** `docs/diagrams/c4-jotp-01-system-context.puml`

### Purpose
Shows JOTP as a black box within the Java ecosystem, identifying the key external actors and systems.

### Key Entities

| Entity | Role |
|--------|------|
| **Java Developer** | Primary user; builds fault-tolerant distributed systems |
| **io.github.seanchatmangpt.jotp** | The JOTP framework itself; 15 core OTP primitives |
| **Java 26 Runtime** | Execution environment; provides virtual threads, structured concurrency |
| **Observability System** | Receives metrics/logs via `ProcSys` introspection module |

### Key Relationships

- **Developer → JOTP:** Uses JOTP to build fault-tolerant systems
- **JOTP → Java 26 Runtime:** Runs on, leveraging virtual threads and structured concurrency
- **JOTP → Observability:** Exposes process state, metrics, statistics via introspection

---

## C4 Level 2: Containers

**Diagram:** `docs/diagrams/c4-jotp-02-containers.puml`

### Purpose
Decomposes JOTP into 6 logical containers, each grouping related OTP primitives by function.

### Container Breakdown

#### 1. **Process Container** — Core Process Abstractions
*Handles process creation, lifecycle, and linking*

**Primitives:**
- `Proc<S,M>` — Virtual thread handler + mailbox (OTP: `spawn/3`)
- `ProcRef<S,M>` — Stable opaque process reference (OTP: Pid)
- `ProcessLink` — Bilateral crash propagation (OTP: `link/1`)
- `ProcessMonitor` — Unilateral monitoring (OTP: `monitor/2`)
- `ProcLib` — Startup handshake (OTP: `proc_lib`)

**Key Responsibility:** Managing process identity, messaging, and linking semantics.

**Code Location:** `src/main/java/org/acme/` → `Proc.java`, `ProcRef.java`, `ProcessLink.java`, `ProcessMonitor.java`, `ProcLib.java`

---

#### 2. **Supervision Container** — Fault Tolerance & Recovery
*Handles process crashes and recovery strategies*

**Primitives:**
- `Supervisor` — Supervision tree with restart strategies (OTP: `supervisor`)
- `CrashRecovery` — Isolated recovery execution (OTP: gen_server crash handling)
- `ExitSignal` — Exit signal record (OTP: exit signal)

**Strategies Supported:**
- `ONE_FOR_ONE` — Restart only failed child
- `ONE_FOR_ALL` — Restart all children if one fails
- `REST_FOR_ONE` — Restart failed + descendants

**Key Responsibility:** Implementing Erlang's "let it crash" philosophy with configurable restart strategies.

**Code Location:** `src/main/java/org/acme/` → `Supervisor.java`, `CrashRecovery.java`, `ExitSignal.java`

---

#### 3. **Concurrency Container** — Structured Parallel Execution
*Handles parallel fan-out with fail-fast semantics*

**Primitives:**
- `Parallel` — Structured concurrency wrapper (OTP: `pmap`)

**Key Responsibility:** Spawning multiple processes in parallel with guaranteed cleanup and error propagation.

**Code Location:** `src/main/java/org/acme/` → `Parallel.java`

---

#### 4. **State Management Container** — State & Event Handling
*Manages state transitions and event dispatch*

**Primitives:**
- `StateMachine<S,E,D>` — State/event/data separation (OTP: `gen_statem`)
- `EventManager<E>` — Typed event dispatch (OTP: `gen_event`)
- `ProcessRegistry` — Global name registry (OTP: `global` / `pg`)

**Key Responsibility:** Providing deterministic state machines and fault-isolated event handling.

**Code Location:** `src/main/java/org/acme/` → `StateMachine.java`, `EventManager.java`, `ProcessRegistry.java`

---

#### 5. **Timing Container** — Time-Based Operations
*Handles delayed and periodic message delivery*

**Primitives:**
- `ProcTimer` — Timed message delivery (OTP: `timer:send_after/3`, `timer:send_interval/3`)
- `ProcSys` — Process introspection (OTP: `sys:*`)

**Key Responsibility:** Scheduling timed messages and providing process introspection without stopping processes.

**Code Location:** `src/main/java/org/acme/` → `ProcTimer.java`, `ProcSys.java`

---

#### 6. **Error Handling Container** — Result Type
*Railway-oriented programming for error handling*

**Primitives:**
- `Result<S,F>` — Sealed success/failure type

**Key Responsibility:** Providing composable error handling without unchecked exceptions.

**Code Location:** `src/main/java/org/acme/` → `Result.java`

---

## C4 Level 3: Components

**Diagram:** `docs/diagrams/c4-jotp-03-components.puml`

### Purpose
Shows all 15 primitives as components with detailed interaction arrows.

### Component Interactions

**Process Creation & Initialization:**
```
ProcLib.start_link() → spawns Proc → identifies via ProcRef → initializes
```

**Supervision & Recovery:**
```
Proc crashes → ExitSignal → Supervisor → CrashRecovery → new Proc
```

**Monitoring & Linking:**
```
Proc links with ProcessLink (bilateral)
Proc monitored by ProcessMonitor (unilateral)
```

**Message Dispatch:**
```
Proc.send(message) → mailbox → StateMachine | EventManager → reply
```

**Naming & Discovery:**
```
ProcessRegistry.register("name", procRef) → whereis/1 lookup
```

**Timed Messages:**
```
ProcTimer.send_after(delay, receiver, msg) → scheduled → Proc mailbox
```

**Introspection:**
```
ProcSys.get_state(procRef) → query without stopping
```

---

## Sequence Diagrams

### Process Lifecycle

**Diagram:** `docs/diagrams/c4-jotp-04-process-lifecycle.puml`

Shows the complete lifecycle of a process:

1. **Startup** — `ProcLib.start_link()` spawns `Proc`, calls handler's `initialize()`
2. **Running** — Process dequeues mailbox, calls handler, updates state
3. **Event Handling** — Optional `EventManager` routes events to `StateMachine`
4. **Crash** — Unhandled exception or exit signal
5. **Recovery** — Supervisor detects crash, `CrashRecovery` restarts process
6. **Restart** — `ProcessRegistry` auto-updates reference, process continues

---

### Supervision Tree

**Diagram:** `docs/diagrams/c4-jotp-05-supervision-tree.puml`

Shows a realistic supervision hierarchy:

```
RootSupervisor (ONE_FOR_ALL)
├─ LoggerSupervisor (ONE_FOR_ONE)
│  └─ LoggerProc
├─ DatabaseSupervisor (ONE_FOR_ONE)
│  ├─ DBConnProc
│  └─ DBPoolProc
└─ WorkerSupervisor (REST_FOR_ONE)
   ├─ Worker1
   ├─ Worker2
   └─ Worker3
```

**Key Points:**
- Different restart strategies at each level
- Cascading failures propagate up the tree
- Only affected branches restart based on strategy

---

### Message Flow

**Diagram:** `docs/diagrams/c4-jotp-06-message-flow.puml`

Shows three message patterns:

#### 1. **Asynchronous Send**
```
Sender.send(message) → Receiver.mailbox (non-blocking)
Receiver dequeues & processes asynchronously
```

#### 2. **Synchronous Request-Reply (ask)**
```
Sender.ask(message, timeout) → blocks waiting for reply
Receiver processes, replies to sender's slot
Returns Result<Reply, Timeout>
```

#### 3. **Timed Messages**
```
ProcTimer.send_after(delay, receiver, message)
Timer schedules delivery
Message queued to receiver after delay
```

#### 4. **Error Handling**
```
Result.of(() -> send(...))
Returns Success(unit) or Failure(exception)
Composable error chains
```

---

## The 15 OTP Primitives

### Reference Table

| # | Primitive | OTP Equivalent | Role | Container |
|---|-----------|---|------|-----------|
| 1 | `Proc<S,M>` | `spawn/3` | Process handler | Process |
| 2 | `ProcRef<S,M>` | Pid | Process reference | Process |
| 3 | `Supervisor` | `supervisor` | Tree manager | Supervision |
| 4 | `CrashRecovery` | gen_server recovery | Recovery logic | Supervision |
| 5 | `StateMachine<S,E,D>` | `gen_statem` | State machine | State Mgmt |
| 6 | `ProcessLink` | `link/1` | Bilateral linking | Process |
| 7 | `Parallel` | `pmap` | Fan-out | Concurrency |
| 8 | `ProcessMonitor` | `monitor/2` | Unilateral monitor | Process |
| 9 | `ProcessRegistry` | `global` / `pg` | Name registry | State Mgmt |
| 10 | `ProcTimer` | `timer:send_after/3` | Timed messages | Timing |
| 11 | `ExitSignal` | Exit signal | Signal record | Supervision |
| 12 | `ProcSys` | `sys:*` | Introspection | Timing |
| 13 | `ProcLib` | `proc_lib` | Startup handshake | Process |
| 14 | `EventManager<E>` | `gen_event` | Event dispatch | State Mgmt |
| 15 | `Result<S,F>` | — | Error handling | Error Handling |

---

## Common Patterns & Use Cases

### Pattern 1: Simple Worker Process

```java
// Define process state
record WorkerState(String name, int count) {}

// Define message type
sealed interface WorkerMsg {}
record Increment() implements WorkerMsg {}
record GetCount(ProcRef<WorkerState, WorkerMsg> replyTo) implements WorkerMsg {}

// Startup
var procRef = ProcLib.start_link(
    new WorkerState("worker-1", 0),
    (state, msg) -> {
        if (msg instanceof Increment) {
            return new WorkerState(state.name, state.count + 1);
        } else if (msg instanceof GetCount(var replyTo)) {
            replyTo.send(new CountReply(state.count));
            return state;
        }
        return state;
    }
);

// Usage
procRef.send(new Increment());
procRef.send(new Increment());
var reply = procRef.ask(new GetCount(procRef), Duration.ofSeconds(1));
```

**Containers Used:** Process

---

### Pattern 2: Supervised Worker Pool

```java
var supervisor = new Supervisor(
    supervisorState("worker-pool"),
    RestartStrategy.ONE_FOR_ONE,
    (state, msg) -> {
        // Spawn 10 workers
        for (int i = 0; i < 10; i++) {
            supervisor.spawn(
                "worker-" + i,
                new WorkerState("w" + i, 0),
                workerHandler
            );
        }
        return state;
    }
);

// Supervisor will restart any failed worker automatically
```

**Containers Used:** Process, Supervision

---

### Pattern 3: State Machine with Events

```java
record TrafficLightState(String color) {}
sealed interface TrafficLightEvent {}
record Green() implements TrafficLightEvent {}
record Red() implements TrafficLightEvent {}

var stateMachine = new StateMachine<>(
    new TrafficLightState("red"),
    (state, event, data) -> {
        return switch (state.color) {
            case "red" when event instanceof Green() ->
                new Transition<>(new TrafficLightState("green"), Unit.INSTANCE);
            case "green" when event instanceof Red() ->
                new Transition<>(new TrafficLightState("red"), Unit.INSTANCE);
            default -> new Transition<>(state, Unit.INSTANCE);
        };
    }
);

// Dispatch events
stateMachine.handle(new Green());
stateMachine.handle(new Red());
```

**Containers Used:** State Management

---

### Pattern 4: Parallel Worker Execution

```java
var results = Parallel.pmap(
    List.of(1, 2, 3, 4, 5),
    num -> {
        var workerRef = ProcLib.start_link(
            new WorkerState("task-" + num, 0),
            workerHandler
        );
        return workerRef.ask(new GetCount(workerRef), Duration.ofSeconds(10));
    },
    Duration.ofSeconds(30)  // overall timeout
);

results.forEach(result ->
    result.peek(count -> System.out.println("Count: " + count))
          .orElseThrow()
);
```

**Containers Used:** Process, Concurrency, Error Handling

---

### Pattern 5: Timed Retry with Recovery

```java
var timer = new ProcTimer();

// Send retry message after 5 seconds
timer.send_after(
    Duration.ofSeconds(5),
    procRef,
    new RetryMessage()
);

// Process handles retry
(state, msg) -> {
    if (msg instanceof RetryMessage) {
        try {
            // Attempt operation
            return executeOperation(state);
        } catch (Exception e) {
            // Schedule another retry
            timer.send_after(Duration.ofSeconds(5), procRef, new RetryMessage());
            return state;
        }
    }
    return state;
}
```

**Containers Used:** Process, Timing, Supervision

---

## Integration & References

### Related Documentation

- **PhD Thesis:** [`docs/phd-thesis-otp-java26.md`](phd-thesis-otp-java26.md) — Formal equivalence proofs, performance benchmarks, migration guides
- **Quick Start:** [`README.md`](../README.md) — Getting started with JOTP
- **Build Guide:** [`CLAUDE.md`](../CLAUDE.md) — Development & toolchain setup
- **Code Generation:** `bin/jgen` — Template-based code generation for JOTP patterns

### Viewing PlantUML Diagrams

All `.puml` files can be rendered using:

- **Online:** [PlantUML Online Editor](http://www.plantuml.com/plantuml/uml/) — Paste diagram code
- **VS Code:** Install "PlantUML" extension by jebbs
- **IntelliJ:** Built-in PlantUML support
- **CLI:** `plantuml -Tpng c4-jotp-*.puml`

### Source Code Locations

**Core Module:** `src/main/java/org/acme/`

Key files (alphabetical):
- `CrashRecovery.java` — Recovery logic
- `EventManager.java` — Event dispatch
- `ExitSignal.java` — Exit signals
- `Parallel.java` — Parallel execution
- `Proc.java` — Process handler
- `ProcLib.java` — Startup protocol
- `ProcRef.java` — Process reference
- `ProcSys.java` — Introspection
- `ProcTimer.java` — Timers
- `ProcessLink.java` — Bilateral linking
- `ProcessMonitor.java` — Monitoring
- `ProcessRegistry.java` — Name registry
- `Result.java` — Error handling
- `StateMachine.java` — State machine
- `Supervisor.java` — Supervision tree

**Tests:** `src/test/java/org/acme/`
- Full test coverage for all 15 primitives
- JUnit 5 + AssertJ + jqwik
- Integration tests for complex scenarios

---

## Architecture Principles

1. **Shared-Nothing Isolation** — Processes don't share mutable state; only message passing
2. **Virtual Threads** — Lightweight processes (100k+ feasible on modern JVM)
3. **Fault Tolerance** — Explicit supervision trees with configurable restart strategies
4. **Asynchronous by Default** — Non-blocking message sends; optional blocking ask()
5. **Sealed Types** — Exhaustive pattern matching on messages and states
6. **Error as Values** — `Result<S,F>` for composable error handling

---

## Next Steps

1. **Understand Container Interactions** — Review `c4-jotp-02-containers.puml`
2. **Explore Component Details** — Study `c4-jotp-03-components.puml`
3. **Trace Message Flows** — Walk through `c4-jotp-06-message-flow.puml`
4. **Review Code** — Start with `src/main/java/org/acme/Proc.java`
5. **Run Tests** — Execute `./mvnw test` to see primitives in action
6. **Build Examples** — Use `bin/jgen list --category patterns` to explore templates

---

**Last Updated:** 2026-03-11
**Framework:** JOTP v1.0 (io.github.seanchatmangpt.jotp)
**Java Version:** Java 26 with preview features
**Status:** Production-ready
