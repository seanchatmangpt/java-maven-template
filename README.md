# Java 26 OTP Library & Migration Toolkit

[![Maven Build](https://github.com/seanchatmangpt/java-maven-template/workflows/Maven%20Build/badge.svg)](https://github.com/seanchatmangpt/java-maven-template/actions)

A Java 26 implementation of all 15 Erlang/OTP concurrency primitives, paired with the `jgen` code generation toolchain (72 templates, 9 categories), 6 automated innovation engines, and a one-command refactor pipeline for migrating legacy Java codebases to modern Java 26 idioms. Built as a JPMS library (`org.acme`) with full parallel test execution and dogfood verification.

## OTP Primitives (15)

All primitives live in the `org.acme` module and map directly to Erlang/OTP concepts.

| Primitive | OTP Equivalent | Description |
|---|---|---|
| `Proc<S,M>` | `spawn/3` | Lightweight process: virtual-thread mailbox + pure state handler |
| `ProcRef<S,M>` | Pid | Stable opaque handle that survives supervisor restarts |
| `Supervisor` | `supervisor` | Supervision tree: ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE with sliding restart window |
| `CrashRecovery` | "let it crash" | Supervised retry via isolated virtual threads |
| `StateMachine<S,E,D>` | `gen_statem` | State/event/data separation + sealed `Transition` hierarchy |
| `ProcessLink` | `link/1` | Bilateral crash propagation (`link/1`, `spawn_link/3`) |
| `Parallel` | `pmap` | Structured fan-out with fail-fast semantics (`StructuredTaskScope`) |
| `ProcessMonitor` | `monitor/2` | Unilateral DOWN notifications; does NOT kill the monitoring side |
| `ProcessRegistry` | `global` | Name table: `register/2`, `whereis/1`, `unregister/1`; auto-deregisters on exit |
| `ProcTimer` | `timer:send_after/3` | Timed message delivery: send_after, send_interval, cancel |
| `ExitSignal` | `EXIT` message | Exit signal record delivered when a process traps exits |
| `ProcSys` | `sys` module | `get_state`, `suspend`, `resume`, `statistics` — introspection without stopping |
| `ProcLib` | `proc_lib` | Startup handshake: `start_link` blocks until child calls `initAck()` |
| `EventManager<E>` | `gen_event` | Typed event manager: addHandler, notify, syncNotify, call |
| `Proc.trapExits` / `Proc.ask` | `process_flag(trap_exit)` / `gen_server:call` | Trap exits and timed synchronous calls on core `Proc` |

Also included: `Result<T, E>` — a sealed interface (`Success`/`Failure`) for railway-oriented programming. Use `Result.of(supplier)` to wrap throwing operations; supports `map`, `flatMap`, `fold`, `recover`, `peek`, `orElseThrow`.

## Quick Start

**Prerequisites:** Java 26 (GraalVM Community CE 25.0.2) and mvnd 2.0.0-rc-3.

```bash
# Clone
git clone https://github.com/seanchatmangpt/java-maven-template.git
cd java-maven-template

# Run unit tests
mvnd test

# Run all tests + quality checks
mvnd verify

# Format code (Google Java Format, AOSP style)
mvnd spotless:apply

# Run a single test class
mvnd test -Dtest=ProcTest

# Run a single integration test
mvnd verify -Dit.test=SupervisorIT

# Build fat JAR
mvnd package -Dshade

# Start JShell REPL
./mvnw jshell:run

# Faster builds with the Maven Daemon wrapper
bin/mvndw verify
```

## Code Generation (jgen / ggen)

`jgen` wraps [seanchatmangpt/ggen](https://github.com/seanchatmangpt/ggen) — a Rust-based code generation engine for Java 26 migration. 72 templates across 9 categories, backed by RDF ontologies and SPARQL queries.

**Install ggen:**
```bash
cargo install ggen-cli --features paas,ai
```

**Key commands:**
```bash
bin/jgen list                                    # List all 72 templates
bin/jgen list --category patterns                # Filter by category
bin/jgen generate -t core/record -n Person -p com.example.model
bin/jgen migrate --source ./legacy               # Detect legacy patterns
bin/jgen refactor --source ./legacy              # Full analysis: score + ranked commands
bin/jgen refactor --source ./legacy --plan       # Saves executable migrate.sh
bin/jgen refactor --source ./legacy --score      # Score-only modernization report
bin/jgen verify                                  # Compile + format + test check
```

**Template categories:**

| Category | Count | Highlights |
|---|---|---|
| `core/` | 14 | Records, sealed types, pattern matching, streams, gatherers |
| `patterns/` | 17 | All GoF patterns reimagined for modern Java |
| `testing/` | 12 | JUnit 5, AssertJ, jqwik, Instancio, ArchUnit, Awaitility |
| `concurrency/` | 5 | Virtual threads, structured concurrency, scoped values |
| `build/` | 7 | POM, Maven wrapper, Spotless, Surefire/Failsafe, CI/CD |
| `api/` | 6 | HttpClient, java.time, NIO.2, ProcessBuilder, collections |
| `modules/` | 4 | JPMS module-info, SPI, qualified exports |
| `error-handling/` | 3 | Result<T,E> railway, functional errors, Optional↔Result |
| `security/` | 4 | Modern crypto, encapsulation, validation, Jakarta EE migration |

## Innovation Engines

Six coordinated analysis engines in `org.acme.dogfood.innovation` power the automated refactor pipeline:

| Engine | Role |
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

## Dogfood Verification

The `org.acme.dogfood` package contains real Java code rendered from templates, proving every template produces compilable, testable output. One dogfood example exists per template category.

```bash
bin/dogfood generate           # Check all dogfood source files exist
bin/dogfood report             # Show template coverage report
bin/dogfood verify             # Full pipeline: check + compile + test + report
mvnd verify -Ddogfood          # Same via Maven Daemon (fastest)
./mvnw verify -Ddogfood        # Same via Maven wrapper
```

## Testing

**Test libraries:** JUnit 5, AssertJ (`implements WithAssertions`), jqwik (`@Property`/`@ForAll` property-based testing), Instancio (test data generation), ArchUnit (architecture rules), Awaitility (async assertions).

**Execution:** Full parallel execution via JUnit Platform — dynamic strategy, concurrent mode for both methods and classes (`src/test/resources/junit-platform.properties`).

**Test separation:**
- Unit tests: `*Test.java` — run by maven-surefire-plugin (`mvnd test`)
- Integration tests: `*IT.java` — run by maven-failsafe-plugin (`mvnd verify`)

```bash
mvnd test -Dtest=ProcTest              # single unit test class
mvnd verify -Dit.test=SupervisorIT     # single integration test class
```

## Documentation

- `docs/phd-thesis-otp-java26.md` — *"OTP 28 in Pure Java 26: A Formal Equivalence and Migration Framework for Enterprise-Grade Fault-Tolerant Systems"*: formal equivalence proofs, BEAM vs. JVM benchmarks under fault conditions, migration paths from Elixir, Go, Rust, and Scala/Akka.
- `CLAUDE.md` — full contributor and Claude Code configuration guide (hooks, permissions, proxy setup, build cache warm-up).

## Requirements

- **Java 26** — GraalVM Community CE 25.0.2 (Java 26 EA builds required for `--enable-preview`)
- **mvnd 2.0.0-rc-3** — Maven Daemon bundling Maven 4; download from the [Apache Maven Daemon releases](https://github.com/apache/maven-mvnd/releases/download/2.0.0-rc-3/maven-mvnd-2.0.0-rc-3-linux-amd64.tar.gz) page and symlink to `/usr/local/bin/mvnd`
- **Rust + Cargo** — required only for `ggen-cli` installation (`cargo install ggen-cli --features paas,ai`)
