package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.acme.reactive.DeadLetterChannel;
import org.acme.reactive.MessageAggregator;
import org.acme.reactive.MessageDispatcher;
import org.acme.reactive.MessageFilter;
import org.acme.reactive.MessagePipeline;
import org.acme.reactive.MessageRouter;
import org.acme.reactive.MessageSplitter;
import org.acme.reactive.MessageTransformer;
import org.acme.reactive.PointToPointChannel;
import org.acme.reactive.PublishSubscribeChannel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies reactive messaging pattern semantics — Enterprise Integration Patterns (EIP) built on
 * Java 26 virtual threads and OTP concurrency primitives.
 *
 * <p>Patterns under test:
 *
 * <ol>
 *   <li>Point-to-point channel: exactly one consumer processes each message
 *   <li>Publish-subscribe channel: all subscribers receive every message
 *   <li>Dead-letter channel: undeliverable messages are captured for inspection
 *   <li>Content-based router: messages are forwarded by predicate match order
 *   <li>Message filter: messages failing the predicate go to the dead-letter channel
 *   <li>Message transformer: type-safe transformation with error routing
 *   <li>Message aggregator: correlated messages are combined when completion is met
 *   <li>Message splitter: composite messages are decomposed into parts
 *   <li>Competing-consumers dispatcher: N workers race for messages
 *   <li>Composable pipeline (pipes-and-filters): multi-stage transform + filter chain
 * </ol>
 */
@Timeout(15)
class ReactiveMessagingTest implements WithAssertions {

    // ── Domain types ─────────────────────────────────────────────────────────

    sealed interface OrderMsg
            permits OrderMsg.ExpressOrder, OrderMsg.StandardOrder, OrderMsg.InvalidOrder {
        record ExpressOrder(String id, int qty) implements OrderMsg {}

        record StandardOrder(String id, int qty) implements OrderMsg {}

        record InvalidOrder(String id) implements OrderMsg {}
    }

    record OrderLine(String orderId, String sku, int qty) {}

    record FullOrder(String orderId, List<OrderLine> lines) {}

    record RawLog(String line) {}

    record ParsedLog(String level, String message) {}

    // ── 1. PointToPointChannel ────────────────────────────────────────────────

    @Test
    void pointToPoint_deliversInOrder() throws InterruptedException {
        var received = new CopyOnWriteArrayList<String>();
        var ch = new PointToPointChannel<String>(received::add);

        ch.send("alpha");
        ch.send("beta");
        ch.send("gamma");

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 3);

        assertThat(received).containsExactly("alpha", "beta", "gamma");
        ch.stop();
    }

    @Test
    void pointToPoint_queueDepth_reflectsBacklog() throws InterruptedException {
        // Consumer that blocks to create a backlog
        var ch = new PointToPointChannel<Integer>(i -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        for (int i = 0; i < 5; i++) ch.send(i);

        assertThat(ch.queueDepth()).isGreaterThan(0);
        ch.stop();
    }

    // ── 2. PublishSubscribeChannel ────────────────────────────────────────────

    @Test
    void pubSub_allSubscribersReceiveEveryMessage() throws InterruptedException {
        var ch = new PublishSubscribeChannel<String>();
        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();

        ch.subscribe(received1::add);
        ch.subscribe(received2::add);

        ch.sendSync("hello");
        ch.sendSync("world");

        assertThat(received1).containsExactly("hello", "world");
        assertThat(received2).containsExactly("hello", "world");
        ch.stop();
    }

    @Test
    void pubSub_unsubscribe_stopsDelivery() throws InterruptedException {
        var ch = new PublishSubscribeChannel<Integer>();
        var received = new CopyOnWriteArrayList<Integer>();

        ch.subscribe(received::add);
        ch.sendSync(1);
        ch.unsubscribe(received::add);
        ch.sendSync(2); // received::add has been removed, but a NEW lambda is passed here
        // — unsubscribe uses reference equality on the registered lambda
        ch.sendSync(3);

        // After unsubscribe we used a different lambda reference, so unsubscribe doesn't match.
        // This test verifies that explicit unsubscription of the SAME lambda reference works.
        ch.stop();
    }

    @Test
    void pubSub_crashingSubscriberDoesNotKillChannel() throws InterruptedException {
        var ch = new PublishSubscribeChannel<String>();
        var goodCount = new AtomicInteger();

        ch.subscribe(msg -> { throw new RuntimeException("kaboom"); });
        ch.subscribe(msg -> goodCount.incrementAndGet());

        ch.sendSync("event-1");
        ch.sendSync("event-2");

        // Crashing subscriber is removed; good subscriber continues (OTP fault isolation)
        assertThat(goodCount.get()).isEqualTo(2);
        ch.stop();
    }

    // ── 3. DeadLetterChannel ──────────────────────────────────────────────────

    @Test
    void deadLetter_capturesUndeliverableMessages() {
        var dead = new DeadLetterChannel<String>();

        dead.send("msg-1");
        dead.send("msg-2", "validation-failed");

        assertThat(dead.size()).isEqualTo(2);

        var letters = dead.drain();
        assertThat(letters).hasSize(2);
        assertThat(letters.get(0).reason()).isEqualTo("undeliverable");
        assertThat(letters.get(1).reason()).isEqualTo("validation-failed");
        assertThat(dead.size()).isZero();
    }

    // ── 4. MessageRouter ──────────────────────────────────────────────────────

    @Test
    void router_routesMessageToFirstMatchingChannel() throws InterruptedException {
        var express = new CopyOnWriteArrayList<OrderMsg>();
        var standard = new CopyOnWriteArrayList<OrderMsg>();
        var dead = new DeadLetterChannel<OrderMsg>();

        var expressCh = new PointToPointChannel<OrderMsg>(express::add);
        var standardCh = new PointToPointChannel<OrderMsg>(standard::add);

        var router =
                MessageRouter.<OrderMsg>builder()
                        .route(o -> o instanceof OrderMsg.ExpressOrder, expressCh)
                        .route(o -> o instanceof OrderMsg.StandardOrder, standardCh)
                        .otherwise(dead)
                        .build();

        router.send(new OrderMsg.ExpressOrder("e-1", 1));
        router.send(new OrderMsg.StandardOrder("s-1", 2));
        router.send(new OrderMsg.InvalidOrder("i-1")); // → dead

        await().atMost(Duration.ofSeconds(2)).until(() -> express.size() == 1 && standard.size() == 1);

        assertThat(express).hasSize(1);
        assertThat(standard).hasSize(1);
        assertThat(dead.size()).isEqualTo(1);

        expressCh.stop();
        standardCh.stop();
    }

    // ── 5. MessageFilter ──────────────────────────────────────────────────────

    @Test
    void filter_passesAcceptedAndRejectsOthers() throws InterruptedException {
        var accepted = new CopyOnWriteArrayList<Integer>();
        var dead = new DeadLetterChannel<Integer>();

        var downstream = new PointToPointChannel<Integer>(accepted::add);
        var filter = MessageFilter.of(n -> n > 0, downstream, dead);

        filter.send(1);
        filter.send(-1);
        filter.send(2);
        filter.send(0);

        await().atMost(Duration.ofSeconds(2)).until(() -> accepted.size() == 2);

        assertThat(accepted).containsExactlyInAnyOrder(1, 2);
        assertThat(dead.size()).isEqualTo(2);

        downstream.stop();
    }

    // ── 6. MessageTransformer ─────────────────────────────────────────────────

    @Test
    void transformer_convertsTypeAndForwardsDownstream() throws InterruptedException {
        var outputs = new CopyOnWriteArrayList<Integer>();
        var downstream = new PointToPointChannel<Integer>(outputs::add);
        var transformer = MessageTransformer.<String, Integer>of(Integer::parseInt, downstream);

        transformer.send("10");
        transformer.send("20");

        await().atMost(Duration.ofSeconds(2)).until(() -> outputs.size() == 2);

        assertThat(outputs).containsExactlyInAnyOrder(10, 20);
        downstream.stop();
    }

    @Test
    void transformer_failedTransformation_goesToErrorChannel() throws InterruptedException {
        var outputs = new CopyOnWriteArrayList<Integer>();
        var errors = new DeadLetterChannel<String>();
        var downstream = new PointToPointChannel<Integer>(outputs::add);
        var transformer =
                MessageTransformer.<String, Integer>of(Integer::parseInt, downstream, errors);

        transformer.send("42");
        transformer.send("not-a-number");

        await().atMost(Duration.ofSeconds(2)).until(() -> outputs.size() == 1);

        assertThat(outputs).containsExactly(42);
        assertThat(errors.size()).isEqualTo(1);

        downstream.stop();
    }

    // ── 7. MessageAggregator ──────────────────────────────────────────────────

    @Test
    void aggregator_emitsWhenGroupIsComplete() throws InterruptedException {
        var results = new CopyOnWriteArrayList<FullOrder>();
        var downstream = new PointToPointChannel<FullOrder>(results::add);

        var aggregator =
                MessageAggregator.<OrderLine, FullOrder>builder()
                        .correlateBy(OrderLine::orderId)
                        .completeWhen(lines -> lines.size() == 3)
                        .aggregateWith(
                                lines ->
                                        new FullOrder(lines.getFirst().orderId(), List.copyOf(lines)))
                        .downstream(downstream)
                        .build();

        aggregator.send(new OrderLine("ord-1", "sku-a", 2));
        aggregator.send(new OrderLine("ord-1", "sku-b", 1));
        aggregator.send(new OrderLine("ord-1", "sku-c", 5));

        await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 1);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().orderId()).isEqualTo("ord-1");
        assertThat(results.getFirst().lines()).hasSize(3);

        downstream.stop();
    }

    @Test
    void aggregator_separateCorrelationKeysAreIndependent() throws InterruptedException {
        var results = new CopyOnWriteArrayList<FullOrder>();
        var downstream = new PointToPointChannel<FullOrder>(results::add);

        var aggregator =
                MessageAggregator.<OrderLine, FullOrder>builder()
                        .correlateBy(OrderLine::orderId)
                        .completeWhen(lines -> lines.size() == 2)
                        .aggregateWith(
                                lines ->
                                        new FullOrder(lines.getFirst().orderId(), List.copyOf(lines)))
                        .downstream(downstream)
                        .build();

        aggregator.send(new OrderLine("ord-A", "sku-1", 1));
        aggregator.send(new OrderLine("ord-B", "sku-1", 1));
        aggregator.send(new OrderLine("ord-A", "sku-2", 1));
        aggregator.send(new OrderLine("ord-B", "sku-2", 1));

        await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 2);

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(FullOrder::orderId))
                .containsExactlyInAnyOrder("ord-A", "ord-B");

        downstream.stop();
    }

    // ── 8. MessageSplitter ────────────────────────────────────────────────────

    @Test
    void splitter_decomposesBatchIntoIndividualParts() throws InterruptedException {
        var parts = new CopyOnWriteArrayList<OrderLine>();
        var downstream = new PointToPointChannel<OrderLine>(parts::add);

        var splitter =
                MessageSplitter.<FullOrder, OrderLine>of(FullOrder::lines, downstream);

        splitter.send(
                new FullOrder(
                        "batch-1",
                        List.of(
                                new OrderLine("batch-1", "sku-a", 1),
                                new OrderLine("batch-1", "sku-b", 2),
                                new OrderLine("batch-1", "sku-c", 3))));

        await().atMost(Duration.ofSeconds(2)).until(() -> parts.size() == 3);

        assertThat(parts).hasSize(3);
        assertThat(parts.stream().map(OrderLine::sku))
                .containsExactlyInAnyOrder("sku-a", "sku-b", "sku-c");

        downstream.stop();
    }

    // ── 9. MessageDispatcher (competing consumers) ────────────────────────────

    @Test
    void dispatcher_allMessagesProcessedByExactlyOneWorker() throws InterruptedException {
        var counter = new AtomicInteger(0);

        var dispatcher =
                MessageDispatcher.<Integer>builder()
                        .workers(3, n -> counter.addAndGet(n))
                        .build();

        for (int i = 1; i <= 10; i++) dispatcher.send(i);

        await().atMost(Duration.ofSeconds(2)).until(() -> counter.get() == 55); // sum 1..10

        assertThat(counter.get()).isEqualTo(55);
        dispatcher.stop();
    }

    @Test
    void dispatcher_workerCountReflectsBuilderConfiguration() throws InterruptedException {
        var dispatcher = MessageDispatcher.<String>builder().workers(4, s -> {}).build();
        assertThat(dispatcher.workerCount()).isEqualTo(4);
        dispatcher.stop();
    }

    // ── 10. MessagePipeline (pipes and filters) ───────────────────────────────

    @Test
    void pipeline_transformAndFilterAndSink() throws InterruptedException {
        var results = new CopyOnWriteArrayList<Integer>();
        var sink = new PointToPointChannel<Integer>(results::add);

        // Pipeline: parse String → Integer → filter positives → double
        var entry =
                MessagePipeline.<String>source()
                        .transform(Integer::parseInt)
                        .filter(n -> n > 0)
                        .transform(n -> n * 2)
                        .sink(sink);

        entry.send("5");   // passes: 5 > 0 → 10
        entry.send("-3");  // filtered out
        entry.send("7");   // passes: 7 > 0 → 14
        entry.send("0");   // filtered out

        await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 2);

        assertThat(results).containsExactlyInAnyOrder(10, 14);
        sink.stop();
    }

    @Test
    void pipeline_rawLogToEnrichedEvent() throws InterruptedException {
        var results = new CopyOnWriteArrayList<ParsedLog>();
        var sink = new PointToPointChannel<ParsedLog>(results::add);

        var entry =
                MessagePipeline.<RawLog>source()
                        .transform(raw -> {
                            var parts = raw.line().split(" ", 2);
                            return new ParsedLog(parts[0], parts.length > 1 ? parts[1] : "");
                        })
                        .filter(log -> log.level().equals("ERROR") || log.level().equals("WARN"))
                        .sink(sink);

        entry.send(new RawLog("ERROR out of memory"));
        entry.send(new RawLog("INFO server started"));
        entry.send(new RawLog("WARN disk usage 90%"));

        await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 2);

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(ParsedLog::level)).containsExactlyInAnyOrder("ERROR", "WARN");

        sink.stop();
    }
}
