# OTP-Native JDBC: The Database Driver That Cannot Leak Connections

**Technical Specification — Innovation 1**
Status: Proposal | Date: 2026-03-08 | Author: Architecture Working Group

---

## Table of Contents

1. Motivation — Why Current Pools Leak
2. Core Concepts and Design Principles
3. Full API Design with Code Examples
4. Supervision Tree Architecture
5. Performance Analysis vs HikariCP
6. Integration with Existing JDBC Code
7. Blue Ocean Analysis
8. Implementation Roadmap

---

## 1. Motivation: Why Current Connection Pools Leak

### 1.1 The Fundamental Problem with Imperative Pools

HikariCP, c3p0, DBCP2, and every mainstream JDBC connection pool share one architectural flaw: **connection ownership is a convention, not a structural guarantee.** When a caller acquires a connection from the pool, it receives a raw `Connection` object — a mutable, shared-state object that can be passed to any thread, stored in a field, serialized into a closure, or simply forgotten.

The pool relies entirely on the caller to call `connection.close()` (which, for pooled connections, means "return to pool"). This call is voluntary. It can be skipped by:

- An uncaught exception escaping a `try` block with no `finally`
- A developer forgetting to close in a code path reached only under specific conditions
- A `Thread` that is interrupted before cleanup runs
- Returning from a method early without hitting the cleanup branch
- Placing a `Connection` in a `ThreadLocal` and then the thread dying without cleanup
- Passing the connection across an async boundary (e.g., into a `CompletableFuture`) where the original scope's `finally` block closes it prematurely — or never closes it at all

HikariCP mitigates this with a leak-detection timer that logs a stack trace after a configurable threshold (default: 0, i.e., disabled). This is a detection mechanism, not a prevention mechanism. You learn about the leak after it has already happened, potentially after the pool is exhausted and your application is returning HTTP 500 under load.

### 1.2 The Impedance Mismatch with Structured Concurrency

Java 25's `StructuredTaskScope` introduces lexically scoped concurrency: child tasks cannot outlive their enclosing scope. The JDK now enforces what good Java developers have always tried to achieve manually. But connection pools predate this model entirely. A `HikariDataSource.getConnection()` call returns outside any structured scope; the pool has no knowledge of the scope that acquired it, and the scope has no knowledge of the pool.

The result: structured concurrency in Java 25 closes the leak hole for threads, but leaves the leak hole for connections wide open.

### 1.3 The State Corruption Problem

Even setting leaks aside, pooled connections carry mutable state that can be corrupted:

- Uncommitted transactions left open
- `autoCommit` set to `false` when the pool expects `true`
- Custom `Statement` timeouts applied that persist to the next borrower
- `ClientInfo` properties or session variables set on the JDBC connection object

When a "dirtied" connection is returned to HikariCP, the pool's reset logic (`Connection.setAutoCommit(true)`, etc.) may or may not clean up all state, depending on driver version, configuration, and which exotic features were used. The next caller gets a connection in an indeterminate state.

### 1.4 The OTP Insight

Joe Armstrong's answer to these problems is architectural, not procedural: **make the wrong state unrepresentable.** In Erlang/OTP, a process owns its state exclusively. No other process can read or write that state directly — all communication is via message passing. If a process dies, its state dies with it; a supervisor creates a fresh process with fresh state. There is no "dirty state returned to pool" because the state never leaves the process boundary.

OTP-Native JDBC applies this insight directly to database connections.

---

## 2. Core Concepts and Design Principles

### 2.1 The Connection as an Actor

The central idea: each database connection is not an object you hold — it is an actor you send messages to.

```
Traditional pool:  caller holds Connection object → state can be corrupted, reference can escape
OTP-Native JDBC:   caller holds ActorRef → sends SqlMsg → actor processes in isolation
```

The `Connection` object lives inside the actor's private state. No caller ever touches it directly. The actor's virtual thread is the only thread that ever calls methods on the JDBC `Connection`. This makes JDBC thread-safety a non-issue: there is exactly one thread per connection, always.

### 2.2 Structural Leak Prevention via Scope

Connection acquisition is tied to a `StructuredTaskScope`. The pool supervisor is informed when a scope closes (via scope-local cleanup hooks or explicit `release` calls that are enforced by the type system — see Section 3). Because the `ActorRef` is the only handle to the connection, and the `ActorRef` cannot be serialized or placed in a `static` field without explicit effort, the structural pressure is toward correct usage.

### 2.3 Sealed Message Hierarchy

All communication with a connection actor goes through a sealed interface. This makes the complete protocol visible at compile time and exhaustively pattern-matchable:

```java
sealed interface SqlMsg permits
    SqlMsg.Query,
    SqlMsg.Execute,
    SqlMsg.BeginTransaction,
    SqlMsg.Commit,
    SqlMsg.Rollback,
    SqlMsg.Ping,
    SqlMsg.Release {}

record Query(String sql, List<Object> params,
             CompletableFuture<Result<List<Row>, SqlException>> reply)
    implements SqlMsg {}

record Execute(String sql, List<Object> params,
               CompletableFuture<Result<Integer, SqlException>> reply)
    implements SqlMsg {}

record BeginTransaction() implements SqlMsg {}
record Commit(CompletableFuture<Result<Void, SqlException>> reply) implements SqlMsg {}
record Rollback(CompletableFuture<Result<Void, SqlException>> reply) implements SqlMsg {}
record Ping(CompletableFuture<Boolean> reply) implements SqlMsg {}
record Release() implements SqlMsg {}
```

Every message that requires a reply carries its own `CompletableFuture`. The actor resolves the future when processing is complete. This is the Erlang `gen_server:call` pattern, without the RPC abstraction.

### 2.4 ConnectionState

The actor's state is an immutable record capturing the full lifecycle of the connection:

```java
record ConnectionState(
    Connection jdbc,          // the raw JDBC connection — never escapes this actor
    boolean inTransaction,
    int queryCount,
    Instant lastUsedAt,
    Status status
) {
    enum Status { IDLE, ACTIVE, CLOSING }
}
```

State transitions happen only inside the actor's message handler — a pure function
`(ConnectionState, SqlMsg) -> ConnectionState`.

---

## 3. Full API Design with Code Examples

### 3.1 Pool Bootstrap

```java
// Create the pool supervisor with ONE_FOR_ONE restart strategy.
// If a connection actor crashes, only that connection is restarted.
// After 5 crashes within 60 seconds, the supervisor itself terminates
// and propagates failure up to the application-level supervisor.
var poolSupervisor = new OtpConnectionPool(
    OtpPoolConfig.builder()
        .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
        .username("app")
        .password(Secret.fromEnv("DB_PASSWORD"))
        .minConnections(4)
        .maxConnections(20)
        .acquisitionTimeout(Duration.ofSeconds(5))
        .maxRestartsPerWindow(5)
        .restartWindow(Duration.ofSeconds(60))
        .build()
);

// OtpConnectionPool internally creates a Supervisor with ONE_FOR_ONE
// and registers one Actor<ConnectionState, SqlMsg> per connection.
// Each connection actor is identified by "conn-0", "conn-1", etc.
```

### 3.2 Acquiring and Using a Connection

```java
// Acquisition returns a scoped connection handle — not a raw Connection.
// The ScopedConnection is AutoCloseable: closing it sends Release to the actor,
// which returns it to the pool. Closing is the ONLY way to interact with the
// underlying connection.

try (ScopedConnection conn = poolSupervisor.acquire()) {

    // Query: returns Result<List<Row>, SqlException>
    Result<List<Row>, SqlException> rows = conn.query(
        "SELECT id, name FROM users WHERE active = ?",
        List.of(true)
    );

    // Railway-oriented processing — no checked exception propagation
    rows
        .map(list -> list.stream().map(Row::toUserDto).toList())
        .peek(users -> log.info("Found {} users", users.size()))
        .peekError(ex -> metrics.increment("db.query.error"))
        .orElseThrow();

    // Execute (INSERT/UPDATE/DELETE): returns Result<Integer, SqlException>
    Result<Integer, SqlException> affected = conn.execute(
        "UPDATE users SET last_login = ? WHERE id = ?",
        List.of(Instant.now(), userId)
    );

} // ScopedConnection.close() calls conn.tell(new Release())
  // The actor transitions back to IDLE and the supervisor marks it available.
  // If this line is never reached (exception thrown above), the try-with-resources
  // guarantee in the JVM language spec ensures close() is still called.
```

### 3.3 Transactional Scope

Transactions are first-class protocol members. The actor enforces that `Commit` and `Rollback` are only valid when `inTransaction == true`; violations produce a `Result.failure` rather than corrupting state.

```java
try (ScopedConnection conn = poolSupervisor.acquire()) {

    conn.beginTransaction();   // tells actor: BeginTransaction -> sets inTransaction=true

    Result<Integer, SqlException> insert = conn.execute(
        "INSERT INTO orders (user_id, total) VALUES (?, ?)",
        List.of(userId, total)
    );

    Result<Integer, SqlException> debit = conn.execute(
        "UPDATE accounts SET balance = balance - ? WHERE user_id = ?",
        List.of(total, userId)
    );

    // Fold both Results: if either failed, rollback; otherwise commit.
    boolean ok = insert.isSuccess() && debit.isSuccess();

    if (ok) {
        conn.commit()
            .peekError(ex -> log.error("Commit failed: {}", ex.getMessage()));
    } else {
        conn.rollback();
    }

} // close() is still called even if an exception escaped above.
  // The actor's Release handler checks inTransaction:
  //   - if true (caller forgot to commit/rollback), actor rolls back automatically
  //     before returning to IDLE. The pool never receives a dirty connection.
```

### 3.4 Crash-Resilient Acquisition with CrashRecovery

For callers operating under transient conditions (DB restarting, network blip):

```java
Result<List<Row>, Exception> result = CrashRecovery.retry(3, () -> {
    try (ScopedConnection conn = poolSupervisor.acquire()) {
        return conn.query("SELECT 1", List.of()).orElseThrow();
    }
});
```

`CrashRecovery.retry` runs each attempt in a fresh virtual thread. If the actor crashes mid-query (network drop), the supervisor restarts it with a fresh `Connection`, and the next retry gets a healthy actor. The caller sees a clean `Result<T, Exception>` — not a partially-executed JDBC operation.

### 3.5 Parallel Fan-Out with Parallel

For bulk operations across multiple connections simultaneously:

```java
// Parallel.map (using StructuredTaskScope.ShutdownOnFailure internally)
// distributes work across N connection actors concurrently.
List<Result<List<Row>, SqlException>> shardResults = Parallel.map(
    shardIds,
    shardId -> {
        try (ScopedConnection conn = poolSupervisor.acquire()) {
            return conn.query(
                "SELECT * FROM events WHERE shard_id = ?",
                List.of(shardId)
            );
        }
    }
);
```

If any shard query fails, `ShutdownOnFailure` cancels the remaining tasks. Each task's `ScopedConnection` is closed by the try-with-resources, returning each actor to IDLE. No connections are stranded.

---

## 4. Supervision Tree Architecture

```
APPLICATION SUPERVISOR (ONE_FOR_ALL, maxRestarts=1, window=10s)
│
│   Restarts the entire pool if the pool supervisor itself fails.
│   Connected to application-level alerting / circuit breaker.
│
└── POOL SUPERVISOR  (ONE_FOR_ONE, maxRestarts=5, window=60s)
    │   Actor<PoolState, PoolMsg>
    │   Manages connection inventory: free list, wait queue, metrics.
    │   Handles: AcquireRequest, ReleaseNotification, HealthCheck
    │
    ├── conn-0  Actor<ConnectionState, SqlMsg>  [IDLE]
    │   Virtual thread: blocking on mailbox.take()
    │   JDBC Connection: postgresql://... socket fd=7
    │
    ├── conn-1  Actor<ConnectionState, SqlMsg>  [ACTIVE]
    │   Processing: Query("SELECT ...", params, future)
    │   JDBC Connection: postgresql://... socket fd=8
    │
    ├── conn-2  Actor<ConnectionState, SqlMsg>  [IDLE]
    │   Virtual thread: blocking on mailbox.take()
    │   JDBC Connection: postgresql://... socket fd=9
    │
    ├── conn-3  Actor<ConnectionState, SqlMsg>  [RESTARTING]
    │   Previous actor crashed: SocketTimeoutException
    │   Supervisor spawning new Actor with fresh Connection
    │   Callers waiting in pool supervisor's wait queue
    │
    └── conn-N  [on-demand, up to maxConnections]
        Created by pool supervisor when demand exceeds idle count.
        Destroyed (actor stopped) when idle beyond keepalive window.

CRASH SCENARIO (conn-3 dies):
  1. conn-3's handler throws SocketTimeoutException
  2. Supervisor receives ChildCrashed("conn-3", SocketTimeoutException)
  3. ONE_FOR_ONE: only conn-3 is restarted; conn-0/1/2 keep running
  4. Supervisor calls CrashRecovery.retry(3, () -> openNewJdbcConnection())
  5. On success: ActorRef<ConnectionState, SqlMsg> for conn-3 is swapped
     atomically (ActorRef.swap()). All existing references now point to
     the new actor.
  6. Pool supervisor marks conn-3 IDLE again, serves next waiter.
  7. Crash counter incremented. If 5 crashes in 60s: pool supervisor
     itself terminates → application supervisor restarts the whole pool.

ACQUISITION FLOW:
  Caller → poolSupervisor.ask(new AcquireRequest())
         → Pool actor checks free list
         → If free: returns ActorRef<ConnectionState, SqlMsg>
         → If empty: enqueues caller future in wait queue
         → When any conn sends Release: dequeues oldest waiter, fulfills future
         → If acquisitionTimeout exceeded: future completes with AcquisitionTimeout
```

---

## 5. Performance Analysis vs HikariCP

### 5.1 Theoretical Overhead

OTP-Native JDBC introduces one additional layer compared to HikariCP: every SQL operation goes through the actor's mailbox (`LinkedTransferQueue`) rather than being called directly on a `Connection`.

**Per-operation cost breakdown:**

| Operation              | HikariCP                         | OTP-Native JDBC                         |
|------------------------|----------------------------------|-----------------------------------------|
| Acquire connection     | ConcurrentBag.borrow (~200 ns)   | Actor ask() + CompletableFuture (~350 ns)|
| Execute SQL            | Direct JDBC call                 | LinkedTransferQueue.add (~60 ns overhead)|
| Return connection      | ConcurrentBag.requite (~100 ns)  | Actor tell(Release) (~50 ns)            |
| Total overhead/request | ~300 ns                          | ~460 ns                                 |

The overhead is ~160 ns per request. At 100,000 requests/second, this is 16 ms of CPU time per second — well within noise for any workload where SQL execution itself takes more than 500 µs.

### 5.2 Where OTP-Native JDBC Wins

**1. Crash recovery latency.** When HikariCP's background eviction thread detects a dead connection (checks run every 30 seconds by default), callers block for up to 30 seconds waiting for a valid connection. OTP-Native JDBC detects the crash synchronously on the next SQL operation (the actor's handler throws, the supervisor is notified immediately) and restarts within the time it takes to open a new TCP connection — typically under 100 ms on a local network.

**2. No connection validation overhead.** HikariCP validates connections with a `SELECT 1` ping before returning them (configurable, but commonly enabled). In OTP-Native JDBC, the actor itself is the health signal: if it's alive (virtual thread running, mailbox processing), the connection is alive. The `Ping` message can be sent explicitly when needed, but is not needed on every acquisition because the actor would have already crashed if the connection died.

**3. Throughput under failure.** HikariCP's pool lock (`SuspendResumeLock`) can become a bottleneck when many threads simultaneously attempt to acquire connections while the pool is unhealthy. OTP-Native JDBC's pool supervisor is a single actor with a non-blocking mailbox — acquisition requests queue naturally without any lock, and the supervisor drains the queue as actors become available.

**4. Memory footprint per connection.** HikariCP wraps each connection in a `PoolEntry` with several volatile fields and lock structures. OTP-Native JDBC's `Actor` uses a `LinkedTransferQueue` and one virtual thread (~1 KB heap footprint). For pools with 100+ connections, the memory difference is measurable.

### 5.3 Benchmark Targets

- Acquire + single query + release: p99 < 2 ms (dominated by network RTT, not pool overhead)
- Pool throughput at 100% utilization: > 50,000 ops/sec on 20 connections (HikariCP: ~55,000)
- Crash-to-recovery time: < 200 ms (HikariCP default: up to 30,000 ms)
- Memory per 100 connections: < 2 MB (HikariCP: ~4 MB)

---

## 6. Integration with Existing JDBC Code via Proxy

Rewriting every DAO in a codebase to use the new actor-based API is impractical for adoption. OTP-Native JDBC provides two integration paths.

### 6.1 The JDBC Proxy DataSource

`OtpDataSource` implements `javax.sql.DataSource`. Calling `getConnection()` on it acquires a `ScopedConnection` from the pool and returns a dynamic proxy implementing `java.sql.Connection`. The proxy intercepts all method calls and converts them to actor messages.

```java
// Drop-in replacement: change only the DataSource instantiation.
DataSource ds = OtpDataSource.wrap(poolSupervisor);

// Existing code works unchanged:
try (Connection conn = ds.getConnection();
     PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
    ps.setInt(1, userId);
    try (ResultSet rs = ps.executeQuery()) {
        // ... process results
    }
} // conn.close() → proxy intercepts → tells actor Release
```

The proxy is implemented using `java.lang.reflect.Proxy` targeting the `java.sql.Connection` interface. Each intercepted method call is translated to the corresponding `SqlMsg` and dispatched via `actor.ask()`, blocking the calling thread until the actor processes the message (which happens on the actor's virtual thread).

This approach means the JDBC connection is never accessed from more than one thread simultaneously, even when legacy code calls multiple methods on the same `Connection` from different threads — the serialization happens at the mailbox.

### 6.2 The Transactional Template

For code using Spring's `@Transactional` or similar AOP-based transaction management, `OtpTransactionSynchronization` implements Spring's `TransactionSynchronization` interface, routing `beforeCommit`, `afterCompletion(ROLLED_BACK)`, etc. to the actor's transaction messages. The pool supervisor becomes the `DataSourceTransactionManager`'s underlying resource.

```java
@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource(OtpConnectionPool pool) {
        return OtpDataSource.wrap(pool);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource ds) {
        return new DataSourceTransactionManager(ds);
        // @Transactional methods now route through the actor protocol.
        // Commit/rollback decisions flow as SqlMsg.Commit / SqlMsg.Rollback.
    }
}
```

### 6.3 Hibernate / JPA Integration

`OtpConnectionProvider` implements Hibernate's `ConnectionProvider` SPI. This makes OTP-Native JDBC usable with any JPA implementation that targets Hibernate, including Spring Data JPA and Quarkus Panache, without any changes to entity mappings or repository interfaces.

---

## 7. Why This Is a Blue Ocean

### 7.1 What the Competition Does

| Pool       | Connection Model       | Leak Prevention      | Crash Recovery       | Concurrency Model       |
|------------|------------------------|----------------------|----------------------|-------------------------|
| HikariCP   | Object borrow/return   | Detection only       | Background eviction  | ConcurrentBag + locks   |
| c3p0       | Object borrow/return   | Detection only       | Polling              | Synchronized pools      |
| Vibur DBCP | Object borrow/return   | Detection only       | None                 | Concurrent collections  |
| R2DBC pool | Reactive mono/flux     | Backpressure         | Retry operators      | Project Reactor         |
| PGPOOL-II  | Server-side            | TCP keepalive        | Failover             | OS processes            |

No pool on this list uses the actor model. Every JVM pool treats connections as shared mutable objects subject to borrower discipline. R2DBC comes closest to the right model (reactive streams enforce backpressure and structured lifetimes) but operates at the driver protocol level, not the pool level, and requires a complete ecosystem switch away from JDBC.

### 7.2 The Three Structural Novelties

**Novelty 1: The connection is an actor, not an object.** This is not a naming convention or a wrapper pattern. The `Connection` object lives inside the actor's private state and is never extracted. All access is via message passing. This is the first time the Erlang/OTP process model has been applied at the individual connection level in a JDBC pool.

**Novelty 2: Leak prevention is structural, not behavioral.** HikariCP's leak detection is a runtime heuristic. OTP-Native JDBC's leak prevention is a consequence of the type system and concurrency model: you cannot "hold" an actor reference across a scope boundary in the same way you can hold a `Connection`, because the actor reference provides no direct access to the connection state.

**Novelty 3: Crash recovery is supervised, not polled.** The `Supervisor` with `ONE_FOR_ONE` restart means that a dead connection is detected and replaced within one message-processing cycle, not within the next polling interval. The recovery path is the same path used for initial connection creation — `CrashRecovery.retry` — so transient failures during recovery are also handled without special-casing.

### 7.3 Market Position

The target market is teams running Java 25+ services with high reliability requirements: financial services, healthcare, SaaS platforms with SLA commitments. For these teams, a 30-second connection recovery window is not acceptable; a production incident caused by a leaked connection exhausting the pool is not acceptable; an uncommitted transaction from a dirty connection causing data corruption is not acceptable.

HikariCP solves the performance problem. OTP-Native JDBC solves the correctness problem. These are not competing values — they are orthogonal. A future in which HikariCP-class throughput is combined with OTP-class correctness is the innovation this specification describes.

---

## 8. Implementation Roadmap

### Phase 1: Core Pool (Weeks 1–4)

- `SqlMsg` sealed interface and all record variants
- `ConnectionState` record with full lifecycle tracking
- `Actor<ConnectionState, SqlMsg>` message handler with switch expression over `SqlMsg`
- `OtpConnectionPool` wrapping `Supervisor` with `ONE_FOR_ONE`
- `ScopedConnection` with `AutoCloseable` enforcement
- Unit tests with H2 in-memory database

### Phase 2: Proxy Layer (Weeks 5–7)

- `OtpDataSource` implementing `javax.sql.DataSource` via dynamic proxy
- `PreparedStatement` and `ResultSet` proxy chains
- Integration tests with TestContainers (PostgreSQL, MySQL)
- Compatibility test suite against HikariCP's own test harness

### Phase 3: Framework Integration (Weeks 8–10)

- Spring `PlatformTransactionManager` bridge
- Hibernate `ConnectionProvider` SPI implementation
- Spring Boot auto-configuration with `application.properties` binding
- Quarkus extension (optional)

### Phase 4: Observability and Hardening (Weeks 11–12)

- Micrometer metrics: acquisition wait time, crash rate, restart count, active connection count
- OpenTelemetry span propagation through the actor protocol
- Pool warmup on startup via parallel connection establishment using `Parallel.map`
- Graceful shutdown: drain wait queue with `AcquisitionTimeout`, then stop all actors in reverse-registration order (mirrors `Supervisor.stopAll`)

### Phase 5: Benchmark Publication (Week 13)

- JMH benchmark suite comparing OTP-Native JDBC against HikariCP on:
  - Happy-path throughput (no failures)
  - Throughput with 5% connection failure injection
  - Recovery time distribution (p50, p95, p99, p99.9)
  - Memory footprint at pool sizes 10, 50, 100, 500

---

## Appendix: Key Design Decisions

**Why `LinkedTransferQueue` and not `ArrayBlockingQueue`?**
`LinkedTransferQueue` is a lock-free MPMC queue. The existing `Actor<S,M>` implementation already uses it. Back-pressure from a bounded queue would require the pool supervisor to handle rejection, adding complexity without benefit — the pool supervisor's own wait queue provides the back-pressure.

**Why not `CompletableFuture` chains instead of actors?**
`CompletableFuture` chains distribute state across callback closures with no single ownership point. When a chain fails, there is no supervisor to restart it. Actors provide a single-threaded, single-state-owner model that maps cleanly onto the crash-and-restart semantics the Supervisor already implements.

**Why sealed interfaces for `SqlMsg`?**
Sealed interfaces make the protocol exhaustive and compiler-checked. Adding a new message type (e.g., `SetSchema`) requires updating the switch expression in the actor's handler — the compiler rejects incomplete pattern matches. This prevents the silent "message dropped" failure mode that plagues dynamic actor systems.

**Why virtual threads per connection?**
One virtual thread per connection means the actor's message loop is always a straightforward blocking `take()` on the mailbox — no selector loops, no callback scheduling, no thread sharing. Virtual threads are cheap enough (~1 KB) that 500 connections consume 500 KB of heap for thread stacks, less than HikariCP's object overhead for the same pool size. JDBC drivers perform blocking I/O, which virtual threads handle without pinning a carrier thread (assuming the driver does not hold monitor locks during I/O — a known issue with some older drivers that is explicitly documented as a compatibility constraint).

**Why `ONE_FOR_ONE` and not `ONE_FOR_ALL`?**
A connection crash is local — it reflects a problem with one TCP socket or one server-side session, not with all connections. `ONE_FOR_ONE` limits the blast radius. `ONE_FOR_ALL` would take the entire pool offline for a single bad connection, which is exactly the kind of cascading failure OTP supervision trees are designed to avoid.
