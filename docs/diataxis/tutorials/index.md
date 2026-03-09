# Tutorials

> **Diátaxis — Learning-oriented**
> Tutorials are designed to help you learn by doing. You follow guided steps, build something real, and finish each tutorial with a working program and a concrete new skill. The focus is on the experience of learning, not on completeness of reference.

---

## What these tutorials cover

This tutorial set introduces the **Java 26 OTP library** (`org.acme`) — a set of fifteen Erlang/OTP-inspired primitives built on virtual threads and modern Java 26 idioms.

By the end of the series you will know how to:

- Spawn and communicate with lightweight virtual-thread processes
- Build fault-tolerant services with supervision trees
- Model domain workflows with event-driven state machines
- Handle errors expressively using railway-oriented programming
- Scaffold production-ready services in seconds with the `jgen` code generator

---

## Prerequisites for all tutorials

Before starting any tutorial, make sure you have:

- **Java 26** (GraalVM Community CE 25.0.2 or later) — check with `java -version`
- **mvnd** (Maven Daemon 2.0.0-rc-3) installed and on your `PATH`
- The project checked out and buildable: running `mvnd test` should print `BUILD SUCCESS`
- A basic familiarity with Java records, sealed interfaces, and `switch` expressions

If `mvnd test` does not pass yet, follow the [Getting Started](../../getting-started.md) guide first.

---

## The tutorials

| # | Tutorial | What you build | Core concept |
|---|----------|----------------|--------------|
| 1 | [Hello, Proc](01-hello-proc.md) | A counter process you can talk to | `Proc`, `ProcRef`, virtual-thread mailbox |
| 2 | [Supervised Service](02-supervised-service.md) | A resilient counter that restarts on crash | `Supervisor`, ONE_FOR_ONE strategy |
| 3 | [Traffic-Light StateMachine](03-state-machine.md) | A traffic-light controller with timed transitions | `StateMachine`, sealed `Transition` |
| 4 | [Railway Error Handling](04-result-railway.md) | A validated user-registration pipeline | `Result<T,E>`, `map`, `flatMap`, `fold` |
| 5 | [Scaffold with jgen](05-jgen-generate.md) | A complete CRUD service in one command | `jgen generate`, template categories |

Work through them in order — each tutorial builds on vocabulary introduced in the previous one. You can, however, jump to any individual tutorial if you are already comfortable with the preceding concepts.

---

## How to run the code

Every tutorial provides complete, self-contained Java source. Place each file in the path shown at the top of the code block (always under `src/main/java/` or `src/test/java/`) and run:

```bash
mvnd test -Dtest=<ClassName>Test      # run a single unit test
mvnd verify -Dit.test=<ClassName>IT   # run a single integration test
```

Or run the full suite at any time:

```bash
mvnd test        # unit tests only
mvnd verify      # unit + integration tests + quality checks
```

---

## A note on Java 26 idioms

All examples in these tutorials use Java 26 with `--enable-preview`. You will see:

- **Records** for immutable data carriers
- **Sealed interfaces** with **pattern-matching `switch` expressions** for exhaustive branching
- **`var`** for local type inference
- **Virtual threads** (inside `Proc`) for cheap concurrency without callbacks

If any syntax looks unfamiliar, the [Java 26 Language Guide](https://openjdk.org/projects/amber/) is a useful companion.

---

*Next: [Tutorial 1 — Hello, Proc](01-hello-proc.md)*
