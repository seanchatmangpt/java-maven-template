package io.github.seanchatmangpt.jotp.doctest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import io.github.seanchatmangpt.jotp.MessageBus;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Doctest: {@link MessageBus} — jOTP Reactive Messaging.
 *
 * <p>Documents the topic-based {@link MessageBus} API through executable examples. The message bus
 * is the enterprise integration backbone for jOTP applications — publishers and subscribers are
 * decoupled by topic, wildcards enable flexible routing, and dead-letter handling prevents silent
 * message loss.
 *
 * <p>HTML output: {@code target/site/doctester/MessageBusDocIT.html}.
 */
@ExtendWith(DocTestExtension.class)
@Timeout(10)
class MessageBusDocIT implements WithAssertions {

    // ── Basic publish-subscribe ──────────────────────────────────────────────────

    @DocSection("Basic Publish / Subscribe")
    @DocNote(
            "Subscribe to a topic with subscribe(topic, handler). "
                    + "Publish with publish(topic, payload). "
                    + "Each subscriber receives its own virtual-thread delivery.")
    @DocCode(
            """
            MessageBus bus = MessageBus.create();
            List<Object> received = new CopyOnWriteArrayList<>();
            bus.subscribe("orders.created", env -> received.add(env.payload()));
            bus.publish("orders.created", new Order("O-1", 99.99));
            await().atMost(2, SECONDS).until(() -> received.size() == 1);
            bus.onStop(null);
            """)
    @Test
    void publish_subscribe_basicDelivery() throws Exception {
        record Order(String id, double amount) {}

        MessageBus bus = MessageBus.create();
        List<Object> received = new CopyOnWriteArrayList<>();

        bus.subscribe("orders.created", env -> received.add(env.payload()));
        bus.publish("orders.created", new Order("O-1", 99.99));

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> received.size() == 1);
        assertThat(received.get(0)).isInstanceOf(Order.class);
    }

    @DocNote(
            "Multiple subscribers on the same topic each receive every message — "
                    + "broadcast / fan-out semantics.")
    @Test
    void multiple_subscribers_allReceive() throws Exception {
        MessageBus bus = MessageBus.create();
        List<Object> a = new CopyOnWriteArrayList<>();
        List<Object> b = new CopyOnWriteArrayList<>();

        bus.subscribe("telemetry", env -> a.add(env.payload()));
        bus.subscribe("telemetry", env -> b.add(env.payload()));
        bus.publish("telemetry", "sample-1");
        bus.publish("telemetry", "sample-2");

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> a.size() == 2 && b.size() == 2);
        assertThat(a).containsExactly("sample-1", "sample-2");
        assertThat(b).containsExactly("sample-1", "sample-2");
    }

    // ── Wildcard subscriptions ───────────────────────────────────────────────────

    @DocSection("Wildcard Subscriptions")
    @DocNote(
            "subscribePattern(\"telemetry.*\") matches all topics under the 'telemetry' prefix. "
                    + "Use \"topic.>\" for deep hierarchical matching.")
    @DocCode(
            """
            MessageBus bus = MessageBus.create();
            List<String> topics = new CopyOnWriteArrayList<>();
            bus.subscribePattern("telemetry.*", env -> topics.add(env.topic()));
            bus.publish("telemetry.speed", 200);
            bus.publish("telemetry.rpm",   8500);
            bus.publish("orders.created",  "O-1");  // does NOT match
            await().atMost(2, SECONDS).until(() -> topics.size() == 2);
            """)
    @Test
    void wildcard_subscription_matchesPrefix() throws Exception {
        MessageBus bus = MessageBus.create();
        List<String> topics = new CopyOnWriteArrayList<>();

        bus.subscribePattern("telemetry.*", env -> topics.add(env.topic()));
        bus.publish("telemetry.speed", 200);
        bus.publish("telemetry.rpm", 8500);
        bus.publish("orders.created", "O-1"); // should NOT match

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> topics.size() == 2);
        assertThat(topics).containsExactlyInAnyOrder("telemetry.speed", "telemetry.rpm");
    }

    // ── Synchronous publish ──────────────────────────────────────────────────────

    @DocSection("Synchronous Publish")
    @DocNote(
            "publishSync(envelope, timeout) blocks until all subscribers have processed the "
                    + "message. Use for critical events where you need delivery confirmation.")
    @Test
    void publishSync_blocksUntilDelivered() throws Exception {
        MessageBus bus = MessageBus.create();
        List<Object> received = new ArrayList<>();

        bus.subscribe("critical", env -> received.add(env.payload()));

        boolean delivered =
                bus.publishSync(
                        MessageBus.Envelope.of("critical", "ALERT"),
                        Duration.ofSeconds(2));

        assertThat(delivered).isTrue();
        assertThat(received).hasSize(1);
    }

    // ── Subscription cancellation ────────────────────────────────────────────────

    @DocSection("Subscription Lifecycle")
    @DocNote(
            "Subscriptions can be cancelled via sub.cancel(). "
                    + "After cancellation, the handler no longer receives messages.")
    @DocCode(
            """
            MessageBus.Subscription sub = bus.subscribe("events", handler);
            assertThat(sub.isActive()).isTrue();
            sub.cancel();
            assertThat(sub.isActive()).isFalse();
            """)
    @Test
    void subscription_cancel_stopsDelivery() throws Exception {
        MessageBus bus = MessageBus.create();
        List<Object> received = new CopyOnWriteArrayList<>();

        var sub = bus.subscribe("events", env -> received.add(env.payload()));
        assertThat(sub.isActive()).isTrue();

        bus.publish("events", "before-cancel");
        Awaitility.await()
                .atMost(1, TimeUnit.SECONDS)
                .until(() -> received.size() == 1);

        sub.cancel();
        assertThat(sub.isActive()).isFalse();

        bus.publish("events", "after-cancel");
        Thread.sleep(100); // give virtual thread time to deliver (it shouldn't)
        assertThat(received).hasSize(1); // still 1 — no delivery after cancel
    }

    // ── Stats ────────────────────────────────────────────────────────────────────

    @DocSection("Statistics")
    @DocNote(
            "MessageBus.stats() returns counters for published, delivered, and failed messages "
                    + "along with active subscription and topic counts.")
    @Test
    void stats_reflectActivity() throws Exception {
        MessageBus bus = MessageBus.create("stats-test");
        bus.subscribe("stats.topic", env -> {});

        bus.publish("stats.topic", "msg-1");
        bus.publish("stats.topic", "msg-2");

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> bus.stats().delivered() >= 2);

        MessageBus.Stats stats = bus.stats();
        assertThat(stats.published()).isGreaterThanOrEqualTo(2);
        assertThat(stats.delivered()).isGreaterThanOrEqualTo(2);
        assertThat(stats.activeSubscriptions()).isGreaterThanOrEqualTo(1);
    }

    // ── Dead letter handling ─────────────────────────────────────────────────────

    @DocSection("Dead Letter Handling")
    @DocNote(
            "Failed message deliveries (handler throws) are routed to the DeadLetterHandler. "
                    + "The default handler logs to stderr; use builder().deadLetterHandler() "
                    + "to store or re-process failed messages.")
    @DocWarning(
            "A subscriber that always throws will increment the 'failed' counter but will NOT "
                    + "prevent other subscribers on the same topic from receiving the message.")
    @Test
    void deadLetter_countsFailed_doesNotBlockOtherSubscribers() throws Exception {
        List<Object> dlq = new CopyOnWriteArrayList<>();
        MessageBus bus =
                MessageBus.builder()
                        .deadLetterHandler(
                                (env, err) -> dlq.add(env.payload()))
                        .build();

        List<Object> good = new CopyOnWriteArrayList<>();
        bus.subscribe("topic", env -> { throw new RuntimeException("simulated failure"); });
        bus.subscribe("topic", env -> good.add(env.payload()));
        bus.publish("topic", "payload");

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> dlq.size() == 1 || good.size() == 1);

        MessageBus.Stats stats = bus.stats();
        assertThat(stats.failed()).isGreaterThanOrEqualTo(1);
        assertThat(good).hasSize(1);
    }
}
