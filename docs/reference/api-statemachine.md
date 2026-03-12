# Reference: StateMachine<S,E,D> API

Complete documentation of the complex state machine API.

## Overview

`StateMachine<S,E,D>` implements Erlang's `gen_statem` behavior for complex state/event/data handling with pattern matching.

> **Status:** Coming Soon — Complete method signatures, transition semantics, and complex state examples
>
> **See Also:**
> - [API Overview](api.md) — All 15 primitives
> - [How-To: Create Lightweight Processes](../how-to/create-lightweight-processes.md) — State machine patterns
> - [Explanations: OTP Equivalence](../explanations/otp-equivalence.md) — Formal semantics

## Quick Reference

```java
record Transition<S, D>(S state, D data) {}

var sm = StateMachine.create(
    (state, event, data) -> switch(state) {
        case IDLE -> {
            if (event instanceof StartEvent)
                yield new Transition(RUNNING, data);
            else
                yield new Transition(IDLE, data);
        }
        case RUNNING -> {
            if (event instanceof StopEvent)
                yield new Transition(IDLE, data);
            else
                yield new Transition(RUNNING, data);
        }
        default -> new Transition(state, data);
    },
    IDLE,      // initial state
    new Data() // initial data
);

sm.send(new StartEvent());
var result = sm.ask(QUERY_EVENT, Duration.ofSeconds(1));
```

## Topics Covered (Coming Soon)

- State/event/data triple semantics
- Transition record structure and side effects
- Event ordering and processing guarantees
- State change notifications
- Data persistence across transitions
- Complex state patterns (substates, orthogonal states)
- Internal events and cascading transitions
- Timeout handling in state machines
- Testing state machine behavior

---

**Previous:** [Supervisor API](api-supervisor.md) | **Next:** [Configuration](configuration.md)
