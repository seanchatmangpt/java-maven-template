# How-To: Handle Process Failures

This guide covers fault tolerance patterns in JOTP: supervision, crash recovery, and resilience strategies.

## When to Use This Guide

Use this guide when you want to:
- Automatically restart failed processes
- Implement supervisor trees
- Handle and recover from errors
- Build fault-tolerant systems
- Design process monitoring

## Pattern 1: Using Supervisor for Auto-Restart

The simplest fault-tolerance approach: use a `Supervisor` to restart failed processes:

```java
import io.github.seanchatmangpt.jotp.*;

public class SupervisorBasics {
    static class WorkerProcess {
        static int callCount = 0;

        static Object handler(Object state, Object msg) {
            callCount++;
            if (callCount == 3) {
                throw new RuntimeException("Worker crashed!");
            }
            return state;
        }

        static ProcRef<Void, Object> create() {
            return Proc.start(
                state -> msg -> handler(state, msg),
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        // Create a supervisor that restarts the worker if it crashes
        var supervisor = Supervisor.oneForOne()
            .add("worker", () -> WorkerProcess.create())
            .build();

        var worker = supervisor.whereis("worker");

        // Send messages
        worker.send("msg1");  // OK
        worker.send("msg2");  // OK
        worker.send("msg3");  // Crashes and restarts
        worker.send("msg4");  // OK, running on new instance

        System.out.println("Worker survived crash and continued");
    }
}
```

## Pattern 2: One-For-One vs One-For-All

Choose the right supervisor strategy:

```java
public class SupervisorStrategies {
    static class ServiceA {
        static ProcRef<Void, Object> create() {
            return Proc.start(_ -> msg -> null, null);
        }
    }

    static class ServiceB {
        static ProcRef<Void, Object> create() {
            return Proc.start(_ -> msg -> null, null);
        }
    }

    static class ServiceC {
        static ProcRef<Void, Object> create() {
            return Proc.start(_ -> msg -> null, null);
        }
    }

    public static void main(String[] args) throws Exception {
        // ONE_FOR_ONE: if A crashes, only A restarts
        var oneForOne = Supervisor.oneForOne()
            .add("serviceA", ServiceA::create)
            .add("serviceB", ServiceB::create)
            .add("serviceC", ServiceC::create)
            .build();

        // ONE_FOR_ALL: if any service crashes, all services restart
        var oneForAll = Supervisor.oneForAll()
            .add("serviceA", ServiceA::create)
            .add("serviceB", ServiceB::create)
            .add("serviceC", ServiceC::create)
            .build();

        // REST_FOR_ONE: if B crashes, B and C restart, A stays running
        var restForOne = Supervisor.restForOne()
            .add("serviceA", ServiceA::create)
            .add("serviceB", ServiceB::create)
            .add("serviceC", ServiceC::create)
            .build();

        System.out.println("Supervisors created with different strategies");
    }
}
```

## Pattern 3: Hierarchical Supervision Trees

Build multi-level supervision structures:

```java
public class SupervisionTrees {
    // Leaf processes
    static class WebServer {
        static ProcRef<Integer, Object> create() {
            return Proc.start(
                port -> msg -> port,
                8080
            );
        }
    }

    static class Database {
        static ProcRef<String, Object> create() {
            return Proc.start(
                url -> msg -> url,
                "jdbc:sqlite:app.db"
            );
        }
    }

    // Mid-level supervisor: supervises web + db
    static ProcRef<Void, Object> createServicesSupervisor() {
        return Supervisor.oneForOne()
            .add("webserver", WebServer::create)
            .add("database", Database::create)
            .build();
    }

    // Top-level supervisor: supervises the services supervisor
    static ProcRef<Void, Object> createRootSupervisor() {
        return Supervisor.oneForOne()
            .add("services", SupervisionTrees::createServicesSupervisor)
            .build();
    }

    public static void main(String[] args) throws Exception {
        var root = createRootSupervisor();
        System.out.println("Hierarchical supervision tree created");
    }
}
```

## Pattern 4: CrashRecovery for Isolated Retries

Use `CrashRecovery` for transient fault handling:

```java
public class CrashRecoveryPattern {
    static class UnstableOperation {
        static int attempts = 0;

        static String performIO() throws Exception {
            attempts++;
            if (attempts < 3) {
                throw new IOException("Temporary network failure");
            }
            return "Success!";
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            var result = CrashRecovery.of(
                () -> UnstableOperation.performIO(),
                3  // Max retries
            );

            System.out.println("Result: " + result);
        } catch (Exception e) {
            System.out.println("Failed after retries: " + e);
        }
    }
}
```

## Pattern 5: ProcessMonitor for Unilateral Supervision

Monitor process termination without affecting process lifetime:

```java
import java.util.concurrent.TimeUnit;

public class ProcessMonitoring {
    sealed interface Msg permits PingMsg {}
    record PingMsg() implements Msg {}

    static class MonitoredProcess {
        static ProcRef<Integer, Msg> create() {
            return Proc.start(
                count -> msg -> count + 1,
                0
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var proc = MonitoredProcess.create();

        // Monitor process without stopping it
        var monitor = ProcessMonitor.monitor(proc);

        // Main process continues...
        proc.send(new PingMsg());
        proc.send(new PingMsg());

        // Later, check if monitored process has terminated
        var terminated = monitor.getExitSignal(100, TimeUnit.MILLISECONDS);
        if (terminated.isEmpty()) {
            System.out.println("Process still running");
        } else {
            System.out.println("Process terminated: " + terminated.get());
        }
    }
}
```

## Pattern 6: Process Links for Mutual Supervision

Link processes so crash of one kills the other:

```java
public class ProcessLinking {
    sealed interface ParentMsg permits TerminateMsg {}
    record TerminateMsg() implements ParentMsg {}

    sealed interface ChildMsg permits PingMsg {}
    record PingMsg() implements ChildMsg {}

    static class Parent {
        static ProcRef<Void, ParentMsg> create(
            ProcRef<Void, ChildMsg> child) {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case TerminateMsg ->
                        throw new RuntimeException("Parent terminating");
                },
                null
            );
        }
    }

    static class Child {
        static ProcRef<Void, ChildMsg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case PingMsg -> null;
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var child = Child.create();
        var parent = Parent.create(child);

        // Link them: if parent crashes, child dies too
        ProcessLink.link(parent, child);

        System.out.println("Parent and child linked");

        // Kill parent
        parent.send(new TerminateMsg());

        Thread.sleep(100);
        System.out.println("Parent crashed, child should also be down");
    }
}
```

## Pattern 7: Error Recovery with Result<T,E>

Functional error handling without exceptions:

```java
import io.github.seanchatmangpt.jotp.Result;

public class FunctionalErrorHandling {
    sealed interface Msg permits ComputeMsg {}
    record ComputeMsg(int value,
        ProcRef<Result<Integer, String>, Msg> replyTo) implements Msg {}

    static class SafeComputation {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case ComputeMsg c -> {
                        var result = Result.of(() -> {
                            if (c.value() < 0) {
                                throw new IllegalArgumentException(
                                    "Value must be positive");
                            }
                            return c.value() * 2;
                        })
                        .recover(e -> "Error: " + e.getMessage());

                        c.replyTo().send(result);
                        return null;
                    }
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var compute = SafeComputation.create();

        var result1 = compute.ask(
            replyTo -> new ComputeMsg(5, replyTo),
            java.time.Duration.ofSeconds(1)
        );

        result1.fold(
            success -> System.out.println("Computed: " + success),
            failure -> System.out.println("Failed: " + failure)
        );

        var result2 = compute.ask(
            replyTo -> new ComputeMsg(-5, replyTo),
            java.time.Duration.ofSeconds(1)
        );

        result2.fold(
            success -> System.out.println("Computed: " + success),
            failure -> System.out.println("Failed: " + failure)
        );
    }
}
```

## Pattern 8: Timeout-Based Failure Detection

Detect and recover from hanged processes:

```java
import java.time.Duration;

public class TimeoutFailureDetection {
    sealed interface Msg permits SlowTaskMsg {}
    record SlowTaskMsg(int delayMs) implements Msg {}

    static class Worker {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case SlowTaskMsg task -> {
                        Thread.sleep(task.delayMs());
                        return null;
                    }
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var worker = Worker.create();

        // Try to send a task with timeout
        try {
            var response = worker.ask(
                replyTo -> new SlowTaskMsg(5000),  // 5 second delay
                Duration.ofSeconds(1)  // 1 second timeout
            );
            System.out.println("Task completed");
        } catch (java.util.concurrent.TimeoutException e) {
            System.out.println("Worker timed out - detected as failed");
            // Could trigger restart here
        }
    }
}
```

## Best Practices for Fault Tolerance

1. **Always use supervisors** — never run critical processes alone
2. **Choose the right strategy** — ONE_FOR_ONE for independent services, ONE_FOR_ALL for coupled services
3. **Test failure scenarios** — ensure recovery works
4. **Use `Result<T,E>`** — for expected errors
5. **Monitor process health** — use `ProcessMonitor` for visibility
6. **Set appropriate timeouts** — detect hanged processes early
7. **Log failures** — understand why processes crash
8. **Avoid cascading failures** — isolate fault domains

## What's Next?

- **[How-To: Test Concurrent Code](test-concurrent-code.md)** — Testing fault tolerance
- **[How-To: Build Supervision Trees](build-supervision-trees.md)** — Advanced supervision
- **[Reference: API Overview](../reference/api.md)** — Complete supervision API

---

**Previous:** [Send & Receive Messages](send-receive-messages.md) | **Next:** [Test Concurrent Code](test-concurrent-code.md)
