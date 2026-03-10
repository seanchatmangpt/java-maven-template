# 11. Return Address

> *"Include a Return Address in the message so the receiver knows where to send the reply — even if they never saw the requestor before."*

## Intent

A Return Address decouples the reply mechanism from the requester's identity. The requestor embeds a "reply-to" handle directly in the request record. The handler completes that handle directly, without needing to know anything about the original caller.

## OTP Analogy

In Erlang, every `gen_server:call` implicitly embeds `{self(), Ref}` as the reply address. The server uses `gen_server:reply(From, Reply)` to send the response back to exactly the caller, without knowing the caller's Pid in advance:

```erlang
handle_call({get_price, Sku}, From, State) ->
    Price = lookup_price(Sku, State),
    gen_server:reply(From, {ok, Price}),  % From IS the return address
    {noreply, State}.
```

In JOTP we make this explicit: the `From` field is a `CompletableFuture<R>` embedded in the request record itself.

## JOTP Implementation

Embed a `CompletableFuture<R>` inside the request record. The handler calls `replyTo.complete(value)` to deliver the reply. The caller holds the same `CompletableFuture` reference and awaits it.

```
Requestor                           Proc<S, Cmd>
    │                                   │
    │  CompletableFuture<Price> fut      │
    │  proc.tell(new QueryPrice(        │
    │      sku, fut))  ────────────────►│
    │                                   │  handler: fut.complete(price)
    │◄────────────── fut resolves ───────│
```

This pattern is preferable to `proc.ask()` when:
- You want a specific return type `R` instead of the full process state `S`.
- Multiple steps in the handler may complete the future at different points.
- You want to complete the future from any thread (e.g., inside a virtual-thread callback).

Key design points:
- The `replyTo` field is a `CompletableFuture<R>` — a single-use, write-once handle.
- The handler MUST call either `replyTo.complete(value)` or `replyTo.completeExceptionally(ex)` — never neither.
- The caller still MUST use a timeout: `fut.get(2, TimeUnit.SECONDS)`.
- Nest this pattern for multi-hop pipelines: each hop passes the future along until the final stage resolves it.

## API Reference

| Pattern | Description |
|---------|-------------|
| `record Req(…, CompletableFuture<R> replyTo)` | Request record with embedded return address |
| `replyTo.complete(value)` | Handler delivers the reply |
| `replyTo.completeExceptionally(ex)` | Handler signals failure |
| `future.get(2, TimeUnit.SECONDS)` | Caller retrieves reply with mandatory timeout |

## Code Example

```java
import org.acme.Proc;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// --- Specific reply type (not the full state) ---
record PriceQuote(String sku, double price, String currency) {}

// --- Command hierarchy with embedded return address ---
sealed interface CatalogCmd permits QueryPrice, UpdatePrice {}

record QueryPrice(
    String                          sku,
    CompletableFuture<PriceQuote>   replyTo   // ← Return Address
) implements CatalogCmd {}

record UpdatePrice(String sku, double newPrice) implements CatalogCmd {}

// --- Process state ---
record CatalogState(Map<String, Double> prices) {
    static CatalogState defaults() {
        return new CatalogState(new HashMap<>(Map.of(
            "SKU-A", 29.99, "SKU-B", 49.99)));
    }
}

// --- Handler ---
public class CatalogService {

    static CatalogState handle(CatalogState state, CatalogCmd cmd) {
        return switch (cmd) {
            case QueryPrice q -> {
                double price = state.prices().getOrDefault(q.sku(), -1.0);
                if (price < 0)
                    q.replyTo().completeExceptionally(
                        new IllegalArgumentException("Unknown SKU: " + q.sku()));
                else
                    q.replyTo().complete(new PriceQuote(q.sku(), price, "USD"));
                yield state;  // state unchanged by a query
            }
            case UpdatePrice u -> {
                var updated = new HashMap<>(state.prices());
                updated.put(u.sku(), u.newPrice());
                yield new CatalogState(updated);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        var proc = Proc.of(CatalogState.defaults(), CatalogService::handle);

        // Create the return address before sending the request
        var replyTo = new CompletableFuture<PriceQuote>();
        proc.tell(new QueryPrice("SKU-A", replyTo));

        // Wait for the handler to complete the future
        PriceQuote quote = replyTo.get(2, TimeUnit.SECONDS);
        System.out.printf("Price: %s = %.2f %s%n",
            quote.sku(), quote.price(), quote.currency());

        // Unknown SKU — exceptional completion
        var badReply = new CompletableFuture<PriceQuote>();
        proc.tell(new QueryPrice("SKU-UNKNOWN", badReply));
        badReply
            .orTimeout(2, TimeUnit.SECONDS)
            .exceptionally(ex -> { System.out.println("Error: " + ex.getMessage()); return null; })
            .join();
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class ReturnAddressTest implements WithAssertions {

    Proc<CatalogState, CatalogCmd> freshCatalog() {
        return Proc.of(CatalogState.defaults(), CatalogService::handle);
    }

    @Test
    void handlerCompletesReturnAddress() throws Exception {
        var proc    = freshCatalog();
        var replyTo = new CompletableFuture<PriceQuote>();

        proc.tell(new QueryPrice("SKU-B", replyTo));

        var quote = replyTo.get(2, TimeUnit.SECONDS);
        assertThat(quote.sku()).isEqualTo("SKU-B");
        assertThat(quote.price()).isEqualTo(49.99);
    }

    @Test
    void unknownSkuCompletesExceptionally() throws Exception {
        var proc    = freshCatalog();
        var replyTo = new CompletableFuture<PriceQuote>();

        proc.tell(new QueryPrice("NOPE", replyTo));

        assertThatThrownBy(() -> replyTo.get(2, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NOPE");
    }

    @Test
    void multipleIndependentQueriesResolveIndependently() throws Exception {
        var proc  = freshCatalog();
        var fA    = new CompletableFuture<PriceQuote>();
        var fB    = new CompletableFuture<PriceQuote>();

        proc.tell(new QueryPrice("SKU-A", fA));
        proc.tell(new QueryPrice("SKU-B", fB));

        assertThat(fA.get(2, TimeUnit.SECONDS).price()).isEqualTo(29.99);
        assertThat(fB.get(2, TimeUnit.SECONDS).price()).isEqualTo(49.99);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- You want a specific reply type `R`, not the full process state `S` that `proc.ask()` returns.
- The handler makes an async call and completes the future from a callback.
- Building multi-hop pipelines where the return address is forwarded through intermediate stages.

**Avoid when:**
- `proc.ask()` already returns the data you need — the embedded future adds boilerplate for no benefit.
- The `CompletableFuture` may never be completed (e.g., handler crashes before completing it) — use `proc.ask()` which JOTP completes automatically on handler exit.
- Multiple callers share the same `CompletableFuture` — a future can only be completed once; create one per request.
