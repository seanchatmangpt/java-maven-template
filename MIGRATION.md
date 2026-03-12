# JOTP Migration Guide

This guide helps you upgrade from the previous `java-maven-template` to **JOTP (Java OTP Framework)** v1.0.0.

## Overview

The `java-maven-template` repository has been transformed into **JOTP**, a production-ready framework for building fault-tolerant, enterprise-grade systems using Joe Armstrong / Erlang/OTP patterns in pure Java 26.

This migration involves:
- **Namespace change**: `org.acme` → `io.github.seanchatmangpt.jotp`
- **Artifact coordinates update**: `org.acme:lib` → `io.github.seanchatmangpt:jotp`
- **Module name change**: `org.acme` → `io.github.seanchatmangpt.jotp`
- **Documentation restructuring**: Diataxis-based documentation (Tutorials, How-To Guides, References, Explanations)

## Breaking Changes

### 1. Package and Namespace Migration

All classes have moved from `org.acme.*` to `io.github.seanchatmangpt.jotp.*`.

**Before:**
```java
import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.Supervisor;
import org.acme.StateMachine;
```

**After:**
```java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.StateMachine;
```

### 2. Maven Dependency Update

Update your `pom.xml` to reference the new artifact coordinates:

**Before:**
```xml
<dependency>
    <groupId>org.acme</groupId>
    <artifactId>lib</artifactId>
    <version>1.0.0</version>
</dependency>
```

**After:**
```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3. Module Name Update

If you're using Java Platform Module System (JPMS), update your `module-info.java`:

**Before:**
```java
module com.example.app {
    requires org.acme;
    requires java.base;
}
```

**After:**
```java
module com.example.app {
    requires io.github.seanchatmangpt.jotp;
    requires java.base;
}
```

### 4. Maven Compiler Configuration

Update your Maven compiler plugin to enable preview features for Java 26:

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.14.1</version>
    <configuration>
        <release>26</release>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

Also update surefire and failsafe plugins:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.4</version>
    <configuration>
        <argLine>--enable-preview --add-reads io.github.seanchatmangpt.jotp=ALL-UNNAMED</argLine>
    </configuration>
</plugin>

<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.5.4</version>
    <configuration>
        <argLine>--enable-preview --add-reads io.github.seanchatmangpt.jotp=ALL-UNNAMED</argLine>
    </configuration>
</plugin>
```

## Step-by-Step Upgrade Instructions

### Step 1: Update Project Dependencies

1. Open your project's `pom.xml`
2. Find the `<dependency>` section containing the old coordinates
3. Replace with new coordinates as shown above
4. Run: `mvn clean dependency:resolve` to verify

### Step 2: Update Java Imports

Use your IDE's refactoring tools to update imports:

**IntelliJ IDEA:**
- Use "Find and Replace" → "Replace All"
- Search: `org\.acme\.(\w+)`
- Replace: `io.github.seanchatmangpt.jotp.$1`
- Enable "Regex" checkbox

**Eclipse:**
- Right-click project → "Source" → "Clean Up"
- Configure search pattern for imports

**VS Code with Language Server:**
- Use "Find and Replace" with regex support
- Same pattern as above

### Step 3: Update Module Declarations (if applicable)

If using JPMS (Java 9+), update `module-info.java`:

```java
// Before
module com.example.app {
    requires org.acme;
}

// After
module com.example.app {
    requires io.github.seanchatmangpt.jotp;
}
```

### Step 4: Verify Compilation

```bash
# Verify your project compiles
mvn clean compile

# Run tests
mvn test

# Full verification
mvn verify
```

### Step 5: Review Documentation

- **Reference**: See the [API Documentation](docs/reference/api.md) for complete API details
- **How-To Guides**: Check [docs/how-to/](docs/how-to/) for common patterns
- **Tutorials**: Start with [docs/tutorials/getting-started.md](docs/tutorials/getting-started.md)
- **Explanations**: Read [docs/explanations/](docs/explanations/) for deep dives

## Code Examples: Before and After

### Example 1: Creating a Process

**Before (org.acme):**
```java
import org.acme.Proc;
import org.acme.ProcRef;

record CounterMessage(String type, int value) {}

class Counter {
    public static void main(String[] args) {
        var state = new AtomicInteger(0);
        var ref = Proc.spawn(state, (msg, s) -> {
            if ("increment".equals(msg.type())) {
                s.addAndGet(msg.value());
            }
            return s;
        });
    }
}
```

**After (io.github.seanchatmangpt.jotp):**
```java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;

record CounterMessage(String type, int value) {}

class Counter {
    public static void main(String[] args) {
        var state = new AtomicInteger(0);
        var ref = Proc.spawn(state, (msg, s) -> {
            if ("increment".equals(msg.type())) {
                s.addAndGet(msg.value());
            }
            return s;
        });
    }
}
```

### Example 2: Supervision Tree

**Before (org.acme):**
```java
import org.acme.Supervisor;
import org.acme.Supervisor.RestartStrategy;

var supervisor = new Supervisor()
    .withStrategy(RestartStrategy.ONE_FOR_ONE)
    .addChild("worker-1", () -> Proc.spawn(...))
    .addChild("worker-2", () -> Proc.spawn(...));
```

**After (io.github.seanchatmangpt.jotp):**
```java
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.RestartStrategy;

var supervisor = new Supervisor()
    .withStrategy(RestartStrategy.ONE_FOR_ONE)
    .addChild("worker-1", () -> Proc.spawn(...))
    .addChild("worker-2", () -> Proc.spawn(...));
```

### Example 3: State Machine

**Before (org.acme):**
```java
import org.acme.StateMachine;
import org.acme.StateMachine.Transition;

enum State { IDLE, PROCESSING, ERROR }
record Event(String type) {}

var fsm = StateMachine.create(State.IDLE, (state, event) ->
    switch (state) {
        case IDLE -> "process".equals(event.type())
            ? Transition.to(State.PROCESSING)
            : Transition.stay();
        case PROCESSING -> "done".equals(event.type())
            ? Transition.to(State.IDLE)
            : Transition.stay();
        case ERROR -> Transition.to(State.IDLE);
    }
);
```

**After (io.github.seanchatmangpt.jotp):**
```java
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;

enum State { IDLE, PROCESSING, ERROR }
record Event(String type) {}

var fsm = StateMachine.create(State.IDLE, (state, event) ->
    switch (state) {
        case IDLE -> "process".equals(event.type())
            ? Transition.to(State.PROCESSING)
            : Transition.stay();
        case PROCESSING -> "done".equals(event.type())
            ? Transition.to(State.IDLE)
            : Transition.stay();
        case ERROR -> Transition.to(State.IDLE);
    }
);
```

## Known Issues and Workarounds

### Issue 1: Java 26 Preview Features

**Problem**: `--enable-preview` flag is required for Java 26 features.

**Solution**: Ensure your Maven compiler and test plugins are configured with:
```xml
<compilerArgs>
    <arg>--enable-preview</arg>
</compilerArgs>
<argLine>--enable-preview</argLine>
```

### Issue 2: Module System Requirements

**Problem**: When running tests, you may encounter module access errors.

**Solution**: Add `--add-reads` arguments to both surefire and failsafe:
```xml
<argLine>--enable-preview --add-reads io.github.seanchatmangpt.jotp=ALL-UNNAMED</argLine>
```

### Issue 3: Classpath vs Module Path

**Problem**: If your project uses a mix of modular and non-modular JARs.

**Solution**: In test configurations, explicitly declare module reads:
```xml
<argLine>--add-reads io.github.seanchatmangpt.jotp=ALL-UNNAMED</argLine>
```

## Troubleshooting

### Compilation fails with "cannot find symbol"

**Check:**
1. Verify Maven pom.xml has updated coordinates
2. Run `mvn clean` and `mvn dependency:resolve`
3. Refresh IDE project cache
4. Check all imports are updated to `io.github.seanchatmangpt.jotp.*`

### Tests fail with "Module not found"

**Check:**
1. Surefire/failsafe plugins have correct `<argLine>` settings
2. JVM arguments include `--enable-preview`
3. Module reads are configured: `--add-reads io.github.seanchatmangpt.jotp=ALL-UNNAMED`

### IDE shows import errors but project compiles

**Fix:**
- Invalidate IDE caches and restart
- For IntelliJ: File → Invalidate Caches → Restart
- For Eclipse: Project → Clean
- For VS Code: Reload window

## Support

For issues during migration:
- Check the [FAQ](docs/reference/faq.md)
- Review [Common Patterns](docs/how-to/common-patterns.md)
- File an issue on GitHub with migration details

## Summary of Changes

| Item | Before | After |
|------|--------|-------|
| **Namespace** | `org.acme` | `io.github.seanchatmangpt.jotp` |
| **Group ID** | `org.acme` | `io.github.seanchatmangpt` |
| **Artifact ID** | `lib` | `jotp` |
| **Module Name** | `org.acme` | `io.github.seanchatmangpt.jotp` |
| **Java Version** | 21+ | 26+ (preview features required) |
| **Documentation** | Single README | Diataxis structure (4 sections) |

All OTP primitives (Proc, Supervisor, StateMachine, etc.) remain functionally identical—only the namespace and artifact coordinates have changed.
