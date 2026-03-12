# How-To: Migrate from Erlang

This guide helps you port Erlang/Elixir code to JOTP.

## Overview

JOTP provides Java equivalents for all major OTP patterns, enabling smooth migration from Erlang, Elixir, Go, and Rust. This guide covers common patterns and their translations.

> **Status:** Coming Soon — Full mapping of Erlang patterns to JOTP with before/after code examples
>
> **See Also:**
> - [Explanations: Erlang-Java Mapping](../explanations/erlang-java-mapping.md) — Formal pattern equivalence
> - [Explanations: OTP Equivalence](../explanations/otp-equivalence.md) — Theoretical foundation
> - [Reference: API Overview](../reference/api.md) — All 15 JOTP primitives

### Quick Reference

| Erlang/OTP | JOTP Java | Semantics |
|-----------|-----------|-----------|
| `spawn/3` | `Proc.start()` | Lightweight process |
| `spawn_link/3` | `ProcessLink.link()` | Bilateral crash propagation |
| `supervisor` | `Supervisor` | Hierarchical restart |
| `gen_statem` | `StateMachine<S,E,D>` | Complex state machines |
| `gen_event` | `EventManager<E>` | Event broadcast |
| `process_flag(trap_exit, true)` | `Proc.trapExits(true)` | Exit signal handling |

## Topics Covered (Coming Soon)

- Erlang function → Java `Proc.start()` handler
- Erlang message tuples → Java sealed records
- Erlang pattern matching → Java pattern switches
- Hot code reloading → JOTP versioning strategies
- Distributed Erlang → Future JOTP networking
- Performance tuning for Erlang-style concurrency

---

**Previous:** [Build Supervision Trees](build-supervision-trees.md) | **Next:** [Explanations](../explanations/)
