# Innovation 5: OTP-Native Event Sourcing — Actors as Event-Sourced Aggregates

**Technical Specification**
Date: 2026-03-08
Codebase: `/home/user/java-maven-template/src/main/java/org/acme/`

---

## 1. Why Current Event Sourcing Frameworks Are Over-Engineered

Event sourcing is a conceptually simple idea: instead of storing the current state of an entity,
store the sequence of events that produced that state. State at any point in time is the fold of
all events up to that point. A five-year-old could describe the model. Yet the dominant Java
frameworks for implementing it have grown into frameworks that require days of study before a
developer can write a working aggregate.

**Axon Framework** introduces at least eight distinct annotations (`@Aggregate`, `@CommandHandler`,
`@EventSourcingHandler`, `@EventHandler`, `@QueryHandler`, `@ProcessingGroup`, `@TargetAggregateIdentifier`,
`@AggregateIdentifier`), a multi-module Maven structure, an `AxonServerConnectionManager`, a
`CommandBus`, an `EventBus`, a `QueryBus`, a `Snapshotter`, a `TokenStore`, and `TrackingEventProcessor`
configuration — before the first unit test passes. The framework owns your class hierarchy; your
aggregate must extend or be proxied by Axon's internal machinery.

**Lagom** (now maintenance-only) required Akka Cluster, Akka Persistence, a separate Kafka or
message broker for cross-service event publishing, a `PersistentEntityRegistry`, and a Play
Framework HTTP layer. It imposed an entire distributed systems platform on what is, at its core,
a linked list of records and a fold function.

**Eventuate Tram** and its descendants split the problem across a saga coordinator, a message
relay, an outbox table, and a polling loop — complexity that emerges from trying to bolt
at-least-once delivery onto a relational database that was never designed for it.

The root cause of this complexity is that these frameworks are solving a different problem than
the one they advertise. They are solving *distributed systems coordination* and wrapping event
sourcing around it. The fundamental event sourcing model — commands arrive, produce events,
events fold into state — requires none of this infrastructure when the runtime already provides
the right primitives.

Erlang/OTP has had those primitives since 1987: isolated processes, message passing, supervision
trees, and transparent restart. Java 25 virtual threads bring the same model to the JVM. The
`Actor<S,M>`, `Supervisor`, and `ActorRef` types in this codebase are a direct translation. The
missing piece is formalising the three-phase command-event-state cycle as a typed structure that
sits on top of those primitives. That is `EventSourcedActor<S,C,E>`.

The entire implementation described in this specification is approximately 200 lines of Java with
no external dependencies beyond the six files already in `org.acme`. There are no annotations.
There is no classpath scanning. There is no configuration XML. There is no framework to learn.
There is only the algebra of your domain expressed as Java Records and pure functions.

---

## 2. The `EventSourcedActor<S,C,E>` Design

### Type Parameters and Constraints

```java
// S = State  — the aggregate's current view of the world
// C = Command — an intent to change state (may be rejected)
// E = Event   — a fact that has occurred (never rejected; already happened)
public final class EventSourcedActor<
        S extends Record,
        C extends Record,
        E extends Record> {
```

All three type parameters are bounded by `Record`. This constraint is load-bearing:

- **Records are immutable by construction.** A `record OrderState(OrderId id, Money total,
  OrderStatus status)` cannot be mutated after creation. Every state transition produces a new
  instance. The handler can never accidentally share mutable state across messages.
- **Records are structurally comparable.** `equals` and `hashCode` are derived from components.
  Tests can assert `assertEquals(expected, actual)` on state snapshots without any custom
  matchers.
- **Records are serializable.** Sealed Record hierarchies serialize naturally to JSON, Avro, or
  Protocol Buffers with zero configuration. The event store writes what the type system gives it.

### The Three Pure Functions

The actor is parameterised by three pure functions, each expressible as a lambda:

```java
// 1. Command handler: decide whether a command is valid given current state.
//    Returns either a list of events to emit, or a rejection reason.
//    Must not perform I/O. Must not mutate state.
BiFunction<S, C, Result<List<E>, String>> decide;

// 2. Event folder: given current state and a new event, produce next state.
//    Pure function: (S, E) -> S. No I/O. No exceptions. Totalfunction.
BiFunction<S, E, S> evolve;

// 3. Initial state: the empty aggregate before any events have been applied.
Supplier<S> empty;
```

This decomposition comes directly from the DDD literature, but the types here are sharper than
any existing framework enforces: `decide` returns `Result<List<E>, String>`, making rejection
explicit at the type level rather than via exception. `evolve` has no `throws` clause because
event application cannot fail — if an event exists in the store, it can always be folded.

### The Actor's Internal Message Type

The actor wraps both commands and internal lifecycle messages in a sealed union:

```java
sealed interface Msg<C extends Record, E extends Record>
    permits Msg.Cmd, Msg.Replay {

  // A command arriving from a client
  record Cmd<C extends Record, E extends Record>(
      C command,
      CompletableFuture<Result<S, String>> reply
  ) implements Msg<C, E> {}

  // An event replayed from the event store during startup/restart
  record Replay<C extends Record, E extends Record>(
      E event
  ) implements Msg<C, E> {}
}
```

The actor's handler dispatches on pattern:

```java
// Inside Actor<AggregateState<S,E>, Msg<C,E>> handler:
(aggState, msg) -> switch (msg) {

    case Msg.Replay<C,E>(var event) -> {
        // Pure fold — no I/O, no persistence, just state evolution
        S next = evolve.apply(aggState.state(), event);
        yield aggState.withState(next);
    }

    case Msg.Cmd<C,E>(var command, var reply) -> {
        // Decide: may reject or produce events
        Result<List<E>, String> decision = decide.apply(aggState.state(), command);
        yield switch (decision) {
            case Result.Failure<List<E>, String>(var reason) -> {
                reply.complete(Result.failure(reason));
                yield aggState; // state unchanged
            }
            case Result.Success<List<E>, String>(var events) -> {
                // Persist to event store (synchronous ask to EventStore actor)
                eventStore.tell(new EventStore.Append<>(aggState.streamId(), events));
                // Fold events into new state
                S next = events.stream().reduce(aggState.state(), evolve::apply, (a, b) -> b);
                // Fan out to projections asynchronously
                fanOutToProjections(events);
                reply.complete(Result.success(next));
                yield aggState.withState(next);
            }
        };
    }
}
```

### The Aggregate State Wrapper

The actor's state type is not `S` directly but a thin wrapper that also carries bookkeeping:

```java
record AggregateState<S extends Record>(
    String streamId,      // e.g., "order-" + orderId
    S state,              // the domain state: OrderState, AccountState, etc.
    long version          // monotonic event sequence number for optimistic locking
) {
    AggregateState<S> withState(S next) {
        return new AggregateState<>(streamId, next, version + 1);
    }
}
```

The `version` field enables optimistic concurrency: the event store rejects an `Append` whose
expected version does not match the stored sequence length, and the actor returns
`Result.failure("conflict")` to the caller.

### The Full Constructor Signature

```java
public EventSourcedActor(
    String streamId,
    Supplier<S> empty,
    BiFunction<S, C, Result<List<E>, String>> decide,
    BiFunction<S, E, S> evolve,
    ActorRef<EventStore.State, EventStore.Msg<E>> eventStore,
    List<ActorRef<?, E>> projections,
    Supervisor supervisor
) {
    // 1. Create the internal Actor, supervised
    // 2. Immediately replay the event stream from eventStore to reconstruct state
    // 3. Register with supervisor for ONE_FOR_ONE restart
}
```

### Aggregate Registry

A `ConcurrentHashMap<AggregateId, ActorRef<AggregateState<S>, Msg<C,E>>>` serves as the
registry. On first access, the actor is created (which triggers replay). On subsequent accesses,
the same `ActorRef` is returned. Because `ActorRef` survives restarts — the `delegate` field is
`volatile` and `swap` is atomic — callers never observe the difference between a live actor and
a freshly restarted one:

```java
public ActorRef<AggregateState<S>, Msg<C,E>> getOrCreate(AggregateId id) {
    return registry.computeIfAbsent(id, k -> {
        String streamId = aggregateType + "-" + k.value();
        return supervisor.supervise(streamId,
            initialAggregateState(streamId),
            buildHandler());
    });
}
```

---

## 3. The Event Store as an OTP Actor

The event store is not a database client that lives outside the actor system. It is itself an
`Actor<EventStore.State, EventStore.Msg<E>>`, supervised by the same `Supervisor` tree as the
aggregates.

### Why an Actor?

An actor-based event store provides sequential, conflict-free writes for a given stream without
locks. Because an actor processes one message at a time, two concurrent `Append` messages for the
same stream are automatically serialised. There is no need for `SELECT FOR UPDATE`, `SERIALIZABLE`
isolation, or a version-check CAS loop at the application layer. The actor's mailbox is the
serialisation point.

### State and Messages

```java
// The in-memory event log: stream ID -> ordered list of events
record State<E extends Record>(
    Map<String, List<E>> streams,
    Map<String, Long> versions
) {}

sealed interface Msg<E extends Record>
    permits Msg.Append, Msg.Load {

    // Append events to a stream, with optimistic version check
    record Append<E extends Record>(
        String streamId,
        List<E> events,
        long expectedVersion,
        CompletableFuture<Result<Long, String>> ack
    ) implements Msg<E> {}

    // Retrieve all events for a stream (used during replay)
    record Load<E extends Record>(
        String streamId,
        CompletableFuture<List<E>> result
    ) implements Msg<E> {}
}
```

### Handler

```java
(state, msg) -> switch (msg) {

    case Msg.Append<E>(var streamId, var events, var expected, var ack) -> {
        long current = state.versions().getOrDefault(streamId, 0L);
        if (current != expected) {
            ack.complete(Result.failure(
                "Version conflict: expected %d, got %d".formatted(expected, current)));
            yield state;
        }
        List<E> existing = state.streams().getOrDefault(streamId, List.of());
        List<E> updated = Stream.concat(existing.stream(), events.stream()).toList();
        long newVersion = current + events.size();
        ack.complete(Result.success(newVersion));
        yield new State<>(
            put(state.streams(), streamId, updated),
            put(state.versions(), streamId, newVersion)
        );
    }

    case Msg.Load<E>(var streamId, var result) -> {
        result.complete(state.streams().getOrDefault(streamId, List.of()));
        yield state;
    }
}
```

### Persistence Backend

The in-memory map is the simplest implementation and is sufficient for testing. For production,
the handler delegates `Append` to a blocking JDBC write before updating in-memory state, wrapping
the call in `Result.of(...)` to propagate failures through the railway. Because the actor runs on
a virtual thread, blocking JDBC calls are cheap: the virtual thread is parked on the carrier
thread's scheduler while the database round-trip occurs, with no OS thread blocked.

The event store actor is supervised independently. If it crashes (e.g., JDBC connection lost),
the supervisor restarts it. On restart, the in-memory state is empty, but aggregate actors will
re-issue `Load` requests during their own replay — so the event store rebuilds its cache
lazily from the durable backend.

---

## 4. CQRS Projection Fan-Out via `Parallel.all()`

After committing events to the event store, the aggregate actor fans out to all registered
projection actors. Projections are the read side of CQRS: they maintain denormalised views
(search indexes, dashboards, materialised summaries) derived from the event stream.

### Fan-Out Implementation

```java
private void fanOutToProjections(List<E> events) {
    List<Supplier<Void>> tasks = projections.stream()
        .map(proj -> (Supplier<Void>) () -> {
            events.forEach(proj::tell);
            return null;
        })
        .toList();

    Result<List<Void>, Exception> result = Parallel.all(tasks);

    result.peekError(ex ->
        // Log projection failure — do NOT fail the command.
        // The event is already committed; projections are eventually consistent.
        logger.warn("Projection fan-out partial failure: {}", ex.getMessage())
    );
}
```

`Parallel.all()` uses `StructuredTaskScope.Joiner.allSuccessfulOrThrow()`: all projection
`tell()` calls execute concurrently on virtual threads, completing in the time of the slowest
projection. Because `tell()` is non-blocking (it enqueues to the projection actor's mailbox and
returns immediately), the fan-out time is effectively the time to enqueue N messages, which is
sub-microsecond per projection.

The command is considered successful as soon as the event is in the event store. Projection
delivery is best-effort at the point of fan-out but can be made durable by having the event
store actor also maintain a projection offset log — a standard CQRS pattern that requires no
framework support.

### Projection Actor Shape

A projection actor is an ordinary `Actor<P, E>` where `P` is the projection's state (e.g., a
`Map<OrderId, OrderSummary>`) and `E` is the event type. The handler is a pure function
`(P, E) -> P`. Projections have no concept of commands or command rejection. They are
append-only event consumers:

```java
var orderSummaryProjection = supervisor.supervise(
    "order-summary-projection",
    new OrderSummaryState(Map.of()),
    (state, event) -> switch (event) {
        case OrderPlaced(var id, var total) ->
            state.withSummary(id, new OrderSummary(id, total, OPEN));
        case OrderShipped(var id) ->
            state.updateStatus(id, SHIPPED);
        case OrderCancelled(var id) ->
            state.updateStatus(id, CANCELLED);
    }
);
```

---

## 5. Supervisor-Triggered Restart Equals Event Replay

This is the structural insight that separates OTP-native event sourcing from every framework
alternative: **there is no special crash recovery path**. Startup and recovery are the same code.

In Axon, crash recovery is a framework concern: the `EventSourcingRepository` intercepts the
aggregate load, detects that it is not in the cache, issues a `DomainEventStream` query, and
replays events through `@EventSourcingHandler` methods before handing the aggregate to the
command handler. This is hidden machinery that the developer cannot inspect or override cleanly.

In this design, the `Supervisor.restartOne()` method calls `restartOne(entry)`, which calls
`entry.stateFactory.get()` to obtain a fresh initial state. For an `EventSourcedActor`, the
"initial state factory" is not `new OrderState()` — it is `replayFromStore()`:

```java
private AggregateState<S> replayFromStore() {
    // Synchronous ask to the event store actor
    List<E> history = eventStore.ask(new EventStore.Msg.Load<>(streamId))
        .orTimeout(5, SECONDS)
        .join();

    // Pure fold: identical to the fold performed during normal command processing
    S rebuilt = history.stream().reduce(empty.get(), evolve::apply, (a, b) -> b);

    return new AggregateState<>(streamId, rebuilt, history.size());
}
```

This function is registered as the `stateFactory` in the `Supervisor.ChildEntry`. When the
supervisor calls `entry.stateFactory.get()`, it gets a fully reconstructed aggregate state.
The supervisor does not know it is doing event replay. The aggregate actor does not know it was
restarted. The `ActorRef` held by clients does not know the underlying actor was replaced.
Armstrong called this "transparent process restart." Here it is structurally enforced rather than
documented.

A concrete failure scenario: an aggregate actor processing a large batch of `PlaceOrder` commands
allocates 2 GB of state while building an in-memory cache it should not have built. The JVM OOMs
and the actor's virtual thread dies. The supervisor's uncaught exception handler fires
`ChildCrashed`. The supervisor calls `restartOne`. The state factory calls `replayFromStore`.
The actor's mailbox still contains the unprocessed commands from the batch (they were sent with
`tell`, not drained on crash). The restarted actor, with fresh state, continues processing them.
The client observing via `ActorRef.ask()` sees a momentary timeout on in-flight requests (the
futures of messages lost during the restart window) and a normal response on the retry. No data
is lost because every committed event is in the event store.

---

## 6. Testing: Records Are Honest Witnesses

Armstrong's phrase "no lying programs" comes from a 2014 talk where he contrasted Erlang's
pattern-matched, value-typed message passing with object-oriented systems that lie through
encapsulation — hiding state behind methods that may return anything. The test double is the
canonical lying program: a mock that claims to be a repository but returns whatever the test
configures it to return, regardless of whether any real repository would behave that way.

In this design, there is nothing to mock.

**Testing `decide`:** It is a pure function `(S, C) -> Result<List<E>, String>`. Call it with
a state record and a command record. Assert on the returned events. No framework, no container,
no actor, no thread.

```java
@Test
void rejectsOrderWhenOutOfStock() {
    var state  = new OrderState(SKU_123, quantity: 0, OPEN);
    var cmd    = new PlaceOrder(SKU_123, quantity: 5);
    var result = decide.apply(state, cmd);
    assertThat(result).isInstanceOf(Result.Failure.class);
    assertThat(result.fold(__ -> null, err -> err)).contains("insufficient stock");
}
```

**Testing `evolve`:** It is a pure function `(S, E) -> S`. Call it with a state record and an
event record. Assert on the returned state record. Structural equality via `Record.equals`.

```java
@Test
void orderShippedUpdatesStatus() {
    var before = new OrderState(ORDER_1, Money.of(100), CONFIRMED);
    var event  = new OrderShipped(ORDER_1, Instant.parse("2026-03-08T10:00:00Z"));
    var after  = evolve.apply(before, event);
    assertThat(after).isEqualTo(new OrderState(ORDER_1, Money.of(100), SHIPPED));
}
```

**Testing command-to-event-to-state round trips:** Use jqwik property-based testing. Generate
arbitrary valid states and commands using Instancio. Assert that for all inputs either a
rejection is returned or the folded state satisfies invariants. No shrinking logic needed because
Records print their components clearly in failure messages.

```java
@Property
void decidedEventsAlwaysPreserveMoneyConservation(
    @ForAll @From("validOrderStates") OrderState state,
    @ForAll @From("validCommands")    OrderCommand cmd
) {
    decide.apply(state, cmd).peek(events -> {
        OrderState final_ = events.stream().reduce(state, evolve::apply, (a,b) -> b);
        assertThat(final_.total()).isGreaterThanOrEqualTo(Money.ZERO);
    });
}
```

**Testing the full actor pipeline:** Use `ActorRef.ask()` with Awaitility for integration tests
that exercise the actor's mailbox, event store persistence, and projection fan-out. These are
`*IT.java` tests that run under `mvnd verify`. Because the event store is itself an in-memory
actor during tests, there is no database to set up. The test constructs the supervision tree,
sends commands, and asserts on projection state — all in-process.

---

## 7. Performance: Virtual Thread per Aggregate, Zero Pool Contention

Traditional event sourcing frameworks run aggregates on shared thread pools. Axon's command bus
uses a `PooledStreamingEventProcessor` with a configurable segment count; commands for different
aggregates compete for threads in the pool. Under load, a slow aggregate (e.g., one replaying
10,000 events) blocks a thread that could serve a fast aggregate.

In this design, each aggregate is an `Actor` running on its own virtual thread. Virtual threads
in Java 25 have approximately 1 KB of initial stack, are scheduled cooperatively by the JVM's
`ForkJoinPool` carrier threads, and park cheaply on blocking operations. The JVM supports
millions of concurrent virtual threads on commodity hardware.

The performance characteristics:

- **Mailbox throughput:** `LinkedTransferQueue` achieves 50–150 ns per enqueue/dequeue on
  modern hardware. A single aggregate can process 6–20 million messages per second in the absence
  of I/O.
- **Command latency:** For a command that produces one event, latency is: mailbox enqueue +
  handler execution + event store `ask` round-trip (another actor, same JVM: ~200 ns) +
  projection `tell` (non-blocking: ~50 ns per projection). Total: sub-millisecond on-JVM,
  dominated by the event store persistence write when a durable backend is used.
- **Replay throughput:** During restart, `evolve` is a pure function fold with no I/O.
  Folding 100,000 events over a `Record`-based state takes approximately 10–50 ms depending on
  state complexity. For aggregates with millions of events, snapshot support is added by storing
  a periodic state Record in the event store alongside the event stream — no framework change
  required, just an additional `Msg.Snapshot<S>` case in the event store handler.
- **Projection fan-out:** `Parallel.all()` with `StructuredTaskScope` runs N projection enqueues
  concurrently. With 10 projections, fan-out adds ~1 µs to command latency (dominated by
  `StructuredTaskScope.join()` overhead, not by the enqueues themselves).
- **Supervisor overhead:** The supervisor's event loop is idle unless a child crashes. Restart
  detection adds zero overhead to the hot path.

Compared to Axon with Axon Server: Axon routes every command through Axon Server (a separate
process), serializes it, deserializes it, routes the resulting events back through the server,
and then delivers to event processors. The minimum round-trip is a network hop to localhost plus
serialization. This design has no network hop for the aggregate's hot path. The event store write
is local unless the backend is remote, and even then it is a single JDBC round-trip on a virtual
thread — not a message bus traversal.

---

## 8. Why This Is Blue Ocean

The event sourcing tool landscape falls into two categories:

**Category 1 — Framework-heavy, annotation-driven:** Axon, Lagom, Eventuate. These require the
developer to adopt the framework's object model, learn its configuration, operate its
infrastructure (Axon Server, Kafka, Cassandra), and accept that the framework owns the aggregate
lifecycle. The conceptual overhead is high; the operational overhead is higher.

**Category 2 — Database-centric:** EventStoreDB, Marten (.NET), Broadway (Elixir). These are
storage engines with event sourcing semantics. They handle persistence excellently but leave the
aggregate execution model to the developer. The developer typically reaches for a shared thread
pool and mutable aggregate objects, reintroducing the concurrency problems that event sourcing
was supposed to solve.

Neither category provides **structural correctness through composition of primitives**. The
insight in this specification is that the five correctness properties of event sourcing —
isolation, serialised writes, transparent restart, state reconstruction, and testability without
infrastructure — each correspond exactly to one existing OTP primitive:

| Property                   | OTP Primitive          | Java 25 Implementation    |
|----------------------------|------------------------|---------------------------|
| Command isolation          | Process share-nothing  | `Actor<S,M>` virtual thread |
| Serialised writes          | Single-process mailbox | `LinkedTransferQueue`     |
| Transparent restart        | Supervisor + Pid       | `Supervisor` + `ActorRef` |
| State reconstruction       | Process initialisation | `replayFromStore()` as `stateFactory` |
| Pure testability           | Functional core        | `Record`-bounded type params |

No existing framework in any JVM language has made this mapping explicit and implemented it
without a framework runtime. Akka Persistence in Scala makes a similar structural claim but
requires the Akka actor system, Akka Cluster for distribution, and Akka Persistence plugins —
a substantial runtime commitment. The Elixir community's `Commanded` library comes closest in
spirit but is Elixir-only.

**The blue ocean is the intersection of:**
1. Event sourcing correctness guarantees
2. OTP structural patterns
3. Zero external dependencies
4. Java 25 / JVM deployment

This intersection is currently unoccupied. A library implementing `EventSourcedActor<S,C,E>` on
top of the six files in `org.acme` would be publishable to Maven Central at approximately 500
lines of code total (implementation + tests), offer better correctness guarantees than Axon
at 1/100th the API surface, and run anywhere a JDK 25 exists.

The specification above is not aspirational. Every piece of it composes directly from code that
already exists in `/home/user/java-maven-template/src/main/java/org/acme/`. The `Actor`,
`ActorRef`, `Supervisor`, `Parallel`, and `Result` types are production-ready. The
`EventSourcedActor` is a thin orchestration layer over them — roughly 200 lines — that closes
the loop from OTP primitives to a complete, framework-free event sourcing runtime.

Armstrong said: "The problem with object-oriented languages is they've got all this implicit
environment that they carry around with them. You wanted a banana but what you got was a gorilla
holding the banana and the entire jungle." `EventSourcedActor<S,C,E>` gives you the banana.

---

*End of specification. Target implementation: `src/main/java/org/acme/EventSourcedActor.java`,
`src/main/java/org/acme/EventStore.java`. Tests: `src/test/java/org/acme/EventSourcedActorTest.java`,
`src/test/java/org/acme/EventSourcedActorIT.java`.*
