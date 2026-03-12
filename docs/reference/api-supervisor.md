# Reference: Supervisor API

Complete documentation of the supervision tree API.

## Overview

`Supervisor` manages child processes with automatic restart on failure. It implements three restart strategies: ONE_FOR_ONE, ONE_FOR_ALL, and REST_FOR_ONE.

> **Status:** Coming Soon — Complete method signatures, restart behavior, and failure recovery examples
>
> **See Also:**
> - [API Overview](api.md) — All 15 primitives
> - [How-To: Build Supervision Trees](../how-to/build-supervision-trees.md) — Advanced patterns
> - [Tutorial: Supervision Basics](../tutorials/04-supervision-basics.md) — Introduction

## Quick Reference

```java
// ONE_FOR_ONE: restart only the failed child
var sup = Supervisor.oneForOne()
    .add("child1", Child1::create)
    .add("child2", Child2::create)
    .build();

// ONE_FOR_ALL: restart all children if any fails
var sup = Supervisor.oneForAll()
    .add("child1", Child1::create)
    .add("child2", Child2::create)
    .build();

// REST_FOR_ONE: restart failed child and all dependents
var sup = Supervisor.restForOne()
    .add("child1", Child1::create)
    .add("child2", Child2::create)
    .build();

// Look up child by name
var child = sup.whereis("child1");

// List all registered children
var children = sup.registered();
```

## Topics Covered (Coming Soon)

- Supervisor strategy semantics (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- Restart window and maximum restart frequency
- Child shutdown order and termination sequence
- Supervisor-of-supervisors (hierarchical trees)
- Dynamic child addition and removal
- Supervisor lifecycle and shutdown behavior
- Monitoring supervisor health
- Error handling during child initialization

---

**Previous:** [Proc API](api-proc.md) | **Next:** [StateMachine API](api-statemachine.md)
