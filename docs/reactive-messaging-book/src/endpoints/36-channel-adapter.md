# 36. Channel Adapter

> *"A Channel Adapter connects an application to a messaging system, translating between the application's native interface and the messaging channel's interface."*

## Intent

A Channel Adapter bridges an external system — JMS queue, HTTP endpoint, file system, database poll — to a `MessageChannel<T>`. It hides the external system's API behind the uniform channel abstraction. There are two directions: an **inbound adapter** (external → channel) and an **outbound adapter** (channel → external).

## OTP Analogy

In Erlang/OTP, a `gen_server` or `gen_statem` that wraps an external port or socket is the Channel Adapter pattern:

```erlang
%% Inbound adapter: poll a JMS queue, forward to internal process
handle_info({jms_msg, Msg}, State) ->
    Translated = translate(Msg),
    InternalChannel ! {message, Translated},
    {noreply, State};

%% Outbound adapter: receive from internal channel, publish to Kafka
handle_cast({publish, Event}, State) ->
    kafka_client:produce(State#state.topic, encode(Event)),
    {noreply, State}.
```

JOTP implements this with a virtual-thread polling loop for inbound adapters and a `PointToPointChannel` consumer for outbound adapters.

## JOTP Implementation

### Inbound Adapter (External → Channel)

A virtual thread polls the external source and forwards translated messages to a `MessageChannel<T>`:

```
External System (JMS / HTTP / File / DB)
        │
VirtualThread (polling loop or event hook)
        │  translate(externalMsg) → T
        ▼
MessageChannel<T> (JOTP pipeline)
```

### Outbound Adapter (Channel → External)

A `PointToPointChannel<T>` consumer forwards messages to the external system:

```
MessageChannel<T> (JOTP pipeline)
        │
PointToPointChannel<T>
    consumer: externalClient.send(translate(msg))
        │
External System (JMS / HTTP / File / Kafka)
```

Key design points:
- Keep the adapter thin: translate only, no business logic.
- Use virtual threads for non-blocking I/O in polling loops.
- Implement backoff/retry in the polling loop to handle transient external failures.
- Attach a `Supervisor` to restart the adapter Proc if it crashes.

## API Reference

| Pattern | Description |
|---------|-------------|
| `Thread.ofVirtual().start(pollingLoop)` | Inbound: virtual thread polling adapter |
| `new PointToPointChannel<>(outboundConsumer)` | Outbound: channel-to-external adapter |
| `translate(external)` | Conversion: external format → internal type |
| `Supervisor.of(adapterSpec)` | Wrap adapter under supervision |

## Code Example

```java
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.MessageChannel;

// --- Internal canonical type ---
record InternalOrder(String id, double amount, String currency, String source) {}

// --- Simulated external systems ---
class JmsOrderQueue {
    private final Queue<String> queue = new LinkedList<>(List.of(
        "ORD-001|149.99|USD", "ORD-002|89.99|EUR", "ORD-003|29.99|GBP"
    ));

    String poll() { return queue.poll(); }  // returns null when empty
    boolean isEmpty() { return queue.isEmpty(); }
}

class HttpOrderApi {
    record HttpResponse(int status, String body) {}
    HttpResponse post(String json) {
        System.out.printf("[HTTP-OUT] POST %s%n", json);
        return new HttpResponse(200, "{\"status\":\"accepted\"}");
    }
}

// ─────────────────────────────────────────────────────────────
// INBOUND ADAPTER: JMS Queue → MessageChannel<InternalOrder>
// ─────────────────────────────────────────────────────────────
public class JmsInboundAdapter implements AutoCloseable {

    private final JmsOrderQueue          source;
    private final MessageChannel<InternalOrder> target;
    private final Thread                 pollerThread;
    private volatile boolean             running = true;

    public JmsInboundAdapter(JmsOrderQueue source, MessageChannel<InternalOrder> target) {
        this.source = source;
        this.target = target;
        this.pollerThread = Thread.ofVirtual().name("jms-adapter").start(this::poll);
    }

    private void poll() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                String raw = source.poll();
                if (raw != null) {
                    InternalOrder order = translate(raw);
                    System.out.printf("[JMS-IN] Received: %s → %s%n", raw, order.id());
                    target.send(order);
                } else if (source.isEmpty()) {
                    break; // no more messages
                }
                Thread.sleep(10); // backoff to avoid tight poll loop
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Translate from "id|amount|currency" CSV to InternalOrder
    private InternalOrder translate(String raw) {
        var parts = raw.split("\\|");
        return new InternalOrder(parts[0], Double.parseDouble(parts[1]), parts[2], "JMS");
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        pollerThread.join(2000);
    }
}

// ─────────────────────────────────────────────────────────────
// OUTBOUND ADAPTER: MessageChannel<InternalOrder> → HTTP API
// ─────────────────────────────────────────────────────────────
class HttpOutboundAdapter {

    private final HttpOrderApi api;
    private final MessageChannel<InternalOrder> channel;

    HttpOutboundAdapter(HttpOrderApi api) {
        this.api     = api;
        this.channel = new PointToPointChannel<>(this::forward);
    }

    MessageChannel<InternalOrder> getChannel() { return channel; }

    private void forward(InternalOrder order) {
        String json = """
            {"id":"%s","amount":%.2f,"currency":"%s"}
            """.formatted(order.id(), order.amount(), order.currency()).strip();
        var response = api.post(json);
        if (response.status() != 200)
            throw new RuntimeException("HTTP error: " + response.status());
    }

    void stop() throws InterruptedException { channel.stop(); }
}

// ─────────────────────────────────────────────────────────────
// PIPELINE: JMS → Processing → HTTP
// ─────────────────────────────────────────────────────────────
public class ChannelAdapterDemo {
    public static void main(String[] args) throws Exception {
        var httpAdapter = new HttpOutboundAdapter(new HttpOrderApi());

        // Processing channel in the middle
        var processingChannel = new PointToPointChannel<InternalOrder>(order -> {
            System.out.printf("[PROCESS] %s %.2f %s%n",
                order.id(), order.amount(), order.currency());
            // Forward to outbound adapter
            httpAdapter.getChannel().send(order);
        });

        // Inbound adapter feeds the processing channel
        var jmsAdapter = new JmsInboundAdapter(new JmsOrderQueue(), processingChannel);

        Thread.sleep(500); // let adapters process all messages

        jmsAdapter.close();
        processingChannel.stop();
        httpAdapter.stop();
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class ChannelAdapterTest implements WithAssertions {

    @Test
    void inboundAdapterTranslatesAndForwardsMessages() throws Exception {
        var received = new CopyOnWriteArrayList<InternalOrder>();
        var latch    = new CountDownLatch(3); // 3 messages in the simulated queue

        var target = new PointToPointChannel<InternalOrder>(order -> {
            received.add(order);
            latch.countDown();
        });

        try (var adapter = new JmsInboundAdapter(new JmsOrderQueue(), target)) {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received).hasSize(3);
        assertThat(received).extracting(InternalOrder::source).containsOnly("JMS");
        assertThat(received).extracting(InternalOrder::id)
                            .containsExactlyInAnyOrder("ORD-001", "ORD-002", "ORD-003");
        target.stop();
    }

    @Test
    void translationParsesCorrectly() throws Exception {
        var received = new CopyOnWriteArrayList<InternalOrder>();
        var latch    = new CountDownLatch(1);

        // Single-item queue
        var queue = new JmsOrderQueue() {
            { /* queue has only one preset message */ }
        };

        var target = new PointToPointChannel<InternalOrder>(o -> {
            received.add(o); latch.countDown();
        });

        try (var adapter = new JmsInboundAdapter(queue, target)) {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received.get(0).amount()).isEqualTo(149.99);
        assertThat(received.get(0).currency()).isEqualTo("USD");
        target.stop();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- You need to integrate a legacy or external system with a modern `MessageChannel`-based pipeline.
- You want to isolate external API changes behind a thin translation layer.
- The external system uses polling (JMS, DB outbox, SFTP drop) and you need to bridge it to a push-based channel.

**Avoid when:**
- The external system already speaks a channel-compatible protocol (e.g., it can push directly to a `MessageChannel`).
- The adapter grows with business logic — keep it thin; push processing into a downstream `Proc`.
- High-frequency polling is needed — consider an event-driven callback (JMS `MessageListener`, WebSocket, SSE) rather than a polling loop.
