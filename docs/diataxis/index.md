# Java 26 OTP Library — Documentation

This documentation follows the [Diátaxis](https://diataxis.fr) framework: four distinct modes of documentation, each serving a different need.

---

## [Tutorials](tutorials/index.md) — Learn by doing

Guided, hands-on lessons that take you from zero to working code. Start here if you are new to this library.

| Tutorial | What you will build |
|---|---|
| [Hello, Proc](tutorials/01-hello-proc.md) | Spawn your first virtual-thread process |
| [Supervised Service](tutorials/02-supervised-service.md) | A fault-tolerant service with ONE_FOR_ONE supervision |
| [State Machine](tutorials/03-state-machine.md) | A traffic-light StateMachine with sealed transitions |
| [Railway Error Handling](tutorials/04-result-railway.md) | A validation pipeline using `Result<T,E>` |
| [Code Generation with jgen](tutorials/05-jgen-generate.md) | Scaffold a complete CRUD service in one command |

---

## [How-to Guides](how-to/index.md) — Accomplish specific tasks

Practical, step-by-step directions for real-world tasks. Use these when you know what you want to do.

| Guide | Task |
|---|---|
| [Configure Supervision Strategies](how-to/supervision.md) | ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE |
| [Register Processes Globally](how-to/process-registry.md) | `ProcessRegistry.register`, `whereis`, auto-deregister |
| [Link Processes](how-to/process-links.md) | Bilateral crash propagation with `ProcessLink` |
| [Monitor Processes](how-to/process-monitor.md) | Unilateral DOWN notifications |
| [Use Process Timers](how-to/timers.md) | `ProcTimer.sendAfter`, `sendInterval`, `cancel` |
| [Trap Exit Signals](how-to/exit-signals.md) | `trapExits(true)` and `ExitSignal` mailbox messages |
| [Fan-out with Parallel](how-to/parallel.md) | Structured fan-out with fail-fast semantics |
| [Run the Dogfood Pipeline](how-to/dogfood.md) | `bin/dogfood verify` end-to-end |
| [Refactor Legacy Code](how-to/jgen-refactor.md) | `bin/jgen refactor --source ./legacy --plan` |
| [Write OTP-Style Tests](how-to/testing.md) | JUnit 5 + AssertJ + Awaitility patterns |

---

## [Reference](reference/index.md) — Look up technical details

Precise, complete technical descriptions. Use these when you need to know exactly how something works.

| Reference | Covers |
|---|---|
| [All 15 OTP Primitives](reference/primitives.md) | Quick-reference table for every primitive |
| [Proc\<S,M\>](reference/proc.md) | Full API: tell, ask, trapExits, stop, onCrash |
| [Supervisor](reference/supervisor.md) | Strategies, restart window, child specs |
| [Result\<T,E\>](reference/result.md) | map, flatMap, fold, recover, orElseThrow |
| [jgen Template Catalog](reference/jgen-templates.md) | All 72 templates across 9 categories |
| [Build & Toolchain](reference/build.md) | mvnd, spotless, surefire, failsafe, dogfood |

---

## [Explanation](explanation/index.md) — Understand the concepts

Conceptual discussion that illuminates the why behind the library. Read these to deepen your understanding.

| Article | Topic |
|---|---|
| [Why OTP in Java?](explanation/why-otp-java.md) | The case for Erlang's fault model on the JVM |
| [Virtual Threads as Processes](explanation/virtual-threads.md) | How Project Loom maps to `spawn/3` |
| [Let It Crash](explanation/let-it-crash.md) | The philosophy and its Java expression |
| [Supervision Trees](explanation/supervision-trees.md) | How trees of supervisors contain failure |
| [Java 26 Features](explanation/java26-features.md) | Sealed types, records, pattern matching, virtual threads |
| [Formal OTP–Java Equivalence](explanation/formal-equivalence.md) | The twelve-pillar proof |

---

*For the full PhD-level treatment see [docs/phd-thesis-otp-java26.md](../phd-thesis-otp-java26.md). For project setup see [CLAUDE.md](../../CLAUDE.md).*
