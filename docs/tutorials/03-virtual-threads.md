# Tutorial 03: Virtual Threads

Virtual threads are the foundation of JOTP's lightweight concurrency. In this tutorial, you'll understand how Java 26 virtual threads enable millions of concurrent processes without the overhead of platform threads.

## What Are Virtual Threads?

**Virtual threads** (introduced in Java 21, JEP 444) are:

- **Lightweight**: Millions can run on a single machine (vs. thousands of platform threads)
- **Non-preemptive**: JVM scheduler runs them cooperatively
- **Stackful**: Can block at any point in the call stack (no callback chains)
- **Structured**: Run within scopes with clear lifecycle guarantees
- **Cheap to spawn**: ~1KB memory vs. ~1MB for platform threads

**How they work:**

```
Platform Threads (1:1 kernel threads)
└── Virtual Threads (M:N scheduling)
    ├── Carrier Thread 1
    ├── Carrier Thread 2
    └── Carrier Thread N
```

When a virtual thread blocks (e.g., waiting for I/O or a message), the JVM unmounts it from its carrier thread, allowing other virtual threads to run. This is called **structured concurrency**.

## Virtual Threads vs. Platform Threads

| Aspect | Platform Thread | Virtual Thread |
|--------|-----------------|----------------|
| Memory per thread | ~1-2 MB | ~1 KB |
| Max threads per JVM | ~10,000 | ~1,000,000+ |
| Kernel context switch | Yes (expensive) | No (JVM scheduler) |
| Blocking behavior | Blocks carrier | Auto-unmounts |
| Use case | I/O-heavy services | Millions of concurrent units |

## JOTP Processes Use Virtual Threads

Every `Proc<S,M>` runs in its own virtual thread:

```java
var proc = Proc.start(state -> msg -> state + 1, 0);
// Internally: Proc uses ThreadFactory.ofVirtual() to spawn the process
```

This means you can create thousands of processes without performance degradation.

## Example 1: Creating a Virtual Thread Directly

While JOTP usually manages virtual threads, you can create them explicitly:

```java
import java.util.concurrent.*;

public class VirtualThreadExample {
    public static void main(String[] args) throws Exception {
        // Create a virtual thread directly
        Thread vt = Thread.ofVirtual()
            .name("worker-1")
            .start(() -> {
                System.out.println("Running in virtual thread: " +
                    Thread.currentThread().getName());
                // Can block here without blocking carrier thread
                Thread.sleep(1000);
            });

        vt.join();  // Wait for it to finish
    }
}
```

## Example 2: Structured Concurrency with StructuredTaskScope

Java 26 provides `StructuredTaskScope` for managing multiple virtual threads with guaranteed cleanup:

```java
import java.util.concurrent.*;

public class StructuredConcurrencyExample {
    public static void main(String[] args) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // Spawn multiple tasks
            var future1 = scope.fork(() -> longRunningTask1());
            var future2 = scope.fork(() -> longRunningTask2());
            var future3 = scope.fork(() -> longRunningTask3());

            // Wait for all to complete (or first to fail)
            scope.join();  // Blocks until all done or one fails

            // Get results
            var result1 = future1.resultNow();
            var result2 = future2.resultNow();
            var result3 = future3.resultNow();

            System.out.println("All tasks completed: " +
                result1 + ", " + result2 + ", " + result3);
        } catch (StructureViolationException e) {
            System.out.println("Task failed: " + e);
        }
    }

    static String longRunningTask1() { return "Task 1 done"; }
    static String longRunningTask2() { return "Task 2 done"; }
    static String longRunningTask3() { return "Task 3 done"; }
}
```

## Example 3: Virtual Threads in JOTP Processes

JOTP processes are built on virtual threads. Here's how they interact:

```java
public class JOTPVirtualThreadExample {
    public static void main(String[] args) throws Exception {
        // Create 1000 processes, each in a virtual thread
        var processes = new java.util.ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            final int id = i;
            var proc = Proc.start(
                state -> msg -> {
                    System.out.println("Process " + id + " got: " + msg);
                    return state + 1;
                },
                0
            );
            processes.add(proc);
        }

        // Send messages to all processes concurrently
        for (int i = 0; i < processes.size(); i++) {
            processes.get(i).send("message " + i);
        }

        System.out.println("Created 1000 processes with negligible overhead");
        // This would fail with platform threads!
    }
}
```

## Example 4: Blocking in Virtual Threads

Virtual threads can block without deadlocking because the JVM scheduler unmounts them:

```java
public class BlockingVirtualThreadExample {
    public static void main(String[] args) throws Exception {
        var proc = Proc.start(
            state -> msg -> {
                if (msg instanceof String text) {
                    System.out.println("Processing: " + text);
                    try {
                        // Blocking I/O (e.g., database query)
                        // Virtual thread unmounts here, carrier continues
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("Done: " + text);
                }
                return state;
            },
            null
        );

        // Send multiple messages
        for (int i = 1; i <= 5; i++) {
            proc.send("Message " + i);
        }

        Thread.sleep(6000);  // Wait for all to complete
    }
}
```

Output:
```
Processing: Message 1
Processing: Message 2
Processing: Message 3
Processing: Message 4
Processing: Message 5
Done: Message 1
Done: Message 2
Done: Message 3
Done: Message 4
Done: Message 5
```

Even though each message takes 1 second to process, all 5 messages complete in ~1 second because they run concurrently in virtual threads!

## Example 5: Scoped Values (Context Propagation)

Java 26 provides `ScopedValue` for passing context to virtual threads without mutable ThreadLocal:

```java
import java.lang.ScopedValue;

public class ScopedValueExample {
    static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        var proc = Proc.start(
            state -> msg -> {
                if (msg instanceof String) {
                    // Access scoped value (implicitly inherited)
                    var requestId = REQUEST_ID.get();
                    System.out.println("Processing with ID: " + requestId);
                }
                return state;
            },
            null
        );

        // Bind scoped value, send message
        ScopedValue.callWhere(
            REQUEST_ID, "REQ-12345",
            () -> {
                proc.send("Process this");
                return null;
            }
        );
    }
}
```

## Key Advantages of Virtual Threads in JOTP

1. **Scales to millions** — Create 1M processes without resource exhaustion
2. **No callback hell** — Write synchronous-looking code (no `then()`, `await()`)
3. **Natural suspension** — Blocking is natural; scheduler handles unmounting
4. **Fail-fast debugging** — Stack traces show full call chains
5. **Better resource utilization** — Carrier threads do actual work only when needed

## Performance Implications

Virtual threads shine in scenarios with:

- **High concurrency** (10,000+ concurrent tasks)
- **I/O-bound workloads** (databases, HTTP, messaging)
- **Long-lived connections** (WebSockets, streaming)

They're less beneficial for:

- **CPU-bound workloads** (tight loops, math, data processing)
- **Low concurrency** (<100 concurrent tasks)

JOTP's sweet spot is **message-driven systems** with **moderate I/O and supervision**.

## Testing Virtual Threads

JUnit 5 provides annotations for virtual thread testing:

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

public class VirtualThreadTest {
    @Test
    void testVirtualThread() throws Exception {
        var proc = Proc.start(state -> msg -> state + 1, 0);
        proc.send(1);
        var finalState = proc.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));
        assert finalState == 1;
    }

    // Run this test on a virtual thread executor (experimental)
    @Test
    @Disabled("Requires junit-jupiter 5.11+")
    void testOnVirtualThread() {
        // Test code here runs in a virtual thread
    }
}
```

## Key Takeaways

- ✅ Virtual threads are **cheap** (1KB vs. 1MB for platform threads)
- ✅ **Millions** can coexist without OS overhead
- ✅ **JOTP processes run in virtual threads** automatically
- ✅ **Blocking is cheap** — JVM unmounts, not the OS
- ✅ **Structured concurrency** guarantees clean lifecycle with `StructuredTaskScope`
- ✅ **Scoped values** replace ThreadLocal for cleaner context passing

## What's Next?

1. **[Tutorial 04: Supervision Basics](04-supervision-basics.md)** — Learn how JOTP supervises and restarts processes
2. **[How-To: Handle Process Failures](../how-to/handle-process-failures.md)** — Implement fault tolerance
3. **[Explanation: Concurrency Model](../explanations/concurrency-model.md)** — Deep dive into JOTP's threading model
4. **[Reference: Configuration](../reference/configuration.md)** — Virtual thread configuration options

---

**Next:** [Tutorial 04: Supervision Basics](04-supervision-basics.md)
