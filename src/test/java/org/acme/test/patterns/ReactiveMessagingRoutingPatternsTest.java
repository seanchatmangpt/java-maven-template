package org.acme.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.acme.EventManager;
import org.acme.Parallel;
import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.ProcSys;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Vaughn Vernon's Reactive Messaging Routing Patterns with JOTP.
 * Tests: Router, Content-Based Router, Recipient List, Splitter, Aggregator,
 * Resequencer, Scatter-Gather, Routing Slip, Process Manager
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Reactive Messaging Routing Patterns")
class ReactiveMessagingRoutingPatternsTest implements WithAssertions {

    @Nested
    @DisplayName("1. Message Router Pattern")
    class MessageRouterPattern {
        @Test void routeToDifferentHandlers() throws Exception {
            var orderCount = new AtomicInteger(0);
            var paymentCount = new AtomicInteger(0);
            var orderHandler = new Proc<>(0, (Integer s, String msg) -> { orderCount.incrementAndGet(); return s + 1; });
            var paymentHandler = new Proc<>(0, (Integer s, String msg) -> { paymentCount.incrementAndGet(); return s + 1; });
            record Dest(Proc<Integer, String> orders, Proc<Integer, String> payments) {}
            var router = new Proc<>(new Dest(orderHandler, paymentHandler), (Dest d, String msg) -> {
                if (msg.startsWith("O:")) d.orders().tell(msg);
                else if (msg.startsWith("P:")) d.payments().tell(msg);
                return d;
            });
            router.tell("O:order1");
            router.tell("P:payment1");
            router.tell("O:order2");
            await().atMost(Duration.ofSeconds(2)).until(() -> orderCount.get() == 2 && paymentCount.get() == 1);
            orderHandler.stop(); paymentHandler.stop(); router.stop();
        }
    }

    @Nested
    @DisplayName("2. Content-Based Router Pattern")
    class ContentBasedRouterPattern {
        @Test void routeBasedOnContent() throws Exception {
            var standardCount = new AtomicInteger(0);
            var expressCount = new AtomicInteger(0);
            var standardHandler = new Proc<>(0, (Integer s, String msg) -> { standardCount.incrementAndGet(); return s + 1; });
            var expressHandler = new Proc<>(0, (Integer s, String msg) -> { expressCount.incrementAndGet(); return s + 1; });
            record Handlers(Proc<Integer, String> std, Proc<Integer, String> exp) {}
            var router = new Proc<>(new Handlers(standardHandler, expressHandler), (Handlers h, String msg) -> {
                if (msg.contains("express")) h.exp().tell(msg);
                else h.std().tell(msg);
                return h;
            });
            router.tell("order standard");
            router.tell("order express");
            router.tell("order standard 2");
            await().atMost(Duration.ofSeconds(2)).until(() -> standardCount.get() == 2 && expressCount.get() == 1);
            standardHandler.stop(); expressHandler.stop(); router.stop();
        }
    }

    @Nested
    @DisplayName("3. Recipient List Pattern")
    class RecipientListPattern {
        @Test void multicastToMultipleRecipients() throws Exception {
            int count = 10;
            var received = new AtomicInteger[count];
            for (int i = 0; i < count; i++) received[i] = new AtomicInteger(0);
            var em = EventManager.<String>start();
            for (int i = 0; i < count; i++) {
                final int idx = i;
                em.addHandler(s -> received[idx].incrementAndGet());
            }
            em.notify("broadcast");
            await().atMost(Duration.ofSeconds(2)).until(() -> {
                for (int i = 0; i < count; i++) if (received[i].get() != 1) return false;
                return true;
            });
            em.stop();
        }
    }

    @Nested
    @DisplayName("4. Splitter Pattern")
    class SplitterPattern {
        @Test void splitBatchIntoIndividual() throws Exception {
            var processed = new AtomicInteger(0);
            var worker = new Proc<>(0, (Integer s, String item) -> { processed.incrementAndGet(); return s + 1; });
            var splitter = new Proc<>(worker, (Proc<Integer, String> w, List<String> batch) -> {
                for (String item : batch) w.tell(item);
                return w;
            });
            splitter.tell(List.of("i1", "i2", "i3", "i4", "i5"));
            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 5);
            worker.stop(); splitter.stop();
        }
    }

    @Nested
    @DisplayName("5. Aggregator Pattern")
    class AggregatorPattern {
        @Test void aggregateResponses() throws Exception {
            var latch = new CountDownLatch(5);
            var aggregator = new Proc<>(new ArrayList<Integer>(), (List<Integer> list, Integer msg) -> {
                var newList = new ArrayList<>(list);
                newList.add(msg);
                latch.countDown();
                return newList;
            });
            for (int i = 1; i <= 5; i++) aggregator.tell(i);
            latch.await(2, TimeUnit.SECONDS);
            // Query the final state using ProcSys.getState (non-intrusive state query)
            var finalState = ProcSys.getState(aggregator).get(2, TimeUnit.SECONDS);
            var sum = finalState.stream().mapToInt(i -> i).sum();
            assertThat(sum).isEqualTo(15); // 1+2+3+4+5
            aggregator.stop();
        }
    }

    @Nested
    @DisplayName("6. Resequencer Pattern")
    class ResequencerPattern {
        @Test void reorderMessages() throws Exception {
            var outputOrder = new ArrayList<Integer>();
            record SeqMsg(int seq) {}
            record ReseqState(int next, Map<Integer, Boolean> buffer) {
                static ReseqState initial() { return new ReseqState(0, new HashMap<>()); }
            }
            var resequencer = new Proc<>(ReseqState.initial(), (ReseqState s, SeqMsg msg) -> {
                var newBuffer = new HashMap<>(s.buffer());
                newBuffer.put(msg.seq(), true);
                int next = s.next();
                while (newBuffer.containsKey(next)) { outputOrder.add(next); newBuffer.remove(next); next++; }
                return new ReseqState(next, newBuffer);
            });
            resequencer.tell(new SeqMsg(2));
            resequencer.tell(new SeqMsg(0));
            resequencer.tell(new SeqMsg(3));
            resequencer.tell(new SeqMsg(1));
            resequencer.tell(new SeqMsg(4));
            await().atMost(Duration.ofSeconds(2)).until(() -> outputOrder.size() == 5);
            assertThat(outputOrder).containsExactly(0, 1, 2, 3, 4);
            resequencer.stop();
        }
    }

    @Nested
    @DisplayName("7. Scatter-Gather Pattern")
    class ScatterGatherPattern {
        @Test void scatterGatherWithParallel() throws Exception {
            int taskCount = 100;
            var tasks = IntStream.range(0, taskCount)
                    .mapToObj(i -> (java.util.function.Supplier<Integer>) () -> i * i)
                    .collect(Collectors.toList());
            var result = Parallel.all(tasks);
            assertThat(result.isSuccess()).isTrue();
            result.fold(list -> assertThat(list).hasSize(taskCount), error -> { throw new RuntimeException(error); });
        }
    }

    @Nested
    @DisplayName("8. Routing Slip Pattern")
    class RoutingSlipPattern {
        @Test void messageCarriesRoutingSlip() throws Exception {
            var processingOrder = new ArrayList<String>();
            record RoutingSlip(List<String> steps, int current) {}
            // Use an array to hold the reference for self-referencing
            final Proc<Integer, RoutingSlip>[] routerHolder = new Proc[1];
            routerHolder[0] = new Proc<>(0, (Integer s, RoutingSlip slip) -> {
                if (slip.current() < slip.steps().size()) {
                    processingOrder.add(slip.steps().get(slip.current()));
                    if (slip.current() + 1 < slip.steps().size()) {
                        routerHolder[0].tell(new RoutingSlip(slip.steps(), slip.current() + 1));
                    }
                }
                return s + 1;
            });
            routerHolder[0].tell(new RoutingSlip(List.of("validate", "enrich", "transform", "persist"), 0));
            await().atMost(Duration.ofSeconds(2)).until(() -> processingOrder.size() == 4);
            assertThat(processingOrder).containsExactly("validate", "enrich", "transform", "persist");
            routerHolder[0].stop();
        }
    }

    @Nested
    @DisplayName("9. Process Manager Pattern")
    class ProcessManagerPattern {
        @Test void processManagerOrchestration() throws Exception {
            record SagaState(String orderId, boolean paid, boolean reserved) {
                static SagaState start(String id) { return new SagaState(id, false, false); }
            }
            var completedSagas = new ConcurrentHashMap<String, SagaState>();
            var sagaManager = new Proc<>(SagaState.start("ORDER-123"), (SagaState s, String msg) -> {
                if (msg.equals("inventory")) return new SagaState(s.orderId(), s.paid(), true);
                if (msg.equals("payment")) {
                    var newState = new SagaState(s.orderId(), true, s.reserved());
                    if (newState.paid() && newState.reserved()) completedSagas.put(newState.orderId(), newState);
                    return newState;
                }
                return s;
            });
            sagaManager.tell("inventory");
            sagaManager.tell("payment");
            await().atMost(Duration.ofSeconds(2)).until(() -> completedSagas.containsKey("ORDER-123"));
            sagaManager.stop();
        }
    }

    @Nested
    @DisplayName("Integration")
    class Integration {
        @Test void fullRoutingPipeline() throws Exception {
            var sup = new Supervisor("routing-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var processed = new AtomicInteger(0);
            var processor = sup.supervise("processor", 0, (Integer s, String msg) -> {
                processed.incrementAndGet();
                return s + 1;
            });
            for (int i = 0; i < 100; i++) processor.tell("msg-" + i);
            await().atMost(Duration.ofSeconds(5)).until(() -> processed.get() == 100);
            sup.shutdown();
        }
    }
}