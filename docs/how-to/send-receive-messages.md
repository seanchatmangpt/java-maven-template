# How-To: Send & Receive Messages

This guide covers various patterns for inter-process communication in JOTP.

## When to Use This Guide

Use this guide when you want to:
- Send messages between processes
- Implement request-reply patterns
- Handle timeouts in message exchanges
- Build complex message flows
- Coordinate multiple processes

## Pattern 1: Fire-and-Forget (Asynchronous)

Send a message and don't wait for a response:

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

public class FireAndForget {
    sealed interface Msg permits LogMsg {}
    record LogMsg(String level, String text) implements Msg {}

    static class Logger {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case LogMsg log ->
                        System.out.println("[" + log.level() + "] "
                            + log.text());
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var logger = Logger.create();

        // Fire-and-forget: send message, don't wait
        logger.send(new LogMsg("INFO", "Application started"));
        logger.send(new LogMsg("DEBUG", "Processing request"));

        // Program continues immediately
        System.out.println("Main thread continues");

        Thread.sleep(100);  // Give logger time to process
    }
}
```

## Pattern 2: Request-Reply (Synchronous)

Send a message and wait for a response using `ask()`:

```java
public class RequestReply {
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

    public static void main(String[] args) throws Exception {
        var holder = ValueHolder.create("initial");

        // Request-reply: send message, wait for response
        String value = holder.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );

        System.out.println("Value: " + value);  // Value: initial

        holder.send(new SetMsg("updated"));
        value = holder.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );

        System.out.println("Value: " + value);  // Value: updated
    }
}
```

## Pattern 3: Timeout Handling

Use try-catch to handle timeouts gracefully:

```java
import java.util.concurrent.TimeoutException;

public class TimeoutHandling {
    sealed interface Msg permits SlowMsg {}
    record SlowMsg(int delayMs,
        ProcRef<String, Msg> replyTo) implements Msg {}

    static class SlowService {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case SlowMsg slow -> {
                        // Simulate slow operation
                        Thread.sleep(slow.delayMs());
                        slow.replyTo().send("Done!");
                        return null;
                    }
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var service = SlowService.create();

        // Short timeout
        try {
            var response = service.ask(
                replyTo -> new SlowMsg(5000, replyTo),
                Duration.ofMillis(1000)  // 1 second timeout
            );
            System.out.println(response);
        } catch (TimeoutException e) {
            System.out.println("Service timeout - operation too slow");
        }

        // Longer timeout
        try {
            var response = service.ask(
                replyTo -> new SlowMsg(500, replyTo),
                Duration.ofSeconds(2)  // 2 second timeout
            );
            System.out.println(response);  // Done!
        } catch (TimeoutException e) {
            System.out.println("Service timeout");
        }
    }
}
```

## Pattern 4: Broadcasting to Multiple Processes

Send the same message to multiple recipients:

```java
import java.util.List;

public class Broadcasting {
    sealed interface Msg permits NotificationMsg {}
    record NotificationMsg(String content) implements Msg {}

    static class NotificationService {
        final List<ProcRef<Void, Msg>> subscribers;

        NotificationService(List<ProcRef<Void, Msg>> subscribers) {
            this.subscribers = subscribers;
        }

        void broadcast(String message) {
            var msg = new NotificationMsg(message);
            for (var sub : subscribers) {
                sub.send(msg);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var subs = List.of(
            Proc.start(
                _ -> msg -> switch(msg) {
                    case NotificationMsg n ->
                        System.out.println("Sub1: " + n.content());
                },
                null
            ),
            Proc.start(
                _ -> msg -> switch(msg) {
                    case NotificationMsg n ->
                        System.out.println("Sub2: " + n.content());
                },
                null
            )
        );

        var service = new NotificationService(subs);
        service.broadcast("Hello subscribers!");

        Thread.sleep(100);
    }
}
```

## Pattern 5: Message Pipeline

Chain processes together where output of one becomes input to another:

```java
public class MessagePipeline {
    sealed interface Msg permits TransformMsg {}
    record TransformMsg(String text,
        ProcRef<String, Msg> replyTo) implements Msg {}

    static class UppercaseService {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case TransformMsg t -> {
                        t.replyTo().send(t.text().toUpperCase());
                        return null;
                    }
                },
                null
            );
        }
    }

    static class ReverseService {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case TransformMsg t -> {
                        String reversed = new StringBuilder(t.text())
                            .reverse().toString();
                        t.replyTo().send(reversed);
                        return null;
                    }
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var upper = UppercaseService.create();
        var reverse = ReverseService.create();

        // Step 1: Send to uppercase service
        String step1 = upper.ask(
            replyTo -> new TransformMsg("hello", replyTo),
            Duration.ofSeconds(1)
        );  // "HELLO"

        // Step 2: Send result to reverse service
        String step2 = reverse.ask(
            replyTo -> new TransformMsg(step1, replyTo),
            Duration.ofSeconds(1)
        );  // "OLLEH"

        System.out.println("Pipeline result: " + step2);
    }
}
```

## Pattern 6: Request-Reply with Error Handling

Use `Result<T,E>` for safe request-reply exchanges:

```java
import io.github.seanchatmangpt.jotp.Result;

public class SafeRequestReply {
    sealed interface Msg permits DivideMsg {}
    record DivideMsg(int a, int b,
        ProcRef<Result<Integer, String>, Msg> replyTo) implements Msg {}

    static class Calculator {
        static ProcRef<Void, Msg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case DivideMsg d -> {
                        var result = Result.of(() -> d.a() / d.b())
                            .recover(e -> "Error: " + e.getMessage());

                        d.replyTo().send(result);
                        return null;
                    }
                },
                null
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var calc = Calculator.create();

        // Successful divide
        var result1 = calc.ask(
            replyTo -> new DivideMsg(10, 2, replyTo),
            Duration.ofSeconds(1)
        );

        result1.fold(
            success -> System.out.println("Result: " + success),
            failure -> System.out.println("Failed: " + failure)
        );

        // Failed divide
        var result2 = calc.ask(
            replyTo -> new DivideMsg(10, 0, replyTo),
            Duration.ofSeconds(1)
        );

        result2.fold(
            success -> System.out.println("Result: " + success),
            failure -> System.out.println("Failed: " + failure)
        );
    }
}
```

## Pattern 7: Batch Message Processing

Process multiple messages in a single batch:

```java
import java.util.ArrayList;
import java.util.List;

public class BatchProcessing {
    sealed interface Msg permits BatchMsg, FlushMsg {}
    record BatchMsg(String item) implements Msg {}
    record FlushMsg(ProcRef<List<String>, Msg> replyTo) implements Msg {}

    static class BatchCollector {
        static ProcRef<List<String>, Msg> create(int batchSize) {
            return Proc.start(
                batch -> msg -> switch(msg) {
                    case BatchMsg b -> {
                        batch.add(b.item());
                        if (batch.size() >= batchSize) {
                            System.out.println("Processing batch of "
                                + batch.size());
                            return new ArrayList<>();
                        }
                        yield batch;
                    }
                    case FlushMsg f -> {
                        System.out.println("Flushing " + batch.size()
                            + " items");
                        f.replyTo().send(new ArrayList<>(batch));
                        yield new ArrayList<>();
                    }
                },
                new ArrayList<>()
            );
        }
    }

    public static void main(String[] args) throws Exception {
        var collector = BatchCollector.create(3);

        collector.send(new BatchMsg("item1"));
        collector.send(new BatchMsg("item2"));
        collector.send(new BatchMsg("item3"));  // Triggers batch

        Thread.sleep(100);

        var final_batch = collector.ask(
            replyTo -> new FlushMsg(replyTo),
            Duration.ofSeconds(1)
        );

        System.out.println("Final batch: " + final_batch);
    }
}
```

## Best Practices

1. **Use `ask()` for critical operations** — ensure responses are received
2. **Always set appropriate timeouts** — prevent indefinite waiting
3. **Handle `TimeoutException`** — don't let it crash your process
4. **Design stateless message handlers** — avoid side effects when possible
5. **Use sealed interfaces** — ensure exhaustive message handling
6. **Document message semantics** — explain what each message does
7. **Test message flow** — verify end-to-end behavior
8. **Monitor message latency** — watch for bottlenecks

## What's Next?

- **[How-To: Handle Process Failures](handle-process-failures.md)** — Error recovery
- **[How-To: Test Concurrent Code](test-concurrent-code.md)** — Testing patterns
- **[Reference: API Overview](../reference/api.md)** — Complete API documentation

---

**Previous:** [Create Lightweight Processes](create-lightweight-processes.md) | **Next:** [Handle Process Failures](handle-process-failures.md)
