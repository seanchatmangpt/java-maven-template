# Tutorial 01: Getting Started with JOTP

Welcome to JOTP! In this tutorial, you'll set up your development environment, clone the repository, and run your first build.

## Prerequisites

Before you start, make sure you have:

1. **Java 26** (GraalVM Community CE 25.0.2 or later)
   - Check: `java -version`
   - Verify `--enable-preview` is available

2. **Maven 4** (via mvnd or maven-wrapper)
   - The JOTP project includes `./mvnw` (Maven Wrapper)
   - For faster builds, install `mvnd`: [maven-mvnd releases](https://github.com/apache/maven-mvnd/releases)

3. **Git** for cloning the repository

4. **A code editor** (VS Code, IntelliJ IDEA, Emacs, etc.)

## Step 1: Verify Your Java Environment

```bash
java -version
```

Expected output (Java 26):
```
openjdk version "26" 2025-09-16
GraalVM Community Edition 25.0.2
```

If you don't have Java 26, install [GraalVM Community CE 25.0.2](https://www.graalvm.org/downloads/).

## Step 2: Clone the JOTP Repository

```bash
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp
```

## Step 3: Explore the Project Structure

```bash
# View the main source directory
ls -la src/main/java/io/github/seanchatmangpt/jotp/

# View the test directory
ls -la src/test/java/io/github/seanchatmangpt/jotp/

# View documentation
ls -la docs/
```

Key directories:

- `src/main/java/io/github/seanchatmangpt/jotp/` — Core JOTP primitives
- `src/test/java/io/github/seanchatmangpt/jotp/` — Test suite
- `docs/` — Documentation (where you are now!)
- `templates/` — Code generation templates for `jgen`
- `schema/` — OWL ontologies for migration rules
- `bin/` — Helper scripts (`mvndw`, `jgen`, etc.)

## Step 4: Run Your First Build

```bash
# Run unit tests only
./mvnw test

# Run all tests + quality checks
./mvnw verify

# Format code (Spotless)
./mvnw spotless:apply
```

Expected output:
```
[INFO] BUILD SUCCESS
[INFO] Total time: X.XXs
```

If the build fails, check:
- Java version is 26: `java -version`
- Maven has sufficient memory: `export MAVEN_OPTS="-Xmx2g"`

## Step 5 (Optional): Use Maven Daemon for Faster Builds

Maven Daemon (`mvnd`) caches the Maven/JVM instance, making subsequent builds 2-3x faster:

```bash
# First time: downloads mvnd
bin/mvndw verify

# Subsequent runs: reuses JVM (much faster)
bin/mvndw test
```

## Step 6: Explore a Simple Test

Open `src/test/java/io/github/seanchatmangpt/jotp/ProcTest.java`:

```java
public class ProcTest {
    @Test
    void testSimpleProcess() throws Exception {
        var proc = Proc.start(
            state -> msg -> state + 1,  // Handler: increment on each message
            0                             // Initial state
        );

        proc.send(1);
        proc.send(1);

        var finalState = proc.ask(msg -> msg, Duration.ofSeconds(1));
        assertThat(finalState).isEqualTo(2);
    }
}
```

This demonstrates:
- Creating a `Proc<Integer, Integer>` (state = Integer, messages = Integer)
- Sending messages with `send()`
- Querying state with `ask()`

## Step 7: What's Next?

You've successfully:
- ✅ Set up Java 26
- ✅ Cloned JOTP
- ✅ Run your first build
- ✅ Explored the project structure

Next steps:

1. **[Tutorial 02: Your First Process](02-first-process.md)** — Create and run a custom `Proc<S,M>`
2. **[Tutorial 03: Virtual Threads](03-virtual-threads.md)** — Understand Java 26 virtual thread concurrency
3. **[Tutorial 04: Supervision Basics](04-supervision-basics.md)** — Build your first supervision tree

Or jump to **[How-To Guides](../how-to/)** if you have a specific problem to solve.

## Troubleshooting

**Build fails with "preview features not enabled"**
- Solution: Java 26 requires `--enable-preview`. The pom.xml includes this automatically.
- If you're using a different Java version, set: `export JAVA_HOME=/path/to/java26`

**Build fails with "cannot find symbol"**
- Solution: Run `./mvnw clean compile` to rebuild from scratch

**Tests time out**
- Solution: Increase Maven's memory: `export MAVEN_OPTS="-Xmx2g -Xms1g"`

---

**Next:** [Tutorial 02: Your First Process](02-first-process.md)
