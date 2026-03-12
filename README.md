# JOTP — Java OTP Framework

[![Maven Build](https://github.com/seanchatmangpt/jotp/workflows/Maven%20Build/badge.svg)](https://github.com/seanchatmangpt/jotp/actions)

**JOTP** is a production-ready Java 26 framework implementing all 15 OTP (Erlang/OTP) primitives, bringing battle-tested concurrency patterns and fault tolerance to the JVM.

**🌐 [View Full Documentation](https://github.com/seanchatmangpt/jotp/docs)**

## Core Capabilities

**🎯 15 OTP Primitives** — All core Erlang/OTP patterns implemented in pure Java 26:
- `Proc<S,M>` — Lightweight processes with virtual-thread mailboxes
- `Supervisor` — Supervision trees with ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE strategies
- `StateMachine<S,E,D>` — gen_statem-style state machines with event routing
- `EventManager<E>` — gen_event pattern for decoupled event handlers
- `ProcessRegistry` — Global name registry for process discovery
- `ProcessMonitor` — Unilateral process monitoring with DOWN signals
- `ProcessLink` — Bilateral process linking with crash propagation
- `ProcTimer` — Timed message delivery (send_after, send_interval)
- `Parallel` — Structured concurrency with fail-fast semantics
- Plus 5 more: `Result<T,E>` error handling, `CrashRecovery`, `ProcSys` introspection, `ProcLib` startup, `ExitSignal` handling

**⚙️ Production-Ready Framework**
- Java 26 with preview features (`--enable-preview`)
- JPMS module: `io.github.seanchatmangpt.jotp`
- 100+ comprehensive tests (unit + integration, JUnit 5, parallel execution)
- Spotless code formatting (Google Java Format, AOSP style)
- Maven Daemon (mvnd) for fast builds
- GitHub Actions CI/CD ready

**📚 Comprehensive Documentation** — Diataxis-organized:
- Tutorials: Getting started, first process, virtual threads
- How-to Guides: Common patterns, error handling, testing
- Explanations: Architecture, concurrency model, OTP equivalence
- References: Full API docs, configuration, glossary

## Installation & Usage

Add JOTP to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

```java
import io.github.seanchatmangpt.jotp.*;

// Spawn a lightweight process
var proc = Proc.spawn((mailbox, state) -> {
    var message = mailbox.receive();
    System.out.println("Received: " + message);
    return state;
}, initialState);

// Send a message
proc.send("Hello, JOTP!");
```

## Build & Development Commands

```bash
./mvnw clean compile      # Compile with preview features
./mvnw test               # Run unit tests (parallel)
./mvnw verify             # All tests + quality checks + dogfood
./mvnw spotless:apply     # Auto-format code
./mvnw verify -Ddogfood   # Generate and verify example code
bin/mvndw verify          # Same as above, but faster (Maven Daemon)
```

## Requirements

- **Java 26** (with preview features)
- **Maven 4** (or use included Maven Wrapper: `./mvnw`)
- Optional: `mvnd` (Maven Daemon) for faster builds

## Documentation

- **📖 [Tutorials](docs/tutorials/)** — Step-by-step introduction to JOTP
- **🔧 [How-to Guides](docs/how-to/)** — Solve specific problems
- **💡 [Explanations](docs/explanations/)** — Understand architecture & design
- **📚 [References](docs/reference/)** — API docs, configuration, glossary
- **🎓 [PhD Thesis](docs/phd-thesis/)** — Formal OTP ↔ Java 26 equivalence

## Getting Help

- Check the [tutorials](docs/tutorials/) for learning-oriented guides
- Browse [how-to guides](docs/how-to/) for task-specific solutions
- Report issues: [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)

---

**Package:** `io.github.seanchatmangpt:jotp` • **Module:** `io.github.seanchatmangpt.jotp` • **Java:** 26+ with preview