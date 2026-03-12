# How-To: Test Concurrent Code

This guide covers best practices for testing concurrent JOTP applications.

## When to Use This Guide

Use this guide when you want to:
- Write reliable tests for concurrent code
- Test message-passing patterns
- Verify supervisor behavior
- Test race conditions safely
- Validate fault recovery

## Pattern 1: Basic Unit Tests with Await Assertions

Use assertions with appropriate timeouts for async operations:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.time.Duration;
import io.github.seanchatmangpt.jotp.*;

public class BasicProcessTest {
    sealed interface Msg permits IncrementMsg {}
    record IncrementMsg() implements Msg {}

    @Test
    void testProcessStateMutation() throws Exception {
        var proc = Proc.start(
            state -> msg -> state + 1,
            0
        );

        proc.send(new IncrementMsg());
        proc.send(new IncrementMsg());
        proc.send(new IncrementMsg());

        var finalState = proc.ask(msg -> msg,
            Duration.ofSeconds(1));

        assertThat(finalState).isEqualTo(3);
    }

    @Test
    void testMultipleMessageTypes() throws Exception {
        var proc = Proc.start(
            state -> msg -> switch(msg) {
                case "inc" -> state + 1;
                case "dec" -> state - 1;
                case "reset" -> 0;
                default -> state;
            },
            0
        );

        proc.send("inc");
        proc.send("inc");
        proc.send("dec");
        proc.send("reset");

        var finalState = proc.ask(msg -> msg,
            Duration.ofSeconds(1));

        assertThat(finalState).isZero();
    }
}
```

## Pattern 2: Testing Request-Reply Patterns

Verify blocking message exchange:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;

public class RequestReplyTest {
    sealed interface Msg permits GetMsg, SetMsg {}
    record GetMsg(ProcRef<String, Msg> replyTo) implements Msg {}
    record SetMsg(String value) implements Msg {}

    static class ValueHolder {
        static ProcRef<String, Msg> create(String initial) {
            return Proc.start(
                value -> msg -> switch(msg) {
                    case GetMsg get -> {
                        get.replyTo().send(value);
                        yield value;
                    }
                    case SetMsg set -> set.value();
                },
                initial
            );
        }
    }

    @Test
    void testRequestReply() throws Exception {
        var holder = ValueHolder.create("initial");

        String value1 = holder.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );
        assertThat(value1).isEqualTo("initial");

        holder.send(new SetMsg("updated"));

        String value2 = holder.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );
        assertThat(value2).isEqualTo("updated");
    }

    @Test
    void testRequestTimeoutHandling() throws Exception {
        var holder = ValueHolder.create("value");

        // Very short timeout
        assertThatThrownBy(() ->
            holder.ask(
                replyTo -> new GetMsg(replyTo),
                Duration.ofMillis(1)  // Unrealistic timeout
            )
        ).isInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
```

## Pattern 3: Testing Supervisor Behavior

Verify that supervisors restart failed processes:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SupervisorTest {
    sealed interface Msg permits ActionMsg {}
    record ActionMsg(String action) implements Msg {}

    static class CrashingWorker {
        static final AtomicInteger creationCount = new AtomicInteger(0);

        static ProcRef<Integer, Msg> create() {
            creationCount.incrementAndGet();
            return Proc.start(
                state -> msg -> switch(msg) {
                    case ActionMsg action ->
                        "crash".equals(action)
                            ? throw new RuntimeException("Crashed")
                            : state + 1;
                },
                0
            );
        }
    }

    @Test
    void testSupervisorRestarts() throws Exception {
        CrashingWorker.creationCount.set(0);

        var supervisor = Supervisor.oneForOne()
            .add("worker", CrashingWorker::create)
            .build();

        var worker = supervisor.whereis("worker");

        // Normal operation
        worker.send(new ActionMsg("increment"));
        assertThat(CrashingWorker.creationCount.get()).isOne();

        // Trigger crash
        assertThatThrownBy(() ->
            worker.send(new ActionMsg("crash"))
        ).isInstanceOf(RuntimeException.class);

        // Supervisor should restart
        Thread.sleep(100);
        assertThat(CrashingWorker.creationCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void testOneForOneVsOneForAll() throws Exception {
        var oneForOne = Supervisor.oneForOne()
            .add("worker1", () -> Proc.start(_ -> msg -> null, null))
            .add("worker2", () -> Proc.start(_ -> msg -> null, null))
            .build();

        var oneForAll = Supervisor.oneForAll()
            .add("worker1", () -> Proc.start(_ -> msg -> null, null))
            .add("worker2", () -> Proc.start(_ -> msg -> null, null))
            .build();

        assertThat(oneForOne).isNotNull();
        assertThat(oneForAll).isNotNull();
    }
}
```

## Pattern 4: Testing Message Ordering

Verify messages are processed in order:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageOrderingTest {
    sealed interface Msg permits RecordMsg {}
    record RecordMsg(int value) implements Msg {}

    static class OrderTracker {
        static ProcRef<List<Integer>, Msg> create() {
            return Proc.start(
                list -> msg -> switch(msg) {
                    case RecordMsg r -> {
                        list.add(r.value());
                        yield list;
                    }
                },
                Collections.synchronizedList(new ArrayList<>())
            );
        }
    }

    @Test
    void testMessageOrderingPreserved() throws Exception {
        var tracker = OrderTracker.create();

        for (int i = 1; i <= 10; i++) {
            tracker.send(new RecordMsg(i));
        }

        // Give async processing time
        Thread.sleep(100);

        var list = tracker.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        assertThat(list).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }
}
```

## Pattern 5: Testing Concurrent Access Patterns

Test multiple threads accessing the same process:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentAccessTest {
    sealed interface Msg permits IncrementMsg {}
    record IncrementMsg() implements Msg {}

    @Test
    void testConcurrentMessaging() throws Exception {
        var proc = Proc.start(
            state -> msg -> state + 1,
            0
        );

        // 100 threads sending messages concurrently
        var executor = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() ->
                proc.send(new IncrementMsg())
            ));
        }

        // Wait for all to complete
        for (var f : futures) {
            f.get();
        }

        executor.shutdown();

        // Final state should be 100
        var finalState = proc.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        assertThat(finalState).isEqualTo(100);
    }
}
```

## Pattern 6: Testing Error Recovery

Test how processes handle errors:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.Result;

public class ErrorRecoveryTest {
    sealed interface Msg permits ComputeMsg {}
    record ComputeMsg(int value,
        ProcRef<Result<Integer, String>, Msg> replyTo) implements Msg {}

    @Test
    void testErrorHandlingWithResult() throws Exception {
        var proc = Proc.start(
            _ -> msg -> switch(msg) {
                case ComputeMsg c -> {
                    var result = Result.of(() -> {
                        if (c.value() < 0)
                            throw new IllegalArgumentException("Negative");
                        return c.value() * 2;
                    })
                    .recover(e -> "Error: " + e.getMessage());

                    c.replyTo().send(result);
                    return null;
                }
            },
            null
        );

        // Test success case
        var success = proc.ask(
            replyTo -> new ComputeMsg(5, replyTo),
            java.time.Duration.ofSeconds(1)
        );

        assertThat(success.isSuccess()).isTrue();
        success.fold(
            val -> assertThat(val).isEqualTo(10),
            err -> fail("Should succeed")
        );

        // Test failure case
        var failure = proc.ask(
            replyTo -> new ComputeMsg(-5, replyTo),
            java.time.Duration.ofSeconds(1)
        );

        assertThat(failure.isFailure()).isTrue();
        failure.fold(
            val -> fail("Should fail"),
            err -> assertThat(err).startsWith("Error:")
        );
    }
}
```

## Pattern 7: Property-Based Testing

Use jqwik for property-based testing:

```java
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;

public class PropertyBasedTest {
    sealed interface Msg permits ValueMsg {}
    record ValueMsg(int value) implements Msg {}

    @Property
    boolean testAdditionCommutativity(
        @IntRange(min = -100, max = 100) int a,
        @IntRange(min = -100, max = 100) int b) throws Exception {

        var proc = Proc.start(
            state -> msg -> switch(msg) {
                case ValueMsg v -> state + v.value();
            },
            0
        );

        // Add a then b
        proc.send(new ValueMsg(a));
        proc.send(new ValueMsg(b));

        var result1 = proc.ask(msg -> msg, Duration.ofSeconds(1));

        // Result should be a + b
        return result1 == (a + b);
    }

    @Test
    void testPropertyBasedExample() {
        Assertions.assertThat(
            new PropertyBasedTest().testAdditionCommutativity(3, 4)
        ).isTrue();
    }
}
```

## Pattern 8: Integration Testing with Supervisors

Test complete system behavior:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class IntegrationTest {
    static class Database {
        static ProcRef<String, Object> create() {
            return Proc.start(state -> msg -> state, "connected");
        }
    }

    static class WebServer {
        static ProcRef<Integer, Object> create() {
            return Proc.start(state -> msg -> state, 8080);
        }
    }

    static class SystemSupervisor {
        static ProcRef<Void, Object> create() {
            return Supervisor.oneForOne()
                .add("db", Database::create)
                .add("web", WebServer::create)
                .build();
        }
    }

    @Test
    void testSystemStartup() throws Exception {
        var system = SystemSupervisor.create();

        var db = system.whereis("db");
        var web = system.whereis("web");

        assertThat(db).isNotNull();
        assertThat(web).isNotNull();
    }
}
```

## Best Practices for Testing

1. **Use `ask()` with appropriate timeouts** — prevent tests hanging
2. **Avoid `Thread.sleep()` when possible** — use Awaitility instead
3. **Test both success and failure paths** — verify error handling
4. **Test concurrent access** — ensure thread safety
5. **Use property-based testing** — find edge cases
6. **Test message ordering** — verify FIFO semantics
7. **Isolate tests** — each test should be independent
8. **Test supervisor strategies** — verify recovery behavior

## What's Next?

- **[How-To: Build Supervision Trees](build-supervision-trees.md)** — Advanced supervision
- **[How-To: Migrate from Erlang](migrate-from-erlang.md)** — Port Erlang code
- **[Reference: API Overview](../reference/api.md)** — Complete API documentation

---

**Previous:** [Handle Process Failures](handle-process-failures.md) | **Next:** [Build Supervision Trees](build-supervision-trees.md)
