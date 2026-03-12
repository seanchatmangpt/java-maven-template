# Examples: Basic Process Example

Complete, runnable example of a simple counter process.

## The Counter Process

This example demonstrates the fundamentals:
- Creating a `Proc<S,M>`
- Defining message types
- Sending messages
- Querying state

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

public class CounterExample {
    // Define message types as sealed interface
    sealed interface CounterMsg permits IncrementMsg, DecrementMsg, GetMsg {}

    record IncrementMsg() implements CounterMsg {}
    record DecrementMsg() implements CounterMsg {}
    record GetMsg(ProcRef<Integer, CounterMsg> replyTo) implements CounterMsg {}

    public static void main(String[] args) throws Exception {
        // Create a counter process
        // State: Integer (counter value)
        // Messages: CounterMsg union
        var counter = Proc.start(
            state -> msg -> switch(msg) {
                case IncrementMsg -> state + 1;
                case DecrementMsg -> state - 1;
                case GetMsg getMsg -> {
                    // Send current value back to requester
                    getMsg.replyTo().send(state);
                    yield state;
                }
            },
            0  // Initial state
        );

        // Send messages
        System.out.println("Sending 3 increment messages...");
        counter.send(new IncrementMsg());
        counter.send(new IncrementMsg());
        counter.send(new IncrementMsg());

        // Query state using request-reply
        System.out.println("Querying state...");
        Integer count = counter.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Counter value: " + count);  // 3

        // More operations
        counter.send(new DecrementMsg());
        Integer newCount = counter.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Counter value: " + newCount);  // 2
    }
}
```

## How It Works

1. **Define Messages** — Sealed interface + records for type-safe routing
2. **Create Handler** — Pure function: `(state) → (msg) → new_state`
3. **Start Process** — `Proc.start(handler, initial_state)`
4. **Send Messages** — Async with `send()` or sync with `ask()`

## With Tests

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;

public class CounterTest {
    sealed interface CounterMsg permits IncrementMsg, GetMsg {}
    record IncrementMsg() implements CounterMsg {}
    record GetMsg(ProcRef<Integer, CounterMsg> replyTo) implements CounterMsg {}

    @Test
    void testCounterIncrement() throws Exception {
        var counter = Proc.start(
            state -> msg -> switch(msg) {
                case IncrementMsg -> state + 1;
                case GetMsg getMsg -> {
                    getMsg.replyTo().send(state);
                    yield state;
                }
            },
            0
        );

        counter.send(new IncrementMsg());
        counter.send(new IncrementMsg());
        counter.send(new IncrementMsg());

        var value = counter.ask(
            replyTo -> new GetMsg(replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(value).isEqualTo(3);
    }
}
```

## What's Next?

- **[Supervision Tree Example](supervision-tree-example.md)** — Multiple processes under supervision
- **[Message Passing Example](message-passing-example.md)** — Inter-process communication
- **[How-To: Create Lightweight Processes](../how-to/create-lightweight-processes.md)** — Advanced patterns
- **[Tutorial: Your First Process](../tutorials/02-first-process.md)** — Detailed walkthrough

---

**See Also:** [Reference: Proc API](../reference/api-proc.md) | [Reference: Glossary](../reference/glossary.md)
