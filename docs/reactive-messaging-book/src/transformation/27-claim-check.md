# 27. Claim Check

> *"A Claim Check stores the full message payload in a store and passes only a lightweight ticket downstream. The recipient redeems the ticket when it needs the data."*

## Intent

When a message payload is too large to pass through a messaging channel efficiently, the Claim Check pattern offloads the payload to a data store and replaces it with a small reference token (the "claim check" or "ticket"). Downstream consumers retrieve the full payload only when they actually need it, and the store can apply TTL-based eviction to reclaim memory.

## OTP Analogy

In Erlang, large binaries are reference-counted and shared — passing them in messages is cheap because you pass a reference, not a copy. But for cross-node or cross-process stores, a common pattern is to store in ETS (Erlang Term Storage) and pass the ETS key:

```erlang
%% Store large term in ETS, pass the key
Key = make_ref(),
ets:insert(payload_store, {Key, LargePayload}),
gen_server:cast(NextStage, {process, Key}).

%% Recipient retrieves when needed
handle_cast({process, Key}, State) ->
    [{Key, LargePayload}] = ets:lookup(payload_store, Key),
    do_work(LargePayload),
    ets:delete(payload_store, Key),
    {noreply, State}.
```

JOTP's `MessageStore` uses `ConcurrentHashMap<UUID, T>` backed by a `Proc` for thread-safe access.

## JOTP Implementation

```
Producer
  │  LargePayload
  │
  ├──[store]──► MessageStore.put(uuid, payload)
  │
  │  ClaimTicket(uuid)
  ▼
PointToPointChannel<ClaimTicket>
  │  (small ticket travels through pipeline)
  ▼
Consumer
  │  payload = MessageStore.get(uuid)   ← redeem
  │  MessageStore.remove(uuid)          ← purge after use
  │  process(payload)
```

Key design points:
- Tickets are tiny (`UUID` = 16 bytes); payloads stay in the store.
- `MessageStore` is backed by a `Proc` — all reads/writes are serialised through the mailbox, preventing concurrent modification issues.
- Implement TTL-based eviction via `ProcTimer.sendAfter` to prevent unbounded growth.
- The store can be a `ConcurrentHashMap` for simple cases or an external cache (Redis, Hazelcast) for cross-process scenarios.

## API Reference

| Method | Description |
|--------|-------------|
| `MessageStore.put(uuid, payload)` | Store payload; return UUID ticket |
| `MessageStore.get(uuid)` | Retrieve payload by ticket |
| `MessageStore.remove(uuid)` | Purge payload after consumption |
| `MessageStore.size()` | Number of stored payloads |
| `ProcTimer.sendAfter(delay, proc, Evict(uuid))` | Schedule TTL eviction |

## Code Example

```java
import org.acme.reactive.MessageStore;
import org.acme.reactive.PointToPointChannel;
import java.util.UUID;

// --- Large payload (imagine this is megabytes of data) ---
record LargeReport(
    String   reportId,
    String   title,
    byte[]   pdfContent,      // potentially large
    Map<String, Double> metrics,
    List<String> rawLines
) {}

// --- Claim ticket: just a UUID reference ---
record ClaimTicket(UUID claimId, String reportId, Instant issuedAt) {
    static ClaimTicket forReport(UUID claimId, String reportId) {
        return new ClaimTicket(claimId, reportId, Instant.now());
    }
}

// --- Simple in-process store ---
public class ClaimCheckDemo {

    // The store: ConcurrentHashMap guarded by a Proc for safe concurrent access
    private static final MessageStore<LargeReport> STORE = new MessageStore<>();

    // Producer side: store payload, emit ticket
    static ClaimTicket checkIn(LargeReport report) {
        UUID claimId = STORE.put(report);  // returns a UUID ticket
        System.out.printf("[STORE] Checked in report=%s claimId=%s size=%d bytes%n",
            report.reportId(),
            claimId.toString().substring(0, 8),
            report.pdfContent().length);
        return ClaimTicket.forReport(claimId, report.reportId());
    }

    // Consumer side: redeem ticket, process, purge
    static void redeem(ClaimTicket ticket) {
        LargeReport report = STORE.get(ticket.claimId());
        if (report == null) {
            System.out.printf("[ERROR] Claim expired or unknown: %s%n", ticket.claimId());
            return;
        }
        System.out.printf("[PROCESS] Processing %s (%d metrics)%n",
            report.reportId(), report.metrics().size());
        STORE.remove(ticket.claimId());  // purge after use
        System.out.printf("[STORE] Purged claimId=%s, store size=%d%n",
            ticket.claimId().toString().substring(0, 8), STORE.size());
    }

    public static void main(String[] args) throws InterruptedException {
        // Simulated large reports
        var report1 = new LargeReport("RPT-001", "Q1 Sales",
            new byte[1_000_000],  // 1 MB of PDF
            Map.of("revenue", 1_500_000.0, "units", 3421.0),
            List.of("line1", "line2"));

        var report2 = new LargeReport("RPT-002", "Q2 Forecast",
            new byte[2_000_000],  // 2 MB
            Map.of("projected", 1_800_000.0),
            List.of("forecast_line1"));

        // Check-in: only tickets flow through the channel
        var ticketChannel = new PointToPointChannel<ClaimTicket>(ClaimCheckDemo::redeem);

        ClaimTicket t1 = checkIn(report1);
        ClaimTicket t2 = checkIn(report2);

        System.out.printf("Store size after check-in: %d%n", STORE.size()); // 2

        // Tickets flow through the pipeline (lightweight)
        ticketChannel.send(t1);
        ticketChannel.send(t2);

        Thread.sleep(100); // let the channel process
        System.out.printf("Store size after redemption: %d%n", STORE.size()); // 0

        ticketChannel.stop();
    }
}
```

## TTL Eviction with ProcTimer

```java
import org.acme.ProcTimer;

// Schedule automatic eviction 5 minutes after check-in
sealed interface StoreCmd permits Evict {}
record Evict(UUID claimId) implements StoreCmd {}

// On check-in, schedule eviction
UUID claimId = STORE.put(report);
ProcTimer.sendAfter(
    Duration.ofMinutes(5),
    storeProc,          // the Proc backing the store
    new Evict(claimId)  // message to send after delay
);
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class ClaimCheckTest implements WithAssertions {

    @Test
    void checkInReturnsMeaningfulTicket() {
        var store  = new MessageStore<String>();
        var uuid   = store.put("large payload data");

        assertThat(uuid).isNotNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void redeemReturnsOriginalPayload() {
        var store   = new MessageStore<String>();
        var payload = "large payload content";
        var uuid    = store.put(payload);

        assertThat(store.get(uuid)).isEqualTo(payload);
    }

    @Test
    void removeEvictsFromStore() {
        var store = new MessageStore<String>();
        var uuid  = store.put("data to evict");

        store.remove(uuid);

        assertThat(store.get(uuid)).isNull();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void unknownTicketReturnsNull() {
        var store = new MessageStore<String>();
        assertThat(store.get(UUID.randomUUID())).isNull();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Message payloads are large (multi-MB) and passing them through channels is expensive.
- Multiple downstream stages exist but only the final consumer needs the full payload.
- You want to apply TTL-based retention without burdening message consumers.

**Avoid when:**
- Payloads are small — the check-in/redeem overhead outweighs the savings.
- The consumer is always co-located and can receive the full payload cheaply.
- You need cross-process or crash-resilient storage — `MessageStore` is in-memory; use Redis or S3 with a signed URL as the ticket for durability.
