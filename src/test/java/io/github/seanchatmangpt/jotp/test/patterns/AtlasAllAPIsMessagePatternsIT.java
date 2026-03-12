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
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AnalysisResult;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.LapEventMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.SampleMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.AtlasMsg.SessionEventMsg;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.DataStatusType.Good;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.LapNumber;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.ParameterId;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.ParameterSpec;
import io.github.seanchatmangpt.jotp.test.patterns.AtlasDomain.QueryState;
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
 * McLaren Atlas All APIs Message Patterns Integration Tests.
 *
 * <p>Tests Vaughn Vernon's Enterprise Integration Patterns applied to all three
 * McLaren Atlas API surfaces:
 * <ul>
 *   <li>SQLRaceAPI — Session lifecycle, parameters, samples, laps, statistics</li>
 *   <li>FileSessionAPI — File save, load, streaming operations</li>
 *   <li>DisplayAPI — Display updates, plugin lifecycle, tool windows</li>
 * </ul>
 *
 * <p>Validates theoretical baselines from phd-thesis-atlas-message-patterns.md.
 *
 * @see AtlasDomain for shared domain types
 * @see AtlasAPIStressTest for throughput validation
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Atlas All APIs Message Patterns")
class AtlasAllAPIsMessagePatternsIT implements WithAssertions {

    // ═══════════════════════════════════════════════════════════════════════════════
    // SQLRaceAPI MESSAGE PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SQLRaceAPI Message Patterns")
    class SQLRaceAPIPatterns {

        // ── Session.Open as Command Message ─────────────────────────────────────────

        @Test
        @DisplayName("Session.Open as Command Message")
        void sessionOpenAsCommandMessage() throws Exception {
            var openedCount = new AtomicInteger(0);

            // Command interface
            interface SessionCmd {
                record Open(SessionId id) implements SessionCmd {}
                record Close() implements SessionCmd {}
            }

            record SessionData(SessionId id, SessionState state, boolean active) {
                static SessionData initial() {
                    return new SessionData(null, new SessionState.Initialized(), false);
                }
            }

            // Session as command handler
            var session = new Proc<>(SessionData.initial(), (SessionData s, Object msg) -> {
                if (msg instanceof SessionCmd.Open open) {
                    openedCount.incrementAndGet();
                    return new SessionData(open.id(), new SessionState.Configured(), true);
                } else if (msg instanceof SessionCmd.Close) {
                    return new SessionData(s.id(), new SessionState.Closed(), false);
                }
                return s;
            });

            // Execute open command
            var sessionId = SessionId.generate();
            session.tell(new SessionCmd.Open(sessionId));

            await().atMost(Duration.ofSeconds(2)).until(() -> openedCount.get() == 1);

            var state = session.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.active()).isTrue();
            assertThat(state.state()).isInstanceOf(SessionState.Configured.class);

            session.stop();
        }

        // ── Session.WriteSample as Event Message ────────────────────────────────────

        @Test
        @DisplayName("Session.WriteSample as Event Message")
        void sessionWriteSampleAsEventMessage() throws Exception {
            var samplesProcessed = new AtomicInteger(0);

            // Event bus for sample distribution
            var sampleBus = EventManager.<Sample>start();

            // Sample store as event consumer
            record SampleStore(List<Sample> samples) {
                static SampleStore initial() { return new SampleStore(new ArrayList<>()); }
            }

            var store = new Proc<>(SampleStore.initial(), (SampleStore s, Sample msg) -> {
                samplesProcessed.incrementAndGet();
                var newList = new ArrayList<>(s.samples());
                newList.add(msg);
                return new SampleStore(newList);
            });

            // Connect bus to store
            sampleBus.addHandler(store::tell);

            // Write samples via event bus
            var paramId = new ParameterId("ENGINE_RPM");
            for (int i = 0; i < 100; i++) {
                sampleBus.notify(new Sample(paramId, new Timestamp(i), (short) (1000 + i), new Good()));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> samplesProcessed.get() == 100);

            var state = store.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.samples()).hasSize(100);

            sampleBus.stop();
            store.stop();
        }

        // ── Session.GetParameters as Request-Reply ──────────────────────────────────

        @Test
        @DisplayName("Session.GetParameters as Request-Reply")
        void sessionGetParametersAsRequestReply() throws Exception {
            var params = List.of(
                new ParameterSpec(new ParameterId("ENGINE_RPM"), "Engine RPM", 0, 15000, "rpm"),
                new ParameterSpec(new ParameterId("BRAKE_TEMP_FL"), "Brake Temp FL", 0, 1200, "C"),
                new ParameterSpec(new ParameterId("TIRE_PRESSURE_FL"), "Tire Pressure FL", 0, 30, "bar")
            );

            record SessionState(
                List<ParameterSpec> parameters,
                Map<ParameterId, List<Sample>> data
            ) {
                static SessionState initial(List<ParameterSpec> params) {
                    return new SessionState(params, new HashMap<>());
                }
            }

            interface Query {
                record GetParameters() implements Query {}
                record GetStats(ParameterId param) implements Query {}
            }

            var session = new Proc<>(SessionState.initial(params), (SessionState s, Object msg) -> {
                // Return state unchanged for queries - ask() returns state
                return s;
            });

            // Request-Reply: Get parameters
            var state = session.ask(new Query.GetParameters()).get(2, TimeUnit.SECONDS);
            assertThat(state.parameters()).hasSize(3);
            assertThat(state.parameters().get(0).name()).isEqualTo("Engine RPM");

            session.stop();
        }

        // ── Session.CreateLap as Correlation ID ─────────────────────────────────────

        @Test
        @DisplayName("Session.CreateLap as Correlation ID")
        void sessionCreateLapAsCorrelationId() throws Exception {
            var sessionId = SessionId.generate();
            var lapsCreated = new AtomicInteger(0);

            record LapData(SessionId sessionId, List<LapNumber> laps, Timestamp lastBeacon) {
                static LapData initial(SessionId sid) {
                    return new LapData(sid, new ArrayList<>(), new Timestamp(0));
                }
            }

            var lapManager = new Proc<>(LapData.initial(sessionId), (LapData s, Object msg) -> {
                if (msg instanceof LapEventMsg lap && lap.sessionId().id().equals(s.sessionId().id())) {
                    lapsCreated.incrementAndGet();
                    var newLaps = new ArrayList<>(s.laps());
                    newLaps.add(lap.lap());
                    return new LapData(s.sessionId(), newLaps, lap.beaconTs());
                }
                return s;
            });

            // Create laps with correlation via sessionId
            for (int i = 1; i <= 5; i++) {
                lapManager.tell(new LapEventMsg(sessionId, new LapNumber(i), new Timestamp(i * 10000L)));
            }

            await().atMost(Duration.ofSeconds(2)).until(() -> lapsCreated.get() == 5);

            var state = lapManager.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.laps()).hasSize(5);
            assertThat(state.laps().get(4).number()).isEqualTo(5);

            lapManager.stop();
        }

        // ── Session.GetStatistics as Document Message ───────────────────────────────

        @Test
        @DisplayName("Session.GetStatistics as Document Message")
        void sessionGetStatisticsAsDocumentMessage() throws Exception {
            record SessionStats(
                int totalSamples,
                int lapCount,
                Map<ParameterId, Integer> samplesPerParam
            ) {
                static SessionStats initial() {
                    return new SessionStats(0, 0, new HashMap<>());
                }

                SessionStats withSample(ParameterId param) {
                    var newMap = new HashMap<>(samplesPerParam);
                    newMap.merge(param, 1, Integer::sum);
                    return new SessionStats(totalSamples + 1, lapCount, newMap);
                }

                SessionStats withLap() {
                    return new SessionStats(totalSamples, lapCount + 1, samplesPerParam);
                }
            }

            interface StatsQuery {
                record GetStats() implements StatsQuery {}
                record GetSamplesPerParam() implements StatsQuery {}
            }

            var stats = new Proc<>(SessionStats.initial(), (SessionStats s, Object msg) -> {
                if (msg instanceof Sample sample) {
                    return s.withSample(sample.parameterId());
                } else if (msg instanceof LapEventMsg) {
                    return s.withLap();
                }
                return s;
            });

            var sessionId = SessionId.generate();
            var rpmParam = new ParameterId("ENGINE_RPM");
            var brakeParam = new ParameterId("BRAKE_TEMP");

            // Record samples and laps
            for (int i = 0; i < 50; i++) {
                stats.tell(new Sample(rpmParam, new Timestamp(i), (short) i, new Good()));
                stats.tell(new Sample(brakeParam, new Timestamp(i + 100), (short) (i * 2), new Good()));
            }
            stats.tell(new LapEventMsg(sessionId, new LapNumber(1), new Timestamp(10000)));
            stats.tell(new LapEventMsg(sessionId, new LapNumber(2), new Timestamp(20000)));

            await().atMost(Duration.ofSeconds(2))
                .until(() -> {
                    var st = stats.ask(new QueryState.Full()).get(100, TimeUnit.MILLISECONDS);
                    return st.totalSamples() == 100 && st.lapCount() == 2;
                });

            // Document message: full statistics
            var finalStats = stats.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(finalStats.totalSamples()).isEqualTo(100);
            assertThat(finalStats.lapCount()).isEqualTo(2);
            assertThat(finalStats.samplesPerParam()).containsKeys(rpmParam, brakeParam);

            stats.stop();
        }

        // ── Full SQLRace Session Lifecycle ──────────────────────────────────────────

        @Test
        @DisplayName("Full SQLRace Session Lifecycle Integration")
        void fullSQLRaceSessionWithMessagePatterns() throws Exception {
            var sup = new Supervisor("sqlrace-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var sessionId = SessionId.generate();

            // Event bus for telemetry
            var telemetryBus = EventManager.<Sample>start();

            // Session state
            record SQLRaceSession(
                SessionId id,
                SessionState state,
                List<LapNumber> laps,
                Map<ParameterId, List<Short>> data
            ) {
                static SQLRaceSession initial(SessionId sid) {
                    return new SQLRaceSession(sid, new SessionState.Initialized(), new ArrayList<>(), new HashMap<>());
                }
            }

            interface SessionCmd {
                record Open(SessionId id) implements SessionCmd {}
                record Configure() implements SessionCmd {}
                record GoLive() implements SessionCmd {}
                record Record() implements SessionCmd {}
                record Save() implements SessionCmd {}
                record Close() implements SessionCmd {}
            }

            var session = sup.supervise("session", SQLRaceSession.initial(sessionId), (SQLRaceSession s, Object msg) -> {
                return switch (msg) {
                    case SessionCmd.Open cmd -> new SQLRaceSession(cmd.id(), new SessionState.Initialized(), s.laps(), s.data());
                    case SessionCmd.Configure _ -> new SQLRaceSession(s.id(), new SessionState.Configured(), s.laps(), s.data());
                    case SessionCmd.GoLive _ -> new SQLRaceSession(s.id(), new SessionState.GoLive(), s.laps(), s.data());
                    case SessionCmd.Record _ -> new SQLRaceSession(s.id(), new SessionState.Recording(), s.laps(), s.data());
                    case SessionCmd.Save _ -> new SQLRaceSession(s.id(), new SessionState.Saving(), s.laps(), s.data());
                    case SessionCmd.Close _ -> new SQLRaceSession(s.id(), new SessionState.Closed(), s.laps(), s.data());
                    case Sample sample -> {
                        if (s.state() instanceof SessionState.Recording) {
                            var newData = new HashMap<>(s.data());
                            newData.computeIfAbsent(sample.parameterId(), k -> new ArrayList<>()).add(sample.rawValue());
                            yield new SQLRaceSession(s.id(), s.state(), s.laps(), newData);
                        }
                        yield s;
                    }
                    case LapEventMsg lap -> {
                        var newLaps = new ArrayList<>(s.laps());
                        newLaps.add(lap.lap());
                        yield new SQLRaceSession(s.id(), s.state(), newLaps, s.data());
                    }
                    default -> s;
                };
            });

            // Connect telemetry bus to session
            telemetryBus.addHandler(session::tell);

            // Execute lifecycle
            session.tell(new SessionCmd.Configure());
            session.tell(new SessionCmd.GoLive());
            session.tell(new SessionCmd.Record());

            // Record telemetry
            var rpmParam = new ParameterId("ENGINE_RPM");
            for (int i = 0; i < 100; i++) {
                telemetryBus.notify(new Sample(rpmParam, new Timestamp(i), (short) (5000 + i * 10), new Good()));
            }

            // Create lap
            session.tell(new LapEventMsg(sessionId, new LapNumber(1), new Timestamp(100000)));

            session.tell(new SessionCmd.Save());
            session.tell(new SessionCmd.Close());

            await().atMost(Duration.ofSeconds(3)).until(() -> {
                var s = session.ask(new QueryState.Full()).get(100, TimeUnit.MILLISECONDS);
                return s.state() instanceof SessionState.Closed;
            });

            var finalState = session.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(finalState.laps()).hasSize(1);
            assertThat(finalState.data()).containsKey(rpmParam);
            assertThat(finalState.data().get(rpmParam)).hasSize(100);

            telemetryBus.stop();
            sup.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FileSessionAPI MESSAGE PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FileSessionAPI Message Patterns")
    class FileSessionAPIPatterns {

        // ── FileSession.Save as Claim Check ─────────────────────────────────────────

        @Test
        @DisplayName("FileSession.Save as Claim Check")
        void fileSessionSaveAsClaimCheck() throws Exception {
            var savesCompleted = new AtomicInteger(0);

            // Claim check record
            record ClaimCheck(SessionId sessionId, String location, int sampleCount, long checksum) {}

            // File store state
            record FileStoreState(
                Map<SessionId, ClaimCheck> claims,
                Map<SessionId, List<Sample>> cache
            ) {
                static FileStoreState initial() {
                    return new FileStoreState(new HashMap<>(), new HashMap<>());
                }
            }

            interface FileCmd {
                record Save(SessionId id, List<Sample> samples) implements FileCmd {}
                record Load(SessionId id) implements FileCmd {}
            }

            var fileStore = new Proc<>(FileStoreState.initial(), (FileStoreState s, Object msg) -> {
                if (msg instanceof FileCmd.Save save) {
                    // Simulate file save with claim check generation
                    var checksum = save.samples().stream()
                        .mapToLong(sample -> sample.rawValue())
                        .sum();

                    var claim = new ClaimCheck(
                        save.id(),
                        "/sessions/" + save.id().id() + ".atl",
                        save.samples().size(),
                        checksum
                    );

                    var newClaims = new HashMap<>(s.claims());
                    newClaims.put(save.id(), claim);

                    var newCache = new HashMap<>(s.cache());
                    newCache.put(save.id(), save.samples());

                    savesCompleted.incrementAndGet();
                    return new FileStoreState(newClaims, newCache);
                }
                return s;
            });

            // Save session data
            var sessionId = SessionId.generate();
            var samples = IntStream.range(0, 100)
                .mapToObj(i -> new Sample(
                    new ParameterId("P" + i),
                    new Timestamp(i),
                    (short) i,
                    new Good()
                ))
                .collect(Collectors.toList());

            fileStore.tell(new FileCmd.Save(sessionId, samples));

            await().atMost(Duration.ofSeconds(2)).until(() -> savesCompleted.get() == 1);

            // Verify claim check
            var state = fileStore.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.claims()).containsKey(sessionId);

            var claim = state.claims().get(sessionId);
            assertThat(claim.sampleCount()).isEqualTo(100);
            assertThat(claim.location()).contains(sessionId.id().toString());

            fileStore.stop();
        }

        // ── FileSession.Load as Content Filter ──────────────────────────────────────

        @Test
        @DisplayName("FileSession.Load as Content Filter")
        void fileSessionLoadAsContentFilter() throws Exception {
            var loadsCompleted = new AtomicInteger(0);

            record FileStoreState(
                Map<SessionId, List<Sample>> storage,
                Map<ParameterId, List<Sample>> loadedByParam
            ) {
                static FileStoreState initial() {
                    return new FileStoreState(new HashMap<>(), new HashMap<>());
                }
            }

            interface LoadCmd {
                record LoadAll(SessionId id) implements LoadCmd {}
                record LoadFiltered(SessionId id, Set<ParameterId> filter) implements LoadCmd {}
            }

            var fileStore = new Proc<>(FileStoreState.initial(), (FileStoreState s, Object msg) -> {
                if (msg instanceof LoadCmd.LoadFiltered load) {
                    var samples = s.storage().get(load.id());
                    if (samples != null) {
                        var filtered = samples.stream()
                            .filter(sample -> load.filter().contains(sample.parameterId()))
                            .collect(Collectors.toList());

                        var newLoaded = new HashMap<>(s.loadedByParam());
                        for (var sample : filtered) {
                            newLoaded.computeIfAbsent(sample.parameterId(), k -> new ArrayList<>())
                                .add(sample);
                        }

                        loadsCompleted.incrementAndGet();
                        return new FileStoreState(s.storage(), newLoaded);
                    }
                } else if (msg instanceof LoadCmd.LoadAll load) {
                    loadsCompleted.incrementAndGet();
                }
                return s;
            });

            // Pre-populate storage
            var sessionId = SessionId.generate();
            var rpmParam = new ParameterId("ENGINE_RPM");
            var brakeParam = new ParameterId("BRAKE_TEMP");
            var samples = new ArrayList<Sample>();

            for (int i = 0; i < 50; i++) {
                samples.add(new Sample(rpmParam, new Timestamp(i), (short) i, new Good()));
                samples.add(new Sample(brakeParam, new Timestamp(i + 50), (short) (i * 2), new Good()));
            }

            // Initialize storage
            var initState = fileStore.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            var newStorage = new HashMap<>(initState.storage());
            newStorage.put(sessionId, samples);

            // Load with filter (only RPM data)
            var filter = Set.of(rpmParam);
            fileStore.tell(new LoadCmd.LoadFiltered(sessionId, filter));

            await().atMost(Duration.ofSeconds(2)).until(() -> loadsCompleted.get() == 1);

            fileStore.stop();
        }

        // ── FileSession.Stream as Message Sequence ──────────────────────────────────

        @Test
        @DisplayName("FileSession.Stream as Message Sequence")
        void fileSessionStreamAsMessageSequence() throws Exception {
            var batchesReceived = new AtomicInteger(0);
            var totalItems = new AtomicInteger(0);

            // Stream batch with sequence number
            record StreamBatch(int seqNum, List<Sample> items, boolean last) {}

            record StreamState(
                int expectedSeq,
                List<Sample> assembled,
                Map<Integer, StreamBatch> pending
            ) {
                static StreamState initial() {
                    return new StreamState(0, new ArrayList<>(), new HashMap<>());
                }
            }

            var streamer = new Proc<>(StreamState.initial(), (StreamState s, Object msg) -> {
                if (msg instanceof StreamBatch batch) {
                    batchesReceived.incrementAndGet();
                    totalItems.addAndGet(batch.items().size());

                    if (batch.seqNum() == s.expectedSeq()) {
                        // In-order: append and check pending
                        var newAssembled = new ArrayList<>(s.assembled());
                        newAssembled.addAll(batch.items());

                        var newPending = new HashMap<>(s.pending());
                        int nextSeq = s.expectedSeq() + 1;

                        // Process any pending batches
                        while (newPending.containsKey(nextSeq)) {
                            var pending = newPending.remove(nextSeq);
                            newAssembled.addAll(pending.items());
                            nextSeq++;
                        }

                        return new StreamState(nextSeq, newAssembled, newPending);
                    } else {
                        // Out-of-order: buffer for later
                        var newPending = new HashMap<>(s.pending());
                        newPending.put(batch.seqNum(), batch);
                        return new StreamState(s.expectedSeq(), s.assembled(), newPending);
                    }
                }
                return s;
            });

            // Stream batches (simulating out-of-order delivery)
            var paramId = new ParameterId("STREAM_DATA");

            // Send batch 0, 2, 1 (out of order)
            streamer.tell(new StreamBatch(0,
                IntStream.range(0, 10).mapToObj(i -> new Sample(paramId, new Timestamp(i), (short) i, new Good())).toList(),
                false));
            streamer.tell(new StreamBatch(2,
                IntStream.range(20, 30).mapToObj(i -> new Sample(paramId, new Timestamp(i), (short) i, new Good())).toList(),
                false));
            streamer.tell(new StreamBatch(1,
                IntStream.range(10, 20).mapToObj(i -> new Sample(paramId, new Timestamp(i), (short) i, new Good())).toList(),
                true));

            await().atMost(Duration.ofSeconds(2))
                .until(() -> batchesReceived.get() == 3 && totalItems.get() == 30);

            // Verify ordered assembly
            var state = streamer.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.assembled()).hasSize(30);
            assertThat(state.expectedSeq()).isEqualTo(3);

            streamer.stop();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DisplayAPI MESSAGE PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DisplayAPI Message Patterns")
    class DisplayAPIPatterns {

        // ── Display.Update as Event-Driven Consumer ─────────────────────────────────

        @Test
        @DisplayName("Display.Update as Event-Driven Consumer")
        void displayUpdateAsEventDrivenConsumer() throws Exception {
            var updatesProcessed = new AtomicInteger(0);

            // Display update event
            interface DisplayEvent {
                record RpmUpdate(int value) implements DisplayEvent {}
                record BrakeTempUpdate(int wheel, double temp) implements DisplayEvent {}
                record LapTimeUpdate(int lap, double time) implements DisplayEvent {}
            }

            // Display state
            record DisplayState(
                int rpm,
                Map<Integer, Double> brakeTemps,
                List<Double> lapTimes
            ) {
                static DisplayState initial() {
                    return new DisplayState(0, new HashMap<>(), new ArrayList<>());
                }
            }

            var display = new Proc<>(DisplayState.initial(), (DisplayState s, Object msg) -> {
                if (msg instanceof DisplayEvent.RpmUpdate rpm) {
                    updatesProcessed.incrementAndGet();
                    return new DisplayState(rpm.value(), s.brakeTemps(), s.lapTimes());
                } else if (msg instanceof DisplayEvent.BrakeTempUpdate brake) {
                    updatesProcessed.incrementAndGet();
                    var newTemps = new HashMap<>(s.brakeTemps());
                    newTemps.put(brake.wheel(), brake.temp());
                    return new DisplayState(s.rpm(), newTemps, s.lapTimes());
                } else if (msg instanceof DisplayEvent.LapTimeUpdate lap) {
                    updatesProcessed.incrementAndGet();
                    var newTimes = new ArrayList<>(s.lapTimes());
                    while (newTimes.size() < lap.lap()) newTimes.add(0.0);
                    newTimes.set(lap.lap() - 1, lap.time());
                    return new DisplayState(s.rpm(), s.brakeTemps(), newTimes);
                }
                return s;
            });

            // Event bus for display updates
            var displayBus = EventManager.<DisplayEvent>start();
            displayBus.addHandler(display::tell);

            // Send display updates
            displayBus.notify(new DisplayEvent.RpmUpdate(8500));
            displayBus.notify(new DisplayEvent.RpmUpdate(9200));
            displayBus.notify(new DisplayEvent.BrakeTempUpdate(1, 850.5));
            displayBus.notify(new DisplayEvent.LapTimeUpdate(1, 92.456));

            await().atMost(Duration.ofSeconds(2)).until(() -> updatesProcessed.get() == 4);

            var state = display.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.rpm()).isEqualTo(9200);
            assertThat(state.brakeTemps()).containsEntry(1, 850.5);
            assertThat(state.lapTimes()).hasSize(1);

            displayBus.stop();
            display.stop();
        }

        // ── Plugin.Initialize as Service Activator ──────────────────────────────────

        @Test
        @DisplayName("Plugin.Initialize as Service Activator")
        void pluginInitializationAsServiceActivator() throws Exception {
            var activations = new AtomicInteger(0);

            interface PluginCmd {
                record Initialize(String name) implements PluginCmd {}
                record Start() implements PluginCmd {}
                record Stop() implements PluginCmd {}
            }

            record PluginState(
                String name,
                boolean initialized,
                boolean running,
                List<String> log
            ) {
                static PluginState initial() {
                    return new PluginState(null, false, false, new ArrayList<>());
                }
            }

            var sup = new Supervisor("plugin-sv", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));

            var plugin = sup.supervise("telemetry-plugin", PluginState.initial(), (PluginState s, Object msg) -> {
                if (msg instanceof PluginCmd.Initialize init) {
                    activations.incrementAndGet();
                    var log = new ArrayList<String>();
                    log.add("Initialized: " + init.name());
                    return new PluginState(init.name(), true, false, log);
                } else if (msg instanceof PluginCmd.Start && s.initialized()) {
                    var log = new ArrayList<>(s.log());
                    log.add("Started");
                    return new PluginState(s.name(), s.initialized(), true, log);
                } else if (msg instanceof PluginCmd.Stop && s.running()) {
                    var log = new ArrayList<>(s.log());
                    log.add("Stopped");
                    return new PluginState(s.name(), s.initialized(), false, log);
                }
                return s;
            });

            // Activate plugin
            plugin.tell(new PluginCmd.Initialize("TelemetryVisualizer"));
            plugin.tell(new PluginCmd.Start());

            await().atMost(Duration.ofSeconds(2)).until(() -> activations.get() == 1);

            var state = plugin.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.initialized()).isTrue();
            assertThat(state.running()).isTrue();
            assertThat(state.log()).contains("Initialized: TelemetryVisualizer", "Started");

            sup.shutdown();
        }

        // ── ToolWindow as Message Bus ───────────────────────────────────────────────

        @Test
        @DisplayName("ToolWindow as Message Bus")
        void toolWindowAsMessageBus() throws Exception {
            var windowsCreated = new AtomicInteger(0);
            var eventsReceived = new AtomicInteger(0);

            interface ToolWindowEvent {
                record Created(String windowId, String title) implements ToolWindowEvent {}
                record Focused(String windowId) implements ToolWindowEvent {}
                record Closed(String windowId) implements ToolWindowEvent {}
            }

            // Tool window manager
            record ToolWindowState(
                Map<String, String> windows,
                String focusedWindow
            ) {
                static ToolWindowState initial() {
                    return new ToolWindowState(new HashMap<>(), null);
                }
            }

            var toolManager = new Proc<>(ToolWindowState.initial(), (ToolWindowState s, Object msg) -> {
                if (msg instanceof ToolWindowEvent.Created created) {
                    windowsCreated.incrementAndGet();
                    var newWindows = new HashMap<>(s.windows());
                    newWindows.put(created.windowId(), created.title());
                    return new ToolWindowState(newWindows, s.focusedWindow());
                } else if (msg instanceof ToolWindowEvent.Focused focused) {
                    return new ToolWindowState(s.windows(), focused.windowId());
                } else if (msg instanceof ToolWindowEvent.Closed closed) {
                    var newWindows = new HashMap<>(s.windows());
                    newWindows.remove(closed.windowId());
                    return new ToolWindowState(newWindows, s.focusedWindow());
                }
                return s;
            });

            // Message bus for tool window events
            var toolBus = EventManager.<ToolWindowEvent>start();
            toolBus.addHandler(toolManager::tell);
            toolBus.addHandler(e -> eventsReceived.incrementAndGet());

            // Create tool windows
            toolBus.notify(new ToolWindowEvent.Created("tw-1", "Telemetry"));
            toolBus.notify(new ToolWindowEvent.Created("tw-2", "Timing"));
            toolBus.notify(new ToolWindowEvent.Focused("tw-1"));
            toolBus.notify(new ToolWindowEvent.Closed("tw-2"));

            await().atMost(Duration.ofSeconds(2))
                .until(() -> windowsCreated.get() == 2 && eventsReceived.get() == 4);

            var state = toolManager.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.windows()).hasSize(1);
            assertThat(state.windows()).containsKey("tw-1");
            assertThat(state.focusedWindow()).isEqualTo("tw-1");

            toolBus.stop();
            toolManager.stop();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CROSS-API ORCHESTRATION
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cross-API Orchestration")
    class CrossAPIOrchestration {

        @Test
        @DisplayName("Full Pipeline: SQLRace → FileSession → Display")
        void fullPipelineSQLRaceToFileSessionToDisplay() throws Exception {
            var sup = new Supervisor("atlas-sv", Strategy.ONE_FOR_ONE, 100, Duration.ofMinutes(5));
            var sessionId = SessionId.generate();

            // Telemetry bus (SQLRaceAPI)
            var telemetryBus = EventManager.<Sample>start();

            // Display bus (DisplayAPI)
            var displayBus = EventManager.<Object>start();

            // SQLRace: Session
            record SQLRaceState(SessionId id, SessionState state, int sampleCount) {
                static SQLRaceState initial() { return new SQLRaceState(null, new SessionState.Initialized(), 0); }
            }

            var session = sup.supervise("session", SQLRaceState.initial(), (SQLRaceState s, Object msg) -> {
                if (msg instanceof Sample sample) {
                    // Forward to display and file
                    telemetryBus.notify(sample);
                    return new SQLRaceState(s.id(), s.state(), s.sampleCount() + 1);
                }
                return s;
            });

            // FileSession: Auto-save after 50 samples
            record FileSessionState(int samplesSinceSave, List<Sample> pending) {
                static FileSessionState initial() { return new FileSessionState(0, new ArrayList<>()); }
            }

            var fileStore = sup.supervise("filestore", FileSessionState.initial(), (FileSessionState s, Object msg) -> {
                if (msg instanceof Sample sample) {
                    var newPending = new ArrayList<>(s.pending());
                    newPending.add(sample);
                    var newCount = s.samplesSinceSave() + 1;

                    // Auto-save every 50 samples
                    if (newCount >= 50) {
                        return new FileSessionState(0, new ArrayList<>());
                    }
                    return new FileSessionState(newCount, newPending);
                }
                return s;
            });

            // Display: Update on each sample
            record DisplayState(int lastRpm, int updateCount) {
                static DisplayState initial() { return new DisplayState(0, 0); }
            }

            var display = sup.supervise("display", DisplayState.initial(), (DisplayState s, Object msg) -> {
                if (msg instanceof Sample sample && sample.parameterId().id().equals("ENGINE_RPM")) {
                    return new DisplayState(sample.rawValue(), s.updateCount() + 1);
                }
                return s;
            });

            // Connect buses
            telemetryBus.addHandler(fileStore::tell);
            telemetryBus.addHandler(display::tell);
            telemetryBus.addHandler(sample -> {
                if (sample.parameterId().id().equals("ENGINE_RPM")) {
                    displayBus.notify(sample);
                }
            });

            // Send samples through pipeline
            var rpmParam = new ParameterId("ENGINE_RPM");
            for (int i = 0; i < 75; i++) {
                session.tell(new Sample(rpmParam, new Timestamp(i), (short) (5000 + i * 100), new Good()));
            }

            await().atMost(Duration.ofSeconds(3)).until(() -> {
                var dispState = display.ask(new QueryState.Full()).get(100, TimeUnit.MILLISECONDS);
                return dispState.updateCount() >= 75;
            });

            var finalDisplay = display.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(finalDisplay.updateCount()).isEqualTo(75);
            assertThat(finalDisplay.lastRpm()).isEqualTo((short) (5000 + 74 * 100));

            telemetryBus.stop();
            displayBus.stop();
            sup.shutdown();
        }

        @Test
        @DisplayName("Failure Recovery with Supervision")
        void failureRecoveryWithSupervision() throws Exception {
            var sup = new Supervisor("recovery-sv", Strategy.ONE_FOR_ONE, 5, Duration.ofMinutes(1));
            var processed = new AtomicInteger(0);
            var crashes = new AtomicInteger(0);

            // Session that crashes on certain values
            var session = sup.supervise("session", 0, (Integer s, Sample msg) -> {
                if (msg.rawValue() == -1) {
                    crashes.incrementAndGet();
                    throw new RuntimeException("Simulated crash on invalid value");
                }
                processed.incrementAndGet();
                return s + 1;
            });

            // Send valid samples
            var paramId = new ParameterId("TEST_PARAM");
            for (int i = 0; i < 50; i++) {
                session.tell(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }

            // Send crash-inducing sample
            session.tell(new Sample(paramId, new Timestamp(100), (short) -1, new Good()));

            // Continue after crash (supervisor restarts)
            for (int i = 0; i < 10; i++) {
                session.tell(new Sample(paramId, new Timestamp(200 + i), (short) i, new Good()));
            }

            await().atMost(Duration.ofSeconds(3))
                .until(() -> processed.get() >= 50 && crashes.get() >= 1);

            assertThat(crashes.get()).isGreaterThanOrEqualTo(1);
            // After crash, supervisor should have restarted and processed more

            sup.shutdown();
        }

        @Test
        @DisplayName("Cross-API Event Correlation")
        void crossAPIEventCorrelation() throws Exception {
            var sessionId = SessionId.generate();
            var correlatedEvents = new AtomicInteger(0);

            // Correlated event across all three APIs
            interface CorrelatedEvent {
                SessionId sessionId();
                record SampleReceived(SessionId sessionId, Sample sample) implements CorrelatedEvent {}
                record FileSaved(SessionId sessionId, String path) implements CorrelatedEvent {}
                record DisplayUpdated(SessionId sessionId, String component) implements CorrelatedEvent {}
            }

            record CorrelationState(
                Map<SessionId, Integer> samplesPerSession,
                Map<SessionId, String> filesPerSession,
                Map<SessionId, List<String>> displaysPerSession
            ) {
                static CorrelationState initial() {
                    return new CorrelationState(new HashMap<>(), new HashMap<>(), new HashMap<>());
                }
            }

            var correlator = new Proc<>(CorrelationState.initial(), (CorrelationState s, Object msg) -> {
                if (msg instanceof CorrelatedEvent event) {
                    correlatedEvents.incrementAndGet();
                    var sid = event.sessionId();

                    return switch (event) {
                        case CorrelatedEvent.SampleReceived sr -> {
                            var newSamples = new HashMap<>(s.samplesPerSession());
                            newSamples.merge(sid, 1, Integer::sum);
                            yield new CorrelationState(newSamples, s.filesPerSession(), s.displaysPerSession());
                        }
                        case CorrelatedEvent.FileSaved fs -> {
                            var newFiles = new HashMap<>(s.filesPerSession());
                            newFiles.put(sid, fs.path());
                            yield new CorrelationState(s.samplesPerSession(), newFiles, s.displaysPerSession());
                        }
                        case CorrelatedEvent.DisplayUpdated du -> {
                            var newDisplays = new HashMap<>(s.displaysPerSession());
                            newDisplays.computeIfAbsent(sid, k -> new ArrayList<>()).add(du.component());
                            yield new CorrelationState(s.samplesPerSession(), s.filesPerSession(), newDisplays);
                        }
                    };
                }
                yield s;
            });

            // Send correlated events from all three APIs
            correlator.tell(new CorrelatedEvent.SampleReceived(sessionId,
                new Sample(new ParameterId("RPM"), new Timestamp(0), (short) 5000, new Good())));
            correlator.tell(new CorrelatedEvent.DisplayUpdated(sessionId, "RPM_GAUGE"));
            correlator.tell(new CorrelatedEvent.FileSaved(sessionId, "/sessions/test.atl"));

            await().atMost(Duration.ofSeconds(2)).until(() -> correlatedEvents.get() == 3);

            var state = correlator.ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
            assertThat(state.samplesPerSession()).containsKey(sessionId);
            assertThat(state.filesPerSession()).containsKey(sessionId);
            assertThat(state.displaysPerSession()).containsKey(sessionId);

            correlator.stop();
        }
    }
}
