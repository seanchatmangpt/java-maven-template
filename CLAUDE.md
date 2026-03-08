# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Tool: mvnd (Maven Daemon, Maven 4) — REQUIRED

**mvnd is mandatory.** Raw `mvn`/`./mvnw` is not used — mvnd 2.0.0-rc-3 (bundling Maven 4) is the build tool.

**Install once:**
```bash
# Download mvnd 2.0.0-rc-3 (Linux x86_64)
# https://github.com/apache/maven-mvnd/releases/download/2.0.0-rc-3/maven-mvnd-2.0.0-rc-3-linux-amd64.tar.gz
# Symlink: ln -sf /path/to/mvnd/bin/mvnd /usr/local/bin/mvnd
```

**Start proxy before building** (required when `https_proxy` is active in the environment):
```bash
python3 maven-proxy-v2.py &   # starts local proxy on 127.0.0.1:3128
```

**Configure Maven to use local proxy** (add to `~/.m2/settings.xml`):
```xml
<settings>
  <proxies>
    <proxy><id>local</id><active>true</active><protocol>https</protocol>
      <host>127.0.0.1</host><port>3128</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>
    <proxy><id>local-http</id><active>true</active><protocol>http</protocol>
      <host>127.0.0.1</host><port>3128</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>
  </proxies>
</settings>
```

## Commands

```bash
mvnd test              # Run unit tests only
mvnd verify            # Run all tests (unit + integration) and quality checks
mvnd spotless:apply    # Format code (Google Java Format, AOSP style)
mvnd spotless:check    # Check formatting without applying
mvnd package -Dshade   # Build a fat/uber JAR (shade profile)
```

**Run a single test class:**
```bash
mvnd test -Dtest=MathsTest
mvnd verify -Dit.test=MathsIT  # integration test
```

## Architecture

**Java 25 JPMS library** (`org.acme` module) targeting Java 25 with preview features enabled (`--enable-preview`). JDK: GraalVM Community CE 25.0.2.

**Test separation:**
- Unit tests: `*Test.java` — run by maven-surefire-plugin via `./mvnw test`
- Integration tests: `*IT.java` — run by maven-failsafe-plugin during `verify` phase

**Test execution:** JUnit 5 is configured for full parallel execution (dynamic strategy, concurrent mode for both methods and classes) via `src/test/resources/junit-platform.properties`.

**Test libraries available:** JUnit 5, AssertJ (use `implements WithAssertions`), jqwik (property-based testing via `@Property`/`@ForAll`), Instancio (test data generation), ArchUnit (architecture rules), Awaitility (async assertions).

**`Result<T, E>` type:** A sealed interface with `Success`/`Failure` variants providing railway-oriented programming. Use `Result.of(supplier)` to wrap throwing operations. Supports `map`, `flatMap`, `fold`, `recover`, `peek`, and `orElseThrow`.

**Formatting:** Spotless with Google Java Format (AOSP style) runs automatically at compile phase. The PostToolUse hook (see below) auto-runs `spotless:apply` after every Java file edit — do not run it manually.

**Joe Armstrong / Erlang/OTP patterns:** Three additions from YAWL demonstrate core principles:
- `CrashRecovery` — "let it crash" + supervised retry via virtual threads
- `Actor<S,M>` — lightweight actor with virtual-thread mailbox and message passing
- `Parallel` — structured fan-out with fail-fast semantics (`StructuredTaskScope.ShutdownOnFailure`)

## Claude Code Configuration (`.claude/`)

`.claude/settings.json` is checked in and applies to all contributors using Claude Code.

### Hooks

**SessionStart** — runs when a session begins:
- Displays `git status`, current branch, and last 5 commits so Claude has immediate project context
- Verifies the Java version (must be 25 for `--enable-preview`)

**PostToolUse (Edit/Write on `.java` files)** — runs automatically after every Java file edit:
- Auto-runs `./mvnw spotless:apply -q` after each edit
- Since `spotless:check` runs at compile phase, this prevents build failures without any manual step

### Permissions

`mvnd *`, `./mvnw *`, and `git *` are pre-approved; Claude Code will not prompt for confirmation on these commands.

### Optional: Pre-warm the build cache

For long sessions, warm the build cache before starting:

```bash
mvnd compile -q -T1C
```
