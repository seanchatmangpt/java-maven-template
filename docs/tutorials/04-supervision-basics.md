# Tutorial 04: Supervision Basics

In this tutorial, you'll learn JOTP's supervision system: how to build hierarchical process trees that automatically restart failed processes.

## What is Supervision?

**Supervision** is JOTP's answer to the "let it crash" philosophy:

1. **Child processes crash** when they encounter an unrecoverable error
2. **Parent supervisor** detects the crash via process links
3. **Supervisor restarts** the child according to a restart strategy
4. **System continues operating** without manual intervention

This is how Erlang achieves "99.9999999% uptime" — systems are designed to fail and self-heal.

## The Three Restart Strategies

JOTP supports three restart strategies (from Erlang/OTP):

| Strategy | When a child crashes | Effect |
|----------|---------------------|--------|
| **ONE_FOR_ONE** | Restart only that child | Other children unaffected |
| **ONE_FOR_ALL** | Restart crashed child AND all siblings | Nuclear option: reset entire group |
| **REST_FOR_ONE** | Restart the child and all children started after it | Middle ground: restart logical dependency chain |

## Example 1: Simple ONE_FOR_ONE Supervisor

Create a supervisor that restarts failed children independently:

```java
import io.github.seanchatmangpt.jotp.*;

public class SimpleSupervisionExample {
    static record WorkMsg(String work) {}
    static record CrashMsg() {}

    public static void main(String[] args) throws Exception {
        // Create a supervisor with ONE_FOR_ONE restart strategy
        var supervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(3)  // Max 3 restarts in 5 seconds
            .maxRestartWindow(java.time.Duration.ofSeconds(5))
            .child("worker-1", () -> createWorker(1))
            .child("worker-2", () -> createWorker(2))
            .child("worker-3", () -> createWorker(3))
            .build();

        // Workers run and can be queried
        supervisor.getChild("worker-1").send(new WorkMsg("Do something"));

        // If worker-1 crashes, supervisor restarts it automatically
        supervisor.getChild("worker-1").send(new CrashMsg());
        Thread.sleep(100);

        // worker-1 is now restarted and ready
        supervisor.getChild("worker-1").send(new WorkMsg("Do more work"));
    }

    static ProcRef<Integer, Object> createWorker(int id) {
        return Proc.start(
            state -> msg -> switch(msg) {
                case WorkMsg w -> {
                    System.out.println("Worker " + id + " working: " + w.work());
                    yield state + 1;
                }
                case CrashMsg -> throw new RuntimeException("Worker crashed!");
                default -> state;
            },
            0
        );
    }
}
```

## Example 2: ONE_FOR_ALL Strategy

Use `ONE_FOR_ALL` when children are interdependent and must be reset together:

```java
public class OneForAllExample {
    static record StartServiceMsg() {}
    static record StopServiceMsg() {}

    public static void main(String[] args) throws Exception {
        var supervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)
            .child("database", () -> createDatabase())
            .child("cache", () -> createCache())
            .child("api", () -> createAPI())
            .build();

        // Start all services
        supervisor.getChild("api").send(new StartServiceMsg());

        // If cache crashes, all services restart (to ensure consistency)
        supervisor.getChild("cache").send(new StopServiceMsg());

        System.out.println("All services restarted");
    }

    static ProcRef<String, Object> createDatabase() {
        return Proc.start(state -> msg -> "db-ok", "database");
    }

    static ProcRef<String, Object> createCache() {
        return Proc.start(state -> msg -> "cache-ok", "cache");
    }

    static ProcRef<String, Object> createAPI() {
        return Proc.start(state -> msg -> "api-ok", "api");
    }
}
```

## Example 3: REST_FOR_ONE Strategy

Use `REST_FOR_ONE` for processes with startup dependencies:

```java
public class RestForOneExample {
    // Service startup order: config → connection → worker

    public static void main(String[] args) throws Exception {
        var supervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.REST_FOR_ONE)
            .child("config", () -> createConfigService())
            .child("connection", () -> createConnection())
            .child("worker", () -> createWorker())
            .build();

        // If connection crashes:
        // - connection is restarted
        // - worker (started after connection) is restarted
        // - config (started before) is NOT restarted

        supervisor.getChild("connection").send("crash");
        Thread.sleep(100);
        System.out.println("Connection and dependent worker restarted");
    }

    static ProcRef<String, Object> createConfigService() {
        return Proc.start(state -> msg -> state, "config-loaded");
    }

    static ProcRef<String, Object> createConnection() {
        return Proc.start(state -> msg -> {
            if ("crash".equals(msg)) throw new RuntimeException("Connection failed");
            return state;
        }, "connected");
    }

    static ProcRef<String, Object> createWorker() {
        return Proc.start(state -> msg -> "working", "worker-ok");
    }
}
```

## Example 4: Hierarchical Supervision Tree

Build a tree of supervisors for complex systems:

```java
public class HierarchicalSupervisionExample {
    public static void main(String[] args) throws Exception {
        // Level 1: Root supervisor
        var rootSupervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)
            .child("http-supervisor", () -> createHttpSupervisor())
            .child("db-supervisor", () -> createDbSupervisor())
            .build();

        // Query nested children
        // rootSupervisor.getChild("http-supervisor") → supervisor
        // rootSupervisor.getChild("http-supervisor").getChild("handler-1") → process
    }

    static ProcRef<?, Object> createHttpSupervisor() {
        return (ProcRef<?, Object>) Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
            .child("handler-1", () -> createHandler(1))
            .child("handler-2", () -> createHandler(2))
            .child("handler-3", () -> createHandler(3))
            .build();
    }

    static ProcRef<?, Object> createDbSupervisor() {
        return (ProcRef<?, Object>) Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)
            .child("primary", () -> createDbNode())
            .child("replica", () -> createDbNode())
            .build();
    }

    static ProcRef<Integer, Object> createHandler(int id) {
        return Proc.start(state -> msg -> state, id);
    }

    static ProcRef<String, Object> createDbNode() {
        return Proc.start(state -> msg -> state, "db-node");
    }
}
```

## Example 5: Configuring Restart Limits

Prevent infinite restart loops by setting limits:

```java
public class RestartLimitExample {
    public static void main(String[] args) throws Exception {
        var supervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
            // Allow max 5 restarts within 30 seconds
            .maxRestarts(5)
            .maxRestartWindow(java.time.Duration.ofSeconds(30))
            // If limit exceeded: crash the supervisor itself (let parent handle it)
            .build()
            .child("flaky-worker", () -> createFlakyWorker());

        // If flaky-worker crashes more than 5 times in 30 seconds,
        // the supervisor crashes and reports to its parent
    }

    static ProcRef<Integer, Object> createFlakyWorker() {
        return Proc.start(
            state -> msg -> {
                if (state > 3) throw new RuntimeException("Too many attempts!");
                return state + 1;
            },
            0
        );
    }
}
```

## Example 6: Testing Supervision

Write tests to verify supervisor behavior:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class SupervisorTest {
    @Test
    void testOneForOneRestarts() throws Exception {
        var supervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
            .child("worker", () -> Proc.start(state -> msg -> {
                if (msg instanceof String s && s.equals("crash")) {
                    throw new RuntimeException("Crashed!");
                }
                return state + 1;
            }, 0))
            .build();

        var worker = supervisor.getChild("worker");
        var initialState = worker.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        // Cause crash
        worker.send("crash");
        Thread.sleep(100);

        // Supervisor has restarted worker
        var restartedState = worker.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        // State is reset to initial (0)
        assertThat(restartedState).isEqualTo(0);
    }

    @Test
    void testRestartLimitExceeded() throws Exception {
        var supervisor = Supervisor.builder()
            .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(2)
            .maxRestartWindow(java.time.Duration.ofSeconds(5))
            .child("flaky", () -> Proc.start(
                state -> msg -> { throw new RuntimeException("Always crashes"); },
                0
            ))
            .build();

        var flaky = supervisor.getChild("flaky");

        // Crash repeatedly
        flaky.send("msg1");
        Thread.sleep(100);
        flaky.send("msg2");
        Thread.sleep(100);
        flaky.send("msg3");  // Third crash exceeds limit

        // After limit: supervisor itself should fail
        // (Verify by checking if supervisor is still alive)
    }
}
```

## Key Takeaways

- ✅ **ONE_FOR_ONE** — Restart only the failed child
- ✅ **ONE_FOR_ALL** — Restart all children (for interdependent systems)
- ✅ **REST_FOR_ONE** — Restart child and all dependents (for ordered startup)
- ✅ **Hierarchical trees** — Nest supervisors for complex applications
- ✅ **Restart limits** — Prevent infinite restart loops
- ✅ **Automatic restart** — No manual intervention needed
- ✅ **"Let it crash"** — Design systems to fail and self-heal

## Real-World Pattern: Microservice Stack

```java
// Root supervisor: application
Supervisor.builder()
    .strategy(ONE_FOR_ALL)
    .child("metrics", () -> setupMetrics())      // Essential
    .child("logging", () -> setupLogging())      // Essential
    .child("http-api", () -> createHttpSupervisor())
    .child("workers", () -> createWorkerSupervisor())
    .child("db-pool", () -> createDatabasePool())
    .build();

// HTTP API supervisor: ONE_FOR_ONE (handlers are independent)
// Worker supervisor: ONE_FOR_ALL (must stay synchronized)
// DB pool: ONE_FOR_ALL (must reset connection state together)
```

## What's Next?

1. **[How-To: Build Supervision Trees](../how-to/build-supervision-trees.md)** — Advanced patterns
2. **[How-To: Handle Process Failures](../how-to/handle-process-failures.md)** — Crash recovery strategies
3. **[Reference: Supervisor API](../reference/api-supervisor.md)** — Full API documentation
4. **[Examples: Supervision Tree Example](../examples/supervision-tree-example.md)** — Complete working example

---

**Next:** [How-To Guides](../how-to/) or [Full Supervision Reference](../reference/api-supervisor.md)
