# 39. Message History

> *"Message History records how a message has been processed, tracking the sequence of all processing steps it has gone through."*

## Intent

Message History attaches an immutable, append-only log to a message as it travels through a pipeline. Each stage appends its name (or ID) to the history list before forwarding the message. This provides end-to-end observability: when a message arrives at a downstream consumer or a dead-letter channel, its history tells you exactly which stages it visited and in what order.

## OTP Analogy

In Erlang, message history is typically carried in the message tuple itself, threaded through each process:

```erlang
%% History as a reversed list (prepend is O(1), reverse at the end)
forward(Msg = #{history := H}, NextPid) ->
    NextPid ! Msg#{history := ["current_stage" | H]}.

%% At the end, reverse for chronological order
lists:reverse(maps:get(history, Msg)).
```

In Java 26, an immutable `List<String>` on a generic `Envelope<T>` record provides the same capability with compile-time type safety.

## JOTP Implementation

Add a `List<String> history` field to the `Envelope<T>` wrapper. Each stage creates a new `Envelope` with its name prepended (or appended) to the history before forwarding. Because records are immutable, the history list is never mutated — each stage produces a new envelope.

```
Envelope<Order>(history=[])
        │
   [OrderValidator]
        │
Envelope<Order>(history=["OrderValidator"])
        │
   [PriceCalculator]
        │
Envelope<Order>(history=["OrderValidator","PriceCalculator"])
        │
   [TaxEnricher]
        │
Envelope<Order>(history=["OrderValidator","PriceCalculator","TaxEnricher"])
        │
   [FulfillmentDispatcher]
        │
Envelope<Order>(history=["OrderValidator","PriceCalculator","TaxEnricher","FulfillmentDispatcher"])
```

Key design points:
- Use `List.copyOf` + add to create an immutable new list at each step; never mutate in place.
- The history grows with each hop — for long pipelines, consider only recording entry/exit points.
- Include the `Instant` of each step for latency profiling: `record HistoryEntry(String stage, Instant at)`.
- Wire `WireTap<T>` on any channel to observe message history without modifying the pipeline.

## API Reference

| Method | Description |
|--------|-------------|
| `envelope.history()` | Read-only history list |
| `envelope.addHistory(stage)` | Return new envelope with stage appended |
| `envelope.visitedStages()` | Alias for `history()` |
| `envelope.lastStage()` | Most recently visited stage |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.MessageChannel;

// --- History-aware envelope ---
record HistoryEntry(String stage, Instant at) {
    static HistoryEntry now(String stage) { return new HistoryEntry(stage, Instant.now()); }

    @Override public String toString() {
        return "%s@%s".formatted(stage, at.toString().substring(11, 23)); // HH:MM:SS.mmm
    }
}

record TrackedEnvelope<T>(
    T                    payload,
    UUID                 correlationId,
    List<HistoryEntry>   history
) {
    /** Factory: create a fresh envelope with empty history. */
    static <T> TrackedEnvelope<T> of(T payload) {
        return new TrackedEnvelope<>(payload, UUID.randomUUID(), List.of());
    }

    /** Return a new envelope with this stage appended to the history. */
    TrackedEnvelope<T> addHistory(String stage) {
        var newHistory = new ArrayList<>(history);
        newHistory.add(HistoryEntry.now(stage));
        return new TrackedEnvelope<>(payload, correlationId, List.copyOf(newHistory));
    }

    /** Convenience: append to history and transform the payload. */
    <U> TrackedEnvelope<U> transform(String stage, java.util.function.Function<T, U> fn) {
        var newHistory = new ArrayList<>(history);
        newHistory.add(HistoryEntry.now(stage));
        return new TrackedEnvelope<>(fn.apply(payload), correlationId, List.copyOf(newHistory));
    }

    String historyString() {
        return history.stream()
                      .map(HistoryEntry::toString)
                      .collect(java.util.stream.Collectors.joining(" → "));
    }
}

// --- Domain types ---
record Order(String id, double amount, String currency) {}
record PricedOrder(String id, double amount, double tax, String currency) {}

// --- Pipeline stages ---
public class MessageHistoryDemo {

    // Stage 1: validate
    static TrackedEnvelope<Order> validate(TrackedEnvelope<Order> env) {
        var order = env.payload();
        if (order.amount() <= 0) throw new IllegalArgumentException("invalid amount");
        System.out.printf("[VALIDATE] %s%n", order.id());
        return env.addHistory("OrderValidator");
    }

    // Stage 2: calculate tax
    static TrackedEnvelope<PricedOrder> calculateTax(TrackedEnvelope<Order> env) {
        var order = env.payload();
        double tax = order.amount() * 0.20;  // 20% VAT
        System.out.printf("[TAX] %s tax=%.2f%n", order.id(), tax);
        return env.transform("TaxCalculator",
            o -> new PricedOrder(o.id(), o.amount(), tax, o.currency()));
    }

    // Stage 3: dispatch to fulfilment
    static void dispatch(TrackedEnvelope<PricedOrder> env) {
        var finalEnv = env.addHistory("FulfillmentDispatcher");
        System.out.printf("[DISPATCH] %s total=%.2f tax=%.2f%n",
            finalEnv.payload().id(),
            finalEnv.payload().amount() + finalEnv.payload().tax(),
            finalEnv.payload().tax());
        System.out.printf("[HISTORY] %s%n", finalEnv.historyString());
    }

    public static void main(String[] args) throws InterruptedException {
        // Build the pipeline
        var dispatchChannel = new PointToPointChannel<TrackedEnvelope<PricedOrder>>(
            MessageHistoryDemo::dispatch);

        var taxChannel = new PointToPointChannel<TrackedEnvelope<Order>>(
            env -> dispatchChannel.send(calculateTax(env)));

        var validateChannel = new PointToPointChannel<TrackedEnvelope<Order>>(
            env -> taxChannel.send(validate(env)));

        // Send orders — each carries its own history
        validateChannel.send(TrackedEnvelope.of(new Order("ORD-001", 100.0, "GBP")));
        validateChannel.send(TrackedEnvelope.of(new Order("ORD-002",  50.0, "EUR")));
        validateChannel.send(TrackedEnvelope.of(new Order("ORD-003", 200.0, "USD")));

        Thread.sleep(200);
        validateChannel.stop();
        taxChannel.stop();
        dispatchChannel.stop();
    }
}
```

## Dead-Letter History Inspection

```java
// When a message is dead-lettered, its history shows where it failed
var deadLetter = new DeadLetterChannel<TrackedEnvelope<Order>>();

// After a pipeline run, inspect failed messages
for (var failed : deadLetter.drain()) {
    System.out.printf("[DEAD-LETTER] corrId=%s stages=%s%n",
        failed.correlationId().toString().substring(0, 8),
        failed.historyString());
    // Output: [DEAD-LETTER] corrId=3f8a1b2c stages=OrderValidator@10:45:32.123
    // → failed before reaching TaxCalculator
}
```

## WireTap Integration

```java
import org.acme.reactive.WireTap;

// Tap the pipeline after the tax stage to observe history without modifying it
var wireTap = new WireTap<TrackedEnvelope<PricedOrder>>(
    env -> System.out.printf("[WIRETAP] %s history=%s%n",
        env.payload().id(), env.historyString()),
    dispatchChannel  // primary channel continues normally
);

taxChannel → wireTap → dispatchChannel
// The WireTap observes the envelope (with history) and forwards unchanged
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class MessageHistoryTest implements WithAssertions {

    @Test
    void freshEnvelopeHasEmptyHistory() {
        var env = TrackedEnvelope.of(new Order("O1", 10.0, "USD"));
        assertThat(env.history()).isEmpty();
    }

    @Test
    void addHistoryAppendsStageInOrder() {
        var env = TrackedEnvelope.of(new Order("O2", 20.0, "EUR"));
        var e1  = env.addHistory("StageA");
        var e2  = e1.addHistory("StageB");
        var e3  = e2.addHistory("StageC");

        assertThat(e3.history()).extracting(HistoryEntry::stage)
                                .containsExactly("StageA", "StageB", "StageC");
    }

    @Test
    void addHistoryIsImmutable() {
        var env = TrackedEnvelope.of(new Order("O3", 30.0, "GBP"));
        var e1  = env.addHistory("StageA");
        var e2  = env.addHistory("StageB"); // branch from original

        assertThat(e1.history()).extracting(HistoryEntry::stage).containsExactly("StageA");
        assertThat(e2.history()).extracting(HistoryEntry::stage).containsExactly("StageB");
        assertThat(env.history()).isEmpty(); // original unchanged
    }

    @Test
    void transformPreservesHistoryAndCorrelationId() {
        var env    = TrackedEnvelope.of(new Order("O4", 40.0, "USD"));
        var priced = env.transform("TaxCalc",
            o -> new PricedOrder(o.id(), o.amount(), 8.0, o.currency()));

        assertThat(priced.correlationId()).isEqualTo(env.correlationId());
        assertThat(priced.history()).extracting(HistoryEntry::stage).containsExactly("TaxCalc");
        assertThat(priced.payload().tax()).isEqualTo(8.0);
    }

    @Test
    void historyStringFormatsCorrectly() {
        var env = TrackedEnvelope.of("payload")
                     .addHistory("A")
                     .addHistory("B")
                     .addHistory("C");

        assertThat(env.historyString()).contains("A").contains("B").contains("C").contains("→");
    }
}
```

## Caveats & Trade-offs

**Use when:**
- End-to-end observability across a multi-stage pipeline is required.
- You need to diagnose where in the pipeline a message stalled or failed.
- Compliance or audit requires a traceable record of every processing step.

**Avoid when:**
- The pipeline has many stages and the history list grows large — consider logging stage transitions to an external trace system (OpenTelemetry) instead of embedding in the message.
- History is security-sensitive — internal stage names embedded in messages may leak infrastructure details if messages are forwarded externally.
- All messages are ephemeral and observability is handled by a logging framework — the history overhead adds garbage collection pressure at high throughput.
