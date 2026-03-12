# JOTP (Joe's OTP) Documentation

Welcome to **JOTP** — Joe Armstrong's Erlang/OTP patterns implemented in pure **Java 26**. JOTP provides production-ready, fault-tolerant concurrency primitives without the BEAM VM, enabling migration from Erlang, Elixir, Go, and other concurrent languages to Java 26.

## What is JOTP?

JOTP implements **15 Erlang/OTP architectural primitives** in `io.github.seanchatmangpt.jotp`, proving that Java 26 can express all meaningful OTP patterns idiomatically. With virtual threads (JEP 444), sealed types, structured concurrency, and pattern matching, Java 26 has everything OTP required — and now delivers it to the world's largest developer community.

---

## Documentation Navigation

JOTP documentation follows the **[Diataxis framework](https://diataxis.fr/)**, organized into four complementary information types:

### 1. **[Tutorials](tutorials/)** — Learning-Oriented

Step-by-step guides to learn JOTP hands-on. Start here if you're new to JOTP or concurrent programming.

- **[Getting Started](tutorials/01-getting-started.md)** — Clone, build, and run your first JOTP application
- **[Your First Process](tutorials/02-first-process.md)** — Create and run a simple Proc<S,M>
- **[Virtual Threads](tutorials/03-virtual-threads.md)** — Understanding lightweight concurrency in Java
- **[Supervision Basics](tutorials/04-supervision-basics.md)** — Build your first supervision tree

### 2. **[How-To Guides](how-to/)** — Task-Oriented

Practical solutions to specific problems. Use these when you know what you want to do but need guidance.

- **[Create Lightweight Processes](how-to/create-lightweight-processes.md)** — Build reusable Proc<S,M> abstractions
- **[Send & Receive Messages](how-to/send-receive-messages.md)** — Implement message-passing patterns
- **[Handle Process Failures](how-to/handle-process-failures.md)** — Use CrashRecovery and supervision
- **[Test Concurrent Code](how-to/test-concurrent-code.md)** — Test JOTP applications with JUnit 5
- **[Build Supervision Trees](how-to/build-supervision-trees.md)** — Implement ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE
- **[Migrate from Erlang](how-to/migrate-from-erlang.md)** — Port Erlang/Elixir code to Java 26

### 3. **[Explanations](explanations/)** — Understanding-Oriented

Deep conceptual guides. Use these to understand *why* JOTP works the way it does.

- **[Architecture Overview](explanations/architecture-overview.md)** — JOTP system design at 30,000 feet
- **[OTP Equivalence](explanations/otp-equivalence.md)** — Formal proof that Java 26 equals OTP 28
- **[Concurrency Model](explanations/concurrency-model.md)** — How JOTP uses virtual threads and message passing
- **[Design Decisions](explanations/design-decisions.md)** — Why JOTP made specific architectural choices
- **[Erlang-Java Mapping](explanations/erlang-java-mapping.md)** — Line-by-line Erlang ↔ Java equivalence

### 4. **[Reference](reference/)** — Information-Oriented

Technical lookup documentation. Use these when you know what you need and want exact details.

- **[API Overview](reference/api.md)** — All 15 JOTP primitives with signatures and examples
- **[Proc<S,M>](reference/api-proc.md)** — Lightweight process API
- **[Supervisor](reference/api-supervisor.md)** — Supervision tree API
- **[StateMachine<S,E,D>](reference/api-statemachine.md)** — State machine API
- **[Configuration](reference/configuration.md)** — JOTP runtime settings
- **[Glossary](reference/glossary.md)** — Key terms and concepts
- **[Troubleshooting](reference/troubleshooting.md)** — Common issues and solutions

### 5. **[Examples](examples/)** — Code Snippets

Working code examples for quick reference and copy-paste.

- **[Basic Process Example](examples/basic-process-example.md)** — Hello world for Proc<S,M>
- **[Supervision Tree Example](examples/supervision-tree-example.md)** — Multi-level supervisor
- **[Message Passing Example](examples/message-passing-example.md)** — Request-reply patterns

### 6. **[PhD Thesis](phd-thesis/)** — Formal Research

Academic reference material and research contributions.

- **[OTP 28 in Pure Java 26](phd-thesis/otp-28-java26.md)** — Formal equivalence proof with benchmarks

---

## Quick Start

```bash
# Clone JOTP
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp

# Build and test
./mvnw test
./mvnw verify

# Run your first Proc
./mvnw jshell:run
# In JShell:
# var proc = Proc.start(state -> msg -> state, 0);
# proc.send("hello");
```

---

## The 15 JOTP Primitives

| Primitive | Java Class | OTP Equivalent | Use Case |
|-----------|------------|----------------|----------|
| Lightweight Processes | `Proc<S,M>` | `spawn/3` | Core concurrent unit |
| Process References | `ProcRef<S,M>` | Pid | Stable handle across restarts |
| Supervision Trees | `Supervisor` | `supervisor` | Hierarchical restart strategy |
| Crash Recovery | `CrashRecovery` | "let it crash" | Isolated virtual thread retry |
| State Machine | `StateMachine<S,E,D>` | `gen_statem` | Complex state + event handling |
| Process Links | `ProcessLink` | `link/1` | Bilateral crash propagation |
| Parallel Execution | `Parallel` | `pmap` | Structured fan-out with fail-fast |
| Process Monitors | `ProcessMonitor` | `monitor/2` | Unilateral DOWN notifications |
| Process Registry | `ProcessRegistry` | `register/2` | Global name table |
| Timers | `ProcTimer` | `timer:send_after/3` | Timed message delivery |
| Exit Signals | `ExitSignal` | exit signals | Crash notification record |
| Process Introspection | `ProcSys` | `sys:get_state/1` | Runtime process inspection |
| Startup Handshake | `ProcLib` | `proc_lib:start_link/3` | Synchronous start guarantee |
| Event Manager | `EventManager<E>` | `gen_event` | Typed event broadcast |
| Railway Error Handling | `Result<T,E>` | `{:ok, val} \| {:error, reason}` | Functional error handling |

---

## Repository Structure

```
jotp/
├── docs/                             # Documentation (Diataxis)
│   ├── index.md                      # This file
│   ├── tutorials/                    # 01-04: Getting started
│   ├── how-to/                       # Problem-oriented guides
│   ├── explanations/                 # Conceptual deep dives
│   ├── reference/                    # API lookup
│   ├── examples/                     # Code snippets
│   └── phd-thesis/                   # Formal research
├── src/main/java/io/github/seanchatmangpt/jotp/
│   ├── Proc.java                     # Lightweight process
│   ├── Supervisor.java               # Supervision trees
│   ├── EventManager.java             # Event broadcast
│   ├── Result.java                   # Railway error handling
│   └── ... (15 primitives total)
├── src/test/java/io/github/seanchatmangpt/jotp/
│   └── ... (comprehensive test suite)
├── templates/java/                   # 72 code generation templates
├── schema/*.ttl                      # OWL ontologies
├── queries/*.rq                      # SPARQL migrations
├── bin/
│   ├── jgen                          # Code generation CLI
│   └── mvndw                         # Maven Daemon wrapper
└── pom.xml                           # Maven configuration
```

---

## Quick Links

| Resource | Description |
|----------|-------------|
| [GitHub](https://github.com/seanchatmangpt/jotp) | Source code |
| [Maven Central](https://central.sonatype.com/artifact/io.github.seanchatmangpt/jotp) | Artifact repository |
| [Issues](https://github.com/seanchatmangpt/jotp/issues) | Bug reports & feature requests |
| [Discussions](https://github.com/seanchatmangpt/jotp/discussions) | Community Q&A |

---

## Build & Development

```bash
# Run tests
./mvnw test

# Verify all quality checks
./mvnw verify

# Format code (Spotless + Google Java Format)
./mvnw spotless:apply

# Build fat JAR
./mvnw package -Dshade

# Interactive JShell
./mvnw jshell:run
```

### Maven Daemon (Faster Builds)

```bash
# Use mvnd for 2-3x faster builds
bin/mvndw verify
bin/mvndw test -Dtest=ProcTest
```

---

## Java Requirements

- **JDK 26** (GraalVM Community CE 25.0.2 or later)
- **Maven 4** (via mvnd 2.0.0-rc-3)
- `--enable-preview` enabled for sealed types, pattern matching, virtual threads

---

## Architecture Highlights

**JOTP is a Java 26 JPMS library** with these features:

- **Virtual Threads**: Lightweight concurrency via Java 21+ (JEP 444)
- **Sealed Types**: Safe pattern matching for state and events
- **Structured Concurrency**: Fail-fast task scopes (JEP 453)
- **Railway-Oriented Programming**: `Result<T,E>` for functional error handling
- **OTP Patterns**: 15 primitives implementing Joe Armstrong's architecture
- **Code Generation**: 72 templates + `RefactorEngine` for automated migration
- **Comprehensive Testing**: JUnit 5, AssertJ, jqwik, ArchUnit, Awaitility

---

## Roadmap

| Phase | Status | Description |
|-------|--------|-------------|
| **Core Primitives** | ✅ Complete | All 15 OTP primitives implemented |
| **Documentation** | 🔄 In Progress | Diataxis-based learning paths |
| **Code Generation** | ✅ Complete | 72 templates + RefactorEngine |
| **Performance Tuning** | 📋 Planned | Virtual thread optimization |
| **Distributed JOTP** | 📋 Planned | Multi-node message passing |

---

## Contributing

JOTP welcomes contributions! See [CONTRIBUTING.md](https://github.com/seanchatmangpt/jotp/blob/main/CONTRIBUTING.md) for guidelines.

---

## License

JOTP is distributed under the [Unlicense](https://unlicense.org/) — public domain, no restrictions.

---

**Last Updated:** March 2026
**Repository:** [seanchatmangpt/jotp](https://github.com/seanchatmangpt/jotp)
