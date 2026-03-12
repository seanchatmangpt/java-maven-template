# Reference: Glossary

Key terms and concepts in JOTP.

## Concepts

### Process
A lightweight concurrent unit that maintains isolated state and processes messages sequentially. Implemented via Java 26 virtual threads. Equivalent to Erlang's `spawn/3`.

### Message
An immutable value sent from one process to another via `send()`. Typically a sealed Java record. Processed asynchronously in FIFO order.

### State
Immutable data maintained by a process. Updated by the handler function in response to messages. Never shared between processes (no data races).

### Handler
Pure function `(state) → (msg) → new_state` that processes one message and returns the updated state. Runs synchronously, single-threaded per process.

### Supervision
Hierarchical process management with automatic restart on crash. Implemented via `Supervisor` with THREE_FOR_ONE, ONE_FOR_ALL, or REST_FOR_ONE strategies.

### Restart Strategy
Policy for restarting failed child processes:
- **ONE_FOR_ONE**: Restart only the failed child
- **ONE_FOR_ALL**: Restart all children if any fails
- **REST_FOR_ONE**: Restart failed child and all dependents

### Process Reference (ProcRef)
Opaque handle to a process that survives supervisor restarts. Cannot be forged. Used to send messages and perform request-reply operations.

### Fire-and-Forget
Asynchronous message sending via `send()`. Returns immediately; message queued for processing. No guarantee of delivery order across processes.

### Request-Reply
Synchronous message exchange via `ask()`. Sender blocks until receiver sends response or timeout occurs. Implemented as fire-and-forget with a reply channel.

### Crash Recovery
Isolated retry mechanism for transient faults. Wraps fallible operations with exponential backoff. Does not kill sibling processes (unlike supervision).

### Exit Signal
Message sent to a process that traps exits when a linked process terminates. Contains crash reason and source Pid.

### Process Link
Bilateral relationship between two processes. If one crashes, it sends an exit signal to the other. Creates implicit failure dependencies.

### Process Monitor
Unilateral observation of a process. Monitoring process receives `DOWN` signal if monitored process terminates, but termination does not kill the monitor.

### Process Registry
Global name table mapping human-readable names to process references. Used for service discovery without explicit reference passing.

### Virtual Thread
Lightweight thread managed by the JVM, not the OS. ~100 KB stack (on-demand), cheap context switch, ~1 million per machine. JEP 444 (Java 21+).

### Platform Thread
OS-managed thread (~1 MB stack, expensive context switch, ~10K per machine). JOTP uses virtual threads, not platform threads.

### Structured Concurrency
Programming model where task lifetimes nest properly (tasks complete before parent completes). Implemented via `StructuredTaskScope`. JEP 453 (Java 21+).

### Railway-Oriented Programming
Error handling pattern using sum types: `Result<T,E> = Success(T) | Failure(E)`. Enables functional composition without exceptions.

### Sealed Interface
Java interface that can only be extended by named types. Enables exhaustive pattern matching. JEP 409 (Java 17+).

### Pattern Matching
Language feature for matching values against patterns. JOTP uses sealed interfaces + switch expressions for message routing. JEP 420+ (Java 17+).

### State Machine
Automaton with discrete states and event-triggered transitions. JOTP's `StateMachine<S,E,D>` adds data persistence (D) alongside state (S) and events (E). OTP equivalent: `gen_statem`.

### Event Manager
Process that broadcasts events to multiple subscribed handlers. Crashes in handlers don't kill the manager. OTP equivalent: `gen_event`.

### Handler (Event Manager)
Function called when an event is broadcast. Handlers are isolated; crash in one doesn't affect others.

### Timeout
Maximum time to wait for an operation (e.g., `ask()`, message processing). Specified as `Duration`. If exceeded, throws `TimeoutException`.

### FIFO Queue
First-In-First-Out message queue. JOTP processes receive messages in the order sent to that process (per-process FIFO, not global).

### Backpressure
Mechanism to prevent overwhelming a slow process with messages. Can be implemented via bounded queues or rate-limiting.

### Heap
JVM memory region where objects are allocated. JOTP processes are bounded by available heap (unlike BEAM which has separate process memory).

### GC (Garbage Collection)
Automatic memory reclamation when objects are no longer referenced. JOTP relies on JVM GC; no manual memory management needed.

### OTP (Open Telecom Platform)
Erlang/OTP library providing battle-tested patterns for fault tolerance, distributed systems, and hot code reloading. JOTP reimplements these patterns in Java.

### BEAM (Bogdan/Björn Erlang Abstract Machine)
The Erlang runtime. JOTP doesn't use BEAM; it reimplements OTP patterns natively in Java 26.

## Erlang-Java Terminology

| Erlang | JOTP |
|--------|------|
| Process / Pid | `Proc<S,M>` / `ProcRef<S,M>` |
| Message | Sealed record implementing message interface |
| State | Generic parameter `S` |
| receive...end | Handler function applied to messages |
| ! (send) | `proc.send()` |
| link/1 | `ProcessLink.link()` |
| monitor/2 | `ProcessMonitor.monitor()` |
| supervisor | `Supervisor` |
| gen_statem | `StateMachine<S,E,D>` |
| gen_event | `EventManager<E>` |
| register/2 | `ProcessRegistry.register()` |
| timer:send_after/3 | `ProcTimer.sendAfter()` |
| {ok, V} \| {error, R} | `Result.Success(V) \| Result.Failure(R)` |
| catch...throw | `Result.of(supplier)` |
| try...catch (OTP style) | Functional error handling with `Result<T,E>` |

## Java 26 Features

| Feature | JEP | JOTP Use |
|---------|-----|----------|
| Virtual Threads | 444 | Lightweight process implementation |
| Structured Concurrency | 453 | Task scope and fail-fast semantics |
| Sealed Types | 409 | Safe message type hierarchies |
| Records | 395 | Message value objects |
| Pattern Matching | 420+ | Message dispatch in switch |
| Text Blocks | 378 | Multi-line strings in code |

---

**See Also:** [Architecture Overview](../explanations/architecture-overview.md) | [API Overview](api.md)
