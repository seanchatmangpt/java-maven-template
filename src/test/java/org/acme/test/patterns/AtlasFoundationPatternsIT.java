package org.acme.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.acme.EventManager;
import org.acme.Proc;
import org.acme.ProcSys;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;
import org.acme.test.patterns.AtlasDomain.AnalysisResult;
import org.acme.test.patterns.AtlasDomain.DataStatusType;
import org.acme.test.patterns.AtlasDomain.DataStatusType.Good;
import org.acme.test.patterns.AtlasDomain.DataStatusType.InvalidData;
import org.acme.test.patterns.AtlasDomain.DataStatusType.OutOfRange;
import org.acme.test.patterns.AtlasDomain.ParameterId;
import org.acme.test.patterns.AtlasDomain.ParameterSpec;
import org.acme.test.patterns.AtlasDomain.QueryState;
import org.acme.test.patterns.AtlasDomain.Sample;
import org.acme.test.patterns.AtlasDomain.SessionId;
import org.acme.test.patterns.AtlasDomain.Timestamp;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * McLaren Atlas SQL Race Foundation Patterns with JOTP.
 *
 * <p>Tests Vaughn Vernon's Enterprise Integration Patterns applied to race telemetry:
 * Message Bus, Datatype Channels, Durable Subscriber, Service Activator, Idempotent Receiver.
 *
 * <p>Domain: SessionId (correlation), Timestamp (nanos), ParameterId (sensor channel),
 * Sample (raw 16-bit reading with status), DataStatusType (Good | OutOfRange | InvalidData).
 *
 * @see AtlasDomain for shared domain types
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Atlas Foundation Patterns")
class AtlasFoundationPatternsIT implements WithAssertions {

    // Domain types imported from AtlasDomain - see AtlasDomain class for definitions

    // ── 1. Message Bus & Datatype Channels ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("1. Message Bus & Datatype Channels")
    class MessageBusDatatypeChannelsPattern {

        @Test
        void eventManagerAsTelemetryBus() throws Exception {
            var sampleCount = new AtomicInteger(0);
            var eventCount = new AtomicInteger(0);

            // EventManager acts as the message bus for telemetry
            var bus = EventManager.<Object>start();

            // Sample subscriber (datatype channel for Sample messages)
            bus.addHandler(
                    msg -> {
                        if (msg instanceof Sample) sampleCount.incrementAndGet();
                    });

            // Event subscriber (datatype channel for events)
            bus.addHandler(
                    msg -> {
                        if (msg instanceof String) eventCount.incrementAndGet();
                    });

            // Publish telemetry
            var paramId = new ParameterId("BRAKE_PRESSURE_FL");
            for (int i = 0; i < 100; i++) {
                bus.notify(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }
            bus.notify("SessionStarted");
            bus.notify("GoLive");

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> sampleCount.get() == 100 && eventCount.get() == 2);

            bus.stop();
        }

        @Test
        void procAsDatatypeChannel() throws Exception {
            var processed = new AtomicInteger(0);

            // Proc with typed message channel for Samples only
            record SampleState(int count, List<Sample> samples) {
                static SampleState initial() {
                    return new SampleState(0, new ArrayList<>());
                }
            }

            // Handler accepts both Sample (data) and QueryState (queries) using Object
            var sampleChannel =
                    new Proc<>(
                            SampleState.initial(),
                            (SampleState s, Object msg) -> {
                                if (msg instanceof Sample sample) {
                                    processed.incrementAndGet();
                                    var newList = new ArrayList<>(s.samples());
                                    newList.add(sample);
                                    return new SampleState(s.count() + 1, newList);
                                }
                                // QueryState.Full - return current state unchanged
                                return s;
                            });

            var paramId = new ParameterId("ENGINE_RPM");
            for (int i = 0; i < 50; i++) {
                sampleChannel.tell(new Sample(paramId, new Timestamp(i), (short) (i % 100), new Good()));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 50);

            // Use type-safe QueryState instead of awkward Sample with timestamp -1
            var state = sampleChannel.ask(new QueryState.Full())
                    .get(2, TimeUnit.SECONDS);
            assertThat(state.samples()).hasSize(50);

            sampleChannel.stop();
        }
    }

    // ── 2. Durable Subscriber ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2. Durable Subscriber")
    class DurableSubscriberPattern {

        @Test
        void procMailboxGuaranteesDelivery() throws Exception {
            var processed = new AtomicInteger(0);

            // Durable subscriber - Proc mailbox guarantees all messages are queued
            var subscriber =
                    new Proc<>(
                            0,
                            (Integer s, Sample msg) -> {
                                processed.incrementAndGet();
                                return s + 1;
                            });

            // Publisher sends messages faster than consumer processes
            var paramId = new ParameterId("TIRE_TEMP_FL");
            for (int i = 0; i < 30; i++) {
                subscriber.tell(new Sample(paramId, new Timestamp(i), (short) (20 + i), new Good()));
            }

            // All messages are queued (durable) - mailbox doesn't drop
            await().atMost(Duration.ofSeconds(3)).until(() -> processed.get() == 30);

            // Verify mailbox statistics show all messages were received
            var stats = ProcSys.statistics(subscriber);
            assertThat(stats.messagesIn()).isEqualTo(30);

            subscriber.stop();
        }

        @Test
        void durableSubscriptionWithSupervision() throws Exception {
            var sup = new Supervisor("durable-sv", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));

            var processed = new AtomicInteger(0);
            var subscriber =
                    sup.supervise(
                            "subscriber",
                            0,
                            (Integer s, Sample msg) -> {
                                processed.incrementAndGet();
                                if (msg.rawValue() == -1) {
                                    throw new RuntimeException("Simulated crash");
                                }
                                return s + 1;
                            });

            var paramId = new ParameterId("FUEL_LEVEL");
            for (int i = 0; i < 20; i++) {
                subscriber.tell(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }

            await().atMost(Duration.ofSeconds(3)).until(() -> processed.get() >= 20);

            // After restart, subscription continues (ProcRef provides stable handle)
            subscriber.tell(new Sample(paramId, new Timestamp(100), (short) 100, new Good()));

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() >= 21);

            sup.shutdown();
        }
    }

    // ── 3. Service Activator ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3. Service Activator")
    class ServiceActivatorPattern {

        @Test
        void messageActivatesSampleProcessor() throws Exception {
            var activations = new AtomicInteger(0);
            var samplesStored = new AtomicInteger(0);

            // Service that stores samples when activated by incoming messages
            var sampleStore =
                    new Proc<>(
                            new ArrayList<Sample>(),
                            (List<Sample> state, Sample msg) -> {
                                activations.incrementAndGet();
                                var newState = new ArrayList<>(state);
                                newState.add(msg);
                                samplesStored.incrementAndGet();
                                return newState;
                            });

            var paramId = new ParameterId("SPEED");
            sampleStore.tell(new Sample(paramId, new Timestamp(0), (short) 100, new Good()));
            sampleStore.tell(new Sample(paramId, new Timestamp(1), (short) 101, new Good()));
            sampleStore.tell(new Sample(paramId, new Timestamp(2), (short) 102, new Good()));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> activations.get() == 3 && samplesStored.get() == 3);

            sampleStore.stop();
        }

        @Test
        void activatorWithValidation() throws Exception {
            var validCount = new AtomicInteger(0);
            var invalidCount = new AtomicInteger(0);

            var spec = new ParameterSpec(new ParameterId("BRAKE_TEMP"), "Brake Temperature", 0, 1200, "C");

            // Service activator that validates samples against spec
            var validatingActivator =
                    new Proc<>(
                            0,
                            (Integer s, Sample msg) -> {
                                if (msg.parameterId().id().equals(spec.id().id())) {
                                    if (msg.rawValue() >= spec.minValid()
                                            && msg.rawValue() <= spec.maxValid()) {
                                        validCount.incrementAndGet();
                                    } else {
                                        invalidCount.incrementAndGet();
                                    }
                                }
                                return s + 1;
                            });

            validatingActivator.tell(new Sample(spec.id(), new Timestamp(0), (short) 500, new Good()));
            validatingActivator.tell(new Sample(spec.id(), new Timestamp(1), (short) 1500, new OutOfRange(0, 1200)));
            validatingActivator.tell(new Sample(spec.id(), new Timestamp(2), (short) 800, new Good()));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> validCount.get() == 2 && invalidCount.get() == 1);

            validatingActivator.stop();
        }
    }

    // ── 4. Idempotent Receiver ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("4. Idempotent Receiver")
    class IdempotentReceiverPattern {

        @Test
        void deduplicationByTimestamp() throws Exception {
            var uniqueSamples = new AtomicInteger(0);
            var duplicateCount = new AtomicInteger(0);

            // State includes set of seen timestamps for idempotency
            record IdempotentState(Set<Long> seenTimestamps, List<Sample> samples) {
                static IdempotentState initial() {
                    return new IdempotentState(new HashSet<>(), new ArrayList<>());
                }
            }

            var idempotentReceiver =
                    new Proc<>(
                            IdempotentState.initial(),
                            (IdempotentState s, Sample msg) -> {
                                var newSeen = new HashSet<>(s.seenTimestamps());
                                if (newSeen.add(msg.timestamp().nanos())) {
                                    // First time seeing this timestamp
                                    uniqueSamples.incrementAndGet();
                                    var newSamples = new ArrayList<>(s.samples());
                                    newSamples.add(msg);
                                    return new IdempotentState(newSeen, newSamples);
                                } else {
                                    // Duplicate
                                    duplicateCount.incrementAndGet();
                                    return s;
                                }
                            });

            var paramId = new ParameterId("THROTTLE_POS");

            // Send unique samples
            idempotentReceiver.tell(new Sample(paramId, new Timestamp(0), (short) 10, new Good()));
            idempotentReceiver.tell(new Sample(paramId, new Timestamp(1), (short) 20, new Good()));
            idempotentReceiver.tell(new Sample(paramId, new Timestamp(2), (short) 30, new Good()));

            // Send duplicates (retransmissions)
            idempotentReceiver.tell(new Sample(paramId, new Timestamp(0), (short) 10, new Good()));
            idempotentReceiver.tell(new Sample(paramId, new Timestamp(1), (short) 20, new Good()));

            // More unique
            idempotentReceiver.tell(new Sample(paramId, new Timestamp(3), (short) 40, new Good()));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> uniqueSamples.get() == 4 && duplicateCount.get() == 2);

            idempotentReceiver.stop();
        }

        @Test
        void idempotentWithConcurrency() throws Exception {
            var processed = new ConcurrentHashMap<Long, Boolean>();
            var uniqueCount = new AtomicInteger(0);

            record ConcurrentState(Map<Long, Boolean> processed, int count) {
                static ConcurrentState initial() {
                    return new ConcurrentState(new ConcurrentHashMap<>(), 0);
                }
            }

            var receiver =
                    new Proc<>(
                            ConcurrentState.initial(),
                            (ConcurrentState s, Sample msg) -> {
                                if (s.processed().putIfAbsent(msg.timestamp().nanos(), true) == null) {
                                    uniqueCount.incrementAndGet();
                                    return new ConcurrentState(s.processed(), s.count() + 1);
                                }
                                return s;
                            });

            var paramId = new ParameterId("STEERING_ANGLE");

            // Simulate concurrent sends with duplicates
            for (int i = 0; i < 100; i++) {
                receiver.tell(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }
            // Retransmit first 20
            for (int i = 0; i < 20; i++) {
                receiver.tell(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }

            await().atMost(Duration.ofSeconds(3)).until(() -> uniqueCount.get() == 100);

            receiver.stop();
        }
    }

    // ── Integration ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration: Foundation Patterns")
    class Integration {

        @Test
        void fullTelemetryIngestionPipeline() throws Exception {
            var sup = new Supervisor("foundation-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));

            // Message bus for telemetry distribution
            var bus = EventManager.<Sample>start();

            // Datatype channels (subscribers)
            var brakeSamples = new AtomicInteger(0);
            var engineSamples = new AtomicInteger(0);

            bus.addHandler(
                    s -> {
                        if (s.parameterId().id().startsWith("BRAKE")) brakeSamples.incrementAndGet();
                    });
            bus.addHandler(
                    s -> {
                        if (s.parameterId().id().startsWith("ENGINE")) engineSamples.incrementAndGet();
                    });

            // Service activator - processes and publishes to bus
            var processed = new AtomicInteger(0);
            var activator =
                    sup.supervise(
                            "activator",
                            new HashSet<Long>(),
                            (Set<Long> seen, Sample msg) -> {
                                var newSeen = new HashSet<>(seen);
                                if (newSeen.add(msg.timestamp().nanos())) {
                                    bus.notify(msg);
                                    processed.incrementAndGet();
                                }
                                return newSeen;
                            });

            var brakeParam = new ParameterId("BRAKE_PRESSURE_FL");
            var engineParam = new ParameterId("ENGINE_RPM");

            // Send telemetry
            for (int i = 0; i < 50; i++) {
                activator.tell(new Sample(brakeParam, new Timestamp(i), (short) i, new Good()));
                activator.tell(new Sample(engineParam, new Timestamp(i + 1000), (short) (i * 100), new Good()));
            }

            // Duplicates (retransmissions)
            for (int i = 0; i < 10; i++) {
                activator.tell(new Sample(brakeParam, new Timestamp(i), (short) i, new Good()));
            }

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> processed.get() == 100
                            && brakeSamples.get() == 50
                            && engineSamples.get() == 50);

            bus.stop();
            sup.shutdown();
        }
    }
}
