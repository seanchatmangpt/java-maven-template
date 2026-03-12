# Explanations: Erlang-Java Mapping

Line-by-line translation of Erlang patterns to Java 26 JOTP.

## Overview

This guide provides side-by-side Erlang-to-Java mappings for every major OTP pattern, enabling developers to port Erlang/Elixir code to JOTP.

> **Status:** Coming Soon — Comprehensive pattern catalog with 30+ before/after examples
>
> **See Also:**
> - [OTP Equivalence](otp-equivalence.md) — Theoretical foundation
> - [How-To: Migrate from Erlang](../how-to/migrate-from-erlang.md) — Practical migration guide
> - [Reference: Glossary](../reference/glossary.md) — Erlang/Java terminology

## Quick Reference

### Spawning Processes

**Erlang:**
```erlang
Pid = spawn(Module, Function, Args).
```

**Java:**
```java
var proc = Proc.start(state -> msg -> handler(state, msg), initial);
```

### Message Passing

**Erlang:**
```erlang
Pid ! {increment, 5}.
Pid ! {get, ReplyPid}.
```

**Java:**
```java
sealed interface Msg permits IncrementMsg, GetMsg {}
record IncrementMsg(int value) implements Msg {}
record GetMsg(ProcRef<Integer, Msg> replyTo) implements Msg {}

proc.send(new IncrementMsg(5));
proc.send(new GetMsg(replyTo));
```

### Pattern Matching

**Erlang:**
```erlang
case Msg of
    {ok, Value} -> process(Value);
    {error, Reason} -> handle_error(Reason);
    _ -> default_handler()
end.
```

**Java:**
```java
switch(msg) {
    case OkMsg(int value) -> process(value);
    case ErrorMsg(String reason) -> handleError(reason);
    default -> defaultHandler();
}
```

## Topics Covered (Coming Soon)

- Process spawning and linking
- Message pattern hierarchies
- Supervisor strategy selection
- State machine patterns (gen_statem)
- Event handler registration (gen_event)
- Error handling and recovery
- Timing and delays
- Process registry lookups
- Distributed patterns (future)

---

**Examples Included (Coming Soon)**
- Counter server (basic process)
- Request-reply patterns
- Supervision tree design
- State machine with complex state
- Event broadcast system
- Error recovery strategies
- Message pipeline
- Batch processing

**Previous:** [Design Decisions](design-decisions.md) | **Next:** [Reference](../reference/)
