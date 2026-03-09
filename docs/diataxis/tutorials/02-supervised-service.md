# Tutorial 2 — Build a Fault-Tolerant Supervised Service

> **Learning goal:** wrap a process in a supervision tree so that when it crashes, the supervisor automatically restarts it — without any of your application code needing to know a crash occurred.

---

## Prerequisites

- Completed [Tutorial 1 — Hello, Proc](01-hello-proc.md)
- You understand `Proc.of`, `ProcRef.tell`, and `ProcRef.ask`

---

## What you will build

A **supervised counter service** that:

1. Runs as a named child process under a `Supervisor`
2. Deliberately crashes when it receives a `"boom"` message
3. Is automatically restarted by the supervisor with a fresh state
4. Exposes a stable `ProcRef` (via `ProcessRegistry`) that callers hold across restarts

By the end of this tutorial you will understand the ONE_FOR_ONE restart strategy, restart windows, and how `ProcessRegistry` provides a stable address for a process whose underlying `ProcRef` may change after a restart.

---

## Background: the "let it crash" philosophy

Erlang/OTP popularised a counterintuitive approach to fault tolerance: **don't defensively catch every possible error inside the process**. Instead, let the process crash on unexpected input, and let the *supervisor* decide what to do next.

This keeps process code simple — it only handles the happy path — and moves recovery logic to a single, well-defined place. In Java 26, `Proc` implements this by propagating any uncaught exception out of the handler, which signals the process has crashed. The `Supervisor` detects this and applies its restart strategy.

---

## Step 1 — Define a sealed message type

String messages are fine for a quick demo, but a sealed interface gives you compile-time exhaustiveness checking. Create `src/main/java/org/acme/tutorial/CounterMsg.java`:

```java
package org.acme.tutorial;

/**
 * All messages accepted by the supervised counter.
 *
 * <p>Using a sealed interface means the compiler catches unhandled cases in switch expressions.
 */
public sealed interface CounterMsg
        permits CounterMsg.Increment, CounterMsg.Decrement, CounterMsg.Reset, CounterMsg.Boom {

    record Increment() implements CounterMsg {}

    record Decrement() implements CounterMsg {}

    record Reset() implements CounterMsg {}

    /** Simulates an unexpected error — causes the process to crash. */
    record Boom() implements CounterMsg {}
}
```

Records inside a sealed interface are a Java 26 idiom you will use throughout the OTP library. Each variant carries its own (potentially different) payload.

---

## Step 2 — Write the supervised counter

Create `src/main/java/org/acme/tutorial/SupervisedCounter.java`. The handler now uses a pattern-matching `switch` over the sealed type:

```java
package org.acme.tutorial;

import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.ProcessRegistry;
import org.acme.Supervisor;
import org.acme.tutorial.CounterMsg.Boom;
import org.acme.tutorial.CounterMsg.Decrement;
import org.acme.tutorial.CounterMsg.Increment;
import org.acme.tutorial.CounterMsg.Reset;

/**
 * A counter process managed by a ONE_FOR_ONE supervisor.
 *
 * <p>The process is registered in the global ProcessRegistry under the name "counter".
 * Callers obtain a stable reference via {@link #ref()}.
 */
public final class SupervisedCounter {

    public static final String NAME = "counter";

    private final Supervisor supervisor;

    public SupervisedCounter() {
        supervisor = Supervisor.builder()
                .strategy(Supervisor.Strategy.ONE_FOR_ONE)
                .maxRestarts(5)
                .withinSeconds(10)
                .build();

        // startChild registers a factory. If the process crashes, the supervisor
        // calls the factory again to produce a fresh replacement.
        supervisor.startChild(NAME, SupervisedCounter::spawnAndRegister);
    }

    /** Returns a stable ProcRef by looking up the current registration. */
    @SuppressWarnings("unchecked")
    public ProcRef<Integer, CounterMsg> ref() {
        return (ProcRef<Integer, CounterMsg>) ProcessRegistry.whereis(NAME)
                .orElseThrow(() -> new IllegalStateException("Counter not running"));
    }

    /** Stop the entire supervision tree. */
    public void stop() {
        supervisor.stop();
    }

    // Factory called by the supervisor on every (re)start.
    private static ProcRef<Integer, CounterMsg> spawnAndRegister() {
        var ref = Proc.<Integer, CounterMsg>of(0, SupervisedCounter::handle);
        // Register under the well-known name so ref() always finds the latest instance.
        ProcessRegistry.register(NAME, ref);
        return ref;
    }

    private static Integer handle(Integer state, CounterMsg msg) {
        return switch (msg) {
            case Increment() -> state + 1;
            case Decrement() -> state - 1;
            case Reset()     -> 0;
            case Boom()      ->
                // Throwing from the handler crashes the process.
                // The supervisor will restart it with a fresh state of 0.
                throw new RuntimeException("Simulated crash!");
        };
    }
}
```

Key points:

- The **supervisor factory** is a `Supplier<ProcRef<...>>`. The supervisor calls it once to start the child, and again each time the child needs restarting.
- We call `ProcessRegistry.register(NAME, ref)` **inside the factory**, so after every restart the registry points to the newest `ProcRef`.
- `ref()` looks up the registry every time — callers always get the current live reference.

---

## Step 3 — Test normal operation

Create `src/test/java/org/acme/tutorial/SupervisedCounterTest.java`:

```java
package org.acme.tutorial;

import org.acme.ProcRef;
import org.acme.tutorial.CounterMsg.Decrement;
import org.acme.tutorial.CounterMsg.Increment;
import org.acme.tutorial.CounterMsg.Reset;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupervisedCounterTest implements WithAssertions {

    private SupervisedCounter service;

    @BeforeEach
    void setUp() {
        service = new SupervisedCounter();
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void normalOperationWorks() throws Exception {
        var ref = service.ref();

        ref.tell(new Increment());
        ref.tell(new Increment());
        ref.tell(new Increment());
        int state = ref.ask(new Decrement()).get();

        assertThat(state).isEqualTo(2);
    }

    @Test
    void resetBringsBackToZero() throws Exception {
        var ref = service.ref();
        ref.tell(new Increment());
        ref.tell(new Increment());
        int state = ref.ask(new Reset()).get();

        assertThat(state).isEqualTo(0);
    }
}
```

Run these now to make sure baseline behaviour is correct:

```bash
mvnd test -Dtest=SupervisedCounterTest
```

---

## Step 4 — Test crash and restart

Now add the test that verifies the supervisor actually restarts the process after a crash:

```java
    @Test
    void crashTriggersRestartWithFreshState() throws Exception {
        var ref = service.ref();

        // Build up some state
        ref.tell(new Increment());
        ref.tell(new Increment());
        ref.tell(new Increment());
        ref.ask(new Increment()).get();   // state is now 4; flush the mailbox

        // Boom! The process will crash and the supervisor will restart it.
        // We deliberately do NOT call ask() here — the crash means no response.
        ref.tell(new CounterMsg.Boom());

        // Give the supervisor a moment to detect the crash and start a replacement.
        Thread.sleep(200);

        // After restart, state is fresh (0). We obtain the new ref via the registry.
        var newRef = service.ref();
        int stateAfterRestart = newRef.ask(new Increment()).get();

        // New process started at 0 and we sent one Increment, so state is 1.
        assertThat(stateAfterRestart).isEqualTo(1);
    }
```

Run all tests:

```bash
mvnd test -Dtest=SupervisedCounterTest
```

---

## Step 5 — Understand restart limits

The supervisor is configured with `.maxRestarts(5).withinSeconds(10)`. This is a **sliding window**: if the child crashes more than 5 times within any 10-second window, the supervisor itself gives up and stops.

This prevents a broken process from burning CPU in a tight restart loop. Add a test to confirm the supervisor stops after too many crashes:

```java
    @Test
    void supervisorGivesUpAfterTooManyRestarts() throws Exception {
        // Crash the process 6 times — one more than the allowed 5 within 10 seconds
        for (int i = 0; i < 6; i++) {
            try {
                service.ref().tell(new CounterMsg.Boom());
            } catch (IllegalStateException ignored) {
                // ref() may throw once the supervisor stops — that is expected
                break;
            }
            Thread.sleep(50);
        }

        Thread.sleep(500); // allow the supervisor to process final crash

        // The supervisor has now stopped; ref() should throw
        assertThatThrownBy(() -> service.ref())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }
```

---

## What just happened?

Here is the full lifecycle you exercised:

1. `SupervisedCounter()` created a `Supervisor` with ONE_FOR_ONE strategy and called `startChild`, which invoked the factory once to spawn the first process and register it.
2. `ref.tell(new Boom())` caused the handler to throw `RuntimeException`. The virtual thread terminated with an uncaught exception, which the `Proc` framework detected as a crash.
3. The `Supervisor` received a crash notification and, because the restart count was within the window, called the **same factory again** to produce a fresh `ProcRef`.
4. The factory called `ProcessRegistry.register(NAME, ref)` again, replacing the crashed reference with the new live one.
5. `service.ref()` did a fresh `whereis` lookup and returned the new reference — your calling code never needed to know a crash had occurred.

The ONE_FOR_ONE strategy restarts **only the crashed child**. The library also provides ONE_FOR_ALL (restart all children when any one crashes) and REST_FOR_ONE (restart the crashed child and all children started after it).

---

## Next steps

- **Tutorial 3** — [Implement a traffic-light StateMachine](03-state-machine.md): add time-driven state transitions to your processes.
- Explore `ProcessLink.link(ref1, ref2)` to make two processes live and die together.
- Try `ProcessMonitor.monitor(ref)` to receive a `DownSignal` when a process exits, without being crashed yourself.
- Change the strategy to `ONE_FOR_ALL` and observe how crashing the counter affects other children in the same supervisor.
