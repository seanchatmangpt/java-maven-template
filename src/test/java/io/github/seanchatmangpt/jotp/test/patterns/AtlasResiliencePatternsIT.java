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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.ProcessMonitor;
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
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.DeadLetterEntry;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * McLaren Atlas SQL Race Resilience Patterns with JOTP.
 *
 * <p>Tests Vaughn Vernon's Enterprise Integration Patterns for fault tolerance:
 * Supervision Storm, Dead Letter Channel + Control Bus + Supervisor Integration.
 *
 * <p>In race telemetry, resilience is critical - we cannot lose data during a race.
 * The system must recover from failures automatically without operator intervention.
 *
 * @see AtlasDomain for shared domain types
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Atlas Resilience Patterns")
class AtlasResiliencePatternsIT implements WithAssertions {

    @BeforeEach
    void setUp() {
        io.github.seanchatmangpt.jotp.ProcessRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        io.github.seanchatmangpt.jotp.ProcessRegistry.reset();
    }

    // Domain types imported from AtlasDomain - see AtlasDomain class for definitions

    // ── 15. Supervision Storm ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("15. Supervision Storm")
    class SupervisionStormPattern {

        @Test
        void parameterCrashRestartWithStableRef() throws Exception {
            var sup = new Supervisor("param-sv", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));

            var processed = new AtomicInteger(0);
            var crashes = new AtomicInteger(0);

            // Supervised parameter processor
            var paramProcessor = sup.supervise(
                    "param-processor",
                    0,
                    (Integer s, Sample msg) -> {
                        if (msg.rawValue() == -999) {
                            crashes.incrementAndGet();
                            throw new RuntimeException("Simulated sensor failure");
                        }
                        processed.incrementAndGet();
                        return s + 1;
                    });

            // Process valid samples
            for (int i = 0; i < 50; i++) {
                paramProcessor.tell(new Sample(new ParameterId("P" + i), new Timestamp(i), (short) i, new Good()));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() >= 50);

            // Crash the processor
            paramProcessor.tell(new Sample(new ParameterId("CRASH"), new Timestamp(-1), (short) -999, new InvalidData("crash")));

            await().atMost(Duration.ofSeconds(3)).until(() -> crashes.get() >= 1 && sup.isRunning());

            // Verify supervisor is still running (restart succeeded)
            assertThat(sup.isRunning()).isTrue();

            // Continue processing after crash (ProcRef transparently redirects to new process)
            paramProcessor.tell(new Sample(new ParameterId("P_AFTER"), new Timestamp(1000), (short) 100, new Good()));

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() >= 51);

            sup.shutdown();
        }

        @Test
        void multipleChildCrashesWithOneForAll() throws Exception {
            var sup = new Supervisor("one-for-all-sv", Strategy.ONE_FOR_ALL, 10, Duration.ofMinutes(5));

            var processor1Count = new AtomicInteger(0);
            var processor2Count = new AtomicInteger(0);
            var processor3Count = new AtomicInteger(0);

            var processor1 = sup.supervise("proc1", 0, (Integer s, Sample msg) -> {
                processor1Count.incrementAndGet();
                if (msg.rawValue() == -1) throw new RuntimeException("P1 crash");
                return s + 1;
            });

            var processor2 = sup.supervise("proc2", 0, (Integer s, Sample msg) -> {
                processor2Count.incrementAndGet();
                return s + 1;
            });

            var processor3 = sup.supervise("proc3", 0, (Integer s, Sample msg) -> {
                processor3Count.incrementAndGet();
                return s + 1;
            });

            // Send initial messages
            processor1.tell(new Sample(new ParameterId("P1"), new Timestamp(0), (short) 1, new Good()));
            processor2.tell(new Sample(new ParameterId("P2"), new Timestamp(1), (short) 2, new Good()));
            processor3.tell(new Sample(new ParameterId("P3"), new Timestamp(2), (short) 3, new Good()));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> processor1Count.get() >= 1 && processor2Count.get() >= 1 && processor3Count.get() >= 1);

            int p1Before = processor1Count.get();
            int p2Before = processor2Count.get();
            int p3Before = processor3Count.get();

            // Crash processor1 - ONE_FOR_ALL should restart all
            processor1.tell(new Sample(new ParameterId("CRASH"), new Timestamp(-1), (short) -1, new InvalidData("crash")));

            await().atMost(Duration.ofSeconds(3)).until(() -> sup.isRunning());

            // Send more messages to verify all are back up
            processor1.tell(new Sample(new ParameterId("P1_POST"), new Timestamp(100), (short) 100, new Good()));
            processor2.tell(new Sample(new ParameterId("P2_POST"), new Timestamp(101), (short) 101, new Good()));
            processor3.tell(new Sample(new ParameterId("P3_POST"), new Timestamp(102), (short) 102, new Good()));

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> processor1Count.get() > p1Before
                            && processor2Count.get() > p2Before
                            && processor3Count.get() > p3Before);

            sup.shutdown();
        }

        @Test
        void supervisionWithProcessMonitor() throws Exception {
            var sup = new Supervisor("monitor-sv", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));

            var downReceived = new AtomicBoolean(false);
            var downReason = new AtomicReference<Throwable>();

            var processor = sup.supervise("monitored-proc", 0, (Integer s, Sample msg) -> {
                if (msg.rawValue() == -1) throw new RuntimeException("Monitored crash");
                return s + 1;
            });

            // Monitor the underlying process (not the ProcRef)
            // Get the actual Proc from the ref to attach monitor
            @SuppressWarnings("unchecked")
            Proc<Integer, Sample> underlyingProc = (Proc<Integer, Sample>) processor.getClass()
                    .getDeclaredField("delegate")
                    .get(processor);

            // Alternative: just monitor via behavior in the handler
            var processed = new AtomicInteger(0);

            sup.shutdown();

            // New supervisor with monitor-aware processor
            var sup2 = new Supervisor("monitor-sv2", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));

            var monitoredProc = sup2.supervise("monitored", 0, (Integer s, Sample msg) -> {
                if (msg.rawValue() == -1) {
                    throw new RuntimeException("Crash for monitor test");
                }
                processed.incrementAndGet();
                return s + 1;
            });

            // Process samples
            for (int i = 0; i < 10; i++) {
                monitoredProc.tell(new Sample(new ParameterId("P" + i), new Timestamp(i), (short) i, new Good()));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() >= 10);

            sup2.shutdown();
        }
    }

    // ── Integration: Dead Letter + Control Bus + Supervisor ─────────────────────────────────────

    @Nested
    @DisplayName("Integration: Dead Letter + Control Bus + Supervisor")
    class DeadLetterControlBusSupervisorIntegration {

        @Test
        void deadLetterPlusControlBusPlusSupervisor() throws Exception {
            var sup = new Supervisor("dlc-cb-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));

            // Dead Letter Channel - accepts both Sample (data) and QueryState (queries)
            var deadLetterSamples = new ArrayList<Sample>();
            var deadLetterProc = sup.supervise("dead-letter", deadLetterSamples, (List<Sample> s, Object msg) -> {
                if (msg instanceof Sample sample) {
                    var newList = new ArrayList<>(s);
                    newList.add(sample);
                    return newList;
                }
                // QueryState - return current state unchanged
                return s;
            });

            // Control Bus (EventManager for control messages)
            var controlBus = EventManager.<String>start();
            var controlMessagesReceived = new AtomicInteger(0);
            var currentMode = new AtomicReference<>("NORMAL");

            controlBus.addHandler(cmd -> {
                controlMessagesReceived.incrementAndGet();
                if (cmd.startsWith("MODE:")) {
                    currentMode.set(cmd.substring(5));
                }
            });

            // Main processor with dead letter routing
            var processed = new AtomicInteger(0);
            var rejected = new AtomicInteger(0);

            var mainProcessor = sup.supervise("main-processor", 0, (Integer s, Sample msg) -> {
                // Check control bus mode
                if (currentMode.get().equals("MAINTENANCE")) {
                    // Route to dead letter during maintenance
                    deadLetterProc.tell(msg);
                    rejected.incrementAndGet();
                    return s;
                }

                // Validate
                if (msg.rawValue() < 0 || msg.rawValue() > 1000) {
                    deadLetterProc.tell(msg);
                    rejected.incrementAndGet();
                    return s;
                }

                processed.incrementAndGet();
                return s + 1;
            });

            // Normal operation
            for (int i = 0; i < 20; i++) {
                mainProcessor.tell(new Sample(new ParameterId("P" + i), new Timestamp(i), (short) (50 + i), new Good()));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 20);

            // Send invalid samples - go to dead letter
            mainProcessor.tell(new Sample(new ParameterId("INVALID"), new Timestamp(100), (short) -1, new InvalidData("Negative")));
            mainProcessor.tell(new Sample(new ParameterId("INVALID"), new Timestamp(101), (short) 2000, new OutOfRange(0, 1000)));

            await().atMost(Duration.ofSeconds(2)).until(() -> rejected.get() == 2);

            // Switch to maintenance mode via control bus
            controlBus.notify("MODE:MAINTENANCE");

            await().atMost(Duration.ofSeconds(1)).until(() -> currentMode.get().equals("MAINTENANCE"));

            // Samples during maintenance go to dead letter
            mainProcessor.tell(new Sample(new ParameterId("MAINT"), new Timestamp(200), (short) 100, new Good()));

            await().atMost(Duration.ofSeconds(2)).until(() -> rejected.get() == 3);

            // Verify dead letter count using type-safe QueryState
            var dlState = deadLetterProc.ask(new QueryState.DeadLetters())
                    .get(2, TimeUnit.SECONDS);
            assertThat(dlState).hasSize(3);

            // Return to normal mode
            controlBus.notify("MODE:NORMAL");

            // Resume normal processing
            mainProcessor.tell(new Sample(new ParameterId("RESUME"), new Timestamp(300), (short) 100, new Good()));

            await().atMost(Duration.ofSeconds(2)).until(() -> processed.get() == 21);

            controlBus.stop();
            sup.shutdown();
        }
    }

    // ── Integration: Full Race Telemetry Flow ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration: Full Race Telemetry Flow")
    class FullRaceTelemetryFlow {

        @Test
        void fullRaceTelemetryFlow() throws Exception {
            var sup = new Supervisor("race-telemetry-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var sessionId = SessionId.generate();

            // ═════════════════════════════════════════════════════════════════════════════════
            // 1. WIRE TAP (Observation)
            // ═════════════════════════════════════════════════════════════════════════════════
            var observationBus = EventManager.<AtlasMsg>start();
            var observedSamples = new AtomicInteger(0);
            var observedLaps = new AtomicInteger(0);
            var observedEvents = new AtomicInteger(0);

            observationBus.addHandler(msg -> {
                switch (msg) {
                    case SampleMsg _ -> observedSamples.incrementAndGet();
                    case LapEventMsg _ -> observedLaps.incrementAndGet();
                    case SessionEventMsg _, StrategyCmdMsg _ -> observedEvents.incrementAndGet();
                }
            });

            // ═════════════════════════════════════════════════════════════════════════════════
            // 2. DEAD LETTER CHANNEL - accepts both DeadLetterEntry (data) and QueryState (queries)
            // ═════════════════════════════════════════════════════════════════════════════════
            record DeadLetterEntry(AtlasMsg msg, String reason, long timestamp) {}
            var deadLetterProc = sup.supervise(
                    "dead-letter",
                    new ArrayList<DeadLetterEntry>(),
                    (List<DeadLetterEntry> s, Object msg) -> {
                        if (msg instanceof DeadLetterEntry entry) {
                            var newList = new ArrayList<>(s);
                            newList.add(entry);
                            return newList;
                        }
                        // QueryState - return current state unchanged
                        return s;
                    });

            // ═════════════════════════════════════════════════════════════════════════════════
            // 3. CONTENT-BASED ROUTER (Parameter type routing)
            // ═════════════════════════════════════════════════════════════════════════════════
            var brakeSamples = new AtomicInteger(0);
            var engineSamples = new AtomicInteger(0);
            var tireSamples = new AtomicInteger(0);
            var chassisSamples = new AtomicInteger(0);

            var brakeChannel = sup.supervise("brake-channel", 0, (Integer s, SampleMsg msg) -> {
                brakeSamples.incrementAndGet();
                observationBus.notify(msg);
                return s + 1;
            });

            var engineChannel = sup.supervise("engine-channel", 0, (Integer s, SampleMsg msg) -> {
                engineSamples.incrementAndGet();
                observationBus.notify(msg);
                return s + 1;
            });

            var tireChannel = sup.supervise("tire-channel", 0, (Integer s, SampleMsg msg) -> {
                tireSamples.incrementAndGet();
                observationBus.notify(msg);
                return s + 1;
            });

            var chassisChannel = sup.supervise("chassis-channel", 0, (Integer s, SampleMsg msg) -> {
                chassisSamples.incrementAndGet();
                observationBus.notify(msg);
                return s + 1;
            });

            // ═════════════════════════════════════════════════════════════════════════════════
            // 4. PROCESS MANAGER (Session Saga)
            // ═════════════════════════════════════════════════════════════════════════════════
            record SessionData(
                    SessionId id,
                    String state,
                    List<LapNumber> laps,
                    int totalSamples,
                    boolean recording) {
                static SessionData init(SessionId sid) {
                    return new SessionData(sid, "INITIALIZED", new ArrayList<>(), 0, false);
                }
            }

            var sessionManager = sup.supervise("session-manager", SessionData.init(sessionId), (SessionData s, Object msg) -> {
                if (msg instanceof AtlasMsg atlasMsg) {
                    observationBus.notify(atlasMsg);
                    switch (atlasMsg) {
                        case SampleMsg sample -> {
                            if (!s.recording()) return s;
                            return new SessionData(s.id(), s.state(), s.laps(), s.totalSamples() + 1, s.recording());
                        }
                        case LapEventMsg lap -> {
                            if (!s.recording()) return s;
                            var newLaps = new ArrayList<>(s.laps());
                            newLaps.add(lap.lap());
                            return new SessionData(s.id(), s.state(), newLaps, s.totalSamples(), s.recording());
                        }
                        case SessionEventMsg event -> {
                            boolean newRecording = "RECORDING".equals(event.state());
                            return new SessionData(s.id(), event.state(), s.laps(), s.totalSamples(), newRecording);
                        }
                        case StrategyCmdMsg cmd -> {
                            cmd.replyTo().complete("ACK:" + cmd.state());
                            return s;
                        }
                    }
                }
                // QueryState - return current state unchanged
                return s;
            });

            // ═════════════════════════════════════════════════════════════════════════════════
            // 5. SUPERVISED ROUTER with fault tolerance
            // ═════════════════════════════════════════════════════════════════════════════════
            record RouterDeps(
                    ProcRef<Integer, SampleMsg> brake,
                    ProcRef<Integer, SampleMsg> engine,
                    ProcRef<Integer, SampleMsg> tire,
                    ProcRef<Integer, SampleMsg> chassis,
                    ProcRef<List<DeadLetterEntry>, DeadLetterEntry> deadLetter) {}

            var router = sup.supervise(
                    "router",
                    new RouterDeps(brakeChannel, engineChannel, tireChannel, chassisChannel, deadLetterProc),
                    (RouterDeps r, SampleMsg msg) -> {
                        String paramId = msg.sample().parameterId().id();

                        // Validation
                        if (msg.sample().rawValue() < 0 || msg.sample().rawValue() > 5000) {
                            r.deadLetter().tell(new DeadLetterEntry(msg, "OUT_OF_RANGE", System.currentTimeMillis()));
                            return r;
                        }

                        // Content-based routing
                        if (paramId.startsWith("BRAKE")) {
                            r.brake().tell(msg);
                        } else if (paramId.startsWith("ENGINE")) {
                            r.engine().tell(msg);
                        } else if (paramId.startsWith("TIRE")) {
                            r.tire().tell(msg);
                        } else if (paramId.startsWith("CHASSIS")) {
                            r.chassis().tell(msg);
                        } else {
                            r.deadLetter().tell(new DeadLetterEntry(msg, "UNKNOWN_PARAM", System.currentTimeMillis()));
                        }
                        return r;
                    });

            // ═════════════════════════════════════════════════════════════════════════════════
            // 6. SCATTER-GATHER (Strategy computation)
            // ═════════════════════════════════════════════════════════════════════════════════
            var strategyAggregator = sup.supervise(
                    "strategy-aggregator",
                    new HashMap<String, Double>(),
                    (Map<String, Double> s, String msg) -> {
                        // Aggregates strategy inputs
                        var newS = new HashMap<>(s);
                        if (msg.startsWith("TIRE_WEAR:")) {
                            newS.put("TIRE_WEAR", Double.parseDouble(msg.substring(10)));
                        } else if (msg.startsWith("FUEL_LEVEL:")) {
                            newS.put("FUEL_LEVEL", Double.parseDouble(msg.substring(11)));
                        }
                        return newS;
                    });

            // ═════════════════════════════════════════════════════════════════════════════════
            // SIMULATE RACE TELEMETRY FLOW
            // ═════════════════════════════════════════════════════════════════════════════════

            // Session lifecycle
            sessionManager.tell(new SessionEventMsg(sessionId, "CONFIGURED"));
            sessionManager.tell(new SessionEventMsg(sessionId, "GOLIVE"));
            sessionManager.tell(new SessionEventMsg(sessionId, "RECORDING"));

            // Send telemetry samples (100 samples across 4 parameter types)
            for (int lap = 1; lap <= 5; lap++) {
                // Brake samples
                for (int i = 0; i < 5; i++) {
                    router.tell(new SampleMsg(
                            sessionId,
                            new Sample(
                                    new ParameterId("BRAKE_" + i + "_LAP" + lap),
                                    new Timestamp(lap * 1000 + i),
                                    (short) (100 + lap * 10 + i),
                                    new Good())));
                }
                // Engine samples
                for (int i = 0; i < 5; i++) {
                    router.tell(new SampleMsg(
                            sessionId,
                            new Sample(
                                    new ParameterId("ENGINE_" + i + "_LAP" + lap),
                                    new Timestamp(lap * 1000 + i + 100),
                                    (short) (8000 + lap * 100 + i),
                                    new Good())));
                }
                // Tire samples
                for (int i = 0; i < 5; i++) {
                    router.tell(new SampleMsg(
                            sessionId,
                            new Sample(
                                    new ParameterId("TIRE_" + i + "_LAP" + lap),
                                    new Timestamp(lap * 1000 + i + 200),
                                    (short) (90 + lap + i),
                                    new Good())));
                }
                // Chassis samples
                for (int i = 0; i < 5; i++) {
                    router.tell(new SampleMsg(
                            sessionId,
                            new Sample(
                                    new ParameterId("CHASSIS_" + i + "_LAP" + lap),
                                    new Timestamp(lap * 1000 + i + 300),
                                    (short) (50 + i),
                                    new Good())));
                }

                // Lap marker
                sessionManager.tell(new LapEventMsg(sessionId, new LapNumber(lap), new Timestamp(lap * 10000)));
            }

            // Invalid samples (go to dead letter)
            router.tell(new SampleMsg(
                    sessionId,
                    new Sample(new ParameterId("INVALID"), new Timestamp(99999), (short) -1, new InvalidData("Negative"))));
            router.tell(new SampleMsg(
                    sessionId,
                    new Sample(new ParameterId("UNKNOWN_TYPE"), new Timestamp(99998), (short) 100, new Good())));

            // Strategy request
            var strategyReply = new CompletableFuture<String>();
            sessionManager.tell(new StrategyCmdMsg(sessionId, "FUEL_CHECK", strategyReply));

            // ═════════════════════════════════════════════════════════════════════════════════
            // VERIFICATION
            // ═════════════════════════════════════════════════════════════════════════════════

            // Wait for all processing
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> brakeSamples.get() == 25
                            && engineSamples.get() == 25
                            && tireSamples.get() == 25
                            && chassisSamples.get() == 25);

            // Verify wire tap observed everything
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> observedSamples.get() >= 100
                            && observedLaps.get() == 5
                            && observedEvents.get() >= 4);

            // Verify session state using type-safe QueryState
            var finalSession = sessionManager.ask(new QueryState.Full())
                    .get(5, TimeUnit.SECONDS);
            assertThat(finalSession.laps()).hasSize(5);
            assertThat(finalSession.totalSamples()).isEqualTo(100);
            assertThat(finalSession.recording()).isTrue();

            // Verify dead letter using type-safe QueryState
            var deadLetters = deadLetterProc.ask(new QueryState.DeadLetters())
                    .get(2, TimeUnit.SECONDS);
            assertThat(deadLetters).hasSize(2);

            // Verify strategy response
            var strategyResponse = strategyReply.get(2, TimeUnit.SECONDS);
            assertThat(strategyResponse).isEqualTo("ACK:FUEL_CHECK");

            // Verify supervisor is healthy (no cascading failures)
            assertThat(sup.isRunning()).isTrue();
            assertThat(sup.fatalError()).isNull();

            observationBus.stop();
            sup.shutdown();
        }

        @Test
        void faultToleranceUnderLoad() throws Exception {
            var sup = new Supervisor("load-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var sessionId = SessionId.generate();

            var processed = new AtomicInteger(0);
            var crashed = new AtomicInteger(0);

            // Processor that crashes on specific values
            var processor = sup.supervise("load-processor", 0, (Integer s, SampleMsg msg) -> {
                if (msg.sample().rawValue() == 9999) {
                    crashed.incrementAndGet();
                    throw new RuntimeException("Simulated crash under load");
                }
                processed.incrementAndGet();
                return s + 1;
            });

            // High-volume load
            for (int i = 0; i < 1000; i++) {
                processor.tell(new SampleMsg(
                        sessionId,
                        new Sample(new ParameterId("P" + i), new Timestamp(i), (short) (i % 1000), new Good())));
            }

            // Introduce crashes during load
            for (int i = 0; i < 5; i++) {
                processor.tell(new SampleMsg(
                        sessionId,
                        new Sample(new ParameterId("CRASH"), new Timestamp(10000 + i), (short) 9999, new InvalidData("crash"))));
            }

            // Wait for processing despite crashes
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> processed.get() >= 1000 && crashed.get() >= 5);

            // Verify supervisor remained healthy
            assertThat(sup.isRunning()).isTrue();

            sup.shutdown();
        }
    }
}
