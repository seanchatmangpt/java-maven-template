# 14. Content-Based Router

> *"Selective receive is Erlang's built-in content-based router — the VM pattern-matches each message against a list of guards and dispatches to the first clause that fires. The Content-Based Router makes the same mechanism explicit, composable, and observable."*

## Intent

Route each incoming message to exactly one output channel by evaluating a static, ordered list of predicates at dispatch time, falling back to a dead-letter channel when no predicate matches.

Unlike a filter (which discards non-matching messages silently), the Content-Based Router guarantees every message reaches *some* destination, making it safe to use at trust boundaries where silent message loss is unacceptable.

## OTP Analogy

Erlang's `receive` block is the canonical content-based router:

```erlang
receive
    {order, urgent, Msg} ->
        urgent_queue ! Msg;
    {order, normal, Msg} ->
        normal_queue ! Msg;
    {order, bulk, _Msg} ->
        bulk_queue ! Msg;
    Other ->
        dead_letter ! {unrouted, Other}
end
```

Each clause is a **guard** — a predicate evaluated against the incoming message. Erlang evaluates guards top-to-bottom and dispatches to the first match. If no clause matches, the message stays in the mailbox (or hits the final catch-all). The Content-Based Router mirrors this exactly:

- **Clauses** → `Route<T>` records holding a `Predicate<T>` and a target `MessageChannel<T>`
- **Evaluation order** → list position (first registered, first evaluated)
- **Catch-all** → dead-letter channel (always present, never null)
- **No mutable state** — predicates are pure functions, channels are stable references

The critical OTP lesson: route evaluation is **stateless**. Each message is routed independently. There is no accumulated routing history, no feedback loop. This makes the router trivially restartable under a supervisor — it holds no state that must be recovered.

## JOTP Implementation

**Class:** `MessageRouter<T>`
**Package:** `org.acme.eip.routing`
**Key design decisions:**

1. **Immutable route list** — built once via `MessageRouter.Builder<T>`, stored as `List<Route<T>>` (unmodifiable). No lock needed at dispatch time because the list never changes after construction.

2. **Route record** — `record Route<T>(Predicate<T> predicate, MessageChannel<T> channel, String name)`. The name enables observability (logs, metrics) without coupling to the channel implementation.

3. **Dead-letter channel** — mandatory. The builder rejects construction without a dead-letter channel, making the "unrouted message" case explicit in the type system.

4. **First-match semantics** — routes are evaluated in insertion order. The first `predicate.test(message)` that returns `true` wins. This mirrors Erlang's `receive` clause evaluation and avoids ambiguity.

5. **Thread safety** — `route(T message)` acquires no locks. The route list is immutable; channel `send` implementations are responsible for their own thread safety. The router itself is safe to call from any number of virtual threads simultaneously.

6. **Metrics hooks** — the builder accepts optional `Consumer<RouteEvent<T>>` observer for recording routing decisions (which route fired, latency, dead-letter rate) without polluting core logic.

## API Reference

### `MessageRouter<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `route` | `RoutingResult route(T message)` | Evaluate predicates in order; send to first match or dead-letter |
| `routeAsync` | `CompletableFuture<RoutingResult> routeAsync(T message)` | Non-blocking variant; uses virtual thread per call |
| `routedCount` | `long routedCount()` | Total messages successfully routed to a named channel |
| `deadLetterCount` | `long deadLetterCount()` | Total messages sent to dead-letter |
| `routeStats` | `Map<String, Long> routeStats()` | Per-route hit counts, keyed by route name |
| `routes` | `List<Route<T>> routes()` | Snapshot of the route list (unmodifiable) |

### `MessageRouter.Builder<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `route` | `Builder<T> route(String name, Predicate<T> p, MessageChannel<T> ch)` | Append a named route |
| `route` | `Builder<T> route(Predicate<T> p, MessageChannel<T> ch)` | Append an anonymous route (auto-named `route-N`) |
| `deadLetter` | `Builder<T> deadLetter(MessageChannel<T> ch)` | Set dead-letter channel (required) |
| `onEvent` | `Builder<T> onEvent(Consumer<RouteEvent<T>> observer)` | Register routing event observer |
| `build` | `MessageRouter<T> build()` | Construct the router; throws if dead-letter not set |

### `RoutingResult` (sealed interface)

```
RoutingResult
├── Routed(String routeName, Object channel)
└── DeadLettered(String reason)
```

### `RouteEvent<T>` (record)

```java
record RouteEvent<T>(T message, RoutingResult result, Duration elapsed) {}
```

## Implementation Internals

```
MessageRouter.route(message)
│
├── for each Route<T> in routes (in order):
│   └── if predicate.test(message):
│       ├── channel.send(message)
│       ├── routedCount.increment()
│       ├── routeStats[name].increment()
│       ├── observer.accept(RouteEvent(message, Routed(name, channel), elapsed))
│       └── return Routed(name, channel)
│
└── (no match):
    ├── deadLetterChannel.send(message)
    ├── deadLetterCount.increment()
    ├── observer.accept(RouteEvent(message, DeadLettered("no predicate matched"), elapsed))
    └── return DeadLettered("no predicate matched")
```

The router holds two `LongAdder` instances for high-throughput concurrent counting (preferred over `AtomicLong` when writes dominate reads). Per-route stats use a `ConcurrentHashMap<String, LongAdder>` initialized at build time — one entry per named route, zero contention on distinct routes.

**Predicate short-circuit:** The `for` loop returns immediately on the first match. Remaining predicates are never evaluated. Expensive predicates should be placed after cheap guards (exactly like Erlang guard ordering).

**No exception swallowing:** If a predicate throws, the exception propagates to the caller. The router does not silently dead-letter on predicate failure — that would mask bugs. If you need fault-isolated predicate evaluation, wrap the router in a `CrashRecovery` or put it inside a `Proc`.

## Code Example

```java
import org.acme.eip.routing.MessageRouter;
import org.acme.eip.channel.InMemoryChannel;
import java.util.concurrent.LinkedBlockingQueue;

// --- Domain types ---
record Order(String id, String tier, double amount) {}

// --- Channels ---
var platinumQueue  = new InMemoryChannel<Order>("platinum");
var goldQueue      = new InMemoryChannel<Order>("gold");
var standardQueue  = new InMemoryChannel<Order>("standard");
var deadLetter     = new InMemoryChannel<Order>("dead-letter");

// --- Build router ---
MessageRouter<Order> router = MessageRouter.<Order>builder()
    .route("platinum",
           o -> o.tier().equals("PLATINUM") || o.amount() > 50_000,
           platinumQueue)
    .route("gold",
           o -> o.tier().equals("GOLD") && o.amount() > 10_000,
           goldQueue)
    .route("standard",
           o -> o.amount() > 0,
           standardQueue)
    .deadLetter(deadLetter)
    .onEvent(e -> System.out.printf("[ROUTER] %s -> %s (%s ms)%n",
             e.message().id(),
             e.result(),
             e.elapsed().toMillis()))
    .build();

// --- Route messages ---
var orders = List.of(
    new Order("ORD-001", "PLATINUM", 1_000),   // -> platinum (tier match)
    new Order("ORD-002", "GOLD",     25_000),   // -> gold
    new Order("ORD-003", "STANDARD", 500),      // -> standard
    new Order("ORD-004", "GOLD",     75_000),   // -> platinum (amount > 50k wins first)
    new Order("ORD-005", "STANDARD", -1)        // -> dead-letter (negative amount)
);

orders.forEach(router::route);

// --- Inspect stats ---
System.out.println("Routed:      " + router.routedCount());
System.out.println("Dead-letter: " + router.deadLetterCount());
System.out.println("Per-route:   " + router.routeStats());

// Output:
// [ROUTER] ORD-001 -> Routed[routeName=platinum, ...] (0 ms)
// [ROUTER] ORD-002 -> Routed[routeName=gold, ...] (0 ms)
// [ROUTER] ORD-003 -> Routed[routeName=standard, ...] (0 ms)
// [ROUTER] ORD-004 -> Routed[routeName=platinum, ...] (0 ms)  <- first-match wins
// [ROUTER] ORD-005 -> DeadLettered[reason=no predicate matched] (0 ms)
// Routed:      4
// Dead-letter: 1
// Per-route:   {platinum=2, gold=1, standard=1}
```

### Priority Escalation via Route Ordering

Route ordering doubles as a priority system. To implement "VIP always wins regardless of tier label":

```java
MessageRouter<Order> router = MessageRouter.<Order>builder()
    // High-value check FIRST — overrides tier label
    .route("high-value",  o -> o.amount() > 100_000, vipQueue)
    .route("platinum",    o -> "PLATINUM".equals(o.tier()), platinumQueue)
    .route("gold",        o -> "GOLD".equals(o.tier()),     goldQueue)
    .route("catch-all",   o -> true,                        standardQueue)
    .deadLetter(deadLetter)
    .build();
```

The `catch-all` route with `o -> true` means nothing ever reaches the dead-letter channel — equivalent to Erlang's final `Other -> ...` catch-all clause. Include a catch-all only when you intentionally want to suppress dead-lettering.

### Async Routing with Virtual Threads

```java
// Fan out routing across many messages without blocking the calling thread
var futures = orders.stream()
    .map(order -> router.routeAsync(order))
    .toList();

CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
System.out.println("All routed: " + router.routedCount());
```

## Composition

**Content-Based Router + Splitter:**
Split a batch order into individual items, then route each item independently:
```
BatchOrder -> Splitter -> [Order, Order, Order] -> MessageRouter
```

**Content-Based Router + Aggregator:**
Route to specialist processors, re-aggregate results by correlation ID:
```
Message -> MessageRouter -> [Processor A, Processor B] -> Aggregator -> Output
```

**Content-Based Router + Wire Tap:**
Wrap the router's output channels with Wire Taps for audit logging:
```java
var auditedPlatinum = WireTap.of(platinumQueue, msg -> auditLog.record(msg));
MessageRouter<Order> router = MessageRouter.<Order>builder()
    .route("platinum", o -> "PLATINUM".equals(o.tier()), auditedPlatinum)
    // ...
    .build();
```

**Nested routers:**
The `channel` in a route can itself be a router — building a routing tree:
```java
var tierRouter = MessageRouter.<Order>builder()
    .route("premium", o -> isPremium(o), premiumRouter)  // premiumRouter is also a MessageRouter
    .route("standard", o -> true, standardChannel)
    .deadLetter(deadLetter)
    .build();
```

## Test Pattern

```java
import org.acme.eip.routing.MessageRouter;
import org.acme.eip.channel.CapturingChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import java.util.List;

class MessageRouterTest implements WithAssertions {

    record Payload(String type, int priority) {}

    @Test
    void routesToFirstMatchingChannel() {
        var highCh  = new CapturingChannel<Payload>("high");
        var lowCh   = new CapturingChannel<Payload>("low");
        var dlCh    = new CapturingChannel<Payload>("dead-letter");

        var router = MessageRouter.<Payload>builder()
            .route("high", p -> p.priority() > 5, highCh)
            .route("low",  p -> p.priority() > 0, lowCh)
            .deadLetter(dlCh)
            .build();

        router.route(new Payload("A", 10));  // high
        router.route(new Payload("B", 3));   // low
        router.route(new Payload("C", 0));   // dead-letter (priority == 0, no match)

        assertThat(highCh.captured()).containsExactly(new Payload("A", 10));
        assertThat(lowCh.captured()).containsExactly(new Payload("B", 3));
        assertThat(dlCh.captured()).containsExactly(new Payload("C", 0));
    }

    @Test
    void firstMatchWins_laterRoutesNeverEvaluated() {
        var ch1 = new CapturingChannel<Payload>("ch1");
        var ch2 = new CapturingChannel<Payload>("ch2");
        var dlCh = new CapturingChannel<Payload>("dl");

        // Both predicates match priority=10, but ch1 must win
        var router = MessageRouter.<Payload>builder()
            .route("first",  p -> p.priority() > 5,  ch1)
            .route("second", p -> p.priority() > 3,  ch2)
            .deadLetter(dlCh)
            .build();

        router.route(new Payload("X", 10));

        assertThat(ch1.captured()).hasSize(1);
        assertThat(ch2.captured()).isEmpty();   // never reached
        assertThat(router.routeStats()).containsEntry("first", 1L)
                                       .containsEntry("second", 0L);
    }

    @Test
    void deadLetterReceivesUnroutableMessages() {
        var dlCh = new CapturingChannel<Payload>("dl");
        var router = MessageRouter.<Payload>builder()
            .route("high", p -> p.priority() > 100, new CapturingChannel<>("high"))
            .deadLetter(dlCh)
            .build();

        router.route(new Payload("nobody-matches", 5));

        assertThat(dlCh.captured()).hasSize(1);
        assertThat(router.deadLetterCount()).isEqualTo(1);
        assertThat(router.routedCount()).isEqualTo(0);
    }

    @Test
    void routeStatsAreAccurate() {
        var aCh  = new CapturingChannel<Payload>("a");
        var bCh  = new CapturingChannel<Payload>("b");
        var dlCh = new CapturingChannel<Payload>("dl");

        var router = MessageRouter.<Payload>builder()
            .route("route-a", p -> "A".equals(p.type()), aCh)
            .route("route-b", p -> "B".equals(p.type()), bCh)
            .deadLetter(dlCh)
            .build();

        List.of(
            new Payload("A", 1), new Payload("A", 2), new Payload("A", 3),
            new Payload("B", 1), new Payload("B", 2),
            new Payload("C", 1)
        ).forEach(router::route);

        assertThat(router.routeStats())
            .containsEntry("route-a", 3L)
            .containsEntry("route-b", 2L);
        assertThat(router.deadLetterCount()).isEqualTo(1);
        assertThat(router.routedCount()).isEqualTo(5);
    }

    @Test
    void observerReceivesEveryRoutingDecision() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<
            MessageRouter.RouteEvent<Payload>>();
        var ch   = new CapturingChannel<Payload>("ch");
        var dlCh = new CapturingChannel<Payload>("dl");

        var router = MessageRouter.<Payload>builder()
            .route("main", p -> p.priority() > 0, ch)
            .deadLetter(dlCh)
            .onEvent(events::add)
            .build();

        router.route(new Payload("hit", 1));
        router.route(new Payload("miss", 0));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).result()).isInstanceOf(MessageRouter.RoutingResult.Routed.class);
        assertThat(events.get(1).result()).isInstanceOf(MessageRouter.RoutingResult.DeadLettered.class);
    }

    @Test
    void builderRequiresDeadLetterChannel() {
        assertThatThrownBy(() ->
            MessageRouter.<Payload>builder()
                .route("r", p -> true, new CapturingChannel<>("r"))
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("dead-letter");
    }
}
```

## Caveats & Trade-offs

**Use when:**
- You need guaranteed delivery to exactly one of N channels based on message content
- Route logic is stable — known at startup, does not change at runtime (use `DynamicRouter` for that)
- You want the routing logic version-controlled as code, not configured in a database
- Predicate evaluation cost is low relative to message processing cost

**Avoid when:**
- Routes change frequently at runtime — the static route list requires a new `MessageRouter` instance (or use `DynamicRouter`)
- A message legitimately belongs to multiple channels — use a `Recipient List` instead
- Routing logic is extremely complex or requires database lookups — consider externalizing the routing table and feeding it to a `DynamicRouter`

**Performance:**
- Each `route()` call iterates the predicate list linearly. For N routes, worst-case cost is O(N) predicate evaluations.
- Put cheap, high-selectivity predicates first (exact equality checks before range checks).
- For very large route sets (N > 50), consider partitioning with a coarse first-level router that narrows to a smaller second-level router — a routing tree cuts average path length from O(N) to O(log N).

**Predicate purity:**
- Predicates must be pure (no side effects, no I/O). A predicate that calls a database is a smell — extract the lookup into a message enrichment step upstream and route on the enriched field.
- Predicates that throw will propagate exceptions to the `route()` caller. Wrap in try-catch inside the predicate if needed, returning `false` on exception to fall through to the next route.

**Dead-letter as a circuit breaker signal:**
- A rising dead-letter rate is a leading indicator that the domain model has diverged from the routing rules. Wire a `WireTap` on the dead-letter channel to alert on-call when the rate exceeds a threshold.
