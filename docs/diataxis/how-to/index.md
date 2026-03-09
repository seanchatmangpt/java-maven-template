# How-To Guides — Java 26 OTP Library

These guides solve specific tasks. They assume you know what you want to do and
need the steps to do it. For concepts and background, see the **Explanation**
section; for a full API listing, see the **Reference** section.

---

## Supervision and Fault Tolerance

| Guide | Task |
|---|---|
| [Configure supervision strategies](supervision.md) | ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE |
| [Link processes for crash propagation](process-links.md) | Bilateral crash propagation with `ProcessLink` |
| [Monitor processes for DOWN notifications](process-monitor.md) | Unilateral monitoring with `ProcessMonitor` |
| [Trap exit signals](exit-signals.md) | `trapExits(true)` + `ExitSignal` mailbox messages |

## Process Management

| Guide | Task |
|---|---|
| [Register processes globally](process-registry.md) | Name-based lookup with `ProcessRegistry` |
| [Schedule timed messages](timers.md) | One-shot and recurring timers with `ProcTimer` |
| [Fan out with structured concurrency](parallel.md) | `Parallel.all(...)` fail-fast fan-out |

## Testing

| Guide | Task |
|---|---|
| [Write OTP-style tests](testing.md) | JUnit 5 + AssertJ + Awaitility patterns |

## Build and Tooling

| Guide | Task |
|---|---|
| [Run the dogfood verification pipeline](dogfood.md) | `bin/dogfood verify` and Maven integration |
| [Refactor legacy Java with jgen / RefactorEngine](jgen-refactor.md) | Automated migration to Java 26 patterns |

---

## Quick Reference: Common Imports

```java
import org.acme.*;          // Proc, ProcRef, Supervisor, ProcessLink, Parallel
import org.acme.*;          // ProcessMonitor, ProcessRegistry, ProcTimer, ExitSignal
import org.acme.*;          // ProcSys, ProcLib, EventManager, CrashRecovery
import org.acme.*;          // StateMachine, Result
```

## Prerequisites for All Guides

- Java 26 with `--enable-preview` (GraalVM Community CE 25.0.2 or later)
- JPMS module `org.acme` on the module path
- Build: `mvnd test` (unit) / `mvnd verify` (unit + integration + quality checks)
- All code examples compile with `--enable-preview --release 26`
