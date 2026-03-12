package io.github.seanchatmangpt.jotp.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.LapEventMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.SampleMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.SessionEventMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.StrategyCmdMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.DataStatusType;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.DataStatusType.Good;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.DataStatusType.InvalidData;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.DataStatusType.OutOfRange;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.LapNumber;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.ParameterId;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.QueryState;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.RaceState;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.Recommendation;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.Sample;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.SessionId;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.SessionState;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.Timestamp;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * McLaren Atlas SQL Race Orchestration Patterns with JOTP.
 *
 * <p>Tests Vaughn Vernon's Enterprise Integration Patterns for process orchestration:
 * Process Manager (Session Saga), Correlation Identifier, Publish-Subscribe, Scatter-Gather,
 * Canonical Message Model.
 *
 * <p>In race telemetry, orchestration patterns coordinate the session lifecycle:
 * Initialize → Configure → GoLive → (RecordLaps, AcceptDataItems) → Save → Close
 *
 * @see AtlasDomain for shared domain types
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Atlas Orchestration Patterns")
class AtlasOrchestrationPatternsIT implements WithAssertions {

    // Domain types imported from AtlasDomain - see AtlasDomain class for definitions

    // ── 10. Process Manager (Session Saga) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("10. Process Manager (Session Saga)")
    class ProcessManagerPattern {

        @Test
        void sessionLifecycleAsStateMachine() throws Exception {
            var sessionId = SessionId.generate();

            // Session saga data
            record SessionData(
                    SessionId id, SessionState state, List<LapNumber> laps, int samplesReceived) {
                static SessionData init(SessionId id) {
                    return new SessionData(id, new SessionState.Initialized(), new ArrayList<>(), 0);
                }
            }

            // State machine for session lifecycle
            var sessionSm =
                    new StateMachine<>(
                            new SessionState.Initialized(),
                            SessionData.init(sessionId),
                            (state, event, data) -> {
                                if (event instanceof String cmd) {
                                    return switch (cmd) {
                                        case "configure" -> {
                                            if (state instanceof SessionState.Initialized) {
                                                yield Transition.nextState(
                                                        new SessionState.Configured(),
                                                        new SessionData(
                                                                data.id(),
                                                                new SessionState.Configured(),
                                                                data.laps(),
                                                                data.samplesReceived()));
                                            }
                                            yield Transition.keepState(data);
                                        }
                                        case "golive" -> {
                                            if (state instanceof SessionState.Configured) {
                                                yield Transition.nextState(
                                                        new SessionState.GoLive(),
                                                        new SessionData(
                                                                data.id(),
                                                                new SessionState.GoLive(),
                                                                data.laps(),
                                                                data.samplesReceived()));
                                            }
                                            yield Transition.keepState(data);
                                        }
                                        case "record" -> {
                                            if (state instanceof SessionState.GoLive) {
                                                yield Transition.nextState(
                                                        new SessionState.Recording(),
                                                        data);
                                            }
                                            yield Transition.keepState(data);
                                        }
                                        case "save" -> {
                                            if (state instanceof SessionState.Recording) {
                                                yield Transition.nextState(
                                                        new SessionState.Saving(),
                                                        data);
                                            }
                                            yield Transition.keepState(data);
                                        }
                                        case "close" -> {
                                            if (state instanceof SessionState.Saving) {
                                                yield Transition.nextState(
                                                        new SessionState.Closed(),
                                                        data);
                                            }
                                            yield Transition.keepState(data);
                                        }
                                        default -> Transition.keepState(data);
                                    };
                                }
                                return Transition.keepState(data);
                            });

            // Execute session lifecycle
            assertThat(sessionSm.state()).isInstanceOf(SessionState.Initialized.class);

            sessionSm.send("configure");
            await().atMost(Duration.ofMillis(500)).until(() -> sessionSm.state() instanceof SessionState.Configured);
            assertThat(sessionSm.state()).isInstanceOf(SessionState.Configured.class);

            sessionSm.send("golive");
            await().atMost(Duration.ofMillis(500)).until(() -> sessionSm.state() instanceof SessionState.GoLive);
            assertThat(sessionSm.state()).isInstanceOf(SessionState.GoLive.class);

            sessionSm.send("record");
            await().atMost(Duration.ofMillis(500)).until(() -> sessionSm.state() instanceof SessionState.Recording);
            assertThat(sessionSm.state()).isInstanceOf(SessionState.Recording.class);

            sessionSm.send("save");
            await().atMost(Duration.ofMillis(500)).until(() -> sessionSm.state() instanceof SessionState.Saving);
            assertThat(sessionSm.state()).isInstanceOf(SessionState.Saving.class);

            sessionSm.send("close");
            await().atMost(Duration.ofMillis(500)).until(() -> sessionSm.state() instanceof SessionState.Closed);
            assertThat(sessionSm.state()).isInstanceOf(SessionState.Closed.class);

            sessionSm.stop();
        }

        @Test
        void sessionSagaWithLapEvents() throws Exception {
            var sessionId = SessionId.generate();
            var completedLaps = new AtomicInteger(0);

            record SagaState(
                    SessionId id, SessionState state, List<LapNumber> laps, boolean saving) {
                static SagaState init(SessionId sid) {
                    return new SagaState(sid, new SessionState.Initialized(), new ArrayList<>(), false);
                }
            }

            var sagaManager =
                    new Proc<>(
                            SagaState.init(sessionId),
                            (SagaState s, Object msg) -> {
                                if (msg instanceof LapEventMsg lap) {
                                    var newLaps = new ArrayList<>(s.laps());
                                    newLaps.add(lap.lap());
                                    completedLaps.incrementAndGet();
                                    return new SagaState(s.id(), s.state(), newLaps, s.saving());
                                } else if (msg instanceof String cmd) {
                                    return switch (cmd) {
                                        case "start" -> new SagaState(
                                                s.id(), new SessionState.Recording(), s.laps(), false);
                                        case "save" -> new SagaState(
                                                s.id(), new SessionState.Saving(), s.laps(), true);
                                        case "close" -> new SagaState(
                                                s.id(), new SessionState.Closed(), s.laps(), false);
                                        default -> s;
                                    };
                                }
                                return s;
                            });

            sagaManager.tell("start");
            sagaManager.tell(new LapEventMsg(sessionId, new LapNumber(1), new Timestamp(1000)));
            sagaManager.tell(new LapEventMsg(sessionId, new LapNumber(2), new Timestamp(2000)));
            sagaManager.tell(new LapEventMsg(sessionId, new LapNumber(3), new Timestamp(3000)));
            sagaManager.tell("save");

            await().atMost(Duration.ofSeconds(2)).until(() -> completedLaps.get() == 3);

            var state = sagaManager.ask("close").get(2, TimeUnit.SECONDS);
            assertThat(state.laps()).hasSize(3);

            sagaManager.stop();
        }
    }

    // ── 11. Correlation Identifier ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("11. Correlation Identifier")
    class CorrelationIdentifierPattern {

        @Test
        void sessionIdCorrelatesAllMessages() throws Exception {
            var session1 = SessionId.generate();
            var session2 = SessionId.generate();

            var session1Samples = new AtomicInteger(0);
            var session2Samples = new AtomicInteger(0);

            var correlator =
                    new Proc<>(
                            new HashMap<UUID, List<Sample>>(),
                            (Map<UUID, List<Sample>> state, SampleMsg msg) -> {
                                var newState = new HashMap<>(state);
                                var uuid = msg.sessionId().id();
                                newState.computeIfAbsent(uuid, k -> new ArrayList<>()).add(msg.sample());
                                if (uuid.equals(session1.id())) session1Samples.incrementAndGet();
                                else if (uuid.equals(session2.id())) session2Samples.incrementAndGet();
                                return newState;
                            });

            // Samples for session 1
            for (int i = 0; i < 20; i++) {
                correlator.tell(new SampleMsg(
                        session1,
                        new Sample(new ParameterId("P" + i), new Timestamp(i), (short) i, new Good())));
            }

            // Samples for session 2
            for (int i = 0; i < 15; i++) {
                correlator.tell(new SampleMsg(
                        session2,
                        new Sample(new ParameterId("Q" + i), new Timestamp(i + 1000), (short) (i * 2), new Good())));
            }

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> session1Samples.get() == 20 && session2Samples.get() == 15);

            correlator.stop();
        }

        @Test
        void correlationIdInRequestResponse() throws Exception {
            var sessionId = SessionId.generate();
            var responses = new ConcurrentHashMap<UUID, Recommendation>();

            var strategyEngine =
                    new Proc<>(
                            sessionId,
                            (SessionId sid, StrategyCmdMsg cmd) -> {
                                if (cmd.sessionId().id().equals(sid.id())) {
                                    cmd.replyTo().complete(new Recommendation("PIT_NOW", 0.95));
                                }
                                return sid;
                            });

            var replyFuture = new CompletableFuture<Recommendation>();
            strategyEngine.tell(new StrategyCmdMsg(sessionId, new RaceState("FUEL_LOW"), replyFuture));

            var recommendation = replyFuture.get(2, TimeUnit.SECONDS);
            assertThat(recommendation.strategy()).isEqualTo("PIT_NOW");
            assertThat(recommendation.confidence()).isEqualTo(0.95);

            strategyEngine.stop();
        }
    }

    // ── 12. Publish-Subscribe Channel ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("12. Publish-Subscribe Channel")
    class PubSubChannelPattern {

        @Test
        void eventManagerAsPubSub() throws Exception {
            var subscriber1Count = new AtomicInteger(0);
            var subscriber2Count = new AtomicInteger(0);
            var subscriber3Count = new AtomicInteger(0);

            var bus = EventManager.<AtlasMsg>start();

            // Multiple subscribers
            bus.addHandler(msg -> subscriber1Count.incrementAndGet());
            bus.addHandler(msg -> subscriber2Count.incrementAndGet());
            bus.addHandler(msg -> subscriber3Count.incrementAndGet());

            var sessionId = SessionId.generate();

            // Publish events
            bus.notify(new SessionEventMsg(sessionId, new SessionState.GoLive()));
            bus.notify(new LapEventMsg(sessionId, new LapNumber(1), new Timestamp(1000)));
            bus.notify(new LapEventMsg(sessionId, new LapNumber(2), new Timestamp(2000)));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> subscriber1Count.get() == 3
                            && subscriber2Count.get() == 3
                            && subscriber3Count.get() == 3);

            bus.stop();
        }

        @Test
        void topicBasedSubscription() throws Exception {
            var lapEventCount = new AtomicInteger(0);
            var sessionEventCount = new AtomicInteger(0);
            var sampleCount = new AtomicInteger(0);

            var bus = EventManager.<AtlasMsg>start();

            // Topic-based filtering (simulated)
            bus.addHandler(msg -> {
                if (msg instanceof LapEventMsg) lapEventCount.incrementAndGet();
            });
            bus.addHandler(msg -> {
                if (msg instanceof SessionEventMsg) sessionEventCount.incrementAndGet();
            });
            bus.addHandler(msg -> {
                if (msg instanceof SampleMsg) sampleCount.incrementAndGet();
            });

            var sessionId = SessionId.generate();

            bus.notify(new SessionEventMsg(sessionId, new SessionState.GoLive()));
            for (int i = 0; i < 10; i++) {
                bus.notify(new SampleMsg(
                        sessionId, new Sample(new ParameterId("P" + i), new Timestamp(i), (short) i, new Good())));
            }
            bus.notify(new LapEventMsg(sessionId, new LapNumber(1), new Timestamp(10000)));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> sessionEventCount.get() == 1
                            && sampleCount.get() == 10
                            && lapEventCount.get() == 1);

            bus.stop();
        }
    }

    // ── 13. Scatter-Gather ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("13. Scatter-Gather")
    class ScatterGatherPattern {

        @Test
        void parallelAnalysisWithAggregation() throws Exception {
            // Scatter: analyze multiple parameters in parallel
            var sessionId = SessionId.generate();

            // Analysis result record for this test
            record AnalysisResult(ParameterId param, double value, String status) {}

            List<java.util.function.Supplier<AnalysisResult>> tasks = IntStream.range(0, 10)
                    .mapToObj(i -> (java.util.function.Supplier<AnalysisResult>) () -> {
                        // Simulate parameter analysis (no Thread.sleep - defeats parallelism)
                        return new AnalysisResult(
                                new ParameterId("PARAM_" + i),
                                i * 10.0,
                                i % 2 == 0 ? "OK" : "WARNING");
                    })
                    .collect(Collectors.toList());

            var result = Parallel.all(tasks);

            assertThat(result.isSuccess()).isTrue();
            result.fold(
                    list -> assertThat(list).hasSize(10),
                    error -> { throw new RuntimeException(error); });
        }

        @Test
        void scatterGatherWithProcAggregator() throws Exception {
            var aggregatedResults = new AtomicInteger(0);

            // Aggregator process
            record Aggregated(List<AnalysisResult> results, int expected) {
                static Aggregated init(int expected) {
                    return new Aggregated(new ArrayList<>(), expected);
                }
            }

            var aggregator =
                    new Proc<>(
                            Aggregated.init(5),
                            (Aggregated s, AnalysisResult msg) -> {
                                var newResults = new ArrayList<>(s.results());
                                newResults.add(msg);
                                if (newResults.size() == s.expected()) {
                                    aggregatedResults.incrementAndGet();
                                }
                                return new Aggregated(newResults, s.expected());
                            });

            // Scatter to multiple workers, gather results
            var workers = new ArrayList<Proc<Aggregated, AnalysisResult>>();
            for (int i = 0; i < 5; i++) {
                workers.add(aggregator);
            }

            // Simulate parallel work completing
            for (int i = 0; i < 5; i++) {
                aggregator.tell(new AnalysisResult(
                        new ParameterId("P" + i), Math.random() * 100, "OK"));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> aggregatedResults.get() == 1);

            aggregator.stop();
        }

        record AnalysisResult(ParameterId param, double value, String status) {}
    }

    // ── 14. Canonical Message Model ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("14. Canonical Message Model")
    class CanonicalMessageModelPattern {

        @Test
        void sealedInterfaceAsCanonicalModel() throws Exception {
            var processed = new HashMap<String, AtomicInteger>();
            processed.put("Sample", new AtomicInteger(0));
            processed.put("SessionEvent", new AtomicInteger(0));
            processed.put("LapEvent", new AtomicInteger(0));
            processed.put("StrategyCmd", new AtomicInteger(0));

            // Single handler for all canonical message types
            var canonicalProcessor =
                    new Proc<>(
                            processed,
                            (Map<String, AtomicInteger> state, AtlasMsg msg) -> {
                                switch (msg) {
                                    case AtlasMsg.Sample _ -> state.get("Sample").incrementAndGet();
                                    case AtlasMsg.SessionEvent _ -> state.get("SessionEvent").incrementAndGet();
                                    case AtlasMsg.LapEvent _ -> state.get("LapEvent").incrementAndGet();
                                    case AtlasMsg.StrategyCmd _ -> state.get("StrategyCmd").incrementAndGet();
                                }
                                return state;
                            });

            var sessionId = SessionId.generate();

            // Send all message types
            canonicalProcessor.tell(new AtlasMsg.Sample(
                    sessionId, new ParameterId("P1"), new Timestamp(0), (short) 100, new Good()));
            canonicalProcessor.tell(new AtlasMsg.SessionEvent(sessionId, new SessionState.GoLive()));
            canonicalProcessor.tell(new AtlasMsg.LapEvent(sessionId, new LapNumber(1), new Timestamp(1000)));
            canonicalProcessor.tell(new AtlasMsg.StrategyCmd(sessionId, new RaceState("test"), new CompletableFuture<>()));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> processed.get("Sample").get() == 1
                            && processed.get("SessionEvent").get() == 1
                            && processed.get("LapEvent").get() == 1
                            && processed.get("StrategyCmd").get() == 1);

            canonicalProcessor.stop();
        }

        @Test
        void patternMatchingIsExhaustive() throws Exception {
            // Compile-time exhaustiveness check
            var sessionId = SessionId.generate();

            java.util.function.Function<AtlasMsg, String> classifier = msg -> switch (msg) {
                case AtlasMsg.Sample _ -> "sample";
                case AtlasMsg.SessionEvent _ -> "session";
                case AtlasMsg.LapEvent _ -> "lap";
                case AtlasMsg.StrategyCmd _ -> "strategy";
            };

            assertThat(classifier.apply(new AtlasMsg.Sample(
                    sessionId, new ParameterId("P"), new Timestamp(0), (short) 1, new Good())))
                    .isEqualTo("sample");
            assertThat(classifier.apply(new AtlasMsg.SessionEvent(sessionId, new SessionState.GoLive())))
                    .isEqualTo("session");
            assertThat(classifier.apply(new AtlasMsg.LapEvent(sessionId, new LapNumber(1), new Timestamp(0))))
                    .isEqualTo("lap");
            assertThat(classifier.apply(new AtlasMsg.StrategyCmd(sessionId, new RaceState("x"), new CompletableFuture<>())))
                    .isEqualTo("strategy");
        }
    }

    // ── Integration ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration: Orchestration Patterns")
    class Integration {

        @Test
        void fullSessionLifecycleWithOrchestration() throws Exception {
            var sup = new Supervisor("orchestration-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var sessionId = SessionId.generate();

            // Pub-Sub bus for events
            var bus = EventManager.<AtlasMsg>start();
            var busCount = new AtomicInteger(0);
            bus.addHandler(msg -> busCount.incrementAndGet());

            // Process Manager (Session Saga)
            record SagaState(
                    SessionId id,
                    SessionState state,
                    List<LapNumber> laps,
                    Map<ParameterId, List<Short>> parameterData,
                    boolean active) {
                static SagaState init(SessionId sid) {
                    return new SagaState(
                            sid,
                            new SessionState.Initialized(),
                            new ArrayList<>(),
                            new HashMap<>(),
                            true);
                }
            }

            var sagaManager = sup.supervise("saga", SagaState.init(sessionId), (SagaState s, AtlasMsg msg) -> {
                switch (msg) {
                    case AtlasMsg.Sample sample -> {
                        if (!s.active()) return s;
                        var newData = new HashMap<>(s.parameterData());
                        newData.computeIfAbsent(sample.param(), k -> new ArrayList<>())
                                .add(sample.rawValue());
                        bus.notify(msg);
                        return new SagaState(s.id(), s.state(), s.laps(), newData, s.active());
                    }
                    case AtlasMsg.LapEvent lap -> {
                        if (!s.active()) return s;
                        var newLaps = new ArrayList<>(s.laps());
                        newLaps.add(lap.lap());
                        bus.notify(msg);
                        return new SagaState(s.id(), s.state(), newLaps, s.parameterData(), s.active());
                    }
                    case AtlasMsg.SessionEvent event -> {
                        bus.notify(msg);
                        return new SagaState(s.id(), event.state(), s.laps(), s.parameterData(), s.active());
                    }
                    case AtlasMsg.StrategyCmd cmd -> {
                        bus.notify(msg);
                        cmd.replyTo().complete(new Recommendation("CONTINUE", 0.9));
                        return s;
                    }
                }
            });

            // Execute session lifecycle
            sagaManager.tell(new AtlasMsg.SessionEvent(sessionId, new SessionState.Configured()));
            sagaManager.tell(new AtlasMsg.SessionEvent(sessionId, new SessionState.GoLive()));
            sagaManager.tell(new AtlasMsg.SessionEvent(sessionId, new SessionState.Recording()));

            // Record samples
            for (int i = 0; i < 50; i++) {
                sagaManager.tell(new AtlasMsg.Sample(
                        sessionId,
                        new ParameterId("BRAKE_" + (i % 4)),
                        new Timestamp(i),
                        (short) (50 + i),
                        new Good()));
            }

            // Record laps
            for (int i = 1; i <= 5; i++) {
                sagaManager.tell(new AtlasMsg.LapEvent(sessionId, new LapNumber(i), new Timestamp(i * 1000)));
            }

            // Strategy request
            var replyFuture = new CompletableFuture<Recommendation>();
            sagaManager.tell(new AtlasMsg.StrategyCmd(sessionId, new RaceState("GREEN_FLAG"), replyFuture));

            // Expected bus count: 50 samples + 5 laps + 3 events (Configured, GoLive, Recording) + 1 strategy = 59
            final int expectedBusCount = 50 + 5 + 3 + 1;
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> busCount.get() >= expectedBusCount);

            var recommendation = replyFuture.get(2, TimeUnit.SECONDS);
            assertThat(recommendation.strategy()).isEqualTo("CONTINUE");

            var finalState = sagaManager.ask(new AtlasMsg.SessionEvent(sessionId, new SessionState.Closed()))
                    .get(2, TimeUnit.SECONDS);
            assertThat(finalState.laps()).hasSize(5);
            assertThat(finalState.parameterData()).hasSize(4); // 4 brake sensors

            bus.stop();
            sup.shutdown();
        }
    }
}
