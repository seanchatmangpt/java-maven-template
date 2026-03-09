# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./mvnw test              # Run unit tests only
./mvnw verify            # Run all tests (unit + integration) and quality checks
./mvnw spotless:apply    # Format code (Google Java Format, AOSP style)
./mvnw spotless:check    # Check formatting without applying
./mvnw jshell:run        # Start interactive JShell REPL
./mvnw package -Dshade   # Build a fat/uber JAR (shade profile)
```

**Run a single test class:**
```bash
./mvnw test -Dtest=MathsTest
./mvnw verify -Dit.test=MathsIT  # integration test
```

## Build Commands (dx.sh)

The `dx.sh` script provides a unified build interface that integrates with the yawl submodule when available, or falls back to standalone Maven builds.

```bash
./dx.sh compile          # Compile changed modules
./dx.sh test             # Run tests
./dx.sh all              # Full build + validation (guards)
./dx.sh validate         # Run guard validation only
./dx.sh deploy           # Deploy to cloud (OCI default)
```

**With yawl submodule**: Delegates to `yawl/scripts/dx.sh` for full H+Q validation gates.
**Without yawl**: Uses `dx-standalone.sh` for basic Maven builds with simplified guards.

### Guard Validation

The guard system detects forbidden patterns in production code:

| Pattern | Description | Fix |
|---------|-------------|-----|
| H_TODO | Deferred work markers (TODO, FIXME, etc.) | Implement or remove |
| H_MOCK | Mock/stub/fake implementations | Delete or implement real service |
| H_STUB | Empty/placeholder returns | Throw UnsupportedOperationException |

**Build guard system:**
```bash
cd guard-system && cargo build --release
```

## Architecture

**Java 25 JPMS library** (`org.acme` module) targeting Java 25 with preview features enabled (`--enable-preview`).

**Test separation:**
- Unit tests: `*Test.java` — run by maven-surefire-plugin via `./mvnw test`
- Integration tests: `*IT.java` — run by maven-failsafe-plugin during `verify` phase

**Test execution:** JUnit 5 is configured for full parallel execution (dynamic strategy, concurrent mode for both methods and classes) via `src/test/resources/junit-platform.properties`.

**Test libraries available:** JUnit 5, AssertJ (use `implements WithAssertions`), jqwik (property-based testing via `@Property`/`@ForAll`), Instancio (test data generation), ArchUnit (architecture rules), Awaitility (async assertions).

**`Result<T, E>` type:** A sealed interface with `Success`/`Failure` variants providing railway-oriented programming. Use `Result.of(supplier)` to wrap throwing operations. Supports `map`, `flatMap`, `fold`, `recover`, `peek`, and `orElseThrow`.

**Formatting:** Spotless with Google Java Format (AOSP style) runs automatically at compile phase. The PostToolUse hook (see below) auto-runs `spotless:apply` after every Java file edit — do not run it manually.

**Build cache:** Maven Build Cache Extension is active; incremental builds skip unchanged modules automatically.

**Maven flags (always applied via `.mvn/maven.config`):** `-B` (batch mode), `--fail-at-end`, `--no-transfer-progress`.

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

`./mvnw *` and `git *` are pre-approved; Claude Code will not prompt for confirmation on these commands.

### Optional: Pre-warm the build cache

For long sessions, warm the Maven Build Cache before starting:

```bash
./mvnw compile -q -T1C
```
