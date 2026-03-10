# 7. Command Message

> *"A Command Message tells the receiver to do something. It is the messaging equivalent of a method call."*

## Intent

A Command Message encodes a specific instruction for a single designated receiver. Unlike an Event (which announces something that happened), a Command tells a service what to do. There is typically one sender and one receiver.

## OTP Analogy

In Erlang/OTP, `gen_server:cast/2` sends a fire-and-forget command and `gen_server:call/2` sends a command expecting a reply:

```erlang
%% Fire-and-forget command
gen_server:cast(InventoryServer, {reserve_stock, "SKU-42", 3}).

%% Command with reply
Reply = gen_server:call(InventoryServer, {check_availability, "SKU-42"}).
```

Records in Java map directly to Erlang tagged tuples — each record constructor is a unique command type.

## JOTP Implementation

Java 26 records make ideal Command Messages: immutable, structurally typed, and self-documenting. A sealed interface groups all commands for a given service into an exhaustive, compile-time-checked hierarchy.

```
sealed interface InventoryCmd
       permits ReserveStock, ReleaseStock, CheckAvailability
                    │
    ┌───────────────┼───────────────┐
ReserveStock   ReleaseStock  CheckAvailability
```

The `Proc<S, InventoryCmd>` handler receives commands via its mailbox and switches exhaustively over the sealed type.

Key design points:
- Records are value objects: no setters, no identity, safe to share across threads.
- `proc.tell(cmd)` = fire-and-forget (Erlang `cast`).
- `proc.ask(cmd)` = command with reply (Erlang `call`), returning `CompletableFuture<S>`.
- Add a `CompletableFuture<R> replyTo` field to the record for explicit return-address style (see Pattern 11).

## API Reference

| Usage | Description |
|-------|-------------|
| `record Cmd(…) implements ServiceCmd {}` | Define a command as an immutable record |
| `proc.tell(cmd)` | Fire-and-forget command delivery |
| `proc.ask(cmd)` | Command + reply via `CompletableFuture<S>` |
| `switch (cmd) { case ReserveStock r -> … }` | Exhaustive dispatch in handler |

## Code Example

```java
import org.acme.Proc;

// --- Command hierarchy ---
sealed interface InventoryCmd
    permits ReserveStock, ReleaseStock, CheckAvailability {}

record ReserveStock(String sku, int quantity)         implements InventoryCmd {}
record ReleaseStock(String sku, int quantity)         implements InventoryCmd {}
record CheckAvailability(String sku)                  implements InventoryCmd {}

// --- Process state ---
record InventoryState(Map<String, Integer> stock) {
    static InventoryState empty() {
        return new InventoryState(new HashMap<>(Map.of(
            "SKU-A", 100, "SKU-B", 50)));
    }
}

// --- Handler: pure (S, M) -> S function ---
public class InventoryService {

    static InventoryState handle(InventoryState state, InventoryCmd cmd) {
        return switch (cmd) {
            case ReserveStock r -> {
                var updated = new HashMap<>(state.stock());
                updated.merge(r.sku(), -r.quantity(), Integer::sum);
                yield new InventoryState(updated);
            }
            case ReleaseStock r -> {
                var updated = new HashMap<>(state.stock());
                updated.merge(r.sku(), r.quantity(), Integer::sum);
                yield new InventoryState(updated);
            }
            case CheckAvailability c -> {
                // read-only: state unchanged, side-effect is the reply
                int qty = state.stock().getOrDefault(c.sku(), 0);
                System.out.printf("Available %s: %d%n", c.sku(), qty);
                yield state;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        var proc = Proc.of(InventoryState.empty(), InventoryService::handle);

        // Fire-and-forget commands
        proc.tell(new ReserveStock("SKU-A", 3));
        proc.tell(new ReleaseStock("SKU-B", 10));

        // Command with reply (returns current state)
        var future = proc.ask(new CheckAvailability("SKU-A"));
        var state  = future.get(2, TimeUnit.SECONDS);
        System.out.println("Remaining SKU-A: " + state.stock().get("SKU-A"));
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.TimeUnit;

class CommandMessageTest implements WithAssertions {

    @Test
    void reserveStockReducesInventory() throws Exception {
        var proc = Proc.of(InventoryState.empty(), InventoryService::handle);

        proc.tell(new ReserveStock("SKU-A", 10));

        var state = proc.ask(new CheckAvailability("SKU-A"))
                        .get(2, TimeUnit.SECONDS);

        assertThat(state.stock().get("SKU-A")).isEqualTo(90);
    }

    @Test
    void releaseStockIncreasesInventory() throws Exception {
        var proc = Proc.of(InventoryState.empty(), InventoryService::handle);

        proc.tell(new ReleaseStock("SKU-B", 20));

        var state = proc.ask(new CheckAvailability("SKU-B"))
                        .get(2, TimeUnit.SECONDS);

        assertThat(state.stock().get("SKU-B")).isEqualTo(70);
    }

    @Test
    void commandsAreImmutableValueObjects() {
        var cmd1 = new ReserveStock("SKU-A", 5);
        var cmd2 = new ReserveStock("SKU-A", 5);
        assertThat(cmd1).isEqualTo(cmd2);          // structural equality
        assertThat(cmd1).isNotSameAs(cmd2);        // separate instances
    }
}
```

## Caveats & Trade-offs

**Use when:**
- A specific service must perform a specific action.
- You want compile-time safety over the set of valid commands.
- Commands need to be serializable for audit logs or event sourcing.

**Avoid when:**
- The sender needs to broadcast to multiple receivers — use an Event Message instead.
- The command carries mutable state or is identity-based — records enforce value semantics, not object identity.
- The command set is open-ended at runtime — sealed interfaces require recompilation for new permits.
