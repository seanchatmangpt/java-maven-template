# Reference: Troubleshooting

Common issues and solutions for JOTP applications.

## Build & Compilation Issues

### "Preview features not enabled"

**Symptom:**
```
error: sealed classes are a preview feature and are disabled by default.
```

**Cause:** Java 26 requires `--enable-preview` flag for sealed classes, pattern matching, and records.

**Solution:** The pom.xml includes this automatically via the Maven compiler plugin. If you're building manually:

```bash
export JAVA_OPTS="--enable-preview"
./mvnw clean compile
```

Or ensure your IDE is configured to enable preview features.

---

### "Cannot find symbol: Proc"

**Symptom:**
```
error: cannot find symbol
  symbol: class Proc
  location: package io.github.seanchatmangpt.jotp
```

**Cause:** JOTP library not in classpath or incorrect Maven dependency.

**Solution:**

1. Verify pom.xml includes JOTP dependency:
```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>1.0.0</version>
</dependency>
```

2. Run `./mvnw clean install` to rebuild the local repository

3. Verify Java 26 is being used: `java -version`

---

### Compilation timeout

**Symptom:**
```
[ERROR] Error executing Maven goal org.apache.maven.plugins:maven-compiler-plugin:3.x:compile
[ERROR] Compilation took longer than expected
```

**Cause:** Preview feature compilation is slower; large projects take longer.

**Solution:**

Increase Maven memory:
```bash
export MAVEN_OPTS="-Xmx2g -Xms1g"
./mvnw compile
```

For faster iterative builds, use Maven Daemon:
```bash
./bin/mvndw compile  # Much faster on second run
```

---

## Runtime Issues

### Process hangs or deadlocks

**Symptom:** Application appears frozen, processes don't respond to messages.

**Cause:**
- Process handler is blocking on I/O without virtual thread support
- Synchronous wait with no timeout
- Supervisor restart loop

**Solutions:**

1. **Check for blocking I/O:** Ensure blocking operations are done in virtual threads:
```java
// BAD: Blocks forever
var result = database.query(sql);  // No timeout!

// GOOD: Use ask() with timeout
var result = process.ask(msg -> msg, Duration.ofSeconds(5));
```

2. **Add timeouts to all blocking operations:**
```java
// Blocking query with timeout
var future = executor.submit(() -> database.query(sql));
var result = future.get(5, TimeUnit.SECONDS);  // Will throw TimeoutException
```

3. **Check supervisor configuration:**
```java
// Verify restart limits are set (prevent infinite loops)
Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
    .maxRestarts(3)        // Don't allow infinite restarts
    .maxRestartWindow(Duration.ofSeconds(5))
    .build();
```

---

### Process crashes repeatedly (restart loop)

**Symptom:** Logs show repeated crashes and restarts:
```
[ERROR] Process failed: NullPointerException
[INFO] Supervisor restarting process
[ERROR] Process failed: NullPointerException
...
```

**Cause:** Child process has a persistent bug; supervisor can't recover by restart alone.

**Solution:**

1. **Fix the root cause:** Debug the handler to find the issue
```java
// Add logging to understand the crash
var proc = Proc.start(state -> msg -> {
    System.err.println("Handler state=" + state + ", msg=" + msg);
    try {
        // Your logic here
        return newState;
    } catch (Exception e) {
        e.printStackTrace();
        throw e;  // Let supervisor handle it
    }
}, initialState);
```

2. **Set restart limits to prevent infinite loops:**
```java
Supervisor.builder()
    .maxRestarts(3)                           // Only allow 3 restarts
    .maxRestartWindow(Duration.ofSeconds(5)) // Within 5 seconds
    .build();
    // If limit exceeded, supervisor crashes and reports to parent
```

3. **Use CrashRecovery for isolated retries:**
```java
var result = CrashRecovery.retry(
    () -> risky_operation(),
    3,  // Max 3 attempts
    Duration.ofMillis(100)  // Wait 100ms between retries
);
```

---

### OutOfMemoryError

**Symptom:**
```
Exception in thread "virtual-thread@123" java.lang.OutOfMemoryError: Java heap space
```

**Cause:**
- Too many processes created without cleanup
- Message queue unbounded
- Memory leak in handler

**Solutions:**

1. **Increase heap size:**
```bash
export MAVEN_OPTS="-Xmx4g -Xms2g"
./mvnw test
```

2. **Monitor process count:**
```java
// Add periodic logging
var count = ProcessRegistry.registered().size();
System.out.println("Active processes: " + count);

// Kill old processes
if (count > MAX_PROCESSES) {
    proc.kill();  // Graceful termination
}
```

3. **Use bounded queues:**
```java
// Prevent unbounded message accumulation
var future = executor.submit(() -> process.send(msg));
try {
    future.get(5, TimeUnit.SECONDS);  // Wait for send to complete
} catch (TimeoutException e) {
    // Queue is full, drop message or apply backpressure
}
```

---

## Testing Issues

### Tests timeout

**Symptom:**
```
[ERROR] Tests timed out after 60 seconds
```

**Cause:**
- Virtual threads slower on first JVM run
- Supervisor restarts taking time
- No timeout in `ask()`

**Solutions:**

1. **Increase test timeout:**
```bash
./mvnw test -DargLine="-Dtimeout=10000"
```

2. **Use explicit timeouts in tests:**
```java
@Test(timeout = 5000)  // 5 second timeout
void testProcess() throws Exception {
    var proc = Proc.start(...);
    var result = proc.ask(msg -> msg, Duration.ofSeconds(1));
    assertThat(result).isEqualTo(expected);
}
```

3. **Warm up JVM for faster subsequent runs:**
```bash
./mvnw clean test  # First run slower
./mvnw test        # Second run faster (JIT compiled)
```

---

### Message ordering issues

**Symptom:** Messages processed out of order, race conditions in tests.

**Cause:**
- Multiple processes sending to same receiver
- Handler processing slower than message arrival

**Solution:** Use FIFO message queue verification:
```java
@Test
void testMessageOrdering() throws Exception {
    var received = Collections.synchronizedList(new ArrayList<Integer>());

    var proc = Proc.start(state -> msg -> {
        received.add((Integer) msg);
        return state + 1;
    }, 0);

    // Send messages in order
    proc.send(1);
    proc.send(2);
    proc.send(3);

    Thread.sleep(100);

    // Verify FIFO ordering
    assertThat(received).containsExactly(1, 2, 3);
}
```

---

### Process not receiving messages

**Symptom:** `proc.send()` completes but handler never receives message.

**Cause:**
- Process already terminated
- Handler exception thrown before processing
- Process reference is stale

**Solutions:**

1. **Check if process is alive:**
```java
if (proc.isAlive()) {
    proc.send(msg);
} else {
    System.err.println("Process is dead");
}
```

2. **Check for handler exceptions:**
```java
try {
    proc.send(msg);
    // Give process time to handle
    Thread.sleep(100);
} catch (Exception e) {
    e.printStackTrace();
}
```

3. **Use ask() instead of send() to ensure delivery:**
```java
// ask() blocks until handler responds
var response = proc.ask(
    replyTo -> new Request(replyTo),
    Duration.ofSeconds(1)
);
// If no response within 1 second, throws TimeoutException
```

---

## Performance Issues

### Processes running slowly

**Symptom:** Expected high throughput, but processes seem sluggish.

**Cause:**
- Handler does heavy CPU work
- Too much contention for carrier threads
- Virtual thread pinning (blocking on synchronized blocks)

**Solutions:**

1. **Profile with JFR (Java Flight Recorder):**
```bash
./mvnw test -Dcom.sun.management.jmxremote.port=9999
# Then analyze with JFR GUI
```

2. **Avoid synchronized blocks in handlers:**
```java
// BAD: Blocks virtual thread carrier
synchronized(lock) {
    state = newState;
}

// GOOD: Use ReentrantLock or atomic variables
lock.lock();
try {
    state = newState;
} finally {
    lock.unlock();
}
```

3. **Reduce handler work:**
```java
// BAD: Heavy computation in handler
var proc = Proc.start(state -> msg -> {
    var result = expensiveComputation(msg);
    return result;
}, initialState);

// GOOD: Offload to separate executor
var executor = Executors.newFixedThreadPool(4);
var proc = Proc.start(state -> msg -> {
    executor.submit(() -> expensiveComputation(msg));
    return state;
}, initialState);
```

---

## See Also

- **[How-To: Handle Process Failures](../how-to/handle-process-failures.md)** — Crash recovery strategies
- **[How-To: Test Concurrent Code](../how-to/test-concurrent-code.md)** — Testing best practices
- **[Reference: Configuration](configuration.md)** — JOTP runtime configuration
- **[Reference: Glossary](glossary.md)** — Key terms and concepts

---

**Last Updated:** March 2026
