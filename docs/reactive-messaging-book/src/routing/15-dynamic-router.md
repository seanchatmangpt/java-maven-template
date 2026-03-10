# 15. Dynamic Router

> *"A gen_server holds its routing table as part of its state — `handle_call({add_route, Guard, Dest}, _From, State)` extends the live table without stopping the server. The Dynamic Router is exactly that: a routing table you can mutate while traffic is flowing."*

## Intent

Route each incoming message to the first matching channel using a routing table that can be modified at runtime — adding, removing, prepending, and replacing routes — without restarting or draining the router.

This extends the Content-Based Router (Pattern 14) with live mutability. The trade-off is that route evaluation now requires a consistent read of the route list, which `CopyOnWriteArrayList` provides at the cost of O(N) copy on mutation.

## OTP Analogy

In Erlang/OTP, a `gen_server` owns its state. The routing table is just another field in that state record:

```erlang
-record(state, {
    routes    :: [{Guard :: fun(), Dest :: pid()}],
    default   :: pid() | dead_letter
}).

handle_call({add_route, Guard, Dest}, _From, #state{routes=Rs}=S) ->
    {reply, ok, S#state{routes = Rs ++ [{Guard, Dest}]}};

handle_call({prepend_route, Guard, Dest}, _From, #state{routes=Rs}=S) ->
    {reply, ok, S#state{routes = [{Guard, Dest} | Rs]}};

handle_call({remove_routes_to, Dest}, _From, #state{routes=Rs}=S) ->
    NewRoutes = [{G, D} || {G, D} <- Rs, D =/= Dest],
    {reply, ok, S#state{routes = NewRoutes}};

handle_cast({route, Msg}, #state{routes=Rs, default=Default}=S) ->
    dispatch(Msg, Rs, Default),
    {noreply, S}.

dispatch(Msg, [], Default)          -> Default ! Msg;
dispatch(Msg, [{Guard, Dest}|Rest], Default) ->
    case Guard(Msg) of
        true  -> Dest ! Msg;
        false -> dispatch(Msg, Rest, Default)
    end.
```

The JVM equivalent replaces the mutable state record with a `CopyOnWriteArrayList`. Writes (add/remove) produce a new backing array atomically; concurrent reads continue against their snapshot. This is the most faithful translation of OTP's "update the state in the handler, don't stop the server" semantics into Java's shared-memory model.

The key OTP lesson: mutating the routing table is just another message to the server. There is no global lock, no service interruption. `CopyOnWriteArrayList` achieves the same guarantee without an actor — writes are serialized by the array-copy mechanism, reads are always non-blocking.

## JOTP Implementation

**Class:** `DynamicRouter<T>`
**Package:** `org.acme.eip.routing`
**Key design decisions:**

1. **`CopyOnWriteArrayList<Route<T>>`** — the live routing table. Reads (during `route()`) never block; writes (`addRoute`, `prependRoute`, `removeRoutesTo`, `replaceRoute`) snapshot-and-swap atomically. This is appropriate when reads vastly outnumber writes (the common case for routing tables).

2. **Route identity** — routes are identified by name for add/remove operations. Names must be unique within the router; attempting to add a duplicate name replaces the existing route. This mirrors the `gen_server` pattern of keying the routing table on a route identifier.

3. **Default channel** — set via `setDefault(MessageChannel<T>)`, initialized to a dead-letter channel at construction. Unlike the Content-Based Router, the default can be changed at runtime, enabling "catch-all reassignment" without rebuilding the router.

4. **Prepend vs append** — `prependRoute` inserts at position 0 (highest priority); `addRoute` appends (lowest priority). This models Erlang's ability to cons onto the front of the route list to inject a high-priority override.

5. **Thread safety invariant** — `route(T)` reads the list snapshot atomically (single `toArray()` call), so a concurrent `addRoute` mid-dispatch will not affect the in-flight routing decision. The new route takes effect for the *next* message, not the current one. This is the same "message takes effect from next cycle" guarantee that OTP provides.

6. **Pause/resume** — the router can be paused globally (all incoming messages go directly to the default channel). This is useful during routing table reconstruction when you want to quiesce traffic temporarily.

## API Reference

### `DynamicRouter<T>`

| Method | Signature | Description |
|--------|-----------|-------------|
| `route` | `RoutingResult route(T message)` | Dispatch against current route snapshot |
| `addRoute` | `void addRoute(Route<T> route)` | Append route (lowest priority); replaces if name exists |
| `prependRoute` | `void prependRoute(Route<T> route)` | Prepend route (highest priority); replaces if name exists |
| `removeRoute` | `boolean removeRoute(String name)` | Remove route by name; returns true if found |
| `removeRoutesTo` | `int removeRoutesTo(MessageChannel<T> channel)` | Remove all routes targeting a channel; returns count removed |
| `replaceRoute` | `boolean replaceRoute(String name, Route<T> newRoute)` | Atomic in-place replacement; returns false if name not found |
| `setDefault` | `void setDefault(MessageChannel<T> channel)` | Replace default/dead-letter channel |
| `getDefault` | `MessageChannel<T> getDefault()` | Current default channel |
| `routes` | `List<Route<T>> routes()` | Unmodifiable snapshot of current route list |
| `routeCount` | `int routeCount()` | Current number of active routes |
| `pause` | `void pause()` | All messages go to default channel (table bypassed) |
| `resume` | `void resume()` | Restore normal routing |
| `isPaused` | `boolean isPaused()` | Whether routing is paused |
| `routedCount` | `long routedCount()` | Total messages dispatched to a named route |
| `defaultCount` | `long defaultCount()` | Total messages sent to default channel |
| `routeStats` | `Map<String, Long> routeStats()` | Per-route hit counts |

### `Route<T>` (record)

```java
record Route<T>(String name, Predicate<T> predicate, MessageChannel<T> channel) {
    static <T> Route<T> of(String name, Predicate<T> p, MessageChannel<T> ch) {
        return new Route<>(name, p, ch);
    }
}
```

### Factory methods

```java
DynamicRouter<T> router = DynamicRouter.create(deadLetterChannel);
DynamicRouter<T> router = DynamicRouter.withRoutes(deadLetterChannel, List.of(route1, route2));
```

## Implementation Internals

```
DynamicRouter state:
├── CopyOnWriteArrayList<Route<T>>  routes          (live routing table)
├── volatile MessageChannel<T>      defaultChannel  (updated by setDefault)
├── AtomicBoolean                   paused          (pause/resume flag)
├── LongAdder                       routedCount
├── LongAdder                       defaultCount
└── ConcurrentHashMap<String, LongAdder>  routeStats

route(message):
│
├── if paused.get():
│   └── defaultChannel.send(message); defaultCount++; return DefaultRouted
│
├── snapshot = routes.toArray()  // single volatile read; consistent snapshot
│
├── for each Route<T> in snapshot (in order):
│   └── if predicate.test(message):
│       ├── channel.send(message)
│       ├── routedCount++; routeStats[name]++
│       └── return Routed(name)
│
└── defaultChannel.send(message); defaultCount++; return DefaultRouted

addRoute(route):
├── if name exists: remove existing route with that name
└── routes.add(route)  // COWAL: copy-on-write atomic append

prependRoute(route):
├── if name exists: remove existing route with that name
└── routes.add(0, route)  // COWAL: copy-on-write atomic prepend

removeRoutesTo(channel):
└── routes.removeIf(r -> r.channel() == channel)  // COWAL: atomic filter
```

The `volatile` keyword on `defaultChannel` ensures that a `setDefault()` call on one thread is immediately visible to `route()` calls on other threads, without requiring synchronization. This is the JVM equivalent of Erlang's state update becoming visible to the next `receive` cycle.

**Write contention:** `CopyOnWriteArrayList` serializes writes using an intrinsic lock. If your routing table receives many concurrent updates (e.g., hundreds of route additions per second), contention on that lock becomes a bottleneck. In that case, consider a `ReadWriteLock` with a plain `ArrayList` — reads hold the read lock (shared), writes hold the write lock (exclusive). For most routing table use cases (infrequent updates, high read volume), `CopyOnWriteArrayList` is the right choice.

## Code Example

```java
import org.acme.eip.routing.DynamicRouter;
import org.acme.eip.routing.Route;
import org.acme.eip.channel.InMemoryChannel;

// --- Domain ---
record Event(String source, String severity, String payload) {}

// --- Channels ---
var criticalCh  = new InMemoryChannel<Event>("critical");
var warningCh   = new InMemoryChannel<Event>("warning");
var infoCh      = new InMemoryChannel<Event>("info");
var deadLetter  = new InMemoryChannel<Event>("dead-letter");

// --- Create router with initial routes ---
var router = DynamicRouter.<Event>withRoutes(deadLetter, List.of(
    Route.of("critical", e -> "CRITICAL".equals(e.severity()), criticalCh),
    Route.of("warning",  e -> "WARNING".equals(e.severity()),  warningCh)
));

// Route some events
router.route(new Event("app-1", "CRITICAL", "disk full"));
router.route(new Event("app-2", "WARNING",  "high cpu"));
router.route(new Event("app-3", "INFO",     "started"));  // -> dead-letter (no INFO route yet)

System.out.println("Dead-lettered: " + router.defaultCount());  // 1

// --- Add INFO route at runtime (no restart needed) ---
router.addRoute(Route.of("info", e -> "INFO".equals(e.severity()), infoCh));

router.route(new Event("app-3", "INFO", "started"));   // -> info (route now exists)
System.out.println("Info count: " + router.routeStats().get("info"));  // 1

// --- Prepend a high-priority override (e.g., maintenance mode) ---
var maintenanceCh = new InMemoryChannel<Event>("maintenance");
router.prependRoute(Route.of(
    "maintenance-filter",
    e -> "app-1".equals(e.source()),   // all app-1 traffic to maintenance channel
    maintenanceCh
));

router.route(new Event("app-1", "INFO", "heartbeat"));   // -> maintenance (prepended route wins)
router.route(new Event("app-2", "INFO", "heartbeat"));   // -> info (not app-1)

System.out.println("Maintenance: " + router.routeStats().get("maintenance-filter"));  // 1

// --- Remove the maintenance filter when done ---
router.removeRoute("maintenance-filter");
router.route(new Event("app-1", "INFO", "back to normal"));  // -> info again

// --- Redirect a channel at runtime ---
var archiveCh = new InMemoryChannel<Event>("archive");
router.removeRoutesTo(infoCh);                               // remove all routes to infoCh
router.addRoute(Route.of("info", e -> "INFO".equals(e.severity()), archiveCh));  // reroute to archive

router.route(new Event("app-5", "INFO", "archived-event"));  // -> archive

// --- Pause routing during table reconstruction ---
router.pause();
System.out.println("Paused: " + router.isPaused());  // true

// Messages during pause go directly to default (dead-letter)
router.route(new Event("app-6", "CRITICAL", "during-pause"));  // -> dead-letter

router.resume();
System.out.println("Paused: " + router.isPaused());  // false
router.route(new Event("app-6", "CRITICAL", "after-resume"));  // -> critical

// --- Final stats ---
System.out.println("Route count: " + router.routeCount());
System.out.println("All stats:   " + router.routeStats());
```

### Dynamic Routing with External Config Source

```java
// Reload routing table from config every 30 seconds
var scheduler = Executors.newSingleThreadScheduledExecutor(
    Thread.ofVirtual().factory());

scheduler.scheduleAtFixedRate(() -> {
    var config = configService.loadRoutes();   // returns List<RouteConfig>

    router.pause();
    try {
        // Rebuild table atomically under pause
        router.routes().stream()
              .map(Route::name)
              .forEach(router::removeRoute);

        config.forEach(rc ->
            router.addRoute(Route.of(rc.name(), rc.predicate(), channelFor(rc.dest())))
        );
    } finally {
        router.resume();
    }
}, 0, 30, TimeUnit.SECONDS);
```

## Composition

**DynamicRouter + ProcessRegistry:**
Register the router's default channel in the `ProcessRegistry` so that other processes can redirect it without holding a direct reference:

```java
ProcessRegistry.register("orders.dead-letter", deadLetterChannel);
// Later, redirect dead-letter to a new handler:
var newDl = channelFromRegistry("orders.premium-rescue");
router.setDefault(newDl);
```

**DynamicRouter + Supervisor:**
The router itself holds no restartable state (the routing table is in `CopyOnWriteArrayList`, not a `Proc`). If you need the routing table to survive JVM crashes, externalize it:

```java
// On supervisor restart, reload routing table from durable store
supervisor.onRestart("dynamic-router", () -> {
    var restored = routeStore.load();
    restored.forEach(router::addRoute);
});
```

**DynamicRouter + WireTap:**
Tap the default channel to observe routing misses in real time:

```java
var tapDeadLetter = WireTap.of(deadLetter, e ->
    metrics.increment("routing.dead_letter", "source", e.source()));
var router = DynamicRouter.create(tapDeadLetter);
```

**Chained DynamicRouters:**
A coarse first-level router narrows to domain-specific second-level routers:

```java
var ordersRouter  = DynamicRouter.<Message>create(dlChannel);
var eventsRouter  = DynamicRouter.<Message>create(dlChannel);

// Top-level router dispatches by message type
var topRouter = DynamicRouter.<Message>withRoutes(dlChannel, List.of(
    Route.of("orders", m -> m.type().startsWith("order."), ordersRouter.asChannel()),
    Route.of("events", m -> m.type().startsWith("event."), eventsRouter.asChannel())
));
```

## Test Pattern

```java
import org.acme.eip.routing.DynamicRouter;
import org.acme.eip.routing.Route;
import org.acme.eip.channel.CapturingChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class DynamicRouterTest implements WithAssertions {

    record Msg(String tag, int value) {}

    CapturingChannel<Msg> ch1, ch2, dl;
    DynamicRouter<Msg> router;

    @BeforeEach
    void setUp() {
        ch1 = new CapturingChannel<>("ch1");
        ch2 = new CapturingChannel<>("ch2");
        dl  = new CapturingChannel<>("dl");
        router = DynamicRouter.create(dl);
    }

    @Test
    void addRouteAtRuntime_takesEffectOnNextMessage() {
        router.route(new Msg("A", 1));   // no routes yet -> dead-letter
        assertThat(dl.captured()).hasSize(1);

        router.addRoute(Route.of("tag-a", m -> "A".equals(m.tag()), ch1));
        router.route(new Msg("A", 2));   // now routes to ch1
        assertThat(ch1.captured()).hasSize(1);
    }

    @Test
    void prependRoute_winsPriorityOverExistingRoutes() {
        router.addRoute(Route.of("low",  m -> m.value() > 0, ch1));
        router.prependRoute(Route.of("high", m -> m.value() > 10, ch2));

        router.route(new Msg("x", 15));  // high predicate matches first -> ch2
        router.route(new Msg("x", 5));   // high doesn't match, low does -> ch1

        assertThat(ch2.captured()).hasSize(1);
        assertThat(ch1.captured()).hasSize(1);
    }

    @Test
    void removeRoute_stopsDispatching() {
        router.addRoute(Route.of("r1", m -> "A".equals(m.tag()), ch1));
        router.route(new Msg("A", 1));
        assertThat(ch1.captured()).hasSize(1);

        boolean removed = router.removeRoute("r1");
        assertThat(removed).isTrue();
        router.route(new Msg("A", 2));   // no routes -> dead-letter
        assertThat(dl.captured()).hasSize(1);
        assertThat(ch1.captured()).hasSize(1);  // unchanged
    }

    @Test
    void removeRoutesTo_removesAllRoutesTargetingChannel() {
        router.addRoute(Route.of("r1", m -> m.value() > 0, ch1));
        router.addRoute(Route.of("r2", m -> m.value() > 5, ch1));
        router.addRoute(Route.of("r3", m -> m.value() > 10, ch2));

        int removed = router.removeRoutesTo(ch1);
        assertThat(removed).isEqualTo(2);
        assertThat(router.routeCount()).isEqualTo(1);  // only r3 remains

        router.route(new Msg("x", 15));   // matches r3 -> ch2
        assertThat(ch2.captured()).hasSize(1);
        assertThat(ch1.captured()).isEmpty();
    }

    @Test
    void setDefault_redirectsUnmatchedMessages() {
        var newDl = new CapturingChannel<Msg>("new-dl");
        router.setDefault(newDl);

        router.route(new Msg("z", 0));   // no routes -> new default

        assertThat(dl.captured()).isEmpty();
        assertThat(newDl.captured()).hasSize(1);
    }

    @Test
    void pause_sendsAllMessagesToDefault() {
        router.addRoute(Route.of("r1", m -> true, ch1));
        router.pause();
        assertThat(router.isPaused()).isTrue();

        router.route(new Msg("A", 1));
        router.route(new Msg("B", 2));

        assertThat(ch1.captured()).isEmpty();
        assertThat(dl.captured()).hasSize(2);

        router.resume();
        router.route(new Msg("C", 3));
        assertThat(ch1.captured()).hasSize(1);
    }

    @Test
    void duplicateRouteName_replacesExisting() {
        router.addRoute(Route.of("r1", m -> "A".equals(m.tag()), ch1));
        router.addRoute(Route.of("r1", m -> "B".equals(m.tag()), ch2));  // replaces r1

        assertThat(router.routeCount()).isEqualTo(1);
        router.route(new Msg("A", 1));   // original predicate replaced -> dead-letter
        router.route(new Msg("B", 1));   // new predicate -> ch2

        assertThat(ch1.captured()).isEmpty();
        assertThat(ch2.captured()).hasSize(1);
    }

    @Test
    void concurrentAddAndRoute_noDataCorruption() throws InterruptedException {
        var latch   = new CountDownLatch(1);
        var routed  = new AtomicInteger();
        int threads = 20;
        int msgsPerThread = 100;

        router.addRoute(Route.of("base", m -> m.value() % 2 == 0, ch1));

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                latch.await();
                for (int j = 0; j < msgsPerThread; j++) {
                    // Odd threads add/remove routes, even threads route messages
                    if (threadId % 2 == 0) {
                        router.route(new Msg("x", j));
                        routed.incrementAndGet();
                    } else {
                        var tmpRoute = Route.of("tmp-" + threadId,
                            m -> m.value() == threadId, ch2);
                        router.addRoute(tmpRoute);
                        router.removeRoute("tmp-" + threadId);
                    }
                }
                return null;
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // No messages lost: routed + defaultCount == total messages sent
        assertThat(router.routedCount() + router.defaultCount())
            .isEqualTo(routed.get());
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Routing rules change at runtime based on operational events (feature flags, A/B tests, circuit breakers, canary deployments)
- You need to drain traffic from a channel without restarting (remove routes pointing to it, let in-flight messages complete)
- Multiple operators/threads need to independently modify different parts of the routing table

**Avoid when:**
- Routes are known at startup and never change — use the simpler, allocation-free `MessageRouter` (Pattern 14) instead
- Route updates are extremely frequent (thousands per second) — `CopyOnWriteArrayList` write cost becomes prohibitive; use `ReadWriteLock` with `ArrayList` instead
- Route predicates are expensive (I/O, database calls) — the `CopyOnWriteArrayList` snapshot will hold stale route references for the duration of expensive evaluations; use a `DynamicRouter` backed by a `Proc` to serialize access

**CopyOnWriteArrayList write cost:**
Each `addRoute`/`removeRoute` copies the entire backing array. For a routing table with 100 routes, each write allocates a 100-element array. This is acceptable for infrequent updates (< 1000/sec) but will generate significant GC pressure under high write rates.

**Visibility window:**
A message in flight when `removeRoute` is called will still be dispatched to the removed route's channel if the route snapshot was taken before the removal. The window is bounded by the execution time of the predicate list iteration — typically microseconds. This "at most one extra delivery" semantic is acceptable for most use cases.

**Pause race condition:**
`pause()` sets an `AtomicBoolean`. Messages already in the predicate evaluation loop when `pause()` is called will complete normally. Only messages whose `route()` call starts *after* `pause()` observes the boolean will be redirected to the default channel. This is the JVM equivalent of OTP's "message already dequeued before process flag update takes effect."
