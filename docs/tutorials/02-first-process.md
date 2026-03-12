# Tutorial 02: Your First Process

In this tutorial, you'll create a simple `Proc<S,M>` (lightweight process), send it messages, and query its state.

## What is a Proc<S,M>?

A `Proc<S,M>` is JOTP's core abstraction: a lightweight, isolated process that:

- Runs in a **virtual thread** (not a platform thread)
- Maintains **isolated state** of type `S`
- Receives **messages** of type `M`
- Executes a pure **state handler** function: `(state) → (msg) → new_state`
- Is supervised by a `Supervisor` for automatic restart on crash

It's JOTP's equivalent of Erlang's `spawn(Module, Function, Args)`.

## A Counter Process

Let's build a simple counter that increments on each message.

### Step 1: Create the Process

In a test or main method:

```java
import io.github.seanchatmangpt.jotp.*;

public class CounterExample {
    static record IncrementMsg() {}
    static record GetMsg(ProcRef<Integer, Object> replyTo) {}

    public static void main(String[] args) throws Exception {
        // Create a Proc<Integer, Object>:
        // - State: Integer (the counter value)
        // - Messages: Object (union of IncrementMsg, GetMsg, etc.)

        var counter = Proc.start(
            state -> msg -> {
                if (msg instanceof IncrementMsg) {
                    return state + 1;
                } else if (msg instanceof GetMsg getMsg) {
                    // You'd send the state back to replyTo
                    // (see "Request-Reply Pattern" below)
                    return state;
                }
                return state;
            },
            0  // Initial state
        );

        // Send messages
        counter.send(new IncrementMsg());
        counter.send(new IncrementMsg());
        counter.send(new IncrementMsg());

        // Query state (blocking)
        Integer finalCount = counter.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        System.out.println("Final count: " + finalCount);
        // Output: Final count: 3
    }
}
```

## Step 2: Request-Reply Pattern

For more complex interactions, use `ask()` to send a message and wait for a reply:

```java
static record Request<T>(ProcRef<T, Object> replyTo) {}

var process = Proc.start(
    state -> msg -> {
        if (msg instanceof Request<Integer> req) {
            // Send reply back to requester
            req.replyTo().send(state);
        }
        return state;
    },
    42
);

// Send request and wait for response
var response = process.ask(
    replyTo -> new Request<>(replyTo),
    java.time.Duration.ofSeconds(1)
);

System.out.println("Response: " + response);  // Output: 42
```

## Step 3: State Machine with Multiple Message Types

Build a more realistic state machine that handles different message types:

```java
public class StatusProcess {
    enum Status { IDLE, RUNNING, STOPPED }

    static sealed interface Message permits StartMsg, StopMsg, StatusMsg {}
    static record StartMsg() implements Message {}
    static record StopMsg() implements Message {}
    static record StatusMsg(ProcRef<Status, Message> replyTo) implements Message {}

    public static void main(String[] args) throws Exception {
        var process = Proc.start(
            state -> msg -> switch(msg) {
                case StartMsg -> Status.RUNNING;
                case StopMsg -> Status.STOPPED;
                case StatusMsg statusMsg -> {
                    statusMsg.replyTo().send(new StatusMsg(null));  // Echo back
                    yield state;
                }
            },
            Status.IDLE
        );

        // Send messages
        process.send(new StartMsg());
        process.send(new StopMsg());

        // Query status
        var status = process.ask(
            replyTo -> new StatusMsg(replyTo),
            java.time.Duration.ofSeconds(1)
        );

        System.out.println("Status: " + status);
    }
}
```

## Step 4: Process References and Sending to Other Processes

Processes can send messages to each other via `ProcRef<S,M>`:

```java
public class MessagePassing {
    static record MessageToForward(String text) {}

    public static void main(String[] args) throws Exception {
        // Receiver process
        var receiver = Proc.start(
            state -> msg -> {
                if (msg instanceof MessageToForward m) {
                    System.out.println("Receiver got: " + m.text());
                }
                return state;
            },
            null
        );

        // Sender process (sends to receiver)
        var sender = Proc.start(
            receiverRef -> msg -> {
                // receiverRef is the process to send to
                if (msg instanceof String text) {
                    receiverRef.send(new MessageToForward(text));
                }
                return receiverRef;
            },
            receiver  // Pass receiver's reference as initial state
        );

        // Trigger the sender
        sender.send("Hello, World!");

        // Give time for async message delivery
        Thread.sleep(100);
    }
}
```

## Step 5: Handling Errors with Result<T,E>

Wrap fallible operations in `Result<T,E>` for railway-oriented error handling:

```java
import io.github.seanchatmangpt.jotp.Result;

public class ErrorHandling {
    static record DivideMsg(int a, int b) {}

    public static void main(String[] args) throws Exception {
        var calculator = Proc.start(
            state -> msg -> {
                if (msg instanceof DivideMsg d) {
                    // Result.of() wraps throwing code
                    var result = Result.of(() -> d.a() / d.b());

                    // Handle success/failure functionally
                    result
                        .map(r -> System.out.println("Result: " + r))
                        .recover(e -> System.out.println("Error: " + e));
                }
                return state;
            },
            null
        );

        calculator.send(new DivideMsg(10, 2));  // Success: 5
        calculator.send(new DivideMsg(10, 0));  // Failure: ArithmeticException

        Thread.sleep(100);
    }
}
```

## Step 6: Testing Your Process

Write a JUnit 5 test for your process:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class ProcessTest {
    @Test
    void testProcessStateMachine() throws Exception {
        var proc = Proc.start(
            state -> msg -> state + 1,
            0
        );

        proc.send(1);
        proc.send(1);
        proc.send(1);

        var finalState = proc.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        assertThat(finalState).isEqualTo(3);
    }

    @Test
    void testMultipleMessageTypes() throws Exception {
        var proc = Proc.start(
            state -> msg -> switch(msg) {
                case "increment" -> state + 1;
                case "reset" -> 0;
                default -> state;
            },
            0
        );

        proc.send("increment");
        proc.send("increment");
        proc.send("reset");

        var finalState = proc.ask(msg -> msg,
            java.time.Duration.ofSeconds(1));

        assertThat(finalState).isZero();
    }
}
```

## Key Takeaways

- ✅ **Create processes** with `Proc.start(handler, initialState)`
- ✅ **Send messages** with `send(msg)` (async, non-blocking)
- ✅ **Query state** with `ask(supplier, duration)` (blocking, with timeout)
- ✅ **Pattern match** on message types using sealed interfaces and `switch`
- ✅ **Chain processes** by passing `ProcRef` as state
- ✅ **Handle errors** with `Result<T,E>`

## What's Next?

1. **[Tutorial 03: Virtual Threads](03-virtual-threads.md)** — Deep dive into how virtual threads power JOTP
2. **[Tutorial 04: Supervision Basics](04-supervision-basics.md)** — Learn to supervise and restart processes
3. **[How-To: Create Lightweight Processes](../how-to/create-lightweight-processes.md)** — Advanced process patterns
4. **[Reference: Proc API](../reference/api-proc.md)** — Full API documentation

## Code Example: Complete Counter Server

See `docs/examples/basic-process-example.md` for a complete, working counter server example.

---

**Next:** [Tutorial 03: Virtual Threads](03-virtual-threads.md)
