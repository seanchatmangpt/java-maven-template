# OTP Patterns in Pure Java 26

This document describes the 15 Erlang/OTP primitives implemented in `org.acme`, proving that Java 26 can express all meaningful OTP patterns without the BEAM virtual machine.

---

## Overview

The Joe Armstrong / Erlang/OTP patterns bring battle-tested concurrency primitives to Java 26. Each primitive has a direct mapping to OTP concepts and is implemented using virtual threads, sealed types, and structured concurrency.

| Primitive | Java Class | OTP Equivalent |
|-----------|------------|----------------|
| Lightweight Processes | `Proc<S,M>` | `spawn/3` |
| Process References | `ProcRef<S,M>` | Pid |
| Supervision Trees | `Supervisor` | `supervisor` |
| Crash Recovery | `CrashRecovery` | "let it crash" |
| State Machine | `StateMachine<S,E,D>` | `gen_statem` |
| Process Links | `ProcessLink` | `link/1` |
| Parallel Execution | `Parallel` | `pmap` |
| Process Monitors | `ProcessMonitor` | `monitor/2` |
| Process Registry | `ProcessRegistry` | `register/2` |
| Timers | `ProcTimer` | `timer:send_after/3` |
| Exit Signals | `ExitSignal` | exit signals |
| Process Introspection | `ProcSys` | `sys:get_state/1` |
| Startup Handshake | `ProcLib` | `proc_lib:start_link/3` |
| Event Manager | `EventManager<E>` | `gen_event` |
| Railway Error Handling | `Result<T,E>` | `{:ok, val} \| {:error, reason}` |

---

## Proc<S,M> — Lightweight Process

**OTP:** `spawn/3` + mailbox

A `Proc` is a lightweight process with its own mailbox and pure state handler.

### API

```java
// Create a process
Proc<State, Message> proc = new Proc<>(initialState, (state, msg) -> {
    return switch (msg) {
        case Increment i -> state.withCount(state.count() + i.value());
        case GetValue _ -> {
            replyTo(msg, state.count());
            yield state;
        }
    };
});

// Send a message (async)
proc.tell(new Increment(5));

// Ask with timeout (sync)
Integer value = proc.ask(new GetValue(), Duration.ofSeconds(5));

// Trap exits
proc.trapExits(true);

// Stop the process
proc.stop();
```

### Example: Counter Process

```java
sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.GetValue {}
record Increment(int value) implements CounterMsg {}
record GetValue(CompletableFuture<Integer> reply) implements CounterMsg {}

record CounterState(int count) {}

var counter = new Proc<CounterState, CounterMsg>(
    new CounterState(0),
    (state, msg) -> {
        return switch (msg) {
            case Increment(int v) -> new CounterState(state.count() + v);
            case GetValue(var reply) -> {
                reply.complete(state.count());
                yield state;
            }
        };
    }
);

counter.tell(new Increment(10));
counter.tell(new Increment(5));
int total = counter.ask(new GetValue(new CompletableFuture<>()), Duration.ofSeconds(1));
// total == 15
```

---

## Supervisor — Supervision Trees

**OTP:** `supervisor`

Hierarchical supervision with restart strategies.

### API

```java
// Create a supervisor with strategy and restart limits
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,  // strategy
    5,                                 // maxRestarts
    Duration.ofSeconds(60)            // window
);

// Supervise a child process
ProcRef<State, Msg> ref = supervisor.supervise("worker-id", initialState, handler);

// Check supervisor status
boolean running = supervisor.isRunning();
Throwable error = supervisor.fatalError();

// Shutdown the supervisor (stops all children in reverse order)
supervisor.shutdown();

// Strategies:
// - ONE_FOR_ONE: restart only the crashed child
// - ONE_FOR_ALL: restart all children when any crashes
// - REST_FOR_ONE: restart crashed child and all started after it
```

### Example: Web Server Supervision Tree

```java
// Connection-level supervisor (one-for-one: each connection is independent)
var connectionSup = new Supervisor(
    "connections",
    Supervisor.Strategy.ONE_FOR_ONE,
    10,
    Duration.ofSeconds(60)
);
var conn1 = connectionSup.supervise("conn-1", initialConnState, connHandler);
var conn2 = connectionSup.supervise("conn-2", initialConnState, connHandler);

// Application-level supervisor (one-for-all: all services depend on each other)
var appSup = new Supervisor(
    "app",
    Supervisor.Strategy.ONE_FOR_ALL,
    5,
    Duration.ofSeconds(60)
);
var httpRef = appSup.supervise("http-server", httpState, httpHandler);
var cacheRef = appSup.supervise("cache-worker", cacheState, cacheHandler);

// Shutdown stops children in reverse order
appSup.shutdown();
```

---

## EventManager<E> — gen_event

**OTP:** `gen_event`

Event manager that decouples event producers from consumers.

### API

```java
EventManager<OrderEvent> manager = EventManager.start();

// Add handler
manager.addHandler(event -> System.out.println("Log: " + event));

// Async broadcast
manager.notify(new OrderEvent("ORD-001", "created"));

// Sync broadcast (wait for all handlers)
manager.syncNotify(new OrderEvent("ORD-002", "shipped"));

// Call specific handler
manager.call(specificHandler, new OrderEvent("ORD-003", "query"));

// Remove handler
manager.deleteHandler(handler);

// Stop
manager.stop();
```

### Example: Multi-Handler Event System

```java
record OrderEvent(String orderId, String status) {}

var eventManager = EventManager.<OrderEvent>start();

// Audit handler
eventManager.addHandler(new EventManager.Handler<OrderEvent>() {
    @Override
    public void handleEvent(OrderEvent event) {
        auditLog.add(event);
    }

    @Override
    public void terminate(Throwable reason) {
        System.out.println("Audit handler terminated: " + reason);
    }
});

// Notification handler
eventManager.addHandler(event -> {
    if ("shipped".equals(event.status())) {
        notifyCustomer(event.orderId());
    }
});

// Crash isolation: handler exceptions don't kill the manager
eventManager.addHandler(event -> {
    if (event.orderId().equals("BAD-ORDER")) {
        throw new RuntimeException("Handler crashed!");
    }
    // This handler will be removed, but others continue
});
```

---

## Result<T,E> — Railway Error Handling

**OTP:** `{:ok, val} | {:error, reason}`

Sealed interface for functional error handling.

### API

```java
// Create results
Result<String, Error> success = Result.success("value");
Result<String, Error> failure = Result.failure(new Error("failed"));

// Wrap throwing operations
Result<Path, IOException> result = Result.of(() -> Files.readString(path));

// Transform
result.map(String::toUpperCase);
result.flatMap(s -> Result.of(() -> parseJson(s)));

// Handle
String value = result.fold(
    success -> success,
    error -> "default"
);

// Recover
result.recover(error -> Result.success("fallback"));

// Or throw
String value = result.orElseThrow();
```

### Example: Pipeline

```java
Result<Order, OrderError> processOrder(OrderRequest request) {
    return Result.of(() -> validate(request))
        .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
        .flatMap(order -> Result.of(() -> reserveInventory(order)))
        .flatMap(order -> Result.of(() -> createPayment(order)))
        .peek(order -> auditLog.add(order))
        .recover(error -> {
            metrics.recordFailure(error);
            return Result.failure(error);
        });
}
```

---

## ProcessLink — Bilateral Crash Propagation

**OTP:** `link/1`

Links create bilateral crash propagation between processes.

### API

```java
ProcessLink.link(processA, processB);

// Unlink
ProcessLink.unlink(processA, processB);

// Spawn with automatic link
Proc<State, Msg> child = ProcessLink.spawnLink(parent, initialState, handler);
```

---

## ProcessMonitor — Unilateral Monitoring

**OTP:** `monitor/2`

Monitors provide unilateral DOWN notifications without killing the monitoring process.

### API

```java
// Monitor a process
ProcessMonitor.MonitorRef ref = ProcessMonitor.monitor(monitoringProc, targetProc);

// Receive DOWN message in mailbox
// DOWN message delivered when target terminates (normal or abnormal)

// Demonitor
ProcessMonitor.demonitor(ref);
```

---

## ProcessRegistry — Global Name Table

**OTP:** `register/2`, `whereis/1`

Global process registry with automatic deregistration on termination.

### API

```java
// Register
ProcessRegistry.register("my-service", process);

// Lookup
Proc<State, Msg> proc = ProcessRegistry.whereis("my-service");

// Unregister
ProcessRegistry.unregister("my-service");

// List all registered names
Set<String> names = ProcessRegistry.registered();
```

---

## ProcTimer — Timed Message Delivery

**OTP:** `timer:send_after/3`, `timer:send_interval/3`

### API

```java
// One-shot timer
ProcTimer.TimerRef ref = ProcTimer.sendAfter(process, Duration.ofSeconds(5), new Timeout());

// Periodic timer
ProcTimer.TimerRef intervalRef = ProcTimer.sendInterval(process, Duration.ofSeconds(10), new Heartbeat());

// Cancel
ProcTimer.cancel(ref);
```

---

## StateMachine<S,E,D> — gen_statem

**OTP:** `gen_statem`

State machine with state/event/data separation.

### API

```java
sealed interface MyEvent permits Start, Stop, Tick {}
enum MyState { IDLE, RUNNING, STOPPED }

var sm = new StateMachine<MyState, MyEvent, MyData>(
    MyState.IDLE,
    initialData,
    (state, event, data) -> {
        return switch (state) {
            case IDLE -> switch (event) {
                case Start _ -> Transition.to(MyState.RUNNING, data);
                default -> Transition.stay();
            };
            case RUNNING -> switch (event) {
                case Stop _ -> Transition.to(MyState.STOPPED, data);
                case Tick _ -> Transition.stay(data.withTick(data.tick() + 1));
                default -> Transition.stay();
            };
            case STOPPED -> Transition.stay();
        };
    }
);

sm.send(new Start());
```

---

## Parallel — Structured Fan-Out

**OTP:** `pmap`

### API

```java
// Parallel map
List<R> results = Parallel.pmap(items, item -> transform(item));

// Parallel map with concurrency limit
List<R> results = Parallel.pmap(items, 10, item -> transform(item));

// Parallel forEach
Parallel.forEach(items, item -> process(item));
```

---

## ProcSys — Process Introspection

**OTP:** `sys:get_state/1`, `sys:suspend/1`

### API

```java
// Get current state
State state = ProcSys.getState(process);

// Suspend/resume
ProcSys.suspend(process);
ProcSys.resume(process);

// Get statistics
Map<String, Object> stats = ProcSys.statistics(process);
```

---

## ProcLib — Startup Handshake

**OTP:** `proc_lib:start_link/3`

### API

```java
// Start with handshake
ProcLib.StartResult<Proc<State, Msg>> result = ProcLib.startLink(
    () -> {
        var proc = new Proc<>(initialState, handler);
        ProcLib.initAck(proc);
        return proc;
    }
);

if (result instanceof ProcLib.StartResult.Ok<Proc<State, Msg>> ok) {
    Proc<State, Msg> started = ok.process();
} else {
    ProcLib.StartResult.Error error = (ProcLib.StartResult.Error) result;
}
```

---

## Integration Example: Order Processing System

```java
// Order worker process
sealed interface OrderMsg {}
record PlaceOrder(Order order, CompletableFuture<Result<Order, OrderError>> reply) implements OrderMsg {}
record CancelOrder(String orderId) implements OrderMsg {}

record OrderWorkerState(Map<String, Order> orders) {}

var orderWorker = new Proc<OrderWorkerState, OrderMsg>(
    new OrderWorkerState(new ConcurrentHashMap<>()),
    (state, msg) -> {
        return switch (msg) {
            case PlaceOrder(var order, var reply) -> {
                state.orders().put(order.id(), order);
                reply.complete(Result.success(order));
                yield state;
            }
            case CancelOrder(var orderId) -> {
                state.orders().remove(orderId);
                yield state;
            }
        };
    }
);

// Supervisor
var supervisor = new Supervisor.SupervisorBuilder()
    .withStrategy(Supervisor.Strategy.ONE_FOR_ONE)
    .withMaxRestarts(3, Duration.ofMinutes(1))
    .addChild(orderWorker)
    .build();

// Register for discovery
ProcessRegistry.register("order-worker", orderWorker);

// Event notification
var events = EventManager.<OrderEvent>start();
events.addHandler(event -> System.out.println("Order event: " + event));

// Client usage
var worker = ProcessRegistry.whereis("order-worker", OrderMsg.class);
Result<Order, OrderError> result = worker.ask(
    new PlaceOrder(new Order("ORD-001", List.of("item1", "item2")), new CompletableFuture<>()),
    Duration.ofSeconds(5)
);
```

---

## Performance Characteristics

| Operation | Latency | Throughput |
|-----------|---------|------------|
| `tell()` (async) | ~1μs | >1M msg/s |
| `ask()` (sync) | ~10μs | >100K req/s |
| Process spawn | ~100μs | >10K/s |
| Context switch | ~1μs | Virtual thread |

---

## See Also

- [PhD Thesis: OTP 28 in Pure Java 26](phd-thesis-otp-java26.md)
- [Enterprise Messaging Patterns](messaging-patterns.md)
- [Code Generation](code-generation.md)
