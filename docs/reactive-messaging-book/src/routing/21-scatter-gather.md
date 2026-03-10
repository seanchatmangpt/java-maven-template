# 21. Scatter-Gather

> *"Scatter-Gather broadcasts a request to multiple recipients and re-aggregates their replies into a single response."*

## Intent

Scatter-Gather parallelises a request across N independent services (the scatter phase) and then waits for all (or a quorum of) replies, merging them into a single result (the gather phase). This pattern trades latency for throughput: total time is dominated by the slowest responder, not the sum of all.

## OTP Analogy

In Erlang, scatter is `pmap` — spawn a linked process per task and collect replies:

```erlang
%% Scatter: spawn a worker per supplier
Workers = [spawn_link(fun() ->
               Price = query_supplier(Supplier, Item),
               CollectorPid ! {price, Supplier, Price}
           end) || Supplier <- Suppliers],

%% Gather: collect N replies
Quotes = [receive {price, S, P} -> {S, P} end || _ <- Workers].

%% Best price
{_, Best} = lists:min(fun({_, P1}, {_, P2}) -> P1 =< P2 end, Quotes).
```

JOTP replaces `spawn_link + receive` with `Parallel.all()` using `StructuredTaskScope` — safer and with automatic cancellation on failure.

## JOTP Implementation

`Parallel.all(tasks)` submits a list of `Callable<T>` to a `StructuredTaskScope.ShutdownOnFailure` scope, waits for all to complete, and returns `List<T>`. If any task fails, all others are cancelled and the exception propagates.

```
Request
   │
Parallel.all([
   () -> priceService.quote("SKU-A"),    ─── VThread 1 ──► PricingService
   () -> inventoryService.check("SKU-A"), ─ VThread 2 ──► InventoryService
   () -> shippingService.estimate(…),    ─── VThread 3 ──► ShippingService
])
   │  wait for all (fail-fast on any error)
   ▼
gather: [PriceQuote, InventoryStatus, ShippingEstimate]
   │
aggregate → OrderFeasibility
```

For best-of-N (first reply wins), use `StructuredTaskScope.ShutdownOnSuccess` directly.

Key design points:
- All tasks run on virtual threads — no platform thread starvation even with blocking I/O.
- `ShutdownOnFailure`: all-or-nothing; fail-fast cancels remaining tasks.
- `ShutdownOnSuccess`: first-wins; useful for redundant service calls.
- Keep tasks stateless and idempotent — a task may be cancelled partway through.

## API Reference

| Method | Description |
|--------|-------------|
| `Parallel.all(List<Callable<T>>)` | Scatter to all; gather all results |
| `Parallel.any(List<Callable<T>>)` | Scatter to all; return first success |
| `StructuredTaskScope.ShutdownOnFailure` | Cancel all on first failure |
| `StructuredTaskScope.ShutdownOnSuccess` | Cancel remaining on first success |

## Code Example

```java
import org.acme.Parallel;
import java.util.concurrent.StructuredTaskScope;

// --- Reply types from each service ---
record PriceQuote(String sku, double price, String supplier)     {}
record InventoryStatus(String sku, int available, String warehouse) {}
record ShippingEstimate(String sku, int days, double cost)          {}

// --- Aggregated result ---
record OrderFeasibility(
    String          sku,
    boolean         feasible,
    double          totalCost,
    int             deliveryDays,
    String          warehouse
) {}

// --- Simulated remote services (each blocks for a bit) ---
class PricingService {
    PriceQuote quote(String sku) throws InterruptedException {
        Thread.sleep(80);  // simulate I/O
        return new PriceQuote(sku, 29.99, "SupplierA");
    }
}
class InventoryService {
    InventoryStatus check(String sku) throws InterruptedException {
        Thread.sleep(120);
        return new InventoryStatus(sku, 50, "WH-EU-01");
    }
}
class ShippingService {
    ShippingEstimate estimate(String sku, String warehouse) throws InterruptedException {
        Thread.sleep(60);
        return new ShippingEstimate(sku, 2, 5.99);
    }
}

// --- Scatter-Gather orchestrator ---
public class ScatterGatherDemo {

    public static OrderFeasibility checkOrderFeasibility(String sku) throws Exception {
        var pricing   = new PricingService();
        var inventory = new InventoryService();
        var shipping  = new ShippingService();

        // SCATTER: run all 3 concurrently — total latency = max(80, 120, 60) ms
        var results = Parallel.all(List.of(
            () -> pricing.quote(sku),
            () -> inventory.check(sku),
            () -> shipping.estimate(sku, "WH-EU-01")
        ));

        // GATHER: extract typed results from list
        var priceQuote = (PriceQuote)       results.get(0);
        var invStatus  = (InventoryStatus)  results.get(1);
        var shipEst    = (ShippingEstimate) results.get(2);

        return new OrderFeasibility(
            sku,
            invStatus.available() > 0,
            priceQuote.price() + shipEst.cost(),
            shipEst.days(),
            invStatus.warehouse()
        );
    }

    public static void main(String[] args) throws Exception {
        var feasibility = checkOrderFeasibility("SKU-A");
        System.out.printf(
            "SKU=%s feasible=%b cost=%.2f days=%d warehouse=%s%n",
            feasibility.sku(), feasibility.feasible(),
            feasibility.totalCost(), feasibility.deliveryDays(),
            feasibility.warehouse()
        );
    }
}
```

## Best-of-N Variant

```java
// First supplier to respond wins (ShutdownOnSuccess)
try (var scope = new StructuredTaskScope.ShutdownOnSuccess<PriceQuote>()) {
    for (var supplier : suppliers)
        scope.fork(() -> supplier.quote(sku));

    scope.join();
    PriceQuote best = scope.result();  // fastest responder
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class ScatterGatherTest implements WithAssertions {

    @Test
    void feasibilityAggregatesAllServices() throws Exception {
        var result = ScatterGatherDemo.checkOrderFeasibility("SKU-A");

        assertThat(result.sku()).isEqualTo("SKU-A");
        assertThat(result.feasible()).isTrue();
        assertThat(result.totalCost()).isEqualTo(29.99 + 5.99);
        assertThat(result.deliveryDays()).isEqualTo(2);
    }

    @Test
    void parallelRunFasterThanSequential() throws Exception {
        // 3 tasks: 80 + 120 + 60 = 260 ms sequential
        // Parallel max = 120 ms — should complete well under 200 ms
        long start = System.currentTimeMillis();
        ScatterGatherDemo.checkOrderFeasibility("SKU-A");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(200);
    }

    @Test
    void failFastCancelsRemainingTasksOnError() {
        assertThatThrownBy(() -> Parallel.all(List.of(
            () -> { Thread.sleep(10); return "ok"; },
            () -> { throw new RuntimeException("service down"); },
            () -> { Thread.sleep(500); return "slow"; }
        ))).isInstanceOf(RuntimeException.class)
           .hasMessageContaining("service down");
    }
}
```

## Caveats & Trade-offs

**Use when:**
- N independent services must all contribute to a single result.
- Each service can be called in parallel with no ordering dependency.
- Latency matters and the services are I/O-bound (virtual threads shine here).

**Avoid when:**
- One service depends on the result of another — that is a pipeline, not scatter-gather.
- Tasks are not idempotent and partial failure leaves side effects — implement compensating transactions or sagas (Pattern 22).
- You need partial results when some services are unavailable — use `ShutdownOnSuccess` or implement a timeout with `orTimeout()` per subtask.
