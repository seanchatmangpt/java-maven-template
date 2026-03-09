# Supervision Trees

If "let it crash" is the philosophical foundation of OTP fault tolerance, supervision trees are its structural implementation. A supervision tree is a directed tree of processes where leaf nodes do application work and interior nodes exist solely to monitor and restart their children when they fail. Understanding supervision trees means understanding not just how this library works, but why that structure is the right way to contain failure in concurrent systems.

---

## The core idea: separate work from oversight

In most software systems, error recovery is the responsibility of the code that encounters the error. A method catches an exception and attempts to retry or compensate. This co-location of work and recovery has a fundamental problem: the code that is trying to recover is running in the same execution context as the code that failed. If the failure was caused by corrupted state, the recovery code may encounter the same corrupted state. If the failure was caused by resource exhaustion, the recovery code may encounter the same exhausted resource.

OTP's response to this is structural separation: processes that do application work (workers) are separate from processes that handle failure recovery (supervisors). Workers do not know anything about recovery; they simply perform their function and crash cleanly if something goes wrong. Supervisors do not know anything about the application domain; they simply watch their children and restart them according to a policy.

This separation means that a supervisor always runs in a healthy execution context, even when its workers are failing. The supervisor's job is simple and well-defined, so it is unlikely to fail itself. And if it does fail, it is in turn supervised by its own parent supervisor.

---

## Structure: a tree, not a flat list

Supervisors can supervise other supervisors, not just workers. This produces a tree structure:

```
RootSupervisor
├── DatabaseSupervisor
│   ├── ConnectionPoolWorker
│   └── HealthCheckWorker
├── ApiSupervisor
│   ├── RequestHandlerWorker
│   ├── AuthWorker
│   └── RateLimiterWorker
└── BackgroundSupervisor
    ├── JobQueueWorker
    └── SchedulerWorker
```

Each supervisor in this tree is responsible for a cohesive group of related processes. The `DatabaseSupervisor` knows about database-related processes; the `ApiSupervisor` knows about API-related processes. A crash in a database process does not trigger any action in the `ApiSupervisor` — that subtree is isolated.

This tree structure has two important properties:

**Failure containment.** A crash propagates upward only if a supervisor cannot restart its child within configured limits. Most crashes are handled by the direct supervisor without reaching the root. The blast radius of a failure is bounded by the subtree that contains it.

**Independent restart granularity.** The restart policy at each supervisor node is chosen to match the relationships between that node's children. The `DatabaseSupervisor` might use ONE_FOR_ALL because its children share connection state. The `ApiSupervisor` might use ONE_FOR_ONE because its children are independent.

---

## Restart strategies

This library implements the three OTP restart strategies on `Supervisor`. Each models a different assumption about the relationship between sibling processes.

### ONE_FOR_ONE

When a child crashes, only that child is restarted. All other children in the supervision group continue running unaffected.

```
┌─────────────────────────────────┐
│         Supervisor              │
│   ┌────┐ ┌────┐ ┌────┐         │
│   │ W1 │ │ W2 │ │ W3 │         │
│   └────┘ └─╳──┘ └────┘         │
│              ↓ crashes          │
│   ┌────┐ ┌────┐ ┌────┐         │
│   │ W1 │ │W2' │ │ W3 │         │
│   └────┘ └────┘ └────┘         │
└─────────────────────────────────┘
```

ONE_FOR_ONE is appropriate when children are **independent** — when a crash in one worker cannot leave any shared state in an inconsistent condition that would affect other workers.

A pool of request handler workers is a natural fit: each handler manages its own connection and its own request lifecycle. If one handler crashes mid-request, the others should not be affected.

A set of independent background workers (job processor, cache warmer, metric reporter) is another fit: they share no state, so restarting one does not create any consistency requirement for the others.

### ONE_FOR_ALL

When any child crashes, all children in the group are stopped and then restarted together.

```
┌─────────────────────────────────┐
│         Supervisor              │
│   ┌────┐ ┌────┐ ┌────┐         │
│   │ W1 │ │ W2 │ │ W3 │         │
│   └────┘ └─╳──┘ └────┘         │
│         W2 crashes              │
│              ↓                  │
│   stop W1, stop W3              │
│              ↓                  │
│   start W1', start W2', start W3'│
│   ┌────┐ ┌────┐ ┌────┐         │
│   │W1' │ │W2' │ │W3' │         │
│   └────┘ └────┘ └────┘         │
└─────────────────────────────────┘
```

ONE_FOR_ALL is appropriate when children have **shared or interdependent state** — when a crash in one child leaves the group in an inconsistent state that makes it unsafe for the other children to continue.

A classic example: a producer process and a consumer process that share a queue. If the consumer crashes, the producer is still running and putting items into the queue, but nobody is consuming them. The queue grows without bound. The producer's behavior is correct in isolation but incorrect in the context of the crashed system. Restarting only the consumer might work, but there is a window during which the producer and the orphaned consumer state are inconsistent. ONE_FOR_ALL eliminates this window by stopping the producer before restarting both.

Another example: a leader process and multiple follower processes in a replicated system. If the leader crashes, the followers may be in various states of having received updates. Restarting only the leader means the new leader starts from its initial state while the followers have additional state — a consistency violation. ONE_FOR_ALL ensures the entire group starts fresh.

### REST_FOR_ONE

When a child crashes, that child and all children started *after* it in the startup order are restarted. Children started before it continue running.

```
Startup order: W1 → W2 → W3 → W4 → W5

W3 crashes:
  W1: continues running (started before W3)
  W2: continues running (started before W3)
  W3: restarted
  W4: stopped and restarted (started after W3)
  W5: stopped and restarted (started after W3)
```

REST_FOR_ONE is appropriate when children have **ordered dependency** — when each child depends on the children started before it, but not on those started after it.

A pipeline is the canonical example: a producer process (W1) feeds a transformer process (W2) which feeds an aggregator process (W3) which feeds a sink process (W4). If W2 crashes, W3 and W4 (which depend on W2's output) are in an indeterminate state. W1 (which W2 depends on) is fine and can continue running. REST_FOR_ONE restarts W2, W3, and W4 in order, allowing the pipeline to re-form with the correct startup sequence.

---

## The restart window: frequency limits

Supervisors in this library — mirroring OTP — enforce a **sliding restart window**: a maximum number of restarts within a time period. If a child crashes more than `maxRestarts` times within `windowSeconds`, the supervisor concludes that the child is irrecoverably broken and stops trying. The supervisor then either:

- Escalates by crashing itself (allowing its own supervisor to handle the situation)
- Stops the child permanently (depending on the supervisor configuration)

This mechanism prevents a failing process from consuming all available resources through repeated rapid restarts. It also ensures that a systemic problem — like a configuration error that makes a process always crash on startup — is detected and escalated rather than silently retried forever.

The parameters (`maxRestarts`, `windowSeconds`) are configurable per supervisor. A process that is expected to have occasional transient failures (network hiccups) warrants a liberal window. A process that should reliably start on the first attempt warrants a strict window so that a startup failure is quickly escalated.

---

## Designing a supervision tree

A supervision tree should mirror the structure of your application's failure domains. The question to ask at each level: "If this component fails, what other components need to restart with it?"

**Independent components go under separate supervisors.** The API layer and the background job layer probably do not need to restart together when either fails. Put them under separate supervisors at the appropriate level.

**Tightly coupled components go under the same supervisor.** A database connection pool and a health check process that tests that pool are tightly coupled — if the pool crashes, the health check should restart alongside it rather than continuing to test a dead pool. They belong under the same supervisor, likely with ONE_FOR_ALL.

**Ordered dependencies use REST_FOR_ONE.** If your system has a clear initialization order (network listener → request router → business logic worker → outbound client), and later stages depend on earlier ones, REST_FOR_ONE at their shared supervisor ensures that a crash at any stage cleanly restarts the dependent stages.

**Limit depth.** Deeply nested supervision trees are hard to reason about. Three levels of supervision — root, subsystem, component — covers most applications. More than four or five levels suggests the supervision structure has become its own source of complexity.

---

## The relationship between supervision and state

One subtlety that catches newcomers: when a supervisor restarts a process, the process starts with its **initial state**, not its state at the time of the crash. This is intentional.

The crash happened because something went wrong. The state at the time of the crash is suspect — it may be the corrupted or invalid state that caused the crash. Restarting with initial state guarantees that the process begins from a known-correct baseline.

This has implications for persistent state. If a process maintains state that should survive restarts — a cache, a sequence number, a view of an event log — that state must be stored somewhere external to the process (a database, a file, a persistent message queue). On restart, the process reads its state from the external store rather than starting from scratch.

This externalization of persistent state is not a limitation; it is a design discipline that makes processes composable and restartable. A process with no external persistent state can always be restarted without loss of critical information. A process that persists important state externally is explicit about the durability boundary.

---

## ProcessLink and ProcessMonitor: alternatives to supervision

Supervision trees are the preferred mechanism for managing process lifecycle in well-structured systems. But two lower-level primitives are also available for cases where supervision tree structure doesn't fit.

**ProcessLink** (`link/1`, `spawn_link/3` in Erlang) creates a bilateral relationship between two processes: if either crashes, the other receives an exit signal. By default, receiving an exit signal causes the receiving process to crash as well, propagating the failure. This is useful for processes that should be considered a single unit of failure even if they are not in the same supervision subtree.

Links are symmetric: if process A links to process B, and B crashes, A receives the exit signal. This can be surprising when linking to processes that you don't control. The `Proc.trapExits(true)` flag converts exit signals into ordinary messages (`ExitSignal` records in the mailbox) rather than causing the receiving process to crash, giving the process explicit control over how it handles its linked partners' failures.

**ProcessMonitor** (`monitor/2` in Erlang) creates a unilateral relationship: if the monitored process crashes, the monitoring process receives a DOWN message, but the monitoring process does not crash. This is appropriate when you want to observe another process's lifecycle without coupling your own lifecycle to it.

The distinction matters: use `ProcessLink` when two processes should live and die together. Use `ProcessMonitor` when one process needs to *know about* another's death without being killed by it.

---

## Supervision trees as architectural documentation

A well-designed supervision tree is also a form of architecture documentation. It makes explicit:

- Which components exist in the system (the leaf nodes)
- How they are grouped by failure domain (the subtree structure)
- What restart policy applies to each group (the supervisor configuration)
- What the system's failure response is for any given component failure (traversing upward from the leaf)

When onboarding a new engineer, the supervision tree provides an immediate map of the system's structure that is harder to see from code alone. When debugging a production incident, the supervision tree shows where in the hierarchy a failure propagated and which processes were restarted as a consequence.

This is one of the less-discussed benefits of the OTP model: it forces architectural thinking that other concurrency models defer. In a thread pool model, the "architecture" of concurrent failure is ad hoc — a set of try/catch blocks and retry loops scattered through the codebase, with no single place that documents what happens when something fails. In the supervision tree model, it is explicit and central.

---

## Escalation and graceful degradation

A supervision tree does more than restart processes. It provides a structured path for failure escalation when restart is not enough.

If a supervisor's child crashes more than the allowed rate, the supervisor escalates — it crashes itself, triggering a restart attempt by its own parent supervisor. The parent might use ONE_FOR_ALL, restarting the entire failing subtree from scratch. If the parent supervisor also exceeds its restart budget, it escalates further.

At the top of the tree is the root supervisor. If the root supervisor's restart budget is exhausted, the application exits — which is the correct behavior. The application cannot restart a component that is irrecoverably broken. Exiting is the honest signal that a human should investigate.

This escalation behavior is valuable for distinguishing transient failures from permanent ones. A transient failure (a flaky network, a temporary resource exhaustion) will stay within a subtree's restart budget and recover automatically. A permanent failure (a configuration error, a missing dependency) will exhaust the budget and escalate visibly, ensuring that a human is alerted rather than the system silently spinning on futile restarts.

Graceful degradation is achieved by making each subsystem's supervisor independent. If the `BackgroundJobSupervisor` exceeds its restart budget and stops, the rest of the application — the API layer, the database layer — continues serving traffic in a degraded but partially functional state. The supervisor tree's structure determines what "degraded" means: which features fail independently and which take each other down.

---

## Summary

Supervision trees are the mechanism by which the "let it crash" philosophy becomes a practical system design. They separate the concerns of doing work (workers) from the concern of managing failure recovery (supervisors). They provide three restart strategies that model different assumptions about component interdependency. They enforce restart budgets that distinguish transient from permanent failure and trigger escalation when appropriate. And they structure failure domains in a way that contains blast radius, enables independent restart granularity, and documents the system's failure response in an explicit and auditable form.

The discipline of designing a supervision tree — of thinking carefully about which components are independent and which are coupled, about what restart granularity is appropriate, about where persistent state belongs — is the discipline of thinking carefully about your system's failure semantics. That thinking produces more reliable systems regardless of the implementation technology. The supervision tree makes it structural and enforced rather than optional.
