# JOTP Architecture - Complete C4 Model Documentation (34 Diagrams)

This document provides a comprehensive visual architecture of **JOTP** (`io.github.seanchatmangpt.jotp`) — a complete Java 26 implementation of Erlang/OTP using the **C4 Model** at all four levels plus extended diagrams.

---

## Quick Index of All 34 Diagrams

### C4 Level 1: System Context (1 diagram)
1. **c4-jotp-01-system-context.puml** — JOTP in Java ecosystem context

### C4 Level 2: Containers (1 diagram)
2. **c4-jotp-02-containers.puml** — 6 logical containers with 15 primitives

### C4 Level 3: Component Details (7 diagrams)
3. **c4-jotp-03-components.puml** — All 15 primitives with cross-container interactions
4. **c4-jotp-03a-process-components.puml** — Process Container detailed breakdown
5. **c4-jotp-03b-supervision-components.puml** — Supervision Container detailed breakdown
6. **c4-jotp-03c-state-components.puml** — State Management Container breakdown
7. **c4-jotp-03d-concurrency-components.puml** — Concurrency Container breakdown
8. **c4-jotp-03e-timing-components.puml** — Timing Container breakdown
9. **c4-jotp-03f-errors-components.puml** — Error Handling Container breakdown

### C4 Level 4: Class Details (5 diagrams)
10. **c4-jotp-04a-proc-class-details.puml** — Proc<S,M> class structure
11. **c4-jotp-04b-supervisor-class-details.puml** — Supervisor class structure & strategies
12. **c4-jotp-04c-statemachine-class-details.puml** — StateMachine<S,E,D> sealed hierarchy
13. **c4-jotp-04d-result-class-details.puml** — Result<S,F> sealed type hierarchy
14. **c4-jotp-04e-processregistry-class-details.puml** — ProcessRegistry implementation

### Sequence Diagrams (8 diagrams)
15. **c4-jotp-05-process-lifecycle.puml** — Process spawn → init → crash → recovery → restart
16. **c4-jotp-06-supervision-tree.puml** — Supervision hierarchy with restart strategies
17. **c4-jotp-07-message-flow.puml** — Async send, sync ask(), timers, error handling
18. **c4-jotp-07a-error-recovery-flow.puml** — Exception → crash → recovery → restart sequence
19. **c4-jotp-07b-timer-scheduling.puml** — send_after scheduling & delivery
20. **c4-jotp-07c-registry-operations.puml** — register → whereis → unregister → deregister
21. **c4-jotp-07d-event-dispatch.puml** — EventManager → StateMachine handler dispatch
22. **c4-jotp-07e-link-monitor-semantics.puml** — Bilateral link vs unilateral monitor

### Process Behavior Diagrams (4 diagrams)
23. **c4-jotp-08a-virtual-thread-allocation.puml** — VT pool, carriers, JVM integration
24. **c4-jotp-08b-mailbox-operations.puml** — LinkedTransferQueue lock-free MPMC ops
25. **c4-jotp-08c-state-transition-diagram.puml** — FSM state transitions & lifecycle
26. **c4-jotp-08d-restart-strategy-decision.puml** — ONE_FOR_ONE/ONE_FOR_ALL/REST_FOR_ONE logic

### System Integration Diagrams (4 diagrams)
27. **c4-jotp-09a-initialization-sequence.puml** — System startup & tree initialization
28. **c4-jotp-09b-graceful-shutdown.puml** — Graceful shutdown sequence
29. **c4-jotp-09c-monitoring-observability.puml** — ProcSys → Prometheus/Jaeger/ELK integration
30. **c4-jotp-09d-error-propagation.puml** — Error propagation up supervision tree

### Architecture Overview Diagrams (3 diagrams)
31. **c4-jotp-10a-concurrency-model.puml** — Virtual threads vs traditional threads
32. **c4-jotp-10b-data-flow-overview.puml** — High-level request → process → response flow
33. **c4-jotp-10c-dependency-graph.puml** — Which primitives depend on others

### Reference & Comparison (1 diagram)
34. **c4-jotp-11-erlang-comparison.puml** — Erlang/OTP primitives mapped to JOTP

---

## Diagram Navigation Guide

### For Understanding System Architecture
Start here:
1. **c4-jotp-01-system-context.puml** — Get oriented
2. **c4-jotp-02-containers.puml** — Understand major sections
3. **c4-jotp-03-components.puml** — See all primitives
4. **c4-jotp-10b-data-flow-overview.puml** — Understand message flow

### For Deep Diving into Specific Components
- **Proc & Process Model** → 4a, 8a, 8b
- **Supervision & Fault Tolerance** → 3b, 8d, 9a, 9d
- **State Machines & Events** → 3c, 4c, 7d, 8c
- **Naming & Discovery** → 3c, 7c
- **Timers & Scheduling** → 3e, 7b
- **Error Handling** → 3f, 4d, 7a
- **Concurrency** → 3d, 10a

### For Understanding Sequences
- **Startup**: 9a, 5
- **Message Delivery**: 17, 7b
- **Crash & Recovery**: 15, 7a, 9d
- **Shutdown**: 9b

### For Integration
- **Observability**: 9c
- **Virtual Threads**: 8a
- **Erlang Migration**: 11

---

## The 15 OTP Primitives Quick Reference

| # | Primitive | File | OTP Equiv | Purpose |
|---|-----------|------|-----------|---------|
| 1 | `Proc<S,M>` | Proc.java | spawn/3 | Process handler with state |
| 2 | `ProcRef<S,M>` | ProcRef.java | Pid | Stable process reference |
| 3 | `ProcLib` | ProcLib.java | proc_lib | Startup handshake protocol |
| 4 | `ProcessLink` | ProcessLink.java | link/1 | Bilateral linking |
| 5 | `ProcessMonitor` | ProcessMonitor.java | monitor/2 | Unilateral monitoring |
| 6 | `Supervisor` | Supervisor.java | supervisor | Tree management & restarts |
| 7 | `CrashRecovery` | CrashRecovery.java | gen_server | Recovery logic |
| 8 | `ExitSignal` | ExitSignal.java | exit signal | Crash propagation |
| 9 | `StateMachine<S,E,D>` | StateMachine.java | gen_statem | State/event/data machine |
| 10 | `EventManager<E>` | EventManager.java | gen_event | Event dispatch |
| 11 | `ProcessRegistry` | ProcessRegistry.java | global/pg | Name registry |
| 12 | `ProcTimer` | ProcTimer.java | timer:* | Timed message delivery |
| 13 | `ProcSys` | ProcSys.java | sys:* | Introspection API |
| 14 | `Parallel` | Parallel.java | pmap | Structured fan-out |
| 15 | `Result<S,F>` | Result.java | ok/{error,_} | Error handling |

---

## Key Architecture Patterns

### Pattern 1: Process Creation & Initialization
```
diagram: c4-jotp-05-process-lifecycle.puml
ProcLib.start_link(state, handler)
  → Spawns Proc<S,M> on virtual thread
  → Calls handler.initialize()
  → Returns ProcRef when ready
  → Handler receives (state, message) → state
```

### Pattern 2: Supervision & Recovery
```
diagram: c4-jotp-08d-restart-strategy-decision.puml
Child crashes → Supervisor detects ExitSignal
  → Evaluates RestartStrategy (ONE_FOR_ONE/ONE_FOR_ALL/REST_FOR_ONE)
  → CrashRecovery respects restart window
  → Spawns new Proc or escalates
```

### Pattern 3: Message Passing
```
diagram: c4-jotp-07-message-flow.puml
Async: Sender.send(msg) → Receiver.mailbox (non-blocking)
Sync:  Sender.ask(msg, timeout) → blocks, returns Result<Reply, Timeout>
Timed: ProcTimer.send_after(delay) → scheduled delivery
```

### Pattern 4: State Transitions
```
diagram: c4-jotp-08c-state-transition-diagram.puml
StateMachine<S,E,D>.handle(event)
  → Pattern match on state & event
  → Guard predicates evaluated
  → Transition<S,D> returned
  → State atomically updated
```

### Pattern 5: Error Handling
```
diagram: c4-jotp-07a-error-recovery-flow.puml
Operation wrapped in Result.of(supplier)
  → Success(value) | Failure(exception)
  → Composable: map, flatMap, fold, recover
  → Chain operations without null checks
```

---

## Viewing Diagrams

All diagrams are in `docs/diagrams/` and use PlantUML C4 notation.

### Online
- [PlantUML Online Editor](http://www.plantuml.com/plantuml/uml/) — Copy-paste diagram code
- [PlantUML GitHub](https://github.com/plantuml-stdlib/C4-PlantUML) — C4 library docs

### Local Tools
- **VS Code:** Install "PlantUML" extension by jebbs
- **IntelliJ:** Built-in PlantUML support
- **Command Line:** `plantuml -Tpng docs/diagrams/*.puml`

---

## Related Documentation

- **PhD Thesis:** [`docs/phd-thesis-otp-java26.md`](phd-thesis-otp-java26.md) — Formal equivalence, benchmarks
- **Getting Started:** [`README.md`](../README.md) — Quick start guide
- **Development Guide:** [`CLAUDE.md`](../CLAUDE.md) — Build tools, setup
- **Code Location:** `src/main/java/org/acme/` — Source code

---

## Architecture Principles

1. **Shared-Nothing Isolation** — Processes don't share mutable state; message-only
2. **Virtual Threads** — Lightweight processes on Java 21+; 100k+ feasible
3. **Fault Tolerance** — Explicit supervision with configurable restart strategies
4. **Asynchronous by Default** — Non-blocking sends; optional blocking ask()
5. **Type Safety** — Sealed types, pattern matching, no unchecked casts
6. **Error as Values** — Result<S,F> for composable error handling

---

## Summary: 34 Diagrams Across 6 Categories

| Category | Diagrams | Purpose |
|----------|----------|---------|
| **C4 System Context** | 1 | High-level overview |
| **C4 Containers** | 1 | 6 logical groupings |
| **C4 Components** | 7 | Detailed interactions |
| **C4 Classes** | 5 | Implementation details |
| **Sequences & Flows** | 8 | Dynamic behavior |
| **Behavior & Integration** | 11 | Process behavior, system integration, architecture |

**Total Visual Documentation:** 34 diagrams covering all aspects of JOTP architecture

---

**Last Updated:** 2026-03-11
**Framework:** JOTP v1.0 (io.github.seanchatmangpt.jotp)
**Java Version:** Java 26 with preview features enabled
**Status:** Production-ready, fully documented
