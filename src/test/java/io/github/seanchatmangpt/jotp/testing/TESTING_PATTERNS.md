# Testing Vernon Patterns with JUnit 6 & JOTP

Comprehensive guide to testing the 34 Vernon reactive messaging patterns using custom JUnit 6 annotations, extensions, and utilities.

## Quick Start (5 minutes)

Write your first pattern test in 10 lines:

```java
@PatternTest(pattern = "ContentBasedRouter")
class RouterTest extends PatternTestBase<ContentBasedRouter> {
  @Test
  void testRouting() {
    var router = fixture.createProcess();
    var msg = messageBuilder.withField("type", "ORDER").build();
    send(router, msg);

    assertMessage(msg).hasType("ORDER").assertSucceeds();
  }
}
```

## Architecture Overview

### 4 Layers

1. **Annotations** (9 custom) — Test metadata & configuration
2. **Extensions** (7 JUnit 6) — Auto-wiring, lifecycle management, metrics
3. **Utilities** (6 helpers) — Assertions, builders, introspection, tracking
4. **Base Classes** (4 abstract) — Reusable test logic for pattern families

### Package Structure

```
src/test/java/io/github/seanchatmangpt/jotp/testing/
├── annotations/        → @PatternTest, @AsyncPatternTest, @CorrelationTest, etc.
├── extensions/         → ProcessFixtureExtension, MessageCapturingExtension, etc.
├── util/              → MessageAssertions, PatternTestFixture, JotpTestHelper, etc.
├── base/              → PatternTestBase, AsyncPatternTestBase, JotpTestBase, etc.
└── examples/          → Example test classes demonstrating patterns
```

## Annotations Reference

### @PatternTest
Mark test for a specific Vernon pattern.

```java
@PatternTest(pattern = "ContentBasedRouter", async = true)
class RouterTest {
  @Test void testRouting() { ... }
}
```

**Attributes:**
- `pattern` — Pattern name (required)
- `category` — Category (auto-detect if empty)
- `async` — Is pattern asynchronous? (default: true)
- `description` — What this test validates

### @AsyncPatternTest
Async pattern test with timeout + virtual thread support.

```java
@AsyncPatternTest(timeoutValue = 5, timeoutUnit = TimeUnit.SECONDS)
class AsyncRouterTest extends AsyncPatternTestBase<ContentBasedRouter> {
  @Test void testAsync() {
    var result = ask(router, msg, timeout());
    assertEventually(() -> result.isSuccess());
  }
}
```

**Attributes:**
- `timeoutValue` — Timeout duration (default: 5)
- `timeoutUnit` — Unit (default: SECONDS)
- `pattern` — Pattern name
- `virtualThreadOnly` — Run only on virtual threads?
- `trackCorrelationIds` — Auto-track correlation IDs?

### @CorrelationTest
Track message causality across process boundaries.

```java
@CorrelationTest(autoGenerate = true, reportGraphs = true)
class ProcessManagerTest {
  @Inject CorrelationIdTracker tracker;

  @Test void testCausality() {
    tracker.recordStep(id, "step1");
    tracker.recordStep(id, "step2");
    tracker.assertCausalityChain(id, "step1", "step2");
  }
}
```

**Attributes:**
- `autoGenerate` — Auto-generate correlation IDs
- `reportGraphs` — Print causality graphs on failure
- `maxChainDepth` — Maximum chain depth (0 = unlimited)
- `validateDistributedTracing` — Validate tracing headers?

### @ProcessFixture
Auto-generate process test fixtures.

```java
@ProcessFixture(
  value = ContentBasedRouter.class,
  instances = 2,
  supervisionStrategy = "ONE_FOR_ONE",
  registerInRegistry = true,
  registryName = "test-routers"
)
class RouterFixtureTest {
  @Inject ProcessFixture<ContentBasedRouter> fixture;

  @Test void test() {
    var router = fixture.createProcess();
    // ... test ...
  }
}
```

**Attributes:**
- `value` — Process class (required)
- `instances` — Number of instances to create
- `supervisionStrategy` — ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE
- `autoCleanup` — Auto-terminate processes?
- `captureMessages` — Capture all messages?
- `registerInRegistry` — Register in ProcessRegistry?
- `registryName` — Registry name

### @MessageCapture
Non-invasive message interception.

```java
@MessageCapture(
  captureAll = true,
  maxMessages = 1000,
  includePayload = true
)
class RouterTest {
  @Inject MessageCapture.MessageCapturingRecorder capture;

  @Test void test() {
    send(router, msg1);
    send(router, msg2);

    var all = capture.allMessages();
    assertThat(all).hasSize(2);
  }
}
```

### @JotpTest
Test JOTP core primitives.

```java
@JotpTest(
  primitive = "Supervisor",
  testCrashRecovery = true,
  testStateIntrospection = true,
  testProcessLinks = true
)
class SupervisorTest extends JotpTestBase {
  @Test void testRestart() {
    var supervisor = createSupervisor("ONE_FOR_ONE");
    var child = spawnChild(supervisor, "child1");
    assertProcessAlive(child);
  }
}
```

### @PerformanceBaseline
Assert performance thresholds.

```java
@PerformanceBaseline(
  messagesPerSecond = 100_000,
  p99LatencyMillis = 50,
  p95LatencyMillis = 30,
  maxMemoryMB = 256,
  enableJFR = true,
  reportMetrics = true
)
class RouterPerformanceTest {
  @Test void testHighThroughput() {
    var perf = new PerformanceTestHelper();
    perf.start();
    for (var msg : messages) {
      send(router, msg);
      perf.recordLatency(elapsedNanos);
    }
    perf.stop();
    perf.assertMinThroughput(100_000);
    perf.assertP99Latency(50);
  }
}
```

### @VirtualThreaded
Force test on virtual/platform threads.

```java
@VirtualThreaded(
  mode = ThreadMode.VIRTUAL_ONLY,
  noPinning = true,
  validateContextPropagation = true
)
class VirtualThreadRouterTest {
  @Test void testOnVirtual() {
    assertTrue(Thread.currentThread().isVirtual());
    // ... test ...
  }
}
```

### @IntegrationPattern
Multi-pattern composition test.

```java
@IntegrationPattern(
  patterns = {"ContentBasedRouter", "Aggregator", "MessageFilter"},
  description = "Route, aggregate, and filter messages",
  validateDataIntegrity = true,
  validateCausality = true
)
class RouterAggregatorFilterTest extends IntegrationPatternTestBase {
  @Test void testChain() {
    var router = createPattern("router", ContentBasedRouter.class);
    var agg = createPattern("aggregator", Aggregator.class);
    var filter = createPattern("filter", MessageFilter.class);

    connect(router, agg);
    connect(agg, filter);

    // ... send messages ...
    validateFullChainTraversal(id, "router", "aggregator", "filter");
  }
}
```

## Base Classes Reference

### PatternTestBase<P>
Generic parent for all pattern tests.

**Methods:**
- `fixture: PatternTestFixture<P>` — Create/cleanup processes
- `messageBuilder: MessageBuilder` — Fluent message DSL
- `correlationTracker: CorrelationIdTracker` — Track causality
- `createMessage(field, value)` → Object
- `assertMessage(msg)` → MessageAssertions
- `trackCorrelationId(id, step)` → void
- `validatePatternInvariants()` → void
- `getTimeout(unit)` → long

### AsyncPatternTestBase<P>
Extends PatternTestBase with async support.

**Methods:**
- `timeout()` → long (milliseconds)
- `timeout(unit)` → long
- `assertEventually(condition)` → void
- `assertEventually(condition, millis)` → void
- `startPerformanceMeasurement()` → void
- `stopPerformanceMeasurement()` → void
- `assertMinThroughput(msg/sec)` → void
- `assertP99Latency(ms)` → void
- `assertP95Latency(ms)` → void
- `assertP50Latency(ms)` → void
- `checkTimeout()` → void
- `isVirtualThread()` → boolean

### JotpTestBase
Parent for JOTP core tests.

**Methods:**
- `createSupervisor(strategy)` → Object
- `spawnChild(supervisor, name)` → Object
- `assertProcessAlive(pid)` → void
- `assertProcessDead(pid)` → void
- `getProcessState(pid)` → Object
- `awaitProcessTermination(pid, timeout, unit)` → void
- `getMailboxSize(pid)` → int
- `registerProcess(name, pid)` → void
- `lookupProcess(name)` → Object
- `createLink(p1, p2)` → void
- `createMonitor(pid)` → Object
- `getSupervisionTree()` → Map

### IntegrationPatternTestBase
Parent for multi-pattern composition tests.

**Methods:**
- `createPattern(name, class)` → Object
- `connect(source, target)` → void
- `getPattern(name)` → Object
- `recordMessageFlow(id, patternName)` → void
- `validateNoMessageLoss(count)` → void
- `validateFullChainTraversal(id, patternNames...)` → void
- `getEndToEndLatency(id)` → long
- `startPerformanceMeasurement()` → void
- `stopPerformanceMeasurement()` → void
- `assertChainThroughput(msg/sec)` → void
- `assertChainP99Latency(ms)` → void

## Utilities Reference

### MessageAssertions
Fluent message assertion API.

```java
assertMessage(msg)
  .hasCorrelationId(uuid)
  .isPriority("HIGH")
  .hasType("CommandMessage")
  .hasField("payload", expectedPayload)
  .assertSucceeds();
```

### MessageBuilder
Fluent DSL for message construction.

```java
var msg = MessageBuilder.command()
  .withCorrelationId(uuid)
  .withReplyTo(replyPid)
  .withPriority("HIGH")
  .withHeader("trace-id", "123")
  .build();
```

### PatternTestFixture<P>
Reflection-driven process fixture factory.

```java
var fixture = PatternTestFixture.for(Router.class)
  .withSupervision("ONE_FOR_ONE")
  .registerInRegistry("test-router")
  .captureMessages()
  .build();

var router = fixture.createProcess();
fixture.cleanup();
```

### JotpTestHelper
JOTP introspection utilities.

```java
var state = JotpTestHelper.getProcessState(pid);
var size = JotpTestHelper.getMailboxSize(pid);
JotpTestHelper.awaitProcessTermination(pid, 5, SECONDS);
JotpTestHelper.registerProcess("router", routerPid);
```

### CorrelationIdTracker
Track message causality.

```java
var tracker = new CorrelationIdTracker();
tracker.recordStep(correlationId, "received");
tracker.recordStep(correlationId, "routed");
tracker.recordStep(correlationId, "replied");
tracker.assertCausalityChain(correlationId, "received", "routed", "replied");
tracker.assertNoOrphanedMessages(Set.of(id1, id2));
var latency = tracker.getChainLatencyMillis(correlationId);
System.out.println(tracker.reportGraphs());
```

### PerformanceTestHelper
Throughput & latency assertions.

```java
var perf = new PerformanceTestHelper();
perf.start();
for (var msg : messages) {
  var start = System.nanoTime();
  send(router, msg);
  perf.recordLatency(System.nanoTime() - start);
}
perf.stop();

perf.assertMinThroughput(100_000);     // 100k msg/sec
perf.assertP99Latency(50);             // P99 < 50ms
perf.assertP95Latency(30);             // P95 < 30ms
perf.assertMaxMemory(256);             // < 256 MB delta

System.out.println(perf.getSummary());
System.out.println(perf.getDetailedReport());
```

## Java 26 Reflection API Usage

The framework uses Java 26 reflection extensively:

### Sealed Type Introspection
```java
if (messageClass.isSealed()) {
  var variants = messageClass.permittedSubclasses();
  // Enumerate all message variants
}
```

### Record Components
```java
var recordClass = message.getClass();
if (recordClass.isRecord()) {
  var components = recordClass.getRecordComponents();
  for (var comp : components) {
    var value = comp.getAccessor().invoke(message);
  }
}
```

### Virtual Thread Detection
```java
if (Thread.currentThread().isVirtual()) {
  // Running on virtual thread
}
```

### Generic Type Resolution
```java
var paramType = (ParameterizedType) getClass().getGenericSuperclass();
var typeArgs = paramType.getActualTypeArguments();
// Extract Proc<State, Message> type parameters
```

## Common Testing Scenarios

### Testing Async Message Routing
```java
@AsyncPatternTest(timeoutValue = 5, timeoutUnit = SECONDS)
class AsyncRouterTest extends AsyncPatternTestBase<ContentBasedRouter> {
  @Test
  void testAsyncRouting() {
    startPerformanceMeasurement();

    for (int i = 0; i < 1000; i++) {
      var msg = messageBuilder
        .withCorrelationId(UUID.randomUUID())
        .withField("type", "ORDER")
        .build();

      var result = ask(router, msg, timeout());
      recordLatency(elapsedNanos);
    }

    stopPerformanceMeasurement();
    assertMinThroughput(10_000);
    assertP99Latency(50);
  }
}
```

### Testing Error Handling
```java
class DeadLetterTest extends PatternTestBase<DeadLetterChannel> {
  @Test void testPoisonPillRouting() {
    var fixture = PatternTestFixture.for(DeadLetterChannel.class).build();

    var msg = messageBuilder.withField("corrupted", true).build();
    send(fixture.createProcess(), msg);

    assertEventually(() -> {
      var dlc = processRegistry.whereis("dlc");
      return getMailboxSize(dlc) > 0;
    });
  }
}
```

### Testing Supervisor Crash Recovery
```java
@JotpTest(primitive = "Supervisor", testCrashRecovery = true)
class SupervisorTest extends JotpTestBase {
  @Test void testOneForOneRestart() {
    var supervisor = createSupervisor("ONE_FOR_ONE");
    var child = spawnChild(supervisor, "worker");

    // Simulate crash
    crashProcess(child);

    assertEventually(() -> {
      var restarted = lookupProcess("worker");
      return restarted != null && isProcessAlive(restarted);
    });
  }
}
```

### Testing Pattern Composition
```java
@IntegrationPattern(
  patterns = {"Router", "Aggregator", "Filter"},
  validateDataIntegrity = true
)
class CompositionTest extends IntegrationPatternTestBase {
  @Test void testEndToEnd() {
    var router = createPattern("r", ContentBasedRouter.class);
    var agg = createPattern("a", Aggregator.class);
    connect(router, agg);

    var id = UUID.randomUUID().toString();

    for (int i = 0; i < 10; i++) {
      var msg = MessageBuilder.command()
        .withCorrelationId(id)
        .withField("index", i)
        .build();
      send(router, msg);
    }

    validateFullChainTraversal(id, "r", "a");
    assertNoMessageLoss(10);
  }
}
```

## Best Practices

1. **Use @PatternTest for metadata** — Improves test discoverability
2. **Extend PatternTestBase** — DRY: avoid repeating setup/teardown
3. **Track correlation IDs** — Enable causality validation across processes
4. **Test async with timeouts** — Prevent infinite hangs
5. **Measure performance** — Baseline assertions catch regressions
6. **Use reflection carefully** — Java 26 API handles sealed types/records
7. **Compose patterns** — Test realistic multi-pattern chains
8. **Validate JOTP primitives** — Test Proc, Supervisor, Links, Monitors, etc.

## Running Tests

```bash
# Run all pattern tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=ContentBasedRouterTest

# Run async tests only
./mvnw test -Dgroups=AsyncPatternTest

# Run with performance baseline assertions
./mvnw test -Dtest=RouterPerformanceTest

# Run integration composition tests
./mvnw test -Dtest=*IntegrationPattern
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Timeout in async test | Increase `@AsyncPatternTest.timeoutValue` |
| Orphaned messages | Call `tracker.assertNoOrphanedMessages()` |
| Virtual thread pinning | Use `@VirtualThreaded(noPinning = true)` |
| Causality chain mismatch | Print `tracker.reportGraphs()` for debugging |
| Process not alive after restart | Check `getCrashCount()` in supervisor tests |
| Message loss in composition | Use `@IntegrationPattern(validateDataIntegrity = true)` |

---

**All 34 Vernon patterns are now testable with this unified framework!** 🎉
