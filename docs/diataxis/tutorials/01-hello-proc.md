# Tutorial 1 — Hello, Proc

> **Learning goal:** spawn a virtual-thread process, send it messages, and read its state back — all without locks, callbacks, or explicit thread management.

---

## Prerequisites

- Java 26 installed (`java -version` shows 26)
- `mvnd test` passes in the project root
- You have read the [Tutorials overview](index.md)

---

## What you will build

A simple **counter process** that:

1. Starts with an integer state of `0`
2. Accepts `"inc"`, `"dec"`, and `"reset"` string messages
3. Responds to a `"get"` query by returning the current count
4. Can be stopped cleanly

By the end of this tutorial you will understand how `Proc` and `ProcRef` work, and you will have a running test that talks to a live process.

---

## Background: what is a Proc?

In Erlang/OTP, a *process* is an extremely cheap unit of concurrency — cheaper than a thread, with its own isolated state and a message mailbox. Java 26 virtual threads make the same model practical on the JVM.

`Proc<S, M>` wraps a virtual thread that loops over its mailbox. You provide:

- An **initial state** `S`
- A **pure handler function** `(S state, M message) -> S newState`

The library takes care of the thread, the mailbox queue, and safe state transitions. You interact with the process only through its **handle**: a `ProcRef<S, M>`.

---

## Step 1 — Create the handler function

Create the file `src/main/java/org/acme/tutorial/CounterProc.java`.

The handler is a `BiFunction<Integer, String, Integer>` — given the current count and a message string, it returns the next count. We use a `switch` expression so the compiler checks exhaustiveness for us.

```java
package org.acme.tutorial;

import org.acme.Proc;
import org.acme.ProcRef;

/**
 * A counter process driven by string messages.
 *
 * <p>Messages: "inc", "dec", "reset". Any other message leaves state unchanged.
 */
public final class CounterProc {

    private CounterProc() {}

    /** Spawn the counter and return an opaque handle. */
    public static ProcRef<Integer, String> start() {
        return Proc.of(0, CounterProc::handle);
    }

    // Pure state handler — no side effects, no shared mutable state.
    private static Integer handle(Integer state, String msg) {
        return switch (msg) {
            case "inc"   -> state + 1;
            case "dec"   -> state - 1;
            case "reset" -> 0;
            default      -> state;   // unknown message: leave state unchanged
        };
    }
}
```

Notice that `handle` is a plain static method. It receives the **current** state and returns the **next** state. It has no knowledge of threads, locks, or queues — all of that is managed by `Proc`.

---

## Step 2 — Write a test that sends messages

Create the file `src/test/java/org/acme/tutorial/CounterProcTest.java`.

We will use AssertJ (via `implements WithAssertions`) and JUnit 5. The test spawns the counter, sends a series of messages, and verifies the resulting state.

```java
package org.acme.tutorial;

import org.acme.ProcRef;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CounterProcTest implements WithAssertions {

    private ProcRef<Integer, String> counter;

    @BeforeEach
    void setUp() {
        counter = CounterProc.start();
    }

    @AfterEach
    void tearDown() {
        counter.stop();
    }

    @Test
    void incrementsState() throws Exception {
        counter.tell("inc");
        counter.tell("inc");
        counter.tell("inc");

        // ask() sends a message AND waits for the handler to process it,
        // returning the new state as a CompletableFuture<Integer>.
        int state = counter.ask("inc").get();

        assertThat(state).isEqualTo(4);
    }

    @Test
    void decrementsState() throws Exception {
        counter.tell("inc");
        counter.tell("inc");
        int state = counter.ask("dec").get();

        assertThat(state).isEqualTo(1);
    }

    @Test
    void resetsToZero() throws Exception {
        counter.tell("inc");
        counter.tell("inc");
        counter.tell("inc");
        int state = counter.ask("reset").get();

        assertThat(state).isEqualTo(0);
    }

    @Test
    void unknownMessageIsIgnored() throws Exception {
        counter.tell("inc");
        int state = counter.ask("banana").get();

        // "banana" is not a recognised message, so state stays at 1
        assertThat(state).isEqualTo(1);
    }
}
```

Run this test now:

```bash
mvnd test -Dtest=CounterProcTest
```

All four tests should pass. If they do not, double-check that your package declarations match (`org.acme.tutorial`).

---

## Step 3 — Explore tell vs ask

You have already used both `tell` and `ask`. Here is the key distinction:

| Method | Returns | Blocks? | Use when |
|--------|---------|---------|----------|
| `tell(msg)` | `void` | No — fire and forget | You do not need the resulting state |
| `ask(msg)` | `CompletableFuture<S>` | Until message is processed | You need to read state or synchronise |

Add one more test to see this in action. `tell` is non-blocking — we need `ask` to flush pending messages before reading state:

```java
@Test
void tellIsFireAndForget() throws Exception {
    // tell() returns immediately without waiting for processing
    counter.tell("inc");
    counter.tell("inc");

    // ask() sends "inc" and blocks until all three messages are processed in order.
    // The CompletableFuture resolves to the state AFTER the ask message is handled.
    int state = counter.ask("inc").get();

    // All three messages were processed before get() returned
    assertThat(state).isEqualTo(3);
}
```

The mailbox is a **FIFO queue**. `ask` does not skip the queue — it appends itself after the previous `tell` calls, so state after `get()` reflects all prior messages.

---

## Step 4 — Stop the process cleanly

A `ProcRef` keeps a virtual thread alive. Always stop processes when you are done with them so the JVM can reclaim the thread. In tests, use `@AfterEach` (as shown above). In production code, stop processes in your application shutdown hook.

```java
// Graceful shutdown: drains the mailbox then terminates the virtual thread
counter.stop();
```

After `stop()`, any subsequent `tell()` or `ask()` will be silently discarded or throw — so always stop after your final interaction.

---

## What just happened?

Let's reflect on what the library did for you:

1. `Proc.of(0, handler)` **spawned a virtual thread** and started an event loop inside it. The initial state `0` was stored privately.
2. `tell("inc")` **enqueued** the string `"inc"` in the mailbox and returned immediately. No thread switch occurred in your calling code.
3. The virtual thread **dequeued `"inc"`**, called `handle(0, "inc")`, got `1`, and stored it as the new state.
4. `ask("inc")` enqueued `"inc"` and also **registered a listener**. When the handler finished, the future was completed with the new state.
5. `counter.stop()` **interrupted the virtual thread** and let it terminate.

All of this used no `synchronized` blocks, no `ReentrantLock`, no `AtomicInteger`. The state is safe because only one virtual thread ever touches it.

---

## Next steps

- **Tutorial 2** — [Build a fault-tolerant supervised service](02-supervised-service.md): what happens when a process crashes? You will add a `Supervisor` that restarts it automatically.
- Explore `ProcessRegistry` to give your process a global name so any part of the application can find it with `ProcessRegistry.whereis("counter")`.
- Try changing the message type from `String` to a sealed interface hierarchy — the compiler will enforce exhaustive handling in your `switch`.
