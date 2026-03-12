package io.github.seanchatmangpt.jotp.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Vaughn Vernon's Reactive Messaging Endpoint & Transformation Patterns with JOTP.
 * Tests: Channel Adapter, Messaging Bridge, Message Bus, Pipes and Filters,
 * Message Dispatcher, Event-Driven Consumer, Competing Consumers,
 * Selective Consumer, Idempotent Receiver, Service Activator,
 * Message Translator, Content Filter, Claim Check, Normalizer
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Reactive Messaging Endpoint & Transformation Patterns")
class ReactiveMessagingEndpointPatternsTest implements WithAssertions {

    @BeforeEach void setUp() { org.acme.ProcessRegistry.reset(); }
    @AfterEach void tearDown() { org.acme.ProcessRegistry.reset(); }

    @Nested
    @DisplayName("1. Channel Adapter Pattern")
    class ChannelAdapterPattern {
        @Test void bridgeExternalToMailbox() throws Exception {
            var externalQueue = new LinkedTransferQueue<String>();
            var processed = new AtomicInteger(0);
            var processor = new Proc<>(0, (Integer s, String msg) -> { processed.incrementAndGet(); return s + 1; });
            var running = new AtomicBoolean(true);
            var adapter = Thread.ofVirtual().start(() -> {
                while (running.get()) {
                    try {
                        var msg = externalQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (msg != null) processor.tell(msg);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            });
            for (int i = 0; i < 10; i++) externalQueue.put("msg-" + i);
            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 10);
            running.set(false); adapter.join(1000); processor.stop();
        }
    }

    @Nested
    @DisplayName("2. Messaging Bridge Pattern")
    class MessagingBridgePattern {
        @Test void bridgeTwoChannels() throws Exception {
            var ch2Received = new AtomicInteger(0);
            var channel2 = new Proc<>(0, (Integer s, String msg) -> { ch2Received.incrementAndGet(); return s + 1; });
            var bridge = new Proc<>(channel2, (Proc<Integer, String> ch2, String msg) -> { ch2.tell("bridged:" + msg); return ch2; });
            bridge.tell("m1"); bridge.tell("m2"); bridge.tell("m3");
            await().atMost(Duration.ofSeconds(2)).until(() -> ch2Received.get() == 3);
            channel2.stop(); bridge.stop();
        }
    }

    @Nested
    @DisplayName("3. Message Bus Pattern")
    class MessageBusPattern {
        @Test void eventManagerAsMessageBus() throws Exception {
            var orderCount = new AtomicInteger(0);
            var paymentCount = new AtomicInteger(0);
            var bus = EventManager.<Map.Entry<String, Object>>start();
            bus.addHandler(e -> { if (e.getKey().startsWith("order.")) orderCount.incrementAndGet(); });
            bus.addHandler(e -> { if (e.getKey().startsWith("payment.")) paymentCount.incrementAndGet(); });
            bus.notify(Map.entry("order.created", Map.of()));
            bus.notify(Map.entry("order.updated", Map.of()));
            bus.notify(Map.entry("payment.processed", Map.of()));
            await().atMost(Duration.ofSeconds(2)).until(() -> orderCount.get() == 2 && paymentCount.get() == 1);
            bus.stop();
        }
    }

    @Nested
    @DisplayName("4. Pipes and Filters Pattern")
    class PipesAndFiltersPattern {
        @Test void processingChain() throws Exception {
            var results = new ArrayList<String>();
            // Simple chain using direct tell
            var sink = new Proc<>(0, (Integer s, String msg) -> { synchronized (results) { results.add(msg); } return s + 1; });
            var filter1 = new Proc<>(sink, (Proc<Integer, String> next, String msg) -> { next.tell("processed:" + msg); return next; });
            filter1.tell("hello");
            filter1.tell("world");
            await().atMost(Duration.ofSeconds(2)).until(() -> results.size() == 2);
            assertThat(results).containsExactly("processed:hello", "processed:world");
            sink.stop(); filter1.stop();
        }
    }

    @Nested
    @DisplayName("5. Message Dispatcher Pattern")
    class MessageDispatcherPattern {
        @Test void loadBalanceAcrossWorkers() throws Exception {
            int workerCount = 5;
            var loads = new AtomicInteger[workerCount];
            var workers = new ArrayList<Proc<Integer, String>>();
            for (int i = 0; i < workerCount; i++) {
                loads[i] = new AtomicInteger(0);
                final int idx = i;
                workers.add(new Proc<>(0, (Integer s, String msg) -> { loads[idx].incrementAndGet(); return s + 1; }));
            }
            var dispatcher = new Proc<>(0, (Integer idx, String msg) -> { workers.get(idx % workerCount).tell(msg); return idx + 1; });
            for (int i = 0; i < 100; i++) dispatcher.tell("msg-" + i);
            await().atMost(Duration.ofSeconds(2)).until(() -> { int t = 0; for (var l : loads) t += l.get(); return t == 100; });
            for (var w : workers) w.stop();
            dispatcher.stop();
        }
    }

    @Nested
    @DisplayName("6. Event-Driven Consumer Pattern")
    class EventDrivenConsumerPattern {
        @Test void reactiveHandling() throws Exception {
            var received = new AtomicInteger(0);
            var consumer = new Proc<>(new ArrayList<String>(), (List<String> log, String msg) -> {
                var newLog = new ArrayList<>(log); newLog.add(msg); received.incrementAndGet(); return newLog;
            });
            for (int i = 0; i < 50; i++) consumer.tell("event-" + i);
            await().atMost(Duration.ofSeconds(2)).until(() -> received.get() == 50);
            consumer.stop();
        }
    }

    @Nested
    @DisplayName("7. Competing Consumers Pattern")
    class CompetingConsumersPattern {
        @Test void competingConsumers() throws Exception {
            var queue = new LinkedTransferQueue<String>();
            var counts = new AtomicInteger[3];
            for (int i = 0; i < 3; i++) counts[i] = new AtomicInteger(0);
            for (int i = 0; i < 30; i++) queue.put("msg-" + i);
            var running = new AtomicBoolean(true);
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                Thread.ofVirtual().start(() -> {
                    while (running.get() && counts[idx].get() < 15) {
                        try {
                            var msg = queue.poll(50, TimeUnit.MILLISECONDS);
                            if (msg != null) { counts[idx].incrementAndGet(); Thread.sleep(10); }
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                });
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> { int t = 0; for (var c : counts) t += c.get(); return t == 30; });
            running.set(false);
        }
    }

    @Nested
    @DisplayName("8. Selective Consumer Pattern")
    class SelectiveConsumerPattern {
        @Test void selectiveConsumption() throws Exception {
            var accepted = new AtomicInteger(0);
            var rejected = new AtomicInteger(0);
            var consumer = new Proc<>(0, (Integer s, String msg) -> {
                if (msg.contains("priority")) accepted.incrementAndGet();
                else rejected.incrementAndGet();
                return s + 1;
            });
            consumer.tell("msg priority high");
            consumer.tell("msg low");
            consumer.tell("msg priority medium");
            consumer.tell("msg low 2");
            consumer.tell("msg priority urgent");
            await().atMost(Duration.ofSeconds(2)).until(() -> accepted.get() == 3 && rejected.get() == 2);
            consumer.stop();
        }
    }

    @Nested
    @DisplayName("9. Idempotent Receiver Pattern")
    class IdempotentReceiverPattern {
        @Test void deduplication() throws Exception {
            var processed = new HashSet<String>();
            var uniqueCount = new AtomicInteger(0);
            var duplicateCount = new AtomicInteger(0);
            var receiver = new Proc<>(processed, (Set<String> seen, String msg) -> {
                var newSet = new HashSet<>(seen);
                if (newSet.add(msg)) uniqueCount.incrementAndGet();
                else duplicateCount.incrementAndGet();
                return newSet;
            });
            receiver.tell("MSG-1");
            receiver.tell("MSG-2");
            receiver.tell("MSG-1"); // duplicate
            receiver.tell("MSG-3");
            receiver.tell("MSG-2"); // duplicate
            await().atMost(Duration.ofSeconds(2)).until(() -> uniqueCount.get() == 3 && duplicateCount.get() == 2);
            receiver.stop();
        }
    }

    @Nested
    @DisplayName("10. Service Activator Pattern")
    class ServiceActivatorPattern {
        @Test void serviceActivated() throws Exception {
            var invocations = new AtomicInteger(0);
            var activator = new Proc<>(invocations, (AtomicInteger count, String msg) -> {
                if (msg.equals("activate")) count.incrementAndGet();
                return count;
            });
            activator.tell("activate");
            activator.tell("activate");
            activator.tell("activate");
            await().atMost(Duration.ofSeconds(2)).until(() -> invocations.get() == 3);
            activator.stop();
        }
    }

    @Nested
    @DisplayName("11. Message Translator Pattern")
    class MessageTranslatorPattern {
        @Test void translateFormat() throws Exception {
            record Legacy(String id, double amt) {}
            record Modern(String orderId, double amount) {}
            var translator = new Proc<>(0, (Integer s, Legacy legacy) -> {
                var modern = new Modern(legacy.id(), legacy.amt());
                return s + 1;
            });
            translator.tell(new Legacy("O1", 100.0));
            Thread.sleep(50);
            var stats = ProcSys.statistics(translator);
            assertThat(stats.messagesIn()).isEqualTo(1);
            translator.stop();
        }
    }

    @Nested
    @DisplayName("12. Content Filter Pattern")
    class ContentFilterPattern {
        @Test void extractContent() {
            record Full(String id, String customer, String address, double total) {}
            record Filtered(String id, String customer, double total) {}
            java.util.function.Function<Full, Filtered> filter = full ->
                    new Filtered(full.id(), full.customer(), full.total());
            var filtered = filter.apply(new Full("O1", "John", "123 Main", 150.0));
            assertThat(filtered.id()).isEqualTo("O1");
            assertThat(filtered.total()).isEqualTo(150.0);
        }
    }

    @Nested
    @DisplayName("13. Claim Check Pattern")
    class ClaimCheckPattern {
        @Test void storePayloadSendReference() throws Exception {
            var store = new ConcurrentHashMap<String, Object>();
            var payload = Map.of("large", "data");
            var checkId = "check-123";
            store.put(checkId, payload);
            var receiver = new Proc<>(0, (Integer s, String msg) -> {
                var storedPayload = store.get(msg);
                assertThat(storedPayload).isNotNull();
                return s + 1;
            });
            receiver.tell(checkId);
            Thread.sleep(50);
            var stats = ProcSys.statistics(receiver);
            assertThat(stats.messagesIn()).isEqualTo(1);
            receiver.stop();
        }
    }

    @Nested
    @DisplayName("14. Normalizer Pattern")
    class NormalizerPattern {
        @Test void convertToCanonical() {
            record Canonical(String id, String source, Map<String, Object> data) {}
            record JsonOrder(String orderId, Map<String, Object> properties) {}
            record XmlOrder(String id, String customer, String amount) {}
            java.util.function.Function<JsonOrder, Canonical> fromJson = json ->
                    new Canonical(json.orderId(), "JSON", json.properties());
            java.util.function.Function<XmlOrder, Canonical> fromXml = xml ->
                    new Canonical(xml.id(), "XML", Map.of("customer", xml.customer(), "amount", xml.amount()));
            var jsonResult = fromJson.apply(new JsonOrder("O1", Map.of("customer", "John")));
            assertThat(jsonResult.source()).isEqualTo("JSON");
            var xmlResult = fromXml.apply(new XmlOrder("O2", "Jane", "100"));
            assertThat(xmlResult.source()).isEqualTo("XML");
        }
    }

    @Nested
    @DisplayName("Integration")
    class Integration {
        @Test void completeMessageFlow() throws Exception {
            var sup = new Supervisor("endpoint-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var bus = EventManager.<String>start();
            var busReceived = new AtomicInteger(0);
            bus.addHandler(s -> busReceived.incrementAndGet());
            var activator = sup.supervise("activator", 0, (Integer s, String msg) -> {
                bus.notify("processed:" + msg);
                return s + 1;
            });
            activator.tell("hello world");
            activator.tell("test message");
            await().atMost(Duration.ofSeconds(3)).until(() -> busReceived.get() == 2);
            bus.stop();
            sup.shutdown();
        }

        @Test void faultTolerantEndpoint() throws Exception {
            var sup = new Supervisor("ft-sv", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));
            var processed = new AtomicInteger(0);
            var endpoint = sup.supervise("endpoint", 0, (Integer s, Object msg) -> {
                if (msg.equals("crash")) throw new RuntimeException("intentional");
                processed.incrementAndGet();
                return s + 1;
            });
            endpoint.tell("msg1");
            endpoint.tell("msg2");
            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 2);
            endpoint.tell("crash");
            await().atMost(Duration.ofSeconds(3)).until(() -> sup.isRunning());
            endpoint.tell("msg3");
            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 3);
            sup.shutdown();
        }
    }
}
