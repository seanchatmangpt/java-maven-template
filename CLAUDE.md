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
./mvnw verify -Ddogfood  # Run dogfood: generate-check + compile + test + report
bin/mvndw verify          # Same as ./mvnw but with Maven Daemon (faster)
```

**Run a single test class:**
```bash
./mvnw test -Dtest=MathsTest
./mvnw verify -Dit.test=MathsIT  # integration test
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

## Code Generation (ggen / jgen)

This project wraps [seanchatmangpt/ggen](https://github.com/seanchatmangpt/ggen) as a code generation engine for Java 26 migration.

**Install ggen:**
```bash
cargo install ggen-cli --features paas,ai
```

**jgen CLI wrapper:**
```bash
bin/jgen generate -t core/record -n Person -p com.example.model
bin/jgen list                          # List all 72 templates
bin/jgen list --category patterns      # List templates in a category
bin/jgen migrate --source ./legacy     # Analyze codebase for migration
bin/jgen verify                        # Compile + format + test check
```

**Template categories (72 templates, 108 patterns):**
- `core/` — 14 templates: records, sealed types, pattern matching, streams, lambdas, var, gatherers
- `concurrency/` — 5 templates: virtual threads, structured concurrency, scoped values
- `patterns/` — 17 templates: all GoF patterns reimagined for modern Java (builder, factory, strategy, state machine, visitor, etc.)
- `api/` — 6 templates: HttpClient, java.time, NIO.2, ProcessBuilder, collections, strings
- `modules/` — 4 templates: JPMS module-info, SPI, qualified exports, multi-module
- `testing/` — 12 templates: JUnit 5, AssertJ, jqwik, Instancio, ArchUnit, Awaitility, Mockito, BDD, Testcontainers
- `error-handling/` — 3 templates: Result<T,E> railway, functional errors, Optional↔Result
- `build/` — 7 templates: POM, Maven wrapper, Spotless, Surefire/Failsafe, build cache, CI/CD
- `security/` — 4 templates: modern crypto, encapsulation, validation, Jakarta EE migration

**Architecture:**
- `schema/*.ttl` — RDF ontologies defining Java type system, patterns, concurrency, modules, migration rules
- `queries/*.rq` — SPARQL queries extracting data from ontologies
- `templates/java/**/*.tera` — Tera templates rendering Java 26 code
- `ggen.toml` — ggen project configuration
- `bin/jgen` — CLI wrapper for Java developers
- `bin/dogfood` — validates templates produce compilable, testable Java code
- `bin/mvndw` — Maven Daemon wrapper (faster builds with persistent JVM)

## Dogfood (Eating Our Own Dog Food)

The `org.acme.dogfood` package contains real Java code rendered from templates, proving they compile and pass tests.

**Dogfood commands:**
```bash
bin/dogfood generate     # Check all dogfood source files exist
bin/dogfood report       # Show template coverage report
bin/dogfood verify       # Full pipeline: check + compile + test + report
./mvnw verify -Ddogfood  # Same via Maven (includes dogfood in build lifecycle)
bin/mvndw verify -Ddogfood  # Same via Maven Daemon (fastest)
```

**Dogfood coverage** (one example per template category):
- `core/` → `Person.java` (record with validation + builder)
- `concurrency/` → `VirtualThreadPatterns.java` (virtual thread utilities)
- `patterns/` → `TextTransformStrategy.java` (functional strategy pattern)
- `api/` → `StringMethodPatterns.java` (modern String API)
- `error-handling/` → `ResultRailway.java` (sealed Result type)
- `security/` → `InputValidation.java` (preconditions + error accumulation)
- `testing/` → `PersonTest.java`, `PersonProperties.java` (JUnit 5 + jqwik)
- `build/` → validated implicitly via pom.xml
- `modules/` → validated implicitly via module-info.java

## Maven Daemon (mvnd)

`bin/mvndw` wraps [Apache Maven Daemon](https://github.com/apache/maven-mvnd) for faster builds. It auto-downloads mvnd on first use. Configuration in `.mvn/daemon.properties`.
