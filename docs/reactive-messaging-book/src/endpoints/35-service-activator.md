# 35. Service Activator

> *"A Service Activator connects a messaging channel to a service so that the service is invoked whenever a message arrives — bridging the messaging world to the domain logic world."*

## Intent

A Service Activator decouples the messaging infrastructure from the service implementation. The activator listens on a `MessageChannel`, unpacks each message, invokes the appropriate service method, and optionally places the result on a reply channel. In JOTP, a `Proc<S, Cmd>` is the natural Service Activator — it wraps a service behind a mailbox.

## OTP Analogy

`gen_server` is Erlang's Service Activator — it wraps a module's callbacks behind a process mailbox, activating the service on each `cast` or `call`:

```erlang
%% gen_server activates the service on each message
handle_cast({increment, N}, State) ->
    {noreply, counter_service:increment(State, N)};
handle_cast({reset}, State) ->
    {noreply, counter_service:reset()};
handle_call(get_count, _From, State) ->
    {reply, counter_service:get(State), State}.
```

`Proc<S, Cmd>` in JOTP is the direct equivalent: the `(S, M) -> S` handler activates the service.

## JOTP Implementation

The `Proc<S, Cmd>` activates one service method per sealed command type. Each `switch` arm in the handler dispatches to the corresponding service method. The `Proc` mailbox serialises concurrent activation requests without locks.

```
MessageChannel<CounterCmd>
       │  tell(Increment(5))
       │  tell(Reset)
       │  ask(GetCount)
       ▼
Proc<CounterState, CounterCmd>  ← Service Activator
       │
       ├── case Increment(n) → CounterService.increment(state, n)
       ├── case Reset        → CounterService.reset()
       └── case GetCount     → state (reply to ask caller)
```

Key design points:
- One `case` per message type = one service operation per command.
- `tell()` for fire-and-forget operations (mutations); `ask()` for queries.
- The activator's `Proc` state IS the service state — no separate service object needed for pure services.
- For services with external dependencies (DB, HTTP), inject them into the handler closure.

## API Reference

| Method | Description |
|--------|-------------|
| `Proc.of(initialState, handler)` | Create Service Activator with initial state |
| `proc.tell(cmd)` | Activate service (fire-and-forget) |
| `proc.ask(cmd)` | Activate service and await state reply |
| `switch (cmd) { case CmdType c -> … }` | Dispatch to service method |

## Code Example

```java
import org.acme.Proc;

// --- Service command hierarchy (sealed) ---
sealed interface CounterCmd
    permits Increment, Decrement, Reset, GetCount {}

record Increment(int delta) implements CounterCmd {
    Increment { if (delta < 0) throw new IllegalArgumentException("use Decrement"); }
}
record Decrement(int delta) implements CounterCmd {}
record Reset()              implements CounterCmd {}
record GetCount()           implements CounterCmd {}

// --- Service state ---
record CounterState(int count, int totalIncrements, int totalDecrements) {
    static CounterState zero() { return new CounterState(0, 0, 0); }

    CounterState inc(int n) { return new CounterState(count + n, totalIncrements + 1, totalDecrements); }
    CounterState dec(int n) { return new CounterState(Math.max(0, count - n), totalIncrements, totalDecrements + 1); }
    CounterState reset()    { return new CounterState(0, totalIncrements, totalDecrements); }
}

// --- Service Activator ---
public class ServiceActivatorDemo {

    // Handler: pure (S, M) -> S — one method per command type
    static CounterState handle(CounterState state, CounterCmd cmd) {
        return switch (cmd) {
            case Increment i -> {
                System.out.printf("[ACTIVATE] increment by %d (was %d)%n",
                    i.delta(), state.count());
                yield state.inc(i.delta());
            }
            case Decrement d -> {
                System.out.printf("[ACTIVATE] decrement by %d (was %d)%n",
                    d.delta(), state.count());
                yield state.dec(d.delta());
            }
            case Reset ignored -> {
                System.out.println("[ACTIVATE] reset counter");
                yield state.reset();
            }
            case GetCount ignored -> {
                System.out.printf("[ACTIVATE] query count=%d%n", state.count());
                yield state; // read-only
            }
        };
    }

    public static void main(String[] args) throws Exception {
        // Create the Service Activator (Proc wraps the service)
        var activator = Proc.of(CounterState.zero(), ServiceActivatorDemo::handle);

        // Activate service via messaging
        activator.tell(new Increment(10));
        activator.tell(new Increment(5));
        activator.tell(new Decrement(3));
        activator.tell(new Reset());
        activator.tell(new Increment(7));

        // Query the service — returns updated state
        var state = activator.ask(new GetCount()).get(2, TimeUnit.SECONDS);
        System.out.printf("Final count: %d (increments=%d, decrements=%d)%n",
            state.count(), state.totalIncrements(), state.totalDecrements());
    }
}
```

## With External Dependencies

```java
// Service Activator wrapping a service with an external DB connection
record OrderState(Map<String, Order> orders) {}
sealed interface OrderCmd permits CreateOrder, GetOrder, CancelOrder {}

// External dependency injected into the handler via closure
OrderRepository repo = new PostgresOrderRepository(dataSource);

Proc<OrderState, OrderCmd> activator = Proc.of(
    new OrderState(new HashMap<>()),
    (state, cmd) -> switch (cmd) {
        case CreateOrder c -> {
            Order saved = repo.save(new Order(c.orderId(), c.amount()));
            var updated = new HashMap<>(state.orders());
            updated.put(saved.id(), saved);
            yield new OrderState(updated);
        }
        case GetOrder g -> {
            Order o = repo.findById(g.orderId());  // external I/O inside Proc
            System.out.printf("Order %s: %s%n", o.id(), o.status());
            yield state;
        }
        case CancelOrder x -> {
            repo.cancel(x.orderId());
            yield state;
        }
    }
);
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.TimeUnit;

class ServiceActivatorTest implements WithAssertions {

    Proc<CounterState, CounterCmd> freshActivator() {
        return Proc.of(CounterState.zero(), ServiceActivatorDemo::handle);
    }

    @Test
    void incrementActivatesService() throws Exception {
        var proc  = freshActivator();
        proc.tell(new Increment(10));

        var state = proc.ask(new GetCount()).get(2, TimeUnit.SECONDS);
        assertThat(state.count()).isEqualTo(10);
    }

    @Test
    void decrementDoesNotGoBelowZero() throws Exception {
        var proc = freshActivator();
        proc.tell(new Increment(5));
        proc.tell(new Decrement(10)); // would go negative — clamped to 0

        var state = proc.ask(new GetCount()).get(2, TimeUnit.SECONDS);
        assertThat(state.count()).isEqualTo(0);
    }

    @Test
    void resetClearsCount() throws Exception {
        var proc = freshActivator();
        proc.tell(new Increment(50));
        proc.tell(new Reset());

        var state = proc.ask(new GetCount()).get(2, TimeUnit.SECONDS);
        assertThat(state.count()).isEqualTo(0);
    }

    @Test
    void concurrentActivationsAreSerialized() throws Exception {
        var proc = freshActivator();
        for (int i = 0; i < 100; i++) proc.tell(new Increment(1));

        var state = proc.ask(new GetCount()).get(2, TimeUnit.SECONDS);
        assertThat(state.count()).isEqualTo(100); // no lost updates
    }
}
```

## Caveats & Trade-offs

**Use when:**
- You want to protect service state from concurrent access without locks.
- The service needs to be invoked asynchronously from the messaging pipeline.
- Different message types map to different service operations.

**Avoid when:**
- The service is stateless and thread-safe — a direct function call is simpler.
- The service has very high throughput requirements and the Proc mailbox becomes a bottleneck — use `MessageDispatcher<T>` to fan out across multiple activator instances.
- You need synchronous, in-thread activation — wrapping in a Proc adds one hop of latency.
