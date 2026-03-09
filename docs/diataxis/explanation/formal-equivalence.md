# Formal Equivalence: The Twelve Pillars

The claim that this library provides an OTP-equivalent programming model in Java is not made lightly or by analogy. It rests on a twelve-pillar formal equivalence proof — a systematic demonstration that each core OTP mechanism has a Java 26 counterpart that preserves the essential properties of the original.

This article summarizes the twelve pillars in accessible terms. The full formal treatment — with definitions, proofs, benchmarks, and bidirectional translation rules encoded as SPARQL queries over an OWL ontology — appears in the PhD thesis `docs/phd-thesis-otp-java26.md`. What follows is the conceptual argument for each pillar: what property is being preserved and why the Java implementation preserves it.

---

## What "formal equivalence" means here

Two systems are formally equivalent with respect to a set of properties if every operation in one system has a corresponding operation in the other system that preserves those properties. The properties we care about for OTP equivalence are:

1. **Isolation**: a failure in one process cannot corrupt the state of another
2. **Supervisability**: the lifecycle of every process is observable and controllable by a designated supervisor
3. **Transparency**: process references are opaque to callers and remain valid across restarts
4. **Fairness**: no process can indefinitely starve others of execution

The twelve pillars are the twelve mechanisms through which OTP achieves these properties. For each, we show the Erlang original and the Java 26 implementation, and argue that the essential property is preserved.

---

## Pillar 1: Process isolation — virtual thread heap separation

**The OTP property**: each Erlang process has its own private heap, garbage-collected independently. No process can read or modify another's heap directly. The only interface between processes is message passing.

**The Java 26 implementation**: each `Proc<S,M>` runs on a dedicated virtual thread. The state `S` is a local variable on that thread's stack. The message type `M` is required to be an immutable sealed type (typically records). No reference to the state `S` is ever published outside the virtual thread.

**Why the property is preserved**: while Java does not have per-thread heaps, the isolation is maintained by the type discipline and the `ProcRef` abstraction. The state `S` is never directly readable by external code — the only interface is through the mailbox. Because `M` is immutable, a sent message cannot be modified by the sender after dispatch. The process's internal state is as isolated as Erlang's per-process heap permits, enforced by the API contract rather than by the garbage collector.

**Honest limitation**: Java's shared heap means that a GC pause affects all virtual threads simultaneously, whereas Erlang's per-process GC means each process is collected independently. For most applications, modern JVM GC (especially ZGC with sub-millisecond pauses) makes this a non-issue. For the small set of applications where GC pause consistency is critical, this is a real difference.

---

## Pillar 2: Message passing — LinkedTransferQueue mailbox

**The OTP property**: processes communicate exclusively by sending messages to each other's mailboxes. Messages are delivered in the order sent (FIFO). Sending is non-blocking for the sender. A process blocks only on receive, not on send.

**The Java 26 implementation**: each `Proc<S,M>` owns a `java.util.concurrent.LinkedTransferQueue<M>`. Sending a message calls `queue.offer(message)`, which is non-blocking and wait-free for the producer. The event loop calls `queue.take()`, which parks the virtual thread (without consuming a carrier thread) until a message arrives.

**Why the property is preserved**: `LinkedTransferQueue` provides FIFO ordering, unbounded capacity (no producer is ever blocked by queue fullness), and lock-free operations in the common case. The blocking semantics of `take()` combined with virtual thread parking achieve the same effect as BEAM's `receive` blocking: the process consumes no execution resources while waiting, but resumes immediately when a message arrives.

**Additional nuance**: Erlang's selective receive — the ability to match only certain messages and leave non-matching ones in the queue — is not directly supported. Instead, the typed message hierarchy with sealed interfaces and pattern matching achieves the same result: each message variant is handled explicitly, and the sealed type ensures no variant is accidentally ignored. For request/reply correlation (the main use of selective receive in Erlang), `Proc.ask()` provides a type-safe alternative.

---

## Pillar 3: Transparent process reference — ProcRef

**The OTP property**: a `Pid` is an opaque reference to a process. Callers can send messages to a `Pid` without knowing anything about the process's implementation. A supervisor can restart a crashed process with a new internal identity while callers continue using the same reference (if registered under a name).

**The Java 26 implementation**: `ProcRef<S,M>` is an opaque handle to a process. It wraps the current `Proc<S,M>` instance, which in turn wraps the current virtual thread. When a supervisor restarts a crashed process, it creates a new `Proc<S,M>` and atomically updates the reference inside the `ProcRef`. All callers holding the `ProcRef` now route messages to the new process.

**Why the property is preserved**: the opacity of `ProcRef` — enforced by JPMS encapsulation — means callers cannot obtain or cache the underlying `Proc` or virtual thread directly. The atomic update on restart ensures that no message is lost during the swap: messages sent to the `ProcRef` after the restart are delivered to the new process, not the old one. The `ProcRef` provides transparent persistence of process identity across restarts.

**Improvement over Erlang**: Erlang requires processes to use the registry (`register/2`) to get stable names that survive restarts. This library makes stability the default for every process via `ProcRef`. The registry (`ProcessRegistry`) is an additional mechanism for name-based lookup, not a workaround for unstable Pids.

---

## Pillar 4: Supervision trees — Supervisor with restart strategies

**The OTP property**: the `supervisor` behavior in Erlang manages a group of child processes. It starts them in order, monitors their health, and applies a configured restart strategy when any child crashes. Supervisors form a tree by supervising other supervisors.

**The Java 26 implementation**: `Supervisor` manages a list of children, each with a start function, an initial state, and a `ProcRef`. It runs a monitoring loop on its own virtual thread. When a child's virtual thread terminates with an exception, the supervisor's monitoring detects this and applies the configured restart strategy.

**Why the property is preserved**: the supervisor is itself a process — it runs on its own virtual thread and can be supervised by a parent supervisor. The three restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE) are implemented to match Erlang semantics exactly. The supervisor has no application logic — it is pure lifecycle management — so it is resilient to failures in its children.

---

## Pillar 5: ONE_FOR_ONE restart — isolated child restart

**The OTP property**: when a child process crashes under ONE_FOR_ONE supervision, only that child is restarted. Sibling processes continue running.

**The Java 26 implementation**: the supervisor identifies which `ProcRef` corresponds to the crashed virtual thread, restarts only that child using its configured start function, and updates the `ProcRef` atomically. The event loops of sibling processes are not affected.

**Why the property is preserved**: because each process runs on its own virtual thread with its own mailbox, the failure of one thread has no direct effect on the others. The supervisor's restart is scoped to the specific `ProcRef` corresponding to the failed process.

---

## Pillar 6: ONE_FOR_ALL restart — coordinated group restart

**The OTP property**: when any child crashes under ONE_FOR_ALL supervision, all children in the group are stopped and then restarted in startup order.

**The Java 26 implementation**: the supervisor sends a shutdown signal to all children (by interrupting their virtual threads and draining their mailboxes), waits for all to terminate, and then starts them all fresh in the configured startup order.

**Why the property is preserved**: the key requirement is that all children are stopped before any are restarted, ensuring that no child runs against a partially-updated sibling state. The implementation serializes stop-then-start across all children before resuming normal operation.

---

## Pillar 7: REST_FOR_ONE restart — ordered partial restart

**The OTP property**: when a child crashes under REST_FOR_ONE supervision, that child and all children started after it are stopped and restarted in order. Children started before the crashed child continue running.

**The Java 26 implementation**: the supervisor maintains the startup order of all children. When a crash is detected, it identifies the crashed child's position in the startup order, stops all children at that position or later (in reverse startup order), and restarts them from the crashed child's position forward.

**Why the property is preserved**: the ordering invariant — children started after the crashed child may depend on it, children started before it do not — is preserved by the stop-in-reverse, restart-in-forward sequence. Children before the crash point are never stopped or restarted.

---

## Pillar 8: gen_statem equivalence — StateMachine

**The OTP property**: `gen_statem` in Erlang/OTP provides a process that is explicitly in one of a finite set of states, where each state defines which events are accepted and what transitions they trigger. State, event, and data are separated, preventing the accidental mixing of control flow and business data that afflicts state implemented as ad hoc boolean fields.

**The Java 26 implementation**: `StateMachine<S,E,D>` is generic over state type `S`, event type `E`, and data type `D`. Both `S` and `E` are typically sealed interfaces. The transition function takes the current state, an event, and the current data, and returns a `Transition` (itself a sealed type: `Stay`, `MoveTo`, `Stop`). The exhaustive pattern matching in the transition function is verified at compile time.

**Why the property is preserved**: the sealed type hierarchy on `S` and `E` ensures that all state/event combinations are explicitly handled. The `Transition` sealed type ensures that all outputs (continue in state, move to new state, stop the machine) are explicitly modeled. The separation of `S`, `E`, and `D` enforces the gen_statem discipline structurally.

---

## Pillar 9: Process links — bilateral failure propagation

**The OTP property**: `link/1` in Erlang creates a bilateral relationship between two processes: if either terminates abnormally, the other receives an exit signal. By default, receiving an exit signal causes the receiving process to crash too (propagating the failure). A process can trap exit signals by setting `process_flag(trap_exit, true)`, converting them from crashes into ordinary messages.

**The Java 26 implementation**: `ProcessLink.link(ref1, ref2)` registers each process as a monitor of the other. When either process's virtual thread terminates with an exception, the other receives notification. If the receiving process has not set `trapExits(true)`, its virtual thread is interrupted, causing it to crash. If `trapExits(true)` is set, an `ExitSignal` record is delivered to the receiving process's mailbox instead.

**Why the property is preserved**: the bilateral nature of links is preserved — the link is symmetric, and a crash in either process affects the other. The `trapExits` flag preserves the Erlang semantics of converting crash signals into messages. The use of virtual thread interruption to propagate crashes preserves the default Erlang behavior that linked processes crash together.

---

## Pillar 10: Process monitors — unilateral DOWN signal

**The OTP property**: `monitor/2` in Erlang creates a unilateral relationship: if the monitored process terminates (normally or abnormally), the monitoring process receives a `DOWN` message. The monitoring process does not crash. The relationship is explicitly initiated by the monitoring side and can be cancelled with `demonitor/1`.

**The Java 26 implementation**: `ProcessMonitor.monitor(ref)` registers a callback on the target `ProcRef`. When the target process's virtual thread terminates, a `DOWN` message is placed in the monitoring process's mailbox. The monitoring process handles `DOWN` as an ordinary message variant in its sealed message interface.

**Why the property is preserved**: the unilateral nature is preserved — only the monitored process's death affects the monitoring process, and only through an ordinary mailbox message. The monitoring process does not crash; it receives information. The `demonitor` operation (`ProcessMonitor.demonitor(ref)`) removes the registration, matching Erlang's `demonitor/1` semantics.

**Key distinction from links**: monitors are the right tool when you want to *know* about a process's death without coupling your lifecycle to it. Links are the right tool when two processes should be considered a single failure unit. Both are available, and the appropriate choice depends on the failure semantics you want.

---

## Pillar 11: Named process registry — ProcessRegistry

**The OTP property**: `register(Name, Pid)` in Erlang associates a name (an atom) with a process. `whereis(Name)` looks up the current `Pid` for that name. `unregister(Name)` removes the association. When a registered process dies, the name is automatically deregistered. This allows processes to be found by name rather than by `Pid`, which is useful for long-lived service processes.

**The Java 26 implementation**: `ProcessRegistry` provides `register(name, ref)`, `whereis(name)`, `unregister(name)`, and `registered()` (all names). When a registered process's `ProcRef` detects that the underlying process has died, the registry is automatically updated to remove the name. Thread-safety is provided by a concurrent map.

**Why the property is preserved**: the essential semantics are preserved: names are unique (registering the same name twice throws), lookup returns the current reference or empty, and death triggers automatic deregistration. The `ProcessRegistry` uses string names rather than atoms (because Java has no atom type), but the semantics are equivalent.

**Note on naming discipline**: unlike Erlang's atom-based names, string names in Java require care to avoid typos. The recommended pattern is to define names as constants in the class that registers the process, rather than using string literals at call sites.

---

## Pillar 12: Process-scoped timers — ProcTimer

**The OTP property**: `timer:send_after(Time, Pid, Message)` delivers `Message` to `Pid` after `Time` milliseconds. `timer:send_interval(Time, Pid, Message)` delivers `Message` repeatedly at `Time`-millisecond intervals. `timer:cancel(TRef)` cancels a pending timer. Timers are process-scoped: if the target process dies, the timer fires but the message is lost.

**The Java 26 implementation**: `ProcTimer.sendAfter(ref, message, delay, unit)` schedules a single message delivery. `ProcTimer.sendInterval(ref, message, period, unit)` schedules repeated delivery. Both return a `TimerRef` that can be cancelled with `ProcTimer.cancel(timerRef)`. Scheduling is backed by a `ScheduledExecutorService` using virtual threads, so timer callbacks do not consume carrier threads.

**Why the property is preserved**: the message delivery semantics are preserved: the message arrives in the target process's mailbox as an ordinary message, handled by the same pattern matching switch as all other messages. Cancellation is explicit via `TimerRef`. If the target process is dead when the timer fires, the message is silently dropped — matching Erlang's process-scoped timer semantics.

**Common pattern**: a process that needs to perform periodic work sets up a `sendInterval` timer on startup, handling the timer message in its event loop to trigger the work. This avoids the need for a separate scheduler process or a sleep loop.

---

## The synthesis: why these twelve pillars are sufficient

The twelve pillars together cover the full OTP programming model:

- **Pillars 1–3** establish the process model: isolation, communication, and transparent identity
- **Pillars 4–7** establish the supervision model: the supervisor abstraction and its three restart strategies
- **Pillar 8** establishes the state machine model: explicit states, events, and transitions
- **Pillars 9–10** establish the link/monitor model: bilateral and unilateral lifecycle coupling
- **Pillar 11** establishes the registry model: name-based process discovery
- **Pillar 12** establishes the timer model: time-driven process activation

Every OTP behavior and design pattern in the literature can be expressed using combinations of these twelve mechanisms. The `gen_server` behavior is a `Proc<S,M>` with a specific handler structure. The `gen_event` behavior is an `EventManager<E>` built on processes and message passing. The `proc_lib` startup handshake is `ProcLib`'s `start_link` with `initAck`. The `sys` introspection module is `ProcSys`'s `getState`, `suspend`, `resume`, and `statistics`.

The formal equivalence proof establishes not just that each pillar individually preserves the relevant property, but that the pillars compose correctly — that the interaction properties of OTP (a supervisor monitoring processes that link to each other and use the registry) are preserved when the Java 26 implementations of each pillar are used together.

This is the foundation on which the library rests: not an approximation of OTP, but a formal equivalent, implemented in a language that thirty years of Java development has equipped the world's largest developer community to use.
