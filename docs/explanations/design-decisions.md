# Explanations: Design Decisions

Why JOTP made specific architectural choices.

## Overview

Every architecture involves trade-offs. This document explains the reasoning behind JOTP's major decisions.

> **Status:** Coming Soon — Detailed rationale for API choices, including alternatives considered and rejected
>
> **See Also:**
> - [Architecture Overview](architecture-overview.md) — System overview
> - [OTP Equivalence](otp-equivalence.md) — Formal comparison to Erlang
> - [Reference: API Overview](../reference/api.md) — Final API design

## Key Design Questions (Coming Soon)

- **Why sealed interfaces for messages?** Type-safe pattern matching vs. generic Object
- **Why immutable state?** Concurrency safety vs. mutable objects
- **Why Java records?** Lightweight value types for messages
- **Why no actors?** Rejected Akka-style API in favor of pure functions
- **Why virtual threads?** Cost-benefit vs. callback-based approaches
- **Why no distributed by default?** Complexity vs. single-machine guarantee
- **Why Result<T,E>?** Railway-oriented vs. exception-based error handling

## Decision Log

| Decision | Rationale | Alternatives Rejected |
|----------|-----------|----------------------|
| Use sealed interfaces | Exhaustive pattern matching | Open interfaces with instanceof chains |
| Immutable state | Lock-free concurrency | Synchronized mutable state |
| Pure handler functions | Compositional, testable | Object-oriented Process class |
| Fire-and-forget + ask() | Explicit async/sync intent | All blocking (performance) |
| Result<T,E> type | Functional error handling | Checked exceptions |
| 15 primitives not more | Sufficient for all patterns | 20+ specialized classes |
| No built-in distribution | Complexity vs. clarity | Embedded RMI/gRPC |

---

**Topics Covered (Coming Soon)**

- API design philosophy
- Performance vs. simplicity trade-offs
- Comparison to other frameworks (Akka, Quasar, etc.)
- Feature prioritization and roadmap
- Why Java 26 specifically

**Previous:** [Concurrency Model](concurrency-model.md) | **Next:** [Erlang-Java Mapping](erlang-java-mapping.md)
