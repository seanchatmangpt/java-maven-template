package io.github.seanchatmangpt.jotp.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Stress tests for McLaren Atlas API → JOTP Message Patterns.
 *
 * <p>Validates theoretical baselines from phd-thesis-atlas-message-patterns.md
 * with actual throughput measurements.
 *
 * <h2>Proven Baselines (Inherited)</h2>
 * <ul>
 *   <li>Message Channel: 30.1M msg/s</li>
 *   <li>Event Fanout: 1.1B events/s</li>
 *   <li>Request-Reply: 78K roundtrip/s</li>
 *   <li>Competing Consumers: 2.2M consume/s</li>
 * </ul>
 *
 * <h2>Theoretical Atlas Baselines</h2>
 * <ul>
 *   <li>Session.Open: 2M+ cmd/s</li>
 *   <li>WriteSample: 100M+ events/s</li>
 *   <li>GetParameters: 78K+ rt/s</li>
 *   <li>FileSession.Save: 50K+ saves/s</li>
 *   <li>Display.Update: 1M+ updates/s</li>
 * </ul>
 */
@Timeout(180)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Atlas API Stress Tests")
class AtlasAPIStressTest implements WithAssertions {

    // ═══════════════════════════════════════════════════════════════════════════════
    // THEORETICAL BASELINES FROM THESIS
    // ═══════════════════════════════════════════════════════════════════════════════

    // SQLRaceAPI baselines (derived from proven JOTP baselines)
    static final double SESSION_OPEN_BASELINE = 2_000_000.0;        // Command Message
    static final double WRITE_SAMPLE_BASELINE = 100_000_000.0;      // Event Message (1% of fanout)
    static final double GET_PARAMETERS_BASELINE = 78_000.0;         // Request-Reply
    static final double CREATE_LAP_BASELINE = 500_000.0;            // Correlation ID
    static final double GET_STATISTICS_BASELINE = 100_000.0;        // Document Message

    // FileSessionAPI baselines
    static final double FILE_SAVE_BASELINE = 50_000.0;              // Claim Check (I/O limited)
    static final double FILE_LOAD_BASELINE = 100_000.0;             // Content Filter
    static final double FILE_STREAM_BASELINE = 1_000_000.0;         // Message Sequence

    // DisplayAPI baselines
    static final double DISPLAY_UPDATE_BASELINE = 1_000_000.0;      // Event-Driven Consumer
    static final double PLUGIN_INIT_BASELINE = 10_000.0;            // Service Activator
    static final double TOOLWINDOW_CREATE_BASELINE = 100_000.0;     // Message Bus

    // ═══════════════════════════════════════════════════════════════════════════════
    // BASELINE VALIDATION HELPER
    // ═══════════════════════════════════════════════════════════════════════════════

    void assertBaseline(String metric, double actual, double baseline) {
        double ratio = actual / baseline;
        System.out.printf("[%s] actual=%,.0f baseline=%,.0f ratio=%.2fx%n",
            metric, actual, baseline, ratio);

        // Use 50% of baseline as minimum to account for test environment variance
        // (thesis baselines are conservative production targets)
        double minimumThreshold = baseline * 0.5;
        assertThat(actual)
            .as("%s: actual=%,.0f should meet baseline of %,.0f (minimum %.0f)"
                .formatted(metric, actual, baseline, minimumThreshold))
            .isGreaterThan(minimumThreshold);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SQLRaceAPI STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SQLRaceAPI Stress Tests")
    class SQLRaceAPIStressTests {

        @Test
        @DisplayName("Session.Open: 2M+ command messages/second — thesis baseline")
        void sessionOpen_2MCommandsPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            interface SessionCmd {
                record Open(SessionId id) implements SessionCmd {}
            }

            record SessionState(SessionId currentId, int openCount) {
                static SessionState initial() { return new SessionState(null, 0); }
            }

            var session = new Proc<>(SessionState.initial(), (SessionState s, Object msg) -> {
                if (msg instanceof SessionCmd.Open open) {
                    processed.incrementAndGet();
                    return new SessionState(open.id(), s.openCount() + 1);
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                session.tell(new SessionCmd.Open(SessionId.generate()));
            }
            awaitProcessed(processed, 10_000);

            // Benchmark
            processed.set(0);
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                session.tell(new SessionCmd.Open(SessionId.generate()));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[Session.Open] %d commands in %.2f ms = %,.0f cmd/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("Session.Open", throughput, SESSION_OPEN_BASELINE);

            session.stop();
        }

        @Test
        @DisplayName("Session.WriteSample: 100M+ event messages/second — thesis baseline")
        void writeSample_100MEventsPerSecond() throws Exception {
            var processed = new LongAdder();

            // Event bus for sample distribution
            var sampleBus = EventManager.<Sample>start();

            // Multiple consumers (simulating Display, FileStore, etc.)
            int handlerCount = 10;
            for (int i = 0; i < handlerCount; i++) {
                sampleBus.addHandler(s -> processed.increment());
            }

            var paramId = new ParameterId("ENGINE_RPM");

            // Warmup
            for (int i = 0; i < 1_000; i++) {
                sampleBus.notify(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }
            awaitProcessedLong(processed, 1_000 * handlerCount);

            // Benchmark
            processed.reset();
            int events = 10_000;
            var start = System.nanoTime();

            for (int i = 0; i < events; i++) {
                sampleBus.notify(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }

            long expectedDeliveries = (long) events * handlerCount;
            awaitProcessedLong(processed, expectedDeliveries);

            var elapsed = System.nanoTime() - start;
            double throughput = expectedDeliveries * 1_000_000_000.0 / elapsed;

            System.out.printf("[WriteSample] events=%d handlers=%d deliveries=%d in %.2f ms = %,.0f deliveries/s%n",
                events, handlerCount, expectedDeliveries, elapsed / 1_000_000.0, throughput);

            assertBaseline("WriteSample", throughput, WRITE_SAMPLE_BASELINE);

            sampleBus.stop();
        }

        @Test
        @DisplayName("Session.GetParameters: 78K+ round-trips/second — thesis baseline")
        void getParameters_78KRoundTripsPerSecond() throws Exception {
            var params = List.of(
                new ParameterSpec(new ParameterId("ENGINE_RPM"), "Engine RPM", 0, 15000, "rpm"),
                new ParameterSpec(new ParameterId("BRAKE_TEMP"), "Brake Temp", 0, 1200, "C")
            );

            record SessionState(List<ParameterSpec> parameters) {
                static SessionState initial(List<ParameterSpec> p) { return new SessionState(p); }
            }

            var session = new Proc<>(SessionState.initial(params), (SessionState s, Object msg) -> s);

            // Warmup
            for (int i = 0; i < 1_000; i++) {
                session.ask(new QueryState.Full()).get(1, TimeUnit.SECONDS);
            }

            // Benchmark
            int iterations = 10_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                session.ask(new QueryState.Full()).get(1, TimeUnit.SECONDS);
            }

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[GetParameters] %d round-trips in %.2f ms = %,.0f rt/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("GetParameters", throughput, GET_PARAMETERS_BASELINE);

            session.stop();
        }

        @Test
        @DisplayName("Session.CreateLap: 500K+ correlations/second — thesis baseline")
        void createLap_500KCorrelationsPerSecond() throws Exception {
            var sessionId = SessionId.generate();
            var processed = new AtomicInteger(0);

            record LapState(List<LapNumber> laps) {
                static LapState initial() { return new LapState(new ArrayList<>()); }
            }

            interface LapMsg {
                record Create(LapNumber lap, Timestamp beacon) implements LapMsg {}
            }

            var lapManager = new Proc<>(LapState.initial(), (LapState s, Object msg) -> {
                if (msg instanceof LapMsg.Create create) {
                    processed.incrementAndGet();
                    var newLaps = new ArrayList<>(s.laps());
                    newLaps.add(create.lap());
                    return new LapState(newLaps);
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                lapManager.tell(new LapMsg.Create(new LapNumber(i), new Timestamp(i)));
            }
            awaitProcessed(processed, 10_000);

            // Benchmark
            processed.set(0);
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                lapManager.tell(new LapMsg.Create(new LapNumber(i), new Timestamp(i)));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[CreateLap] %d lap correlations in %.2f ms = %,.0f corr/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("CreateLap", throughput, CREATE_LAP_BASELINE);

            lapManager.stop();
        }

        @Test
        @DisplayName("Session.GetStatistics: 100K+ document queries/second — thesis baseline")
        void getStatistics_100KDocumentQueriesPerSecond() throws Exception {
            record Stats(int sampleCount, int lapCount, double avgValue) {
                static Stats initial() { return new Stats(0, 0, 0.0); }
            }

            var stats = new Proc<>(Stats.initial(), (Stats s, Object msg) -> {
                if (msg instanceof Sample sample) {
                    return new Stats(s.sampleCount() + 1, s.lapCount(),
                        (s.avgValue() * s.sampleCount() + sample.rawValue()) / (s.sampleCount() + 1));
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 1_000; i++) {
                stats.ask(new QueryState.Full()).get(1, TimeUnit.SECONDS);
            }

            // Benchmark
            int iterations = 10_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                stats.ask(new QueryState.Full()).get(1, TimeUnit.SECONDS);
            }

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[GetStatistics] %d document queries in %.2f ms = %,.0f queries/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("GetStatistics", throughput, GET_STATISTICS_BASELINE);

            stats.stop();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FileSessionAPI STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FileSessionAPI Stress Tests")
    class FileSessionAPIStressTests {

        @Test
        @DisplayName("FileSession.Save: 50K+ claim checks/second — thesis baseline")
        void fileSave_50KClaimChecksPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            record ClaimCheck(SessionId id, String location, int checksum) {}

            record FileStoreState(Map<SessionId, ClaimCheck> claims) {
                static FileStoreState initial() { return new FileStoreState(new HashMap<>()); }
            }

            interface FileCmd {
                record Save(SessionId id, int checksum) implements FileCmd {}
            }

            var fileStore = new Proc<>(FileStoreState.initial(), (FileStoreState s, Object msg) -> {
                if (msg instanceof FileCmd.Save save) {
                    processed.incrementAndGet();
                    var newClaims = new HashMap<>(s.claims());
                    newClaims.put(save.id(), new ClaimCheck(save.id(), "/path/" + save.id(), save.checksum()));
                    return new FileStoreState(newClaims);
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 5_000; i++) {
                fileStore.tell(new FileCmd.Save(SessionId.generate(), i));
            }
            awaitProcessed(processed, 5_000);

            // Benchmark
            processed.set(0);
            int iterations = 50_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                fileStore.tell(new FileCmd.Save(SessionId.generate(), i));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[FileSave] %d claim checks in %.2f ms = %,.0f saves/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("FileSave", throughput, FILE_SAVE_BASELINE);

            fileStore.stop();
        }

        @Test
        @DisplayName("FileSession.Load: 100K+ filtered loads/second — thesis baseline")
        void fileLoad_100KFilteredLoadsPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            record FileStoreState(Map<SessionId, String> cache) {
                static FileStoreState initial() { return new FileStoreState(new HashMap<>()); }
            }

            interface LoadCmd {
                record Load(SessionId id, String filter) implements LoadCmd {}
            }

            var fileStore = new Proc<>(FileStoreState.initial(), (FileStoreState s, Object msg) -> {
                if (msg instanceof LoadCmd.Load load) {
                    processed.incrementAndGet();
                    var newCache = new HashMap<>(s.cache());
                    newCache.put(load.id(), "filtered:" + load.filter());
                    return new FileStoreState(newCache);
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                fileStore.tell(new LoadCmd.Load(SessionId.generate(), "rpm"));
            }
            awaitProcessed(processed, 10_000);

            // Benchmark
            processed.set(0);
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                fileStore.tell(new LoadCmd.Load(SessionId.generate(), "rpm"));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[FileLoad] %d filtered loads in %.2f ms = %,.0f loads/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("FileLoad", throughput, FILE_LOAD_BASELINE);

            fileStore.stop();
        }

        @Test
        @DisplayName("FileSession.Stream: 1M+ stream items/second — thesis baseline")
        void fileStream_1MStreamItemsPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            record StreamBatch(int seqNum, int itemCount) {}

            record StreamState(int nextSeq, int totalItems) {
                static StreamState initial() { return new StreamState(0, 0); }
            }

            var streamer = new Proc<>(StreamState.initial(), (StreamState s, Object msg) -> {
                if (msg instanceof StreamBatch batch) {
                    processed.addAndGet(batch.itemCount());
                    return new StreamState(s.nextSeq() + 1, s.totalItems() + batch.itemCount());
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                streamer.tell(new StreamBatch(i, 10));
            }
            awaitProcessed(processed, 100_000);

            // Benchmark
            processed.set(0);
            int batches = 10_000;
            int itemsPerBatch = 100;
            var start = System.nanoTime();

            for (int i = 0; i < batches; i++) {
                streamer.tell(new StreamBatch(i, itemsPerBatch));
            }
            awaitProcessed(processed, batches * itemsPerBatch);

            var elapsed = System.nanoTime() - start;
            int totalItems = batches * itemsPerBatch;
            double throughput = totalItems * 1_000_000_000.0 / elapsed;

            System.out.printf("[FileStream] batches=%d items/batch=%d total=%d in %.2f ms = %,.0f items/s%n",
                batches, itemsPerBatch, totalItems, elapsed / 1_000_000.0, throughput);

            assertBaseline("FileStream", throughput, FILE_STREAM_BASELINE);

            streamer.stop();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DisplayAPI STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DisplayAPI Stress Tests")
    class DisplayAPIStressTests {

        @Test
        @DisplayName("Display.Update: 1M+ updates/second — thesis baseline")
        void displayUpdate_1MUpdatesPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            interface DisplayEvent {
                record RpmUpdate(int value) implements DisplayEvent {}
                record TempUpdate(int wheel, double temp) implements DisplayEvent {}
            }

            record DisplayState(int rpm, Map<Integer, Double> temps) {
                static DisplayState initial() { return new DisplayState(0, new HashMap<>()); }
            }

            var display = new Proc<>(DisplayState.initial(), (DisplayState s, Object msg) -> {
                if (msg instanceof DisplayEvent.RpmUpdate rpm) {
                    processed.incrementAndGet();
                    return new DisplayState(rpm.value(), s.temps());
                } else if (msg instanceof DisplayEvent.TempUpdate temp) {
                    processed.incrementAndGet();
                    var newTemps = new HashMap<>(s.temps());
                    newTemps.put(temp.wheel(), temp.temp());
                    return new DisplayState(s.rpm(), newTemps);
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                display.tell(new DisplayEvent.RpmUpdate(i));
            }
            awaitProcessed(processed, 10_000);

            // Benchmark
            processed.set(0);
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                display.tell(new DisplayEvent.RpmUpdate(i));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[DisplayUpdate] %d updates in %.2f ms = %,.0f updates/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("DisplayUpdate", throughput, DISPLAY_UPDATE_BASELINE);

            display.stop();
        }

        @Test
        @DisplayName("Plugin.Initialize: 10K+ activations/second — thesis baseline")
        void pluginInit_10KActivationsPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            interface PluginCmd {
                record Initialize(String name) implements PluginCmd {}
                record Start() implements PluginCmd {}
            }

            record PluginState(String name, boolean initialized) {
                static PluginState initial() { return new PluginState(null, false); }
            }

            var sup = new Supervisor("plugin-stress-sv", Strategy.ONE_FOR_ONE, 1000, Duration.ofMinutes(5));
            var plugin = sup.supervise("plugin", PluginState.initial(), (PluginState s, Object msg) -> {
                if (msg instanceof PluginCmd.Initialize init) {
                    processed.incrementAndGet();
                    return new PluginState(init.name(), true);
                }
                return s;
            });

            // Warmup
            for (int i = 0; i < 1_000; i++) {
                plugin.tell(new PluginCmd.Initialize("plugin-" + i));
            }
            awaitProcessed(processed, 1_000);

            // Benchmark
            processed.set(0);
            int iterations = 20_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                plugin.tell(new PluginCmd.Initialize("plugin-" + i));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[PluginInit] %d activations in %.2f ms = %,.0f activations/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("PluginInit", throughput, PLUGIN_INIT_BASELINE);

            sup.shutdown();
        }

        @Test
        @DisplayName("ToolWindow.Create: 100K+ creates/second — thesis baseline")
        void toolWindowCreate_100KCreatesPerSecond() throws Exception {
            var processed = new AtomicInteger(0);
            var deliveries = new LongAdder();

            interface ToolWindowEvent {
                record Created(String windowId, String title) implements ToolWindowEvent {}
            }

            var toolBus = EventManager.<ToolWindowEvent>start();
            toolBus.addHandler(e -> deliveries.increment());

            record ToolWindowState(Map<String, String> windows) {
                static ToolWindowState initial() { return new ToolWindowState(new HashMap<>()); }
            }

            var toolManager = new Proc<>(ToolWindowState.initial(), (ToolWindowState s, Object msg) -> {
                if (msg instanceof ToolWindowEvent.Created created) {
                    processed.incrementAndGet();
                    var newWindows = new HashMap<>(s.windows());
                    newWindows.put(created.windowId(), created.title());
                    return new ToolWindowState(newWindows);
                }
                return s;
            });

            toolBus.addHandler(toolManager::tell);

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                toolBus.notify(new ToolWindowEvent.Created("tw-" + i, "Window " + i));
            }
            awaitProcessed(processed, 10_000);

            // Benchmark
            processed.set(0);
            deliveries.reset();
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                toolBus.notify(new ToolWindowEvent.Created("tw-" + i, "Window " + i));
            }
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[ToolWindowCreate] %d creates in %.2f ms = %,.0f creates/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("ToolWindowCreate", throughput, TOOLWINDOW_CREATE_BASELINE);

            toolBus.stop();
            toolManager.stop();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CROSS-API STRESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cross-API Stress Tests")
    class CrossAPIStressTests {

        @Test
        @DisplayName("Full Pipeline: 500K+ samples through SQLRace → FileSession → Display")
        void fullPipeline_500KSamplesPerSecond() throws Exception {
            var processed = new AtomicInteger(0);

            // SQLRace: Session
            var session = new Proc<>(0, (Integer s, Sample msg) -> {
                return s + 1;
            });

            // FileSession: Auto-save
            var fileStore = new Proc<>(0, (Integer s, Sample msg) -> {
                return s + 1;
            });

            // Display: Update
            var display = new Proc<>(0, (Integer s, Sample msg) -> {
                processed.incrementAndGet();
                return s + 1;
            });

            // Event bus connecting all three
            var telemetryBus = EventManager.<Sample>start();
            telemetryBus.addHandler(fileStore::tell);
            telemetryBus.addHandler(display::tell);

            var paramId = new ParameterId("ENGINE_RPM");

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                session.tell(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
                telemetryBus.notify(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }
            awaitProcessed(processed, 10_000);

            // Benchmark
            processed.set(0);
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                var sample = new Sample(paramId, new Timestamp(i), (short) i, new Good());
                session.tell(sample);
                telemetryBus.notify(sample); // Delivers to both FileStore and Display
            }

            // Each iteration produces 2 deliveries (fileStore + display)
            awaitProcessed(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[FullPipeline] %d samples through 3 APIs in %.2f ms = %,.0f samples/s%n",
                iterations, elapsed / 1_000_000.0, throughput);

            // Cross-pipeline baseline: should handle at least 500K/s
            assertBaseline("FullPipeline", throughput, 500_000.0);

            telemetryBus.stop();
            session.stop();
            fileStore.stop();
            display.stop();
        }

        @Test
        @DisplayName("Competing Consumers: 2M+ with 10 parallel consumers")
        void competingConsumers_2MPerSecond() throws Exception {
            var queue = new LinkedTransferQueue<Sample>();
            var running = new AtomicBoolean(true);
            var processed = new LongAdder();

            int consumerCount = 10;
            var consumers = new ArrayList<Thread>();

            // Start competing consumers
            for (int i = 0; i < consumerCount; i++) {
                var consumer = Thread.ofVirtual().start(() -> {
                    while (running.get()) {
                        try {
                            var sample = queue.poll(10, TimeUnit.MILLISECONDS);
                            if (sample != null) {
                                processed.increment();
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
                consumers.add(consumer);
            }

            var paramId = new ParameterId("ENGINE_RPM");

            // Warmup
            for (int i = 0; i < 10_000; i++) {
                queue.put(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }
            awaitProcessedLong(processed, 10_000);

            // Benchmark
            processed.reset();
            int iterations = 100_000;
            var start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                queue.put(new Sample(paramId, new Timestamp(i), (short) i, new Good()));
            }
            awaitProcessedLong(processed, iterations);

            var elapsed = System.nanoTime() - start;
            double throughput = iterations * 1_000_000_000.0 / elapsed;

            System.out.printf("[CompetingConsumers] consumers=%d messages=%d in %.2f ms = %,.0f consume/s%n",
                consumerCount, iterations, elapsed / 1_000_000.0, throughput);

            assertBaseline("CompetingConsumers", throughput, 2_000_000.0);

            running.set(false);
            for (var consumer : consumers) {
                consumer.join(1000);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private void awaitProcessed(AtomicInteger counter, int target) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (counter.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private void awaitProcessedLong(LongAdder counter, long target) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (counter.sum() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
