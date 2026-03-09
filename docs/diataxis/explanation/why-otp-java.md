# Why Bring Erlang/OTP to Java?

Erlang was designed in 1986 at Ericsson to control telephone switches — systems where downtime is measured in milliseconds per year and where hardware can fail mid-call without dropping the connection. The language that emerged from that crucible, along with its Open Telecom Platform (OTP) framework, encodes a particular theory of software reliability that has been quietly correct for nearly four decades.

Java, meanwhile, became the dominant enterprise language partly by solving a different problem: portability and developer productivity at scale. Java's concurrency model evolved through threads, thread pools, `Future`, and reactive streams — a sequence of increasingly sophisticated abstractions, each trying to paper over the fundamental mismatch between the OS thread model and the way concurrent programs actually fail.

This library is a claim: that the time has come to bring OTP's fault-tolerance architecture to Java, and that Java 26 finally provides all the language machinery to do it properly.

---

## What problem are we actually solving?

Consider the failure mode that every Java backend developer has encountered: a single slow database query blocks a thread-pool thread. The pool becomes saturated. Requests queue. Timeouts cascade. The application appears healthy to its load balancer, because it is still running, but it is serving no useful traffic. Eventually, an operator restarts the process and spends the next hour reading logs.

This is not a concurrency bug in the traditional sense. No data was corrupted. The program did not throw an exception. It simply ran out of a shared, limited resource — threads — and had no mechanism to contain or recover from that exhaustion.

The deeper problem is **coupling through shared resources**. When components compete for the same thread pool, the same connection pool, the same lock, a problem in one component can degrade all the others. The system is not a set of isolated parts; it is a single brittle whole.

Erlang's answer to this, from the beginning, was: make isolation the default. Every logical task runs in its own process. Processes share no memory. When one fails, the others continue. This sounds obvious in retrospect, but implementing it correctly — with supervision, restart strategies, and process-transparent references — requires a framework that Java has never had.

---

## Why existing Java concurrency tools fall short

### Thread pools

`ExecutorService` and its derivatives are well-designed for parallelizing CPU-bound work across a fixed number of OS threads. They are not a model for building isolated, fault-tolerant components.

The fundamental problem: a thread pool's threads are *shared* across all submitted tasks. A task that holds a `ThreadLocal` value, acquires a lock, or registers a callback can corrupt the state seen by subsequent tasks running on the same thread. When a task throws an unchecked exception, the default behavior is to log it and silently continue — the caller's `Future` sees an exception, but the pool is unaffected. There is no concept of "this task's failure should be reported to its supervisor."

Thread pools also scale poorly as a model for concurrency: a system with ten thousand simultaneous logical activities cannot run ten thousand OS threads. Before Java 21, this forced developers toward asynchronous callback-based code, which trades isolation for throughput.

### CompletableFuture chains

`CompletableFuture` solves a real problem: composing asynchronous operations without blocking threads. But it is architecturally silent about failure containment.

A chain of `CompletableFuture.thenApply(...).thenCompose(...).exceptionally(...)` stages shares no isolation boundary. An exception in one stage propagates to the next via the `exceptionally` handler — or, if no handler is present, is silently swallowed. There is no supervision: no external entity watching the chain and deciding whether to restart it. There is no process identity: you cannot name a `CompletableFuture` chain, find it in a registry, or send it a message.

The result is that complex `CompletableFuture` graphs are extremely difficult to reason about when they fail. You end up writing defensive `exceptionally` handlers at every stage, recreating in ad hoc form the very thing OTP provides structurally.

### Akka

Akka is the most serious prior attempt to bring the actor model — OTP's closest relative — to the JVM. It has been used in production at scale and has solved real problems.

The practical objection to Akka is not technical but ergonomic. Using Akka requires accepting a large, opinionated framework with its own serialization model, its own cluster management, its own configuration system, and its own dispatcher tuning. The actor abstraction leaks: you must think about actor hierarchies, message serialization, backpressure in Akka Streams, and remote actor routing even for purely local use cases. The API surface is enormous.

More fundamentally, Akka predates Java's own virtual thread story. It was designed to work *around* the OS thread limitation by using asynchronous message passing with explicit mailboxes. Java 26 virtual threads eliminate that constraint: you can block in a virtual thread without consuming an OS thread. The architecture that made sense for Akka in 2013 is not the architecture that makes sense in 2026.

### Reactive streams (Project Reactor, RxJava)

Reactive programming addresses a genuine concern: backpressure — the ability of a slow consumer to signal a fast producer to slow down. This is valuable in high-throughput data pipelines.

But backpressure and fault isolation are orthogonal concerns. A reactive stream has no concept of process identity, no supervisor, no restart strategy. When a `Flux` operator throws an exception, the stream terminates and the subscriber receives an `onError` signal. If you want restart behavior, you compose `retry()` operators — which are point solutions, not a structural approach to failure containment.

Reactive code is also notoriously hard to read and debug. The stack traces produced by a reactive pipeline are essentially meaningless for diagnostic purposes, because the call stack at the time of an error is the reactive framework's internal machinery, not your application code.

---

## What OTP gets right

Erlang/OTP's model rests on three ideas that, taken together, produce systems with extraordinary reliability.

**First: processes are the unit of isolation.** Every logical activity — handling a request, maintaining a connection, running a background job — runs in its own process with its own heap, its own mailbox, and its own lifecycle. Processes communicate by passing messages; they never share memory directly. This means a bug in one process cannot corrupt another.

**Second: failure is normal and expected.** Rather than defending against every possible failure mode, OTP-style code is written for the happy path. The assumption is that something will eventually go wrong — a dependency will time out, a message will be malformed, a network partition will occur — and the right response is to let the failing process crash cleanly rather than attempting to recover in place. Clean crashes are predictable; in-place recovery is not.

**Third: supervisors own recovery.** Separate from the processes that do application work, a tree of supervisor processes monitors those workers. When a worker crashes, its supervisor decides what to do: restart just that worker (ONE_FOR_ONE), restart all workers in the group (ONE_FOR_ALL), or restart the failed worker and all workers that were started after it (REST_FOR_ONE). This decision is made by dedicated infrastructure, not by the failed component itself.

These three ideas compose. A process that fails provides a clean failure signal to its supervisor. The supervisor's restart decision is deterministic and configurable. The restarted process starts fresh, with no corrupted state. Other processes in the tree are unaffected unless their supervisor decides they should be.

---

## Why Java 26, specifically

The OTP model has been conceivable in Java for years, but not practically implementable at the right level of abstraction. Several things changed with Java 21 through 26:

**Virtual threads** (Java 21, stabilized in 22+) make it possible to run millions of lightweight threads per JVM. Each `Proc<S,M>` in this library runs on a dedicated virtual thread — not a thread pool thread, not a callback. You can block in a virtual thread without consuming an OS thread. This is the mechanism that makes cheap, isolated processes viable.

**Sealed types and pattern matching** (Java 17+, extended through 26) allow exhaustive definition of message and state hierarchies. A `StateMachine<S,E,D>` can express that certain states only accept certain events, and the compiler enforces exhaustiveness. This is a direct equivalent to Erlang's pattern matching on atoms and tagged tuples.

**Structured concurrency** (`StructuredTaskScope`, preview in Java 21, evolving through 26) provides a scope within which all forked virtual threads are tracked and cleaned up together. This is the mechanism behind `Parallel` — structured fan-out with automatic cancellation when any member fails.

**Records** provide the immutable, transparent data types that messages and states should be. A record is trivially safe to pass between processes because it cannot be mutated.

**JPMS strong encapsulation** (`module-info.java`) makes it possible to hide implementation details of the OTP machinery from callers, exposing only the intended API surface.

No single one of these features is sufficient. Together, they provide everything needed to build an idiomatic, ergonomic OTP implementation that feels like Java rather than like Erlang transliterated.

---

## The philosophical shift

Using this library well requires a genuine shift in how you think about failure.

The traditional Java approach treats exceptions as exceptional — rare events that indicate something has gone deeply wrong and that must be handled at each call site to prevent the application from terminating. Years of Java programming create strong intuitions that try/catch is responsible engineering, and that uncaught exceptions are a sign of carelessness.

The OTP approach inverts this. Exceptions — crashes — are not exceptional. They are a normal part of a system's operation, and the structural response to them should be designed in advance, not improvised at each call site. When a process crashes, it is not a bug to be debugged in the moment; it is a signal that flows up a well-defined supervision hierarchy to a supervisor with a configured restart policy.

This is not an excuse for writing buggy code. It is a recognition that some failures cannot be anticipated, that the blast radius of a failure should be minimized by isolation, and that the recovery response should be determined by a part of the system that is not itself failing.

The practical consequence: the happy-path code in this style is simpler and clearer than defensive Java, because it does not need to handle every failure mode. The supervision configuration is where failure handling lives, and it is explicit and auditable.

---

## Who benefits most

This library is most valuable for systems with these characteristics:

- **Long-lived processes** — services that maintain stateful connections (WebSocket, MQTT, database connections, cache clients) where individual connection failures should not affect others
- **High concurrency with heterogeneous workloads** — systems where different request types have wildly different latency profiles and should not contend for the same resources
- **Background workers** — job queues, schedulers, and pipeline stages that should restart automatically after failure
- **State machine behavior** — protocol implementations, workflow engines, or saga orchestrators where explicit state transitions are important for correctness
- **Systems requiring high availability** — where manual operator intervention on process failure is unacceptable

It is less valuable for purely CPU-bound batch processing (where a simple `ForkJoinPool` is the right tool) or for stateless request-response handlers at low concurrency (where the overhead of process management is not justified by the benefits).

---

## The thirty-year head start

Erlang systems are famous for uptime figures that Java systems rarely achieve: the Ericsson AXD301 switch ran for eleven years without a restart, accumulating nine minutes of downtime. WhatsApp served eight hundred million users on a small engineering team by relying on Erlang's fault-tolerance model. Elixir and the Phoenix framework brought this model to a new generation of developers.

These outcomes are not accidents of implementation. They are the predictable result of a design philosophy that treats isolation and supervised failure as first-class architectural concerns, not afterthoughts patched on with try/catch blocks.

This library makes that philosophy available to Java developers, on the JVM they already know, with the Java 26 language features that make it expressible without ceremony. The thirty-year head start becomes a library dependency.
