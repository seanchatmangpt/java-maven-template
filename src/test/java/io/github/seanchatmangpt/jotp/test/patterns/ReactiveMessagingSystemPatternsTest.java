package io.github.seanchatmangpt.jotp.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.seanchatmangpt.jotp.reactive.ContentEnricher;
import io.github.seanchatmangpt.jotp.reactive.DeadLetterChannel;
import io.github.seanchatmangpt.jotp.reactive.DurableSubscriber;
import io.github.seanchatmangpt.jotp.reactive.DynamicRouter;
import io.github.seanchatmangpt.jotp.reactive.MessageFilter;
import io.github.seanchatmangpt.jotp.reactive.PointToPointChannel;
import io.github.seanchatmangpt.jotp.reactive.PollingConsumer;
import io.github.seanchatmangpt.jotp.reactive.PublishSubscribeChannel;
import io.github.seanchatmangpt.jotp.reactive.Resequencer;
import io.github.seanchatmangpt.jotp.reactive.WireTap;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * System & Advanced Reactive Messaging Patterns — completing the full EIP catalog to 39 patterns.
 *
 * <p>Patterns 34–39 of the EIP catalog implemented using Java 25+ virtual threads,
 * sealed interfaces, records, and structured concurrency primitives from {@code org.acme.reactive}:
 *
 * <ol start="34">
 *   <li>Dynamic Router — routing table updated at runtime without restart</li>
 *   <li>Content Enricher — augments message with external data before forwarding</li>
 *   <li>Wire Tap — non-intrusive message observation via virtual thread fork</li>
 *   <li>Polling Consumer — consumer controls retrieval rate (pull-based endpoint)</li>
 *   <li>Durable Subscriber — buffers messages across pause/resume cycles</li>
 *   <li>Resequencer — restores message order from an out-of-sequence stream</li>
 * </ol>
 *
 * <p>Together with {@link ReactiveMessagingFoundationPatternsTest} (10 patterns),
 * {@link ReactiveMessagingRoutingPatternsTest} (9 patterns), and
 * {@link ReactiveMessagingEndpointPatternsTest} (14 patterns), this file completes
 * coverage of all 39 canonical Enterprise Integration Patterns.
 */
@Timeout(60)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Reactive Messaging System Patterns (34–39 of 39 EIP)")
class ReactiveMessagingSystemPatternsTest implements WithAssertions {

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern 34: Dynamic Router
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("34. Dynamic Router — runtime routing table updates")
    class DynamicRouterPattern {

        @Test
        @DisplayName("routes to statically-configured channel before runtime update")
        void routesStatically() {
            var premiumReceived = new CopyOnWriteArrayList<String>();
            var standardReceived = new CopyOnWriteArrayList<String>();
            var premium = new PointToPointChannel<String>(premiumReceived::add);
            var standard = new PointToPointChannel<String>(standardReceived::add);

            var router = new DynamicRouter<String>();
            router.addRoute(msg -> msg.startsWith("VIP:"), premium);
            router.addRoute(msg -> msg.startsWith("STD:"), standard);

            router.send("VIP:order-1");
            router.send("STD:order-2");
            router.send("VIP:order-3");

            await().atMost(Duration.ofSeconds(2)).until(
                    () -> premiumReceived.size() == 2 && standardReceived.size() == 1);

            assertThat(premiumReceived).containsExactly("VIP:order-1", "VIP:order-3");
            assertThat(standardReceived).containsExactly("STD:order-2");
            premium.stop();
            standard.stop();
        }

        @Test
        @DisplayName("routes to newly added channel after live update")
        void addsRouteAtRuntime() {
            var expressReceived = new CopyOnWriteArrayList<String>();
            var dead = new DeadLetterChannel<String>();
            var express = new PointToPointChannel<String>(expressReceived::add);

            var router = new DynamicRouter<String>(dead);
            router.send("EXPRESS:order-1"); // no route → dead letter

            assertThat(dead.size()).isEqualTo(1);

            // Hot-add a route at runtime
            router.addRoute(msg -> msg.startsWith("EXPRESS:"), express);

            router.send("EXPRESS:order-2");

            await().atMost(Duration.ofSeconds(2)).until(() -> expressReceived.size() == 1);

            assertThat(expressReceived).containsExactly("EXPRESS:order-2");
            assertThat(dead.size()).isEqualTo(1); // only the first message
            express.stop();
        }

        @Test
        @DisplayName("prepended route takes priority over existing routes")
        void prependedRouteWins() {
            var normalReceived = new CopyOnWriteArrayList<String>();
            var overrideReceived = new CopyOnWriteArrayList<String>();
            var normal = new PointToPointChannel<String>(normalReceived::add);
            var override = new PointToPointChannel<String>(overrideReceived::add);

            var router = new DynamicRouter<String>();
            router.addRoute(_ -> true, normal); // catch-all route

            router.send("msg-1"); // → normal

            await().atMost(Duration.ofSeconds(2)).until(() -> normalReceived.size() == 1);

            // Prepend a higher-priority route for specific messages
            router.prependRoute(msg -> msg.startsWith("OVERRIDE:"), override);

            router.send("OVERRIDE:msg-2"); // → override (priority 0)
            router.send("msg-3");          // → normal  (catch-all)

            await().atMost(Duration.ofSeconds(2)).until(
                    () -> normalReceived.size() == 2 && overrideReceived.size() == 1);

            assertThat(overrideReceived).containsExactly("OVERRIDE:msg-2");
            assertThat(normalReceived).containsExactly("msg-1", "msg-3");
            normal.stop();
            override.stop();
        }

        @Test
        @DisplayName("removing routes redirects messages to default")
        void removeRouteRedirectsToDefault() {
            var mainReceived = new CopyOnWriteArrayList<String>();
            var dead = new DeadLetterChannel<String>();
            var main = new PointToPointChannel<String>(mainReceived::add);

            var router = new DynamicRouter<String>(dead);
            router.addRoute(msg -> msg.startsWith("A:"), main);

            router.send("A:one");
            await().atMost(Duration.ofSeconds(2)).until(() -> mainReceived.size() == 1);

            router.removeRoutesTo(main);
            router.send("A:two"); // → dead (route removed)

            await().atMost(Duration.ofSeconds(1)).untilAsserted(
                    () -> assertThat(dead.size()).isEqualTo(1));

            assertThat(router.routeCount()).isZero();
            main.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern 35: Content Enricher
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("35. Content Enricher — augments messages with external data")
    class ContentEnricherPattern {

        record OrderId(String id) {}

        record EnrichedOrder(String id, String customerName, double creditLimit) {}

        @Test
        @DisplayName("enriches message using external resource lookup")
        void enrichesWithExternalData() {
            var results = new CopyOnWriteArrayList<EnrichedOrder>();
            var downstream = new PointToPointChannel<EnrichedOrder>(results::add);

            // Simulate external customer database
            Map<String, String> customerDb = Map.of("C-1", "Alice", "C-2", "Bob");
            Map<String, Double> creditDb = Map.of("C-1", 5000.0, "C-2", 2500.0);

            record Resources(Map<String, String> names, Map<String, Double> credit) {}
            var resources = new Resources(customerDb, creditDb);

            var enricher = ContentEnricher.of(
                    resources,
                    (orderId, res) -> new EnrichedOrder(
                            orderId.id(),
                            res.names().getOrDefault(orderId.id(), "Unknown"),
                            res.credit().getOrDefault(orderId.id(), 0.0)),
                    downstream);

            enricher.send(new OrderId("C-1"));
            enricher.send(new OrderId("C-2"));
            enricher.send(new OrderId("C-99")); // unknown customer

            await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 3);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).customerName()).isEqualTo("Alice");
            assertThat(results.get(1).customerName()).isEqualTo("Bob");
            assertThat(results.get(2).customerName()).isEqualTo("Unknown");
            downstream.stop();
        }

        @Test
        @DisplayName("failed enrichment routes to error channel")
        void enrichmentFailureRoutedToError() {
            var results = new CopyOnWriteArrayList<String>();
            var errors = new DeadLetterChannel<Integer>();
            var downstream = new PointToPointChannel<String>(results::add);

            // Enricher that throws on negative input
            var enricher = ContentEnricher.of(
                    (Integer n, Void _) -> {
                        if (n < 0) throw new IllegalArgumentException("negative");
                        return "value:" + n;
                    },
                    downstream,
                    errors);

            enricher.send(42);
            enricher.send(-1);
            enricher.send(7);

            await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 2);

            assertThat(results).containsExactly("value:42", "value:7");
            assertThat(errors.size()).isEqualTo(1);
            downstream.stop();
        }

        @Test
        @DisplayName("simple mapping enricher requires no external resource")
        void simpleMappingEnricher() {
            var results = new CopyOnWriteArrayList<String>();
            var downstream = new PointToPointChannel<String>(results::add);

            var enricher = ContentEnricher.of(
                    (String s) -> s.toUpperCase() + "_ENRICHED",
                    downstream);

            enricher.send("hello");
            enricher.send("world");

            await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 2);

            assertThat(results).containsExactly("HELLO_ENRICHED", "WORLD_ENRICHED");
            downstream.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern 36: Wire Tap
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("36. Wire Tap — non-intrusive message observation")
    class WireTapPattern {

        @Test
        @DisplayName("tap receives copy; primary channel unaffected")
        void tapReceivesCopy() throws InterruptedException {
            var primaryReceived = new CopyOnWriteArrayList<String>();
            var tapReceived = new CopyOnWriteArrayList<String>();
            var primary = new PointToPointChannel<String>(primaryReceived::add);

            var tapped = new WireTap<>(primary, tapReceived::add);

            tapped.send("msg-1");
            tapped.send("msg-2");
            tapped.send("msg-3");

            await().atMost(Duration.ofSeconds(2)).until(
                    () -> primaryReceived.size() == 3 && tapReceived.size() == 3);

            assertThat(primaryReceived).containsExactly("msg-1", "msg-2", "msg-3");
            assertThat(tapReceived).containsExactlyInAnyOrder("msg-1", "msg-2", "msg-3");
            primary.stop();
        }

        @Test
        @DisplayName("crashing tap does not affect primary delivery")
        void crashingTapDoesNotAffectPrimary() throws InterruptedException {
            var primaryReceived = new CopyOnWriteArrayList<String>();
            var primary = new PointToPointChannel<String>(primaryReceived::add);

            var tapped = new WireTap<String>(primary, msg -> {
                throw new RuntimeException("tap explodes");
            });

            tapped.send("safe-1");
            tapped.send("safe-2");

            await().atMost(Duration.ofSeconds(2)).until(() -> primaryReceived.size() == 2);

            assertThat(primaryReceived).containsExactly("safe-1", "safe-2");
            primary.stop();
        }

        @Test
        @DisplayName("deactivated tap stops forwarding to observer")
        void deactivatedTapStopsObserving() throws InterruptedException {
            var primaryReceived = new CopyOnWriteArrayList<String>();
            var tapReceived = new CopyOnWriteArrayList<String>();
            var primary = new PointToPointChannel<String>(primaryReceived::add);

            var tapped = new WireTap<>(primary, tapReceived::add);

            tapped.send("before-1"); // tap active
            await().atMost(Duration.ofSeconds(2)).until(() -> tapReceived.size() == 1);

            tapped.deactivate();
            tapped.send("after-1"); // tap inactive
            tapped.send("after-2");

            await().atMost(Duration.ofSeconds(2)).until(() -> primaryReceived.size() == 3);

            assertThat(tapReceived).hasSize(1); // only the first message
            assertThat(primaryReceived).hasSize(3); // all messages reach primary
            primary.stop();
        }

        @Test
        @DisplayName("wire taps can be stacked for multiple observers")
        void stackedWireTaps() throws InterruptedException {
            var primaryReceived = new CopyOnWriteArrayList<String>();
            var tap1Received = new CopyOnWriteArrayList<String>();
            var tap2Received = new CopyOnWriteArrayList<String>();
            var primary = new PointToPointChannel<String>(primaryReceived::add);

            // Stack: tap2(tap1(primary))
            var tapped1 = new WireTap<>(primary, tap1Received::add);
            var tapped2 = new WireTap<String>(tapped1, tap2Received::add);

            tapped2.send("event");

            await().atMost(Duration.ofSeconds(2)).until(
                    () -> primaryReceived.size() == 1 && tap1Received.size() == 1 && tap2Received.size() == 1);

            assertThat(primaryReceived).containsExactly("event");
            assertThat(tap1Received).containsExactly("event");
            assertThat(tap2Received).containsExactly("event");
            primary.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern 37: Polling Consumer
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("37. Polling Consumer — pull-based message retrieval")
    class PollingConsumerPattern {

        @Test
        @DisplayName("polls and processes messages at configured interval")
        void pollsAndProcesses() throws Exception {
            var processed = new CopyOnWriteArrayList<Integer>();

            try (var consumer = new PollingConsumer<Integer>(processed::add, Duration.ofMillis(10))) {
                consumer.send(1);
                consumer.send(2);
                consumer.send(3);

                await().atMost(Duration.ofSeconds(2)).until(() -> processed.size() == 3);

                assertThat(processed).containsExactly(1, 2, 3);
                assertThat(consumer.polledCount()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("buffers messages when consumer is slow")
        void buffersWhenSlow() throws Exception {
            var processed = new CopyOnWriteArrayList<String>();

            // Slow consumer with 50ms processing delay
            try (var consumer = new PollingConsumer<String>(
                    msg -> {
                        processed.add(msg);
                        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    },
                    Duration.ofMillis(5))) {

                for (int i = 0; i < 20; i++) consumer.send("msg-" + i);

                await().atMost(Duration.ofSeconds(5)).until(() -> processed.size() == 20);

                assertThat(processed).hasSize(20);
            }
        }

        @Test
        @DisplayName("integrates with external BlockingQueue as message source")
        void usesExternalQueue() throws Exception {
            BlockingQueue<String> externalQueue = new LinkedBlockingQueue<>();
            var processed = new CopyOnWriteArrayList<String>();

            try (var consumer = new PollingConsumer<>(externalQueue, processed::add, Duration.ofMillis(10))) {
                externalQueue.put("external-1");
                externalQueue.put("external-2");

                await().atMost(Duration.ofSeconds(2)).until(() -> processed.size() == 2);

                assertThat(processed).containsExactly("external-1", "external-2");
                assertThat(externalQueue).isEmpty();
            }
        }

        @Test
        @DisplayName("crashing handler does not stop the polling loop")
        void crashingHandlerDoesNotStopPolling() throws Exception {
            var processed = new AtomicInteger(0);
            var failed = new AtomicInteger(0);

            try (var consumer = new PollingConsumer<Integer>(
                    n -> {
                        if (n % 2 == 0) {
                            failed.incrementAndGet();
                            throw new RuntimeException("even number rejected");
                        }
                        processed.incrementAndGet();
                    },
                    Duration.ofMillis(5))) {

                for (int i = 0; i < 10; i++) consumer.send(i); // 0,2,4,6,8 fail; 1,3,5,7,9 succeed

                await().atMost(Duration.ofSeconds(2)).until(
                        () -> processed.get() + failed.get() == 10);

                assertThat(processed.get()).isEqualTo(5);
                assertThat(failed.get()).isEqualTo(5);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern 38: Durable Subscriber
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("38. Durable Subscriber — messages buffered across pause/resume")
    class DurableSubscriberPattern {

        @Test
        @DisplayName("delivers messages when active")
        void deliversWhenActive() throws Exception {
            var received = new CopyOnWriteArrayList<String>();

            try (var sub = new DurableSubscriber<String>(received::add)) {
                sub.send("msg-1");
                sub.send("msg-2");

                await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 2);

                assertThat(received).containsExactly("msg-1", "msg-2");
                assertThat(sub.deliveredCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("buffers messages while paused; delivers on resume")
        void buffersWhilePausedDeliviversOnResume() throws Exception {
            var received = new CopyOnWriteArrayList<String>();

            try (var sub = new DurableSubscriber<String>(received::add)) {
                sub.send("before-pause");
                await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 1);

                sub.pause();
                sub.send("while-paused-1");
                sub.send("while-paused-2");
                sub.send("while-paused-3");

                // Messages should be buffered, not delivered while paused
                Thread.sleep(100);
                assertThat(received).hasSize(1);
                assertThat(sub.bufferSize()).isEqualTo(3);
                assertThat(sub.isPaused()).isTrue();

                sub.resume();

                await().atMost(Duration.ofSeconds(2)).until(() -> received.size() == 4);

                assertThat(received).containsExactly(
                        "before-pause", "while-paused-1", "while-paused-2", "while-paused-3");
            }
        }

        @Test
        @DisplayName("no messages are lost across multiple pause/resume cycles")
        void noMessagesLostAcrossMultipleCycles() throws Exception {
            var received = new CopyOnWriteArrayList<Integer>();

            try (var sub = new DurableSubscriber<Integer>(received::add)) {
                for (int cycle = 0; cycle < 3; cycle++) {
                    sub.pause();
                    for (int i = 0; i < 10; i++) sub.send(cycle * 10 + i);
                    sub.resume();
                    await().atMost(Duration.ofSeconds(2))
                            .until(() -> received.size() == (cycle + 1) * 10);
                }

                assertThat(received).hasSize(30);
                assertThat(sub.receivedCount()).isEqualTo(30);
                assertThat(sub.deliveredCount()).isEqualTo(30);
            }
        }

        @Test
        @DisplayName("crashing handler does not kill the subscriber")
        void crashingHandlerDoesNotKillSubscriber() throws Exception {
            var delivered = new AtomicInteger(0);
            var crashed = new AtomicInteger(0);

            try (var sub = new DurableSubscriber<String>(msg -> {
                if (msg.startsWith("CRASH")) {
                    crashed.incrementAndGet();
                    throw new RuntimeException("crash");
                }
                delivered.incrementAndGet();
            })) {
                sub.send("ok-1");
                sub.send("CRASH");
                sub.send("ok-2");
                sub.send("CRASH");
                sub.send("ok-3");

                await().atMost(Duration.ofSeconds(2))
                        .until(() -> delivered.get() + crashed.get() == 5);

                assertThat(delivered.get()).isEqualTo(3);
                assertThat(crashed.get()).isEqualTo(2);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern 39: Resequencer
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("39. Resequencer — restores order from out-of-sequence stream")
    class ResequencerPattern {

        record SeqMsg(long seq, String payload) {}

        @Test
        @DisplayName("reorders out-of-sequence messages")
        void reordersOutOfSequenceMessages() throws Exception {
            var ordered = new CopyOnWriteArrayList<SeqMsg>();
            var downstream = new PointToPointChannel<SeqMsg>(ordered::add);

            try (var resequencer = new Resequencer<SeqMsg, Long>(
                    SeqMsg::seq,
                    n -> n + 1,
                    downstream)) {

                // Send out of order: 2, 0, 4, 1, 3
                resequencer.send(new SeqMsg(2, "C"));
                resequencer.send(new SeqMsg(0, "A"));
                resequencer.send(new SeqMsg(4, "E"));
                resequencer.send(new SeqMsg(1, "B"));
                resequencer.send(new SeqMsg(3, "D"));

                await().atMost(Duration.ofSeconds(2)).until(() -> ordered.size() == 5);

                assertThat(ordered.stream().map(SeqMsg::seq))
                        .containsExactly(0L, 1L, 2L, 3L, 4L);
                assertThat(ordered.stream().map(SeqMsg::payload))
                        .containsExactly("A", "B", "C", "D", "E");
            }
            downstream.stop();
        }

        @Test
        @DisplayName("holds messages until gap is filled")
        void holdsMesagesUntilGapFilled() throws Exception {
            var ordered = new CopyOnWriteArrayList<Integer>();
            var downstream = new PointToPointChannel<Integer>(ordered::add);

            try (var resequencer = new Resequencer<Integer, Integer>(
                    n -> n,
                    n -> n + 1,
                    downstream)) {

                resequencer.send(3); // gap: waiting for 0, 1, 2
                resequencer.send(4);
                Thread.sleep(50);
                assertThat(ordered).isEmpty(); // gap at 0 blocks all

                resequencer.send(0); // fills start
                resequencer.send(1);
                resequencer.send(2); // fills entire gap → flush

                await().atMost(Duration.ofSeconds(2)).until(() -> ordered.size() == 5);

                assertThat(ordered).containsExactly(0, 1, 2, 3, 4);
            }
            downstream.stop();
        }

        @Test
        @DisplayName("handles large out-of-order stream efficiently")
        void handlesLargeOutOfOrderStream() throws Exception {
            var ordered = new CopyOnWriteArrayList<Long>();
            var downstream = new PointToPointChannel<Long>(ordered::add);
            int count = 1000;

            try (var resequencer = new Resequencer<Long, Long>(
                    n -> n,
                    n -> n + 1,
                    downstream)) {

                // Shuffle using a Fisher-Yates pass
                var nums = new ArrayList<Long>(count);
                for (long i = 0; i < count; i++) nums.add(i);
                var rng = new java.util.Random(42);
                for (int i = count - 1; i > 0; i--) {
                    int j = rng.nextInt(i + 1);
                    var tmp = nums.get(i);
                    nums.set(i, nums.get(j));
                    nums.set(j, tmp);
                }
                for (long n : nums) resequencer.send(n);

                await().atMost(Duration.ofSeconds(5)).until(() -> ordered.size() == count);

                for (int i = 0; i < count; i++) {
                    assertThat(ordered.get(i)).as("position %d", i).isEqualTo((long) i);
                }
                assertThat(resequencer.resequencedCount()).isEqualTo(count);
            }
            downstream.stop();
        }

        @Test
        @DisplayName("String-keyed resequencer preserves alphabetical ordering")
        void stringKeyedResequencer() throws Exception {
            var ordered = new CopyOnWriteArrayList<String>();
            var downstream = new PointToPointChannel<String>(ordered::add);

            try (var resequencer = new Resequencer<String, String>(
                    s -> s.substring(0, 1), // key is first char
                    s -> String.valueOf((char) (s.charAt(0) + 1)), // A → B → C ...
                    downstream)) {

                resequencer.send("C-third");
                resequencer.send("A-first");
                resequencer.send("B-second");

                await().atMost(Duration.ofSeconds(2)).until(() -> ordered.size() == 3);

                assertThat(ordered).containsExactly("A-first", "B-second", "C-third");
            }
            downstream.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration: all 6 system patterns in a composed pipeline
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration — all 6 system patterns composed")
    class SystemPatternsIntegration {

        record Order(long seq, String type, String customerId) {}
        record EnrichedOrder(long seq, String type, String customerName) {}

        @Test
        @DisplayName("Dynamic routing + enrichment + wire tap + durable delivery pipeline")
        void composedSystemPatternPipeline() throws Exception {
            // Audit log (Wire Tap observer)
            var auditLog = new CopyOnWriteArrayList<EnrichedOrder>();
            // Final results
            var premiumDelivered = new CopyOnWriteArrayList<EnrichedOrder>();
            var standardDelivered = new CopyOnWriteArrayList<EnrichedOrder>();

            // Channels
            var premiumCh = new PointToPointChannel<EnrichedOrder>(premiumDelivered::add);
            var standardCh = new PointToPointChannel<EnrichedOrder>(standardDelivered::add);

            // Dynamic router dispatches enriched orders by type
            var router = new DynamicRouter<EnrichedOrder>();
            router.addRoute(o -> o.type().equals("PREMIUM"), premiumCh);
            router.addRoute(o -> o.type().equals("STANDARD"), standardCh);

            // Wire tap audits every enriched order before routing
            var tappedRouter = new WireTap<EnrichedOrder>(router, auditLog::add);

            // Customer database (enrichment source)
            Map<String, String> customerDb = Map.of("C-1", "Alice", "C-2", "Bob", "C-3", "Carol");

            // Content enricher: Order → EnrichedOrder
            var enricher = ContentEnricher.of(
                    customerDb,
                    (order, db) -> new EnrichedOrder(
                            order.seq(),
                            order.type(),
                            db.getOrDefault(order.customerId(), "Unknown")),
                    tappedRouter);

            // Durable subscriber buffers raw orders for resilient ingestion
            try (var inbound = new DurableSubscriber<Order>(enricher::send)) {

                // Simulate brief outage: orders arrive while paused
                inbound.pause();
                inbound.send(new Order(0, "PREMIUM", "C-1"));
                inbound.send(new Order(1, "STANDARD", "C-2"));
                inbound.send(new Order(2, "PREMIUM", "C-3"));
                inbound.resume();

                // Live orders arrive after resume
                inbound.send(new Order(3, "STANDARD", "C-1"));

                await().atMost(Duration.ofSeconds(3)).until(
                        () -> premiumDelivered.size() == 2 && standardDelivered.size() == 2);

                assertThat(premiumDelivered).hasSize(2);
                assertThat(premiumDelivered.stream().map(EnrichedOrder::customerName))
                        .containsExactlyInAnyOrder("Alice", "Carol");
                assertThat(standardDelivered.stream().map(EnrichedOrder::customerName))
                        .containsExactlyInAnyOrder("Bob", "Alice");
                // Wire tap observed all 4 orders
                await().atMost(Duration.ofSeconds(1)).until(() -> auditLog.size() == 4);
                assertThat(auditLog).hasSize(4);
            }

            premiumCh.stop();
            standardCh.stop();
        }

        @Test
        @DisplayName("Polling consumer feeds resequencer for ordered processing")
        void pollingConsumerFeedsResequencer() throws Exception {
            var ordered = new CopyOnWriteArrayList<Long>();
            var downstream = new PointToPointChannel<Long>(ordered::add);

            try (var resequencer = new Resequencer<Long, Long>(n -> n, n -> n + 1, downstream);
                 var poller = new PollingConsumer<Long>(resequencer::send, Duration.ofMillis(5))) {

                // Inject out-of-order via polling consumer
                var queue = new LinkedTransferQueue<Long>();
                queue.offer(2L);
                queue.offer(0L);
                queue.offer(4L);
                queue.offer(1L);
                queue.offer(3L);

                // Drain the queue through the poller
                for (long v : new long[]{2, 0, 4, 1, 3}) poller.send(v);

                await().atMost(Duration.ofSeconds(3)).until(() -> ordered.size() == 5);

                assertThat(ordered).containsExactly(0L, 1L, 2L, 3L, 4L);
            }
            downstream.stop();
        }
    }
}
