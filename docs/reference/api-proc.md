# Reference: Proc<S,M> API

Complete documentation of the lightweight process API.

## Overview

`Proc<S,M>` is JOTP's core abstraction: a lightweight, virtual-thread-based process that maintains isolated state and processes messages sequentially.

> **Status:** Coming Soon — Complete method signatures, behavior specifications, and examples
>
> **See Also:**
> - [API Overview](api.md) — All 15 primitives
> - [How-To: Create Lightweight Processes](../how-to/create-lightweight-processes.md) — Usage patterns
> - [Tutorial: Your First Process](../tutorials/02-first-process.md) — Hands-on introduction

## Quick Reference

```java
// Create process
var proc = Proc.start(state -> msg -> newState, initial);

// Send message (async)
proc.send(message);

// Request-reply (sync with timeout)
var response = proc.ask(replyTo -> requestMsg, Duration.ofSeconds(1));

// Check if alive
boolean alive = proc.isAlive();

// Terminate
proc.exit("shutdown");

// Control exit signal handling
proc.trapExits(true);
```

## Topics Covered (Coming Soon)

- Complete method signatures with parameter types
- Handler function semantics and guarantees
- Message ordering and queue behavior
- Timeout handling and exceptions
- Process termination semantics
- Exit signal handling with trapExits()
- Performance characteristics and tuning
- Thread safety guarantees
- Memory model and visibility

---

**Previous:** [API Overview](api.md) | **Next:** [Supervisor API](api-supervisor.md)
