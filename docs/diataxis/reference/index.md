# Reference — Java 26 OTP Library

**Package:** `org.acme`
**Module:** Java 26 JPMS (`module org.acme { ... }`)
**JDK:** GraalVM Community CE 25.0.2 (Java 26 EA, `--enable-preview` required)
**Build tool:** `mvnd` (Maven Daemon 2.0.0-rc-3, bundling Maven 4)

---

## Reference Pages

| Page | Contents |
|---|---|
| [primitives.md](primitives.md) | All 15 OTP primitives — quick-reference table |
| [proc.md](proc.md) | `Proc<S,M>` and `ProcRef<S,M>` full API |
| [supervisor.md](supervisor.md) | `Supervisor` full API — strategies, restart window, child specs |
| [result.md](result.md) | `Result<T,E>` full API |
| [jgen-templates.md](jgen-templates.md) | `jgen` template catalog — all 72 templates across 9 categories |
| [build.md](build.md) | Build and toolchain reference — `mvnd`, Spotless, Surefire, Failsafe, `module-info` |

---

## Package Structure

```
org.acme
├── Proc<S,M>             — lightweight process (virtual-thread mailbox)
├── ProcRef<S,M>          — stable opaque handle (survives supervisor restarts)
├── Supervisor            — supervision tree (ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE)
├── CrashRecovery         — "let it crash" + supervised retry
├── StateMachine<S,E,D>   — gen_statem: state/event/data separation
├── ProcessLink           — bilateral crash propagation
├── Parallel              — structured fan-out (StructuredTaskScope)
├── ProcessMonitor        — unilateral DOWN notifications
├── ProcessRegistry       — global name table
├── ProcTimer             — timed message delivery
├── ExitSignal            — exit signal record (trap_exit)
├── ProcSys               — sys module: introspection without stopping
├── ProcLib               — proc_lib startup handshake
├── EventManager<E>       — gen_event: typed event manager
└── Result<T,E>           — sealed Success/Failure railway type

org.acme.dogfood          — template-rendered examples (compiled, tested)
org.acme.dogfood.innovation
├── OntologyMigrationEngine
├── ModernizationScorer
├── TemplateCompositionEngine
├── BuildDiagnosticEngine
├── LivingDocGenerator
└── RefactorEngine        — orchestrator
```

---

## Module Declaration

```java
module org.acme {
    exports org.acme;
    exports org.acme.dogfood;
    exports org.acme.dogfood.innovation;

    requires java.base;
    // preview features enabled via --enable-preview at compile and runtime
}
```

---

## Naming Conventions

| Erlang/OTP term | Java equivalent |
|---|---|
| `spawn/3` | `Proc.of(initialState, handler)` |
| `Pid` | `ProcRef<S,M>` |
| `gen_server:call` | `ProcRef.ask(msg)` |
| `gen_server:cast` | `ProcRef.tell(msg)` |
| `supervisor` | `Supervisor` |
| `gen_statem` | `StateMachine<S,E,D>` |
| `gen_event` | `EventManager<E>` |
| `proc_lib` | `ProcLib` |
| `sys` | `ProcSys` |
| `timer:send_after` | `ProcTimer.sendAfter(...)` |
| `process_flag(trap_exit, true)` | `ProcRef.trapExits(true)` |
| `link/1` | `ProcessLink.link(a, b)` |
| `monitor/2` | `ProcessMonitor.monitor(target)` |
| `register/2` | `ProcessRegistry.register(name, ref)` |

---

## Java Version Requirements

| Feature | Minimum |
|---|---|
| Virtual threads | Java 21 |
| `StructuredTaskScope` | Java 21 (preview), stable Java 25 |
| Sealed classes | Java 17 |
| Records | Java 16 |
| Pattern matching in `switch` | Java 21 (stable) |
| String templates | Java 21 (preview) |
| Foreign Function & Memory API | Java 22 (stable) |
| `--enable-preview` | Required for Java 26 features |
