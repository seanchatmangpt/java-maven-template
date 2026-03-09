# Innovation 3: Actor-Per-Request HTTP Server — Structural Isolation at the Request Level

**Status:** Technical Specification
**Date:** 2026-03-08
**Codebase context:** `org.acme` — Java 25 JPMS library, GraalVM Community CE 25.0.2, `--enable-preview`

---

## Executive Summary

Every mainstream Java HTTP server shares at least one resource across requests: thread pools, schedulers, or execution contexts. This shared ownership is the root cause of a class of failure modes — leaked `ThreadLocal` state, cascading handler panics, and "noisy neighbor" latency spikes — that no amount of application-level discipline can fully eliminate. The Actor-Per-Request architecture described here makes isolation structural: each HTTP request is its own `Actor<RequestState, HttpMsg>`, supervised by a dedicated `RequestSupervisor`, running on exactly one virtual thread. Failure of one request actor is incapable of affecting any other. State that today travels through `ThreadLocal` instead travels through actor messages. The result is an HTTP server with the same failure-isolation properties that Erlang gave telephony systems in 1987 — except built entirely on Project Loom and the OTP primitives already present in `org.acme`.

---

## 1. Why Current Servers Fail the Isolation Test

### 1.1 Tomcat / Servlet Containers: ThreadLocal as Ambient State

Tomcat's thread-per-request model gave Java developers a comfortable mental model: one request, one thread, one stack. The pathology lives in what rides along on that thread invisibly. Spring Security stores the authenticated principal in `SecurityContextHolder`, which wraps a `ThreadLocal<SecurityContext>`. Spring's `RequestContextHolder` stores the current `HttpServletRequest` the same way. Micrometer's `MDC` stores the trace ID. Hibernate's `TransactionSynchronizationManager` stores the current transaction.

None of these data are passed as arguments. They are ambient — retrieved by static call anywhere in the call stack. This creates three failure modes:

**ThreadLocal leakage.** Tomcat uses thread pools. When a request finishes, the thread returns to the pool with its `ThreadLocal` map intact. If any framework fails to clear a context (a single missed `finally` block suffices), the next request that picks up that thread inherits the previous request's security context, transaction, or trace ID. This class of bug is security-critical and nearly invisible in testing because test execution is often single-threaded.

**Handler crash contaminates thread state.** If a handler throws an uncaught exception, the exception unwinds the stack but does not reset `ThreadLocal` values. If the thread is then reused (pooled), those stale values travel to the next request. Tomcat's `ExceptionUtils.handleThrowable` terminates the thread on `OutOfMemoryError` — but the thread pool creates a fresh thread with empty locals, so OOM in one request takes down the thread, not the pool's capacity.

**No structural enforcement.** Nothing in the JVM or in Tomcat prevents a developer from writing `SecurityContextHolder.getContext()` inside a `Runnable` dispatched to a different thread pool, causing the security context to vanish at runtime. The error appears only under load, when the `Runnable` executes on a thread whose `ThreadLocal` map is different from the originating thread.

### 1.2 Netty: Shared EventLoop as Single Point of Contention

Netty's EventLoop model is non-blocking and highly efficient. Each `EventLoop` is a single thread multiplexing I/O across thousands of channels. The problem is that handler code executes on the EventLoop thread directly. If a `ChannelHandler` blocks — on a database call, on a synchronized method, on a slow codec — it blocks all channels registered to that loop. Netty's documentation addresses this by telling developers to dispatch blocking work to a separate `EventExecutorGroup`. This is correct advice that is routinely ignored under deadline pressure, and no compiler or framework enforces it.

More critically, an uncaught exception in a `ChannelHandler` propagates to `DefaultChannelPipeline.onUnhandledInboundException`, which logs the error and closes the channel. If the handler is stateful and shared across channels (a common optimization), a crash in one channel's handler corrupts the shared state for all other channels using the same pipeline. Netty provides no supervision semantics: there is no automatic restart, no state reset, no isolation guarantee.

### 1.3 Spring WebFlux / Reactor: Shared Scheduler, Reactive Complexity

WebFlux replaced `ThreadLocal`-based ambient state with reactive context (`ContextView`), which is a genuine improvement: context flows through the reactive chain as an explicit parameter, not ambient state. However, WebFlux's execution model runs all operator chains on a shared `Schedulers.boundedElastic()` or `Schedulers.parallel()` pool. A slow or blocking `Mono` that escapes onto the wrong scheduler stalls work for all concurrent requests on that scheduler partition.

The deeper problem is that Reactor's error model is operator-local. `Mono.onErrorResume` handles errors at the operator level, but if a subscriber's `onNext` throws an uncaught exception, Reactor delivers it to `onError` and cancels the subscription — but the shared scheduler thread that executed the handler is unaffected. There is no isolation boundary at the request level; the request is a chain of lambda closures sharing the scheduler's thread, not a distinct process.

### 1.4 The Common Root Cause

All three models share a structural defect: the unit of failure is the thread or scheduler, not the request. When a request fails catastrophically, it contaminates whatever shared resource it was using at the time. Isolation is achieved by convention (clear your `ThreadLocal`) or by documentation (don't block the EventLoop) — never by construction.

---

## 2. The Full Actor-Per-Request Architecture

### 2.1 Core Types

The server introduces three sealed message hierarchies and one state record, all expressed as Java 25 records:

```java
// The complete message protocol for a single request actor
sealed interface HttpMsg permits
    HandleRoute, ReadBody, WriteResponse, Timeout, Cancel {}

record HandleRoute(String path, HttpMethod method, Map<String, String> queryParams)
    implements HttpMsg {}
record ReadBody(InputStream raw, int maxBytes) implements HttpMsg {}
record WriteResponse(int statusCode, Map<String, String> headers, byte[] body)
    implements HttpMsg {}
record Timeout(Duration elapsed) implements HttpMsg {}
record Cancel(String reason) implements HttpMsg {}

// The actor's state — all context the request needs, no ambient lookup
record RequestState(
    String requestId,          // UUID assigned at accept time
    AuthContext auth,          // resolved from token, not ThreadLocal
    TraceContext trace,        // span ID + parent span, not MDC
    ParsedHeaders headers,     // parsed once, immutable
    byte[] body,               // populated by ReadBody message
    RequestPhase phase         // ROUTING | BODY_READ | RESPONDING | DONE
) {}
```

`RequestState` is the total memory of the request. Nothing about the request lives outside this record. `ThreadLocal` is structurally impossible: there is no static call site that would know which `RequestState` to return. If a developer attempts `SecurityContextHolder.getContext()`, they get the Spring Security context for whatever thread happens to be running — which, in this model, is the actor's dedicated virtual thread, isolated from all other requests.

### 2.2 RequestActor Lifecycle

Each accepted TCP connection triggers the creation of one `Actor<RequestState, HttpMsg>`. The `Supervisor.supervise()` call registers the actor and returns a stable `ActorRef<RequestState, HttpMsg>` — the request's Pid analogue.

```
ACCEPT                ROUTING               BODY_READ            RESPONDING            DONE
  │                      │                      │                     │                  │
  ▼                      ▼                      ▼                     ▼                  ▼
spawn()──►tell(HandleRoute)──►tell(ReadBody)──►tell(WriteResponse)──►actor.stop()
  │
  │  actor state: RequestState{phase=ROUTING, auth=unresolved, body=null}
  │                      │
  │                      │  handler resolves auth from headers (no ThreadLocal)
  │                      │  dispatches to route handler via pattern match
  │                      ▼
  │              RequestState{phase=BODY_READ, auth=resolved, body=null}
  │                                              │
  │                                              │  handler reads body, validates
  │                                              ▼
  │                                    RequestState{phase=RESPONDING, body=[...]}
  │                                                              │
  │                                                              │  handler writes response
  │                                                              ▼
  └──────────────────────────────────────────────────  actor terminates, supervisor removes entry
```

The handler function `(RequestState, HttpMsg) -> RequestState` is a pure state transition. It receives the current state and a message, returns the next state. The virtual thread executing this function is the actor's dedicated thread — it does not belong to any pool and carries no state from any other request.

### 2.3 Parallel Sub-Requests Within One Actor

A route handler that needs to call three downstream microservices concurrently uses `Parallel.all()` directly inside the actor's message handler:

```java
case HandleRoute(var path, var method, var params) -> {
    // Fan out to three services — all three run on virtual threads
    // scoped to this actor's StructuredTaskScope
    var results = Parallel.all(List.of(
        () -> userService.fetchProfile(state.auth().userId()),
        () -> inventoryService.checkStock(params.get("itemId")),
        () -> pricingService.getPrice(params.get("itemId"), state.auth().tier())
    ));

    yield results.fold(
        data  -> state.withBody(serialize(data)).withPhase(RESPONDING),
        error -> state.withPhase(DONE)  // supervisor will send 500 on crash
    );
}
```

`Parallel.all()` uses `StructuredTaskScope.ShutdownOnFailure` internally. If any of the three sub-requests fails, the scope cancels the remaining two, and the exception propagates back into the actor's message handler. The handler throws, the supervisor observes the crash, sends a 500 response, and restarts the handler for any subsequent request with a fresh `RequestState`. No other request actor is aware that this happened.

### 2.4 Auth and Tracing Without ThreadLocal

Authentication context is resolved synchronously in the first message handler and stored in `RequestState.auth()`. Every subsequent handler receives the state record — it reads `state.auth()` as a plain field access. There is no static security context. There is no way for auth context to bleed from one request to another because the state record is never shared: each actor owns its own copy.

Distributed tracing follows the same pattern. The `TraceContext` record captures the `traceparent` header at parse time:

```java
record TraceContext(
    String traceId,
    String spanId,
    String parentSpanId,
    boolean sampled
) {
    static TraceContext from(ParsedHeaders headers) {
        // parses W3C traceparent header, falls back to generating new IDs
        return parse(headers.get("traceparent"))
            .orElseGet(TraceContext::newRoot);
    }
    TraceContext childSpan() {
        return new TraceContext(traceId, newSpanId(), spanId, sampled);
    }
}
```

When the actor fans out via `Parallel.all()`, each sub-request lambda captures `state.trace().childSpan()` — a new `TraceContext` record with the sub-request's own span ID. Span context flows as data, not as `MDC` side effects. There is no `MDC.put("traceId", ...)` call and no risk that a thread pool thread carries a stale trace ID from a previous request.

---

## 3. Supervision Tree

```
                        ServerSupervisor  (REST_FOR_ONE, maxRestarts=3, window=10s)
                               │
               ┌───────────────┼─────────────────────┐
               │               │                     │
        ListenerActor    ConnectionPoolActor    RequestSupervisor
        (accepts TCP)    (manages NIO selectors)  (ONE_FOR_ONE, unbounded)
                                                       │
                                          ┌────────────┼────────────┐
                                          │            │            │
                                    RequestActor  RequestActor  RequestActor
                                    req-a3f1      req-b7c2      req-d9e4
                                    (virtual thd) (virtual thd) (virtual thd)
```

**ServerSupervisor** uses `REST_FOR_ONE` with a 10-second window. Children are registered in order: `ListenerActor` first, then `ConnectionPoolActor`, then `RequestSupervisor`. If `ListenerActor` crashes (e.g., the accept loop throws on a corrupted selector), `REST_FOR_ONE` restarts `ListenerActor` and every child registered after it — namely `ConnectionPoolActor` and `RequestSupervisor`. Restarting `RequestSupervisor` terminates all in-flight `RequestActor` children. Every client with an in-flight request receives a connection reset; they will retry. This is the correct behavior: a crashed listener implies the I/O layer is in an unknown state, and carrying forward requests that may have read partial data is more dangerous than forcing clean retries.

**RequestSupervisor** uses `ONE_FOR_ONE`. When a `RequestActor` crashes — regardless of cause — the supervisor: (1) sends a 500 response to the waiting client via the actor's `WriteResponse` channel before terminating the actor, (2) stops the crashed actor, (3) removes its entry from the child registry. It does not restart the specific request actor because a request is a one-shot computation with a deadline; a restart would produce a duplicate response. The restart budget (`maxRestarts=3, window=10s`) guards against a route handler that crashes on every invocation, which would indicate a programmer error requiring operator intervention, not automated recovery.

**Crash propagation direction.** A crash in `req-a3f1` stays in `req-a3f1`. A crash in `RequestSupervisor` itself (e.g., OOM while allocating a new child entry) propagates up to `ServerSupervisor`, which applies `REST_FOR_ONE` — a controlled, visible failure of the entire request layer, logged with full context, rather than a silent corruption of shared state.

---

## 4. Request Cancellation Propagation

Cancellation in this model is a first-class message, not an interrupt thrown at an arbitrary stack frame.

When a client closes the connection before the response is written, the `ConnectionPoolActor` detects the channel closure via its NIO selector. It holds a registry of `(channelId -> ActorRef<RequestState, HttpMsg>)`. On detecting closure:

```
ConnectionPoolActor detects EOF on channel ch-7
  │
  └─► registry.get("ch-7")
        │
        └─► actorRef.tell(new Cancel("client disconnected"))
```

The `RequestActor` receives the `Cancel` message in its normal message loop. Its handler for `Cancel` is:

```java
case Cancel(var reason) -> {
    // 1. If a Parallel.all() scope is open, it is scoped to this actor's
    //    virtual thread via StructuredTaskScope — closing the actor's thread
    //    cancels the scope automatically.
    // 2. Record the cancellation reason for observability.
    yield state.withPhase(DONE).withCancelReason(reason);
    // handler returns DONE phase; actor's event loop exits on DONE
}
```

When the handler returns a state with `phase == DONE`, the actor's event loop breaks, the virtual thread exits, and the supervisor removes the child entry. Any `StructuredTaskScope` opened during a `Parallel.all()` fan-out is a child scope of the actor's virtual thread. When the thread exits, the JVM's structured concurrency guarantee ensures all subtasks in the scope are cancelled — the scope cannot outlive its owner thread. This is not a convention; it is enforced by `StructuredTaskScope`'s ownership semantics.

Timeout cancellation follows the same path. A `Timeout` message is scheduled by the `ListenerActor` at accept time:

```java
// Scheduled at accept time — fires after 30 seconds regardless of state
ScheduledExecutorService.schedule(
    () -> actorRef.tell(new Timeout(Duration.ofSeconds(30))),
    30, TimeUnit.SECONDS
);
```

The actor's `Timeout` handler transitions phase to `DONE` exactly as `Cancel` does. The scheduler that fired the timeout message is shared, but it performs only a single `tell()` call — a lock-free enqueue into the actor's `LinkedTransferQueue`. The scheduler thread does not execute request logic and cannot be contaminated by request state.

---

## 5. The ThreadLocal-Free Programming Model

The prohibition on `ThreadLocal` is enforced architecturally, not by code review.

**Why it cannot leak.** The actor's virtual thread is created fresh for each request via `Thread.ofVirtual().start(...)`. It inherits no `InheritableThreadLocal` values from the supervisor's thread (the supervisor thread itself has no request context to inherit). When the actor's virtual thread terminates, the JVM reclaims its `ThreadLocal` map. There is no thread pool: threads are not reused. Therefore, even if a developer manages to call `SecurityContextHolder.setContext(...)` inside a handler (because they depend on a legacy Spring Security library), that context exists only for the duration of the current actor's virtual thread and is inaccessible to any other request.

**Dependency injection of cross-cutting concerns.** Libraries that rely on `ThreadLocal` must be adapted to accept explicit context parameters. The recommended pattern is a `RequestScope` wrapper:

```java
// Instead of: SecurityContextHolder.getContext().getAuthentication()
// The handler receives auth through state:
record RequestState(AuthContext auth, TraceContext trace, ...) {}

// Route handler — auth is a parameter, not ambient state
Result<UserProfile, ApiError> fetchProfile(AuthContext auth, String userId) {
    return userRepository.findById(userId)
        .filter(u -> auth.canAccess(u.tenantId()))
        .map(Result::success)
        .orElse(Result.failure(ApiError.FORBIDDEN));
}
```

Spring Security's `SecurityContext` becomes a value type (`AuthContext`) constructed from the bearer token during the `HandleRoute` message handler and stored in `RequestState`. It is never set on the thread; it is never retrieved from the thread. The same applies to Micrometer's `MDC`: span IDs are fields on `TraceContext`, not ambient log context. Logging libraries that require `MDC` must be called with explicit context: `logger.atInfo().addKeyValue("traceId", state.trace().traceId()).log(...)`.

**ArchUnit enforcement.** An `ArchUnit` rule added to the test suite can forbid all access to `ThreadLocal`, `InheritableThreadLocal`, `SecurityContextHolder`, and `MDC` within the `org.acme.http` package:

```java
noClasses().that().resideInAPackage("org.acme.http..")
    .should().callMethod(ThreadLocal.class, "get")
    .orShould().callMethod(SecurityContextHolder.class, "getContext")
    .orShould().callMethod(MDC.class, "put")
    .check(importedClasses);
```

This converts a runtime failure mode (context leak under load) into a compile-time build failure.

---

## 6. Performance: Virtual Thread Overhead and Comparative Analysis

### 6.1 Virtual Thread Cost Per Request

A Java 25 virtual thread costs approximately 1–2 KB of heap at creation (the initial stack chunk). The `LinkedTransferQueue` mailbox used by `Actor` is allocated once at actor construction and adds approximately 48 bytes of object overhead. Total per-request allocation overhead is approximately 2–3 KB, compared to Tomcat's platform thread stack at 512 KB (the default `-Xss` value).

Message delivery latency through `LinkedTransferQueue.add()` is 50–150 ns under low contention (from the `Actor` class Javadoc, consistent with published JMH benchmarks for MPMC lock-free queues). For a request that processes three messages (`HandleRoute`, `ReadBody`, `WriteResponse`), this adds at most 450 ns of message-passing overhead — negligible against typical handler latency of hundreds of microseconds to milliseconds.

Virtual thread scheduling is performed by the JVM's fork-join pool (default: carrier threads = CPU cores). When the actor's virtual thread blocks on I/O (database call, upstream HTTP), it unmounts from the carrier thread immediately, freeing the carrier for another virtual thread. This is the same scheduling behavior that Netty achieves with non-blocking I/O, but without requiring the developer to structure their code as a chain of callbacks or reactive operators.

### 6.2 Comparison to Netty

| Metric | Netty EventLoop | Actor-Per-Request |
|---|---|---|
| Threads for 10,000 concurrent requests | 8–16 EventLoop threads | 10,000 virtual threads (each ~2 KB) |
| Handler crash impact | Closes channel, may corrupt shared handler state | Terminates exactly one actor, supervisor sends 500 |
| Blocking in handler | Stalls all channels on that EventLoop | Unmounts virtual thread, carrier unblocked |
| Auth/trace context | ThreadLocal or Netty AttributeMap (explicit but per-channel, not per-request) | `RequestState` record fields, typed, compile-checked |
| Cancellation | `ChannelFuture.cancel()`, propagation is manual | `tell(Cancel)` + structured scope auto-cancellation |
| Sub-request fan-out | Explicit `Future` chaining or `EventLoop.submit()` | `Parallel.all()` with `StructuredTaskScope` |

Netty's advantage is throughput at extreme connection counts where the OS scheduler's overhead for 10,000 virtual threads exceeds Netty's per-loop cost. Project Loom's virtual threads have been measured at 1–2 million concurrent virtual threads on modern hardware (JEP 444 benchmarks), so this crossover does not occur until request concurrency exceeds what any single JVM instance should serve.

Netty Incubator IOUring (`netty-incubator-transport-iouring`) reduces system call overhead by batching I/O operations via `io_uring` submission queues. This is a meaningful advantage for high-throughput I/O bound workloads on Linux 5.1+. The Actor-Per-Request server can be layered on top of IOUring for the I/O layer while maintaining actor isolation at the request layer — the two concerns are orthogonal. IOUring handles how bytes move between the NIC and JVM; actors handle what happens to those bytes once received.

### 6.3 Comparison to Tomcat Thread-Per-Request

Tomcat with platform threads supports approximately 200 concurrent requests per GB of heap (200 threads × 512 KB stack = 100 MB, plus heap). Virtual threads reduce this to approximately 500,000 concurrent requests per GB of heap (500,000 threads × 2 KB = 1 GB). The Actor-Per-Request model adds ~1 KB per request for the mailbox and state record, so the practical limit is approximately 300,000–400,000 concurrent in-flight requests per GB — still a 1,000× improvement over platform thread pools.

### 6.4 Comparison to Spring WebFlux

WebFlux achieves high concurrency by eliminating per-request threads entirely. Its throughput ceiling is determined by the scheduler's carrier thread count and the absence of blocking calls. The Actor-Per-Request model matches WebFlux's concurrency characteristics (virtual threads unmount on I/O block) while eliminating WebFlux's primary developer cost: the requirement to express all logic as reactive operator chains. A blocking database call inside an actor handler is safe; on WebFlux it would deadlock the scheduler.

---

## 7. Why This Is Blue Ocean

No production HTTP server for the JVM enforces process isolation at the request level without a full actor framework. The landscape occupies two existing positions:

**Thread-per-request servers** (Tomcat, Jetty, Undertow) provide the mental model of isolation — one thread, one request — but share thread pool resources and permit `ThreadLocal` contamination by construction.

**Async/reactive servers** (Netty, WebFlux, Vert.x) provide high concurrency by eliminating per-request threads entirely, at the cost of requiring non-blocking programming models and forfeiting the isolation guarantee: shared schedulers mean a misbehaving handler affects all concurrent handlers.

The Actor-Per-Request model occupies neither position. It provides:

1. **Structural isolation by construction.** A request actor crashing cannot affect any other request actor. This is not a documentation guideline; it is an invariant enforced by the supervision tree and the absence of shared mutable state between actors.

2. **Blocking-safe concurrency.** Developers write synchronous, blocking code. Virtual threads handle the scheduling. No reactive operators. No callback pyramids. No `flatMap` chains that obscure control flow.

3. **First-class cancellation.** Cancellation is a message, not an interrupt thrown at an unknown stack frame. `StructuredTaskScope` ensures sub-requests are cancelled when their owning actor stops.

4. **Zero-leakage state model.** `ThreadLocal` cannot leak between requests because there is no thread pool to leak through. Auth context, trace context, and request data are fields on an immutable record, not ambient state retrieved by static call.

5. **Transparent fault observability.** Every crash is a `ChildCrashed` event with a `Throwable` and a child ID. The supervision tree logs it, applies the restart strategy, and continues. There are no swallowed exceptions, no silent state corruptions, no `ExceptionHandler` beans wired by convention.

Erlang/OTP proved this model at telecom scale in the 1990s — nine-nines availability, hot code reloading, supervised process trees. Java 25 with Project Loom now provides the primitive that was missing: a lightweight, cheap process (virtual thread) that maps 1:1 to an actor. The `org.acme` library provides the supervision semantics. The Actor-Per-Request server assembles these primitives into a programming model that has never existed in the JVM ecosystem: an HTTP server where the request is the process, the supervisor is the reliability layer, and isolation is not a goal to strive for but a property that holds by construction.

The blue ocean is not a new I/O library. It is a new unit of isolation — the request actor — that makes a class of failure modes structurally impossible rather than merely discouraged.

---

## Appendix: Key API Contracts

| Component | Signature | Notes |
|---|---|---|
| `Actor<S,M>` | `Actor(S initial, BiFunction<S,M,S> handler)` | One virtual thread per instance, lock-free mailbox |
| `ActorRef<S,M>` | `tell(M)`, `ask(M) -> CF<S>`, `stop()` | Stable Pid — survives supervisor restart |
| `Supervisor` | `supervise(id, S, BiFunction) -> ActorRef` | Registers child with crash detection and restart |
| `Supervisor.Strategy` | `ONE_FOR_ONE`, `ONE_FOR_ALL`, `REST_FOR_ONE` | Direct OTP semantics |
| `Parallel.all()` | `List<Supplier<T>> -> Result<List<T>, Exception>` | `StructuredTaskScope` fan-out, fail-fast |
| `Result<T,E>` | `map`, `flatMap`, `fold`, `recover`, `orElseThrow` | Railway-oriented, sealed `Success`/`Failure` |
| `CrashRecovery.retry()` | `(maxAttempts, Supplier<T>) -> Result<T,Exception>` | Each attempt in isolated virtual thread |

---

*End of specification.*
