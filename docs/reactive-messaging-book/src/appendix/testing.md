# Testing Guide

All 39 patterns have corresponding JUnit 5 tests. This guide explains the test structure, patterns, and recommended assertions.

## Test Architecture

```
src/test/java/org/acme/test/
├── ReactiveMessagingTest.java                  # org.acme.reactive package tests
└── patterns/
    ├── ReactiveMessagingFoundationPatternsTest.java  # patterns 01–10 (Proc-based)
    ├── ReactiveMessagingRoutingPatternsTest.java     # patterns 11–19 (Proc-based)
    ├── ReactiveMessagingEndpointPatternsTest.java    # patterns 20–33 (Proc-based)
    └── ReactiveMessagingSystemPatternsTest.java      # patterns 34–39 (reactive pkg)
```

## Running Tests

```bash
# All reactive messaging tests
./mvnw test -Dtest="ReactiveMessaging*"

# Single pattern group
./mvnw test -Dtest=ReactiveMessagingFoundationPatternsTest
./mvnw test -Dtest=ReactiveMessagingSystemPatternsTest

# Single test method
./mvnw test -Dtest="ReactiveMessagingSystemPatternsTest#wireTapReceivesCopy"
```

## Key Testing Patterns

### 1. Await Asynchronous Results

All channel operations are async. Use Awaitility:

```java
import static org.awaitility.Awaitility.await;

@Test
void asyncPattern() throws InterruptedException {
    var received = new CopyOnWriteArrayList<String>();
    var ch = new PointToPointChannel<String>(received::add);

    ch.send("hello");

    await().atMost(Duration.ofSeconds(2))
           .until(() -> received.size() == 1);

    assertThat(received).containsExactly("hello");
    ch.stop();
}
```

### 2. Proc-Based Pattern Testing

For `Proc<S,M>`-backed patterns, use `proc.ask()` for synchronous state snapshots:

```java
var proc = new Proc<>(0, (Integer s, String msg) -> s + 1);
proc.tell("a");
proc.tell("b");

// Ask for state after both messages are processed
int count = proc.ask("c").get(2, TimeUnit.SECONDS);
assertThat(count).isEqualTo(3);
proc.stop();
```

### 3. ProcSys Introspection in Tests

```java
// Statistics
var stats = ProcSys.statistics(proc);
assertThat(stats.messagesIn()).isEqualTo(5);
assertThat(stats.queueDepth()).isZero();  // all processed

// State snapshot
S state = ProcSys.getState(proc).get(2, TimeUnit.SECONDS);
assertThat(state).isEqualTo(expectedState);
```

### 4. Fault Isolation Testing

```java
@Test
void crashingSubscriberDoesNotKillChannel() throws InterruptedException {
    var ch = new PublishSubscribeChannel<String>();
    var goodCount = new AtomicInteger();

    ch.subscribe(msg -> { throw new RuntimeException("kaboom"); });
    ch.subscribe(msg -> goodCount.incrementAndGet());

    ch.sendSync("event-1");
    ch.sendSync("event-2");

    // Crashing subscriber is removed; good subscriber continues
    assertThat(goodCount.get()).isEqualTo(2);
    ch.stop();
}
```

### 5. DurableSubscriber Testing

```java
@Test
void buffersWhilePaused() throws Exception {
    var received = new CopyOnWriteArrayList<String>();

    try (var sub = new DurableSubscriber<String>(received::add)) {
        sub.pause();
        sub.send("while-paused-1");
        sub.send("while-paused-2");

        Thread.sleep(100);
        assertThat(received).isEmpty();  // buffered, not delivered

        sub.resume();
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 2);
        assertThat(received).containsExactly("while-paused-1", "while-paused-2");
    }
}
```

### 6. Resequencer Testing

```java
@Test
void reordersOutOfSequence() throws Exception {
    var ordered = new CopyOnWriteArrayList<Long>();
    var downstream = new PointToPointChannel<Long>(ordered::add);

    try (var reseq = new Resequencer<Long, Long>(n -> n, n -> n + 1, downstream)) {
        reseq.send(2L);
        reseq.send(0L);
        reseq.send(4L);
        reseq.send(1L);
        reseq.send(3L);

        await().atMost(Duration.ofSeconds(2)).until(() -> ordered.size() == 5);
        assertThat(ordered).containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(reseq.resequencedCount()).isEqualTo(5);
    }
    downstream.stop();
}
```

## Test Configuration

`src/test/resources/junit-platform.properties` enables full parallel execution:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```

Pattern tests use `@Execution(ExecutionMode.SAME_THREAD)` where shared mutable state requires sequential execution.

## Timeout Conventions

| Test Class | Timeout | Reason |
|---|---|---|
| `ReactiveMessagingFoundationPatternsTest` | 60s | Proc-based, fast |
| `ReactiveMessagingRoutingPatternsTest` | 120s | Scatter-gather parallelism |
| `ReactiveMessagingEndpointPatternsTest` | 120s | Competing consumers |
| `ReactiveMessagingSystemPatternsTest` | 60s | System patterns, fast |
| `ReactiveMessagingBreakingPointTest` | 300s | Stress tests |

## Coverage by Pattern

| Pattern Group | Test Class | Tests |
|---|---|---|
| Foundation 01–10 | `ReactiveMessagingFoundationPatternsTest` | 12 |
| Routing 11–19 | `ReactiveMessagingRoutingPatternsTest` | 10 |
| Endpoint 20–33 | `ReactiveMessagingEndpointPatternsTest` | 16 |
| System 34–39 | `ReactiveMessagingSystemPatternsTest` | 20 |
| Reactive package | `ReactiveMessagingTest` | 14 |
| **Total** | | **72** |
