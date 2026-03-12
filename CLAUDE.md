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
mvnd test -Dtest=MathsTest
mvnd verify -Dit.test=MathsIT  # integration test
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

**Java 26 JPMS library** (`io.github.seanchatmangpt.jotp` module) targeting Java 26 with preview features enabled (`--enable-preview`). JDK: GraalVM Community CE 25.0.2 (Java 26 EA builds required for Java 26 target).

**Test separation:**
- Unit tests: `*Test.java` — run by maven-surefire-plugin via `./mvnw test`
- Integration tests: `*IT.java` — run by maven-failsafe-plugin during `verify` phase

**Test execution:** JUnit 5 is configured for full parallel execution (dynamic strategy, concurrent mode for both methods and classes) via `src/test/resources/junit-platform.properties`.

**Test libraries available:** JUnit 5, AssertJ (use `implements WithAssertions`), jqwik (property-based testing via `@Property`/`@ForAll`), Instancio (test data generation), ArchUnit (architecture rules), Awaitility (async assertions).

**`Result<T, E>` type:** A sealed interface with `Success`/`Failure` variants providing railway-oriented programming. Use `Result.of(supplier)` to wrap throwing operations. Supports `map`, `flatMap`, `fold`, `recover`, `peek`, and `orElseThrow`.

**Formatting:** Spotless with Google Java Format (AOSP style) runs automatically at compile phase. The PostToolUse hook (see below) auto-runs `spotless:apply` after every Java file edit — do not run it manually.

**Joe Armstrong / Erlang/OTP patterns** — fifteen primitives in `io.github.seanchatmangpt.jotp`:
- `Proc<S,M>` — lightweight process: virtual-thread mailbox + pure state handler (OTP: `spawn/3`)
- `ProcRef<S,M>` — stable Pid: opaque handle that survives supervisor restarts
- `Supervisor` — supervision tree: ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE with sliding restart window
- `CrashRecovery` — "let it crash" + supervised retry via isolated virtual threads
- `StateMachine<S,E,D>` — gen_statem: state/event/data separation + sealed `Transition` hierarchy
- `ProcessLink` — process links: bilateral crash propagation (`link/1`, `spawn_link/3`)
- `Parallel` — structured fan-out with fail-fast semantics (`StructuredTaskScope`, OTP: `pmap`)
- `ProcessMonitor` — unilateral DOWN notifications: `monitor(process, Pid)` / `demonitor/1`; fires on any exit (normal or abnormal); does NOT kill the monitoring side (unlike links)
- `ProcessRegistry` — global name table: `register/2`, `whereis/1`, `unregister/1`, `registered/0`; auto-deregisters when a process terminates
- `ProcTimer` — timed message delivery: `timer:send_after/3`, `timer:send_interval/3`, `timer:cancel/1`
- `ExitSignal` — exit signal record delivered as a mailbox message when a process traps exits (`process_flag(trap_exit, true)`)
- `ProcSys` — sys module: `get_state`, `suspend`, `resume`, `statistics` — process introspection without stopping
- `ProcLib` — proc_lib startup handshake: `start_link` blocks until child calls `initAck()`, returning `StartResult.Ok | Err`
- `EventManager<E>` — gen_event: typed event manager with `addHandler`, `notify`, `syncNotify`, `deleteHandler`, `call`; crashes handlers without killing the manager
- `Proc.trapExits(boolean)` / `Proc.ask(msg, timeout)` — `process_flag(trap_exit)` and timed `gen_server:call` added to core `Proc`

## Claude Code Configuration (`.claude/`)

`.claude/settings.json` is checked in and applies to all contributors using Claude Code.

### Hooks

**SessionStart** — runs when a session begins:
- Displays `git status`, current branch, and last 5 commits so Claude has immediate project context
- Verifies the Java version (must be 26 for `--enable-preview`)

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
bin/jgen migrate --source ./legacy     # Detect legacy patterns (grep-based)
bin/jgen refactor --source ./legacy    # Full analysis: score + ranked commands
bin/jgen refactor --source ./legacy --plan   # Saves executable migrate.sh
bin/jgen refactor --source ./legacy --score  # Score-only modernization report
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

## Innovation Engine (`io.github.seanchatmangpt.jotp.dogfood.innovation`)

Five coordinated analysis engines power the automated refactor pipeline:

| Class | Role |
|---|---|
| `OntologyMigrationEngine` | Analyzes Java source against 12 ontology-driven migration rules; returns sealed `MigrationPlan` hierarchy |
| `ModernizationScorer` | Scores source files 0-100 across 40+ modern/legacy signal detectors; ranks by ROI |
| `TemplateCompositionEngine` | Composes multiple Tera templates into coherent features (CRUD, value objects, service layers) |
| `BuildDiagnosticEngine` | Maps compiler error output to concrete `DiagnosticFix` suggestions (10 fix subtypes) |
| `LivingDocGenerator` | Parses Java source into structured `DocElement` hierarchy; renders Markdown documentation |
| `RefactorEngine` | **Orchestrator**: chains all engines into a single `RefactorPlan` with per-file scores, `JgenCommand` lists, `toScript()`, and `summary()` |

**One-command refactor of any codebase:**
```java
// Java API
var plan = RefactorEngine.analyze(Path.of("./legacy/src"));
System.out.println(plan.summary());
Files.writeString(Path.of("migrate.sh"), plan.toScript());
```
```bash
# CLI
bin/jgen refactor --source ./legacy/src --plan  # writes migrate.sh
bash migrate.sh                                  # applies migrations
```

## PhD Thesis

`docs/phd-thesis-otp-java26.md` — *"OTP 28 in Pure Java 26: A Formal Equivalence and Migration Framework for Enterprise-Grade Fault-Tolerant Systems"*

Establishes formal equivalence between the 7 OTP primitives and Java 26, benchmarks BEAM vs. JVM under fault conditions, provides migration paths from Elixir, Go, Rust, and Scala/Akka, and frames this as a blue ocean strategy for Oracle ecosystem influencers.

## Dogfood (Eating Our Own Dog Food)

The `io.github.seanchatmangpt.jotp.dogfood` package contains real Java code rendered from templates, proving they compile and pass tests.

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
- `api/` → `StringMethodPatterns.java` (modern String API) + `StringMethodPatternsTest.java`
- `error-handling/` → `ResultRailway.java` (sealed Result type) + `ResultRailwayTest.java`
- `security/` → `InputValidation.java` (preconditions + error accumulation) + `InputValidationTest.java`
- `testing/` → `PersonTest.java`, `PersonProperties.java` (JUnit 5 + jqwik)
- `innovation/` → all 6 engine classes + full test suites (`RefactorEngineTest`, etc.)
- `build/` → validated implicitly via pom.xml
- `modules/` → validated implicitly via module-info.java

## Maven Daemon (mvnd)

`bin/mvndw` wraps [Apache Maven Daemon](https://github.com/apache/maven-mvnd) for faster builds. It auto-downloads mvnd on first use. Configuration in `.mvn/daemon.properties`.
