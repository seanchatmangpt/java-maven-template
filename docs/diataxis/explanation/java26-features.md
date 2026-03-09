# Java 26 Features This Library Exploits

This library is not a framework built on top of Java. It is a library built *from* Java 26 — one that would not be expressible in anything earlier than Java 21, and that improves with each language release through Java 26. Understanding which language features are load-bearing and why illuminates both the library's design and the direction Java has been moving.

This article tours six Java 26 feature groups and explains their role in implementing the OTP model idiomatically.

---

## Sealed types: exhaustive message and state hierarchies

Sealed types (`sealed interface`, `sealed class`) are the feature most central to the OTP mapping, and arguably the most underappreciated addition to the Java type system.

In Erlang, processes communicate by passing terms that are pattern-matched. An Erlang function might receive a message that is one of several tagged tuples — `{ok, Value}`, `{error, Reason}`, `{timeout, Ref}` — and dispatch based on the tag. The language guarantees that if you write a `receive` block with cases for `ok`, `error`, and `timeout`, and a message arrives with a different tag, you will get a clear runtime error.

Java has historically lacked this: an unconstrained interface can be implemented by any class, so you can never enumerate all possible implementations at compile time. A switch on interface types would need a default branch to be exhaustive.

Sealed interfaces fix this. When you declare:

```java
sealed interface OrderMsg permits PlaceOrder, CancelOrder, GetStatus {}
record PlaceOrder(String customerId, List<Item> items) implements OrderMsg {}
record CancelOrder(String orderId) implements OrderMsg {}
record GetStatus(String orderId) implements OrderMsg {}
```

you establish that `OrderMsg` has exactly three subtypes — no more, no less. This means a pattern matching switch over `OrderMsg` can be exhaustive without a default branch:

```java
OrderState handle(OrderState state, OrderMsg msg) {
    return switch (msg) {
        case PlaceOrder p  -> processPlacement(state, p);
        case CancelOrder c -> processCancellation(state, c);
        case GetStatus g   -> processStatusQuery(state, g);
    };
}
```

If a new message type is added to the sealed interface but not handled in the switch, the compiler produces an error — not a runtime exception, a compile-time error. This is the Java equivalent of Erlang's compile-time function clause analysis for `receive` blocks.

This matters enormously for the `Proc<S,M>` model. The `M` type parameter is expected to be a sealed interface. The handler function takes `S` and `M` and returns `S`. The compiler can verify that the handler is exhaustive — that every possible message is handled — before the code runs.

The same applies to `StateMachine<S,E,D>`: the state type `S` and event type `E` are both typically sealed, and the transition function can be exhaustively verified.

---

## Records: immutable messages and states

Records are the natural implementation type for the message and state types that flow through OTP-style systems.

A record is:
- **Immutable by default** — all components are final; there are no setters
- **Transparent** — the compiler generates `equals`, `hashCode`, and `toString` based on components
- **Concise** — a single declaration line creates a full data type
- **Deconstructable** — pattern matching can destructure a record into its components

The connection to the OTP model:

**Messages should be immutable.** A `Proc<S,M>` receives a message, handles it, and produces a new state. If the message object were mutable, the sender could modify it after sending — producing the kind of shared mutable state that the isolation model is designed to eliminate. Records are immutable by construction.

**State should be immutable.** The state `S` is passed to the handler function as an argument and returned as a new value. The old state is not modified; it is replaced. Records encourage this pattern because you cannot set fields — you copy with modifications using the with-expression syntax.

**Compact declarations reduce ceremony.** A message hierarchy for an order management system might need a dozen message types. With classes, each needs a constructor, fields, getters, `equals`, `hashCode`, and `toString`. With records, each is a one-liner. The reduced ceremony makes it practical to give each distinct message its own type, which enables the exhaustive pattern matching described above.

Records and sealed types compose naturally: a sealed interface with record subtypes is the idiomatic way to define a message or state hierarchy. The compiler knows exactly which record types are possible and can verify exhaustiveness; the pattern matcher can destructure the record in the case arm.

---

## Pattern matching: dispatching on type and structure

Java's pattern matching has evolved significantly through Java 26. The key capabilities available:

**Type patterns** in switch expressions match a value against a type and bind it to a variable in one operation:

```java
switch (msg) {
    case PlaceOrder p  -> handlePlacement(p.customerId(), p.items());
    case CancelOrder c -> handleCancellation(c.orderId());
}
```

**Guard patterns** allow additional conditions alongside type matching:

```java
switch (event) {
    case Connect c when c.timeout() < 0 -> throw new IllegalArgumentException(...);
    case Connect c                       -> handleConnect(c);
    case Disconnect d                    -> handleDisconnect(d);
}
```

**Record patterns** destructure a record's components inline within the match:

```java
switch (msg) {
    case PlaceOrder(var customerId, var items) -> handlePlacement(customerId, items);
    case CancelOrder(var orderId)              -> handleCancellation(orderId);
}
```

In Erlang, all of this is unified into a single pattern syntax that works for atoms, tuples, lists, and guards simultaneously. Java's pattern matching is more verbose, but it is type-safe in a way that Erlang's is not: a Java pattern match on a sealed type is verified by the compiler to cover all cases.

For `StateMachine<S,E,D>`, pattern matching on both the current state and the incoming event is the heart of the implementation. A nested switch — outer on state, inner on event — expresses the full transition table in a form the compiler can analyze.

---

## Virtual threads: the process execution model

Virtual threads (Project Loom, stable since Java 21, continuing through Java 26) are the execution mechanism that makes everything else possible. The concept is covered in depth in the [virtual threads article](virtual-threads.md); here the focus is on the language and API details.

Virtual threads in Java 26 are created via:

```java
Thread vt = Thread.ofVirtual()
    .name("proc-", idCounter.getAndIncrement())
    .start(runnable);
```

Key properties:

- **JVM-scheduled**: virtual threads are managed by the JVM, not the OS. The JVM multiplexes them onto a pool of platform threads (carrier threads) in its scheduler.
- **Blocking is cheap**: when a virtual thread blocks (on I/O, on `LockSupport.park()`, on `synchronized`, on `LinkedTransferQueue.take()`), the JVM parks the virtual thread's stack to the heap and frees the carrier thread to run another virtual thread.
- **Stack traces are real**: because virtual threads execute sequentially, their stack traces at the time of an exception are the actual call chain of application code, not reactive framework internals.
- **ThreadLocal works but is discouraged for virtual threads**: because virtual threads can be numerous and long-lived, `ThreadLocal` values that are large can create memory pressure. The library uses thread-local state only where necessary and prefers passing state as explicit arguments.

Java 26 continues refining virtual thread behavior, particularly around `synchronized` blocks (which in earlier versions could pin the virtual thread to the carrier thread). By Java 26, most blocking operations fully yield the carrier thread.

The `Proc<S,M>` implementation dedicates one virtual thread per process. The event loop in that thread blocks on `LinkedTransferQueue.take()`, which is a virtual-thread-friendly blocking operation — it parks the virtual thread without blocking a carrier thread. This means millions of processes can exist simultaneously with minimal carrier thread pressure.

---

## Structured concurrency: scoped fan-out

Structured concurrency (`StructuredTaskScope`, in preview from Java 21 and evolving through Java 26) addresses a specific coordination pattern: fanning out work to multiple concurrent tasks and waiting for them all to complete (or for any one to fail).

Without structured concurrency, forking concurrent tasks produces orphaned work: if the parent decides to cancel because one task failed, there is no automatic mechanism to cancel the other tasks. Callbacks and cancel tokens are the traditional solution — verbose and error-prone.

`StructuredTaskScope` defines a scope within which all forked tasks are tracked. When the scope closes (either normally or due to a failure), all tasks that haven't completed are cancelled. This ensures no orphaned work outlives its scope.

This library's `Parallel` class is built directly on `StructuredTaskScope`. It provides:

- **Structured fan-out**: fork N tasks and collect all results
- **Fail-fast semantics**: if any task fails, the scope cancels remaining tasks and the failure propagates
- **Timeout scoping**: a timeout on the scope applies uniformly to all forked tasks

In Erlang, the equivalent is `pmap` — a parallel map over a list that collects results or propagates the first error. The Java 26 version with structured concurrency is more powerful because the JVM automatically cancels in-flight tasks when the scope is cancelled, without requiring the application to thread cancel tokens through every call.

For the OTP model, structured concurrency serves a specific role: it is the mechanism for sending a message to multiple processes and awaiting all their replies, with automatic timeout and cancellation. The alternative — collecting `Future` objects from multiple `ask` calls and joining them — does not provide automatic cancellation of outstanding tasks if one times out or fails.

---

## JPMS: strong encapsulation

The Java Platform Module System (`module-info.java`, introduced in Java 9 and mature in Java 26) provides strong encapsulation: packages within a module are accessible to code outside the module only if explicitly exported.

This library uses JPMS for two purposes:

**Hiding internal implementation details.** The classes that implement `Proc<S,M>` internally — the virtual thread management, the mailbox plumbing, the supervisor state — are in packages that are not exported. Callers cannot extend or bypass the internals. This is important for correctness: if callers could access the underlying `LinkedTransferQueue` directly, they could bypass the message passing model and introduce the shared mutable state that the library is designed to prevent.

**Defining the public API surface explicitly.** The `module-info.java` exports exactly the packages that form the intended public API. Everything else is an implementation detail. This makes the API contract explicit and stable: you can see at a glance what is public and what is not, without needing to read class-level Javadoc.

From a conceptual standpoint, JPMS supports the OTP model's emphasis on isolation: just as processes are isolated from each other's internal state, the module system isolates the library's internals from its callers. The boundary is explicit and enforced by the JVM loader, not by convention.

---

## Preview features and `--enable-preview`

This library targets Java 26 with `--enable-preview`. Preview features in Java 26 that are relevant to the OTP model include continued refinement of pattern matching, record patterns, and structured concurrency.

Preview features are not unstable or experimental in the negative sense. They are features that the Java team has designed carefully and wants feedback on before stabilizing. In practice, most preview features stabilize with minimal changes in subsequent releases. The library uses them because they represent the most expressive current Java and because the library's users are explicitly targeting Java 26.

The `--enable-preview` requirement is documented and is a conscious architectural decision: maximally expressive code today, with the expectation that preview features will stabilize.

---

## How the features compose

The library's power comes from the composition of these features, not from any one in isolation.

A `Proc<S,M>` demonstrates the composition:
- `M` is a **sealed interface** with **record** subtypes — exhaustively enumerable, immutable
- The handler function uses **pattern matching** to dispatch on `M` — exhaustive at compile time
- The process runs on a **virtual thread** — cheap, isolated, blocking-friendly
- The `ProcRef<S,M>` handle uses **JPMS encapsulation** to hide the underlying virtual thread
- Fan-out to multiple processes uses **structured concurrency** for clean lifecycle management

A `StateMachine<S,E,D>` demonstrates the same composition for a different pattern:
- `S` (state) and `E` (event) are **sealed interfaces**
- `D` (data) is a **record**
- The transition function uses **nested pattern matching** on state and event
- Execution is on a **virtual thread**
- The **`Transition` sealed hierarchy** (`Stay`, `MoveTo`, `Stop`) models outputs exhaustively

Each Java 26 feature solves a specific problem in expressing the OTP model. Together, they make the model expressible at a level of conciseness and safety that approaches the Erlang original, in a language the Java ecosystem already knows.

---

## What would not work without these features

It is worth being explicit about which pieces would fall apart without Java 26:

Without **sealed types**: message dispatch would require `instanceof` chains or visitor patterns, losing compile-time exhaustiveness. Adding a new message type would silently break existing handlers.

Without **records**: message types would require four to eight times as much code, discouraging the fine-grained message hierarchy that enables exhaustive dispatch.

Without **virtual threads**: each process would require either an OS thread (limiting to tens of thousands) or asynchronous callbacks (losing readable sequential code and meaningful stack traces).

Without **pattern matching**: the `StateMachine` transition table would require nested `if/instanceof` chains, and record deconstruction would require manual field access.

Without **structured concurrency**: `Parallel` fan-out would require manual `Future` collection and cancellation token threading.

Without **JPMS**: the internal implementation of `Proc`, `Supervisor`, and `ProcRef` would be accessible to callers, enabling them to bypass the isolation model.

Java 26 is the minimum viable Java for this library. That is not a limitation — it is a measure of how much the language has matured, and of the deliberate co-evolution between the language features and the OTP model they enable.
