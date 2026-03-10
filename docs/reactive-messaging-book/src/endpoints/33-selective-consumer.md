# 33. Selective Consumer

> *"A Selective Consumer only consumes messages that match a selection criterion, ignoring all others."*

## Intent

A Selective Consumer filters the message stream before it reaches the consumer's handler. Unlike a Message Filter (Pattern 16) — which is an infrastructure component between channels — a Selective Consumer is a consumer-side concern: the consumer declares what it wants and only processes matching messages, typically relying on `MessageFilter<T>` + `Proc` cooperation.

## OTP Analogy

Erlang's `selective receive` is a first-class language feature — a process can specify a pattern in its `receive` block and leave non-matching messages in the mailbox:

```erlang
%% Selective receive: consume only high-priority alerts
receive_alerts(State) ->
    receive
        {alert, #{priority := high} = Alert} ->
            handle_alert(Alert, State);
        {alert, #{priority := low}} ->
            % ignore — leave in mailbox (but careful: mailbox can grow)
            receive_alerts(State)
    end.
```

In JOTP, we achieve this with `MessageFilter<T>` routing to a dedicated `Proc` consumer rather than selective mailbox receive (to avoid unbounded mailbox growth).

## JOTP Implementation

Combine `MessageFilter<T>` and `PointToPointChannel<T>` (or `Proc`) to create a consumer that only sees pre-filtered messages:

```
Source Channel (all messages)
        │
MessageFilter<Alert>
   predicate: alert.priority() == Priority.HIGH
        │
        ├──► PointToPointChannel → PagerDutyConsumer  (high-priority only)
        └──► DeadLetterChannel                         (low-priority, logged)
```

Alternatively, embed the predicate directly in the consumer's `Proc` handler to skip messages:

```java
// Selective inside Proc handler
case Alert a when a.priority() == Priority.HIGH -> { /* process */ yield state; }
case Alert ignored                              -> state;  // selective: skip
```

Key design points:
- Prefer the `MessageFilter` approach — don't let low-priority messages pollute the consumer's mailbox.
- Pattern-guarded `switch` expressions (Java 21+) provide clean in-handler selection.
- Dead-letter skipped messages — never silently discard messages in production.

## API Reference

| Pattern | Description |
|---------|-------------|
| `MessageFilter<T>(predicate, consumer, deadLetter)` | Pre-filter before consumer |
| `case T t when predicate(t) ->` | In-handler selective switch |
| `Proc<S,T>` | Consumer process with mailbox |
| `DeadLetterChannel<T>` | Capture non-selected messages |

## Code Example

```java
import org.acme.Proc;
import org.acme.reactive.MessageFilter;
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.DeadLetterChannel;

// --- Domain type ---
enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

record Alert(String alertId, String message, Priority priority, Instant issuedAt) {}

// --- High-priority consumer: PagerDuty notifier ---
record NotifierState(int notified) {
    NotifierState increment() { return new NotifierState(notified + 1); }
}

class PagerDutyNotifier {
    static NotifierState handle(NotifierState state, Alert alert) {
        System.out.printf("[PAGER] ALERT %s: %s [%s]%n",
            alert.alertId(), alert.message(), alert.priority());
        notifyPagerDuty(alert);
        return state.increment();
    }

    private static void notifyPagerDuty(Alert alert) {
        // pagerduty.trigger(alert.alertId(), alert.message());
    }
}

public class SelectiveConsumerDemo {

    public static void main(String[] args) throws InterruptedException {
        var deadLetter = new DeadLetterChannel<Alert>();

        // Selective consumer: only HIGH and CRITICAL alerts reach the notifier
        var notifierProc = Proc.of(
            new NotifierState(0),
            PagerDutyNotifier::handle
        );

        // The filter acts as the selection mechanism
        MessageChannel<Alert> channel = new MessageFilter<>(
            alert -> alert.priority() == Priority.HIGH
                  || alert.priority() == Priority.CRITICAL,
            new PointToPointChannel<>(alert -> notifierProc.tell(alert)),
            deadLetter
        );

        // Publish a mix of alerts
        channel.send(new Alert("A-001", "Disk 80% full",           Priority.LOW,      Instant.now()));
        channel.send(new Alert("A-002", "CPU spike 95%",           Priority.HIGH,     Instant.now()));
        channel.send(new Alert("A-003", "API latency elevated",    Priority.MEDIUM,   Instant.now()));
        channel.send(new Alert("A-004", "Database connection lost", Priority.CRITICAL, Instant.now()));
        channel.send(new Alert("A-005", "Cache miss rate up",      Priority.LOW,      Instant.now()));

        Thread.sleep(100);

        var notifiedCount = notifierProc.ask(new Alert("CHECK", "", Priority.LOW, Instant.now()))
                                        .orTimeout(1, TimeUnit.SECONDS)
                                        .exceptionally(e -> new NotifierState(0))
                                        .join()
                                        .notified();

        System.out.printf("PagerDuty notified: %d alerts%n", notifiedCount); // 2
        System.out.printf("Dead-lettered:      %d alerts%n", deadLetter.size()); // 3

        channel.stop();
    }
}
```

## In-Handler Selective Pattern (Proc)

```java
// Alternative: selection inside the Proc handler using guarded patterns
sealed interface SystemMsg permits Alert, Heartbeat {}
record Heartbeat(Instant at) implements SystemMsg {}

Proc<NotifierState, SystemMsg> selective = Proc.of(
    new NotifierState(0),
    (state, msg) -> switch (msg) {
        // Only process HIGH or CRITICAL alerts
        case Alert a when a.priority() == Priority.HIGH
                       || a.priority() == Priority.CRITICAL ->
            PagerDutyNotifier.handle(state, a);
        // All other messages: skip (still consumed from mailbox, not queued)
        case Alert ignored    -> state;
        case Heartbeat ignored -> state;
    }
);
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class SelectiveConsumerTest implements WithAssertions {

    @Test
    void onlyHighAndCriticalReachConsumer() throws InterruptedException {
        var accepted   = new CopyOnWriteArrayList<Alert>();
        var deadLetter = new DeadLetterChannel<Alert>();
        var latch      = new CountDownLatch(2); // expect 2 accepted

        MessageChannel<Alert> channel = new MessageFilter<>(
            a -> a.priority() == Priority.HIGH || a.priority() == Priority.CRITICAL,
            new PointToPointChannel<>(a -> { accepted.add(a); latch.countDown(); }),
            deadLetter
        );

        channel.send(new Alert("1", "low",      Priority.LOW,      Instant.now()));
        channel.send(new Alert("2", "medium",   Priority.MEDIUM,   Instant.now()));
        channel.send(new Alert("3", "high",     Priority.HIGH,     Instant.now()));
        channel.send(new Alert("4", "critical", Priority.CRITICAL, Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(accepted).extracting(Alert::alertId).containsExactly("3", "4");
        assertThat(deadLetter.size()).isEqualTo(2);
        channel.stop();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- The consumer should only see a subset of messages on a shared channel.
- Different consumers on the same channel need different selection criteria.
- You want to enforce contracts about what a consumer is allowed to process.

**Avoid when:**
- Erlang-style selective receive is used to skip messages in the mailbox — this causes unbounded mailbox growth in JOTP; use `MessageFilter` pre-channel instead.
- The selection criteria are complex and stateful — move the selection logic into a dedicated `Proc` that acts as a gatekeeper.
- All messages should reach the consumer — remove the filter entirely.
