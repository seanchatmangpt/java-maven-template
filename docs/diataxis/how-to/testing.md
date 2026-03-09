# How to Write OTP-Style Tests

## Problem

You need to test processes, supervision trees, timers, and async state
transitions in the Java 26 OTP library using the project's standard test stack.

## Solution

Use JUnit 5 with `implements WithAssertions` (AssertJ), Awaitility for async
assertions, jqwik for property-based tests, and the patterns below for
process-lifecycle cleanup.

## Prerequisites

- JUnit 5, AssertJ, Awaitility, jqwik on the test classpath (all provided by
  the project POM)
- Java 26 with `--enable-preview`
- Understand that all OTP tests should use `@Timeout` to prevent hung test suites

---

## Step 1 — Set up a test class

```java
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;
import org.acme.*;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

@Timeout(10)                        // kill hung tests after 10 s
class CounterProcTest implements WithAssertions {

    private ProcRef<Integer, String> counter;

    @BeforeEach
    void start() {
        counter = Proc.of(0, (count, msg) -> "inc".equals(msg) ? count + 1 : count);
    }

    @AfterEach
    void stop() {
        counter.stop();
    }
}
```

---

## Step 2 — Assert synchronous state with ask

```java
@Test
void incrementsCount() throws Exception {
    counter.tell("inc");
    counter.tell("inc");

    var state = counter.ask("get").get(1, SECONDS);
    assertThat(state).isEqualTo(2);
}
```

`ask` returns a `CompletableFuture<S>`. Always provide a timeout to avoid
blocking the test runner.

---

## Step 3 — Assert async state with Awaitility

```java
@Test
void eventuallyReachesTarget() {
    counter.tell("inc");
    counter.tell("inc");
    counter.tell("inc");

    await().atMost(2, SECONDS)
           .until(() -> counter.ask("get").get(500, java.util.concurrent.TimeUnit.MILLISECONDS) >= 3);
}
```

---

## Step 4 — Reset ProcessRegistry between tests

Any test that uses `ProcessRegistry` must reset it to prevent cross-test
leakage:

```java
@BeforeEach
@AfterEach
void resetRegistry() {
    ProcessRegistry.reset();
}

@Test
void registersAndFindsProcess() {
    ProcessRegistry.register("counter", counter);
    var found = ProcessRegistry.whereis("counter");
    assertThat(found).isPresent();
}
```

---

## Step 5 — Test supervision restarts

```java
@Test
void supervisorRestartsChild() throws Exception {
    var supervisor = Supervisor.builder()
        .strategy(Supervisor.Strategy.ONE_FOR_ONE)
        .maxRestarts(3)
        .withinSeconds(5)
        .build();

    var ref = supervisor.startChild("worker",
        () -> Proc.of(0, (count, msg) -> {
            if ("crash".equals(msg)) throw new RuntimeException("boom");
            return count + 1;
        }));

    ref.tell("crash");   // causes a restart

    // After restart the process should respond again
    await().atMost(2, SECONDS).until(() ->
        ref.ask("ping").get(500, java.util.concurrent.TimeUnit.MILLISECONDS) != null
    );

    supervisor.shutdown();
}
```

---

## Step 6 — Test ProcessLink crash propagation

```java
@Test
void linkedProcessDiesWithPartner() {
    var a = Proc.of("alive", (s, m) -> m);
    var b = Proc.of("alive", (s, m) -> m);

    ProcessLink.link(a, b);
    a.stop();

    await().atMost(2, SECONDS).until(() -> !b.isAlive());
    assertThat(b.isAlive()).isFalse();
}
```

---

## Step 7 — Test ProcessMonitor DOWN notification

```java
@Test
void monitorFiresOnStop() {
    var target = Proc.of("running", (s, m) -> m);
    var mon    = ProcessMonitor.monitor(target);

    assertThat(mon.isDown()).isFalse();

    target.stop();

    await().atMost(1, SECONDS).until(mon::isDown);
    assertThat(mon.downSignal().cause()).isNull();   // normal exit
}
```

---

## Step 8 — Test timers

```java
@Test
void timerDeliversMessage() throws Exception {
    var latch = new java.util.concurrent.CountDownLatch(1);

    var ref = Proc.of("waiting", (s, msg) -> {
        if ("tick".equals(msg)) latch.countDown();
        return msg;
    });

    ProcTimer.sendAfter(java.time.Duration.ofMillis(100), ref, "tick");

    assertThat(latch.await(2, SECONDS)).isTrue();
    ref.stop();
}
```

---

## Step 9 — Write property-based tests with jqwik

```java
import net.jqwik.api.*;
import org.acme.Result;

class ResultProperties implements WithAssertions {

    @Property
    void successIsNeverFailure(@ForAll int value) {
        var r = Result.success(value);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
    }

    @Property
    void mapPreservesSuccess(@ForAll int value) {
        var r = Result.<Integer, Exception>success(value).map(x -> x * 2);
        assertThat(r.orElseThrow()).isEqualTo(value * 2);
    }
}
```

---

## Step 10 — Test ExitSignal trapping

```java
@Test
void trapExitsDeliversSignalToMailbox() throws Exception {
    var child = Proc.of("ok", (s, m) -> {
        throw new IllegalStateException("crash");
    });

    var observer = Proc.of("waiting", (s, msg) ->
        msg instanceof ExitSignal ? "got-exit" : s);

    observer.trapExits(true);
    ProcessLink.link(observer, child);

    child.tell("trigger");

    await().atMost(2, SECONDS).until(() ->
        "got-exit".equals(observer.ask("state").get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
    );

    observer.stop();
}
```

---

## Verification

Run the test suite:

```bash
mvnd test                           # all unit tests
mvnd test -Dtest=CounterProcTest    # single class
mvnd verify -Dit.test=MyProcIT      # integration test
```

---

## Troubleshooting

**Test hangs indefinitely.**
Add `@Timeout(10)` at the class level. All OTP tests should have a timeout.
Hung tests usually mean a process mailbox is blocked or `ask()` was called
without a timeout.

**`await().until(...)` throws `ConditionTimeoutException`.**
The condition never became true within the deadline. Increase `atMost` duration
or check that the process actually receives and processes the triggering message.

**Tests pass individually but fail in parallel.**
The project runs tests with full parallelism. Shared global state (e.g.,
`ProcessRegistry`) must be reset in `@BeforeEach` / `@AfterEach`. Check for
static mutable fields in test classes.

**jqwik `@Property` fails intermittently.**
jqwik runs properties 1000 times by default. If the property depends on async
process state, add `await()` inside the property method or reduce concurrency
with `@Property(tries = 100)`.
