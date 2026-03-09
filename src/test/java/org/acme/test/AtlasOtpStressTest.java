package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Gatherers;
import java.util.stream.IntStream;
import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.ProcessRegistry;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;
import org.acme.dogfood.mclaren.AcquisitionSupervisor;
import org.acme.dogfood.mclaren.DataType;
import org.acme.dogfood.mclaren.FrequencyUnit;
import org.acme.dogfood.mclaren.PdaMsg;
import org.acme.dogfood.mclaren.PdaStats;
import org.acme.dogfood.mclaren.RationalConversion;
import org.acme.dogfood.mclaren.SessionEventBus;
import org.acme.dogfood.mclaren.SqlRaceChannel;
import org.acme.dogfood.mclaren.SqlRaceLap;
import org.acme.dogfood.mclaren.SqlRaceParameter;
import org.acme.dogfood.mclaren.SqlRaceSession;
import org.acme.dogfood.mclaren.SqlRaceSessionData;
import org.acme.dogfood.mclaren.SqlRaceSessionEvent;
import org.acme.dogfood.mclaren.SqlRaceSessionState;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

/**
 * McLaren Atlas F1 telemetry — OTP stress tests at 1 million virtual threads.
 *
 * <p>Each test spawns exactly 1 000 000 virtual threads via {@code
 * Executors.newVirtualThreadPerTaskExecutor()}. A {@link Semaphore} parks all but {@code
 * CONCURRENCY} threads cheaply (~1 KB stack each) while the active slice drives real McLaren Atlas
 * OTP work against production domain types.
 *
 * <p>OTP primitives and Atlas domain types under stress:
 *
 * <ol>
 *   <li>{@link AcquisitionSupervisor} — 1 K ONE_FOR_ONE supervised {@code ParameterDataAccess}
 *       processes; 1 M live ECU samples pushed via {@link AcquisitionSupervisor#addSamples}
 *   <li>{@link ProcessRegistry} — 1 K registered {@link PdaMsg}-typed procs; 1 M concurrent
 *       {@code whereis()} lookups followed by {@link PdaMsg.AddSamples} fire-and-forget
 *   <li>{@link SqlRaceSession} — 1 K session state machines configured and moved to {@code Live};
 *       1 M concurrent {@link SqlRaceSessionEvent.AddLap} events; exact lap count verified
 *   <li>{@link SessionEventBus} — 10 registered handlers; 1 M async {@link
 *       SqlRaceSessionEvent.AddLap} broadcasts; every handler must receive every event
 *   <li>{@link Supervisor} crash storm — 1 K supervised procs; 1 M mixed-load {@link
 *       PdaMsg.AddSamples} (10 % poison → crash); ONE_FOR_ONE restarts all; root stays alive
 * </ol>
 *
 * <p>Java 25 {@link Gatherers} ({@code fold} and {@code windowFixed}) aggregate and validate every
 * result using the finalized {@code Stream.gather} API.
 */
@DisplayName("McLaren Atlas — OTP 1 M virtual-thread stress")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class AtlasOtpStressTest implements WithAssertions {

    // ── Concurrency constants ────────────────────────────────────────────────

    /** Total virtual threads spawned per test. */
    private static final int N = 1_000_000;

    /** VTs active simultaneously; the remaining 980 K park on the {@link Semaphore}. */
    private static final int CONCURRENCY = 20_000;

    /** Number of supervised {@code ParameterDataAccess} procs / registered sensors per test. */
    private static final int PARAM_COUNT = 1_000;

    /** Number of {@link SqlRaceSession} state machines used in Test 3. */
    private static final int SESSION_COUNT = 1_000;

    // ── Shared channel / parameter fixture ──────────────────────────────────

    /**
     * 200 Hz signed-16 channel for car speed — used to configure sessions in Test 3.
     * Real ATLAS: {@code new Channel(1L, "vCar", 5_000_000L, DataType.Signed16Bit, Periodic)}.
     */
    private static final SqlRaceChannel SHARED_CHANNEL =
            SqlRaceChannel.periodic(1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);

    /**
     * Car-speed parameter bound to the shared channel.
     * Identifier follows the McLaren convention: {@code "vCar:Chassis"}.
     */
    private static final SqlRaceParameter SHARED_PARAM =
            SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");

    /** Identity conversion: raw ADC value equals engineering value. */
    private static final RationalConversion SHARED_CONV =
            RationalConversion.identity("CONV_vCar:Chassis", "kph");

    // ── Virtual-thread harness ───────────────────────────────────────────────

    /**
     * Spawns exactly {@value #N} virtual threads. Each parks on {@code gate} until a semaphore
     * permit is available, executes {@code task(idx)}, then releases the permit and counts down.
     * Blocks until all threads finish or {@code timeout} expires.
     *
     * @param semaphorePermits max concurrent VTs doing real work (rest park cheaply)
     * @param task             receives the zero-based thread index
     * @param successes        incremented on each successful task invocation
     * @param errors           incremented on each exception thrown by task
     * @param timeout          maximum wall-clock budget for the entire run
     */
    private static void runMillion(
            int semaphorePermits,
            IntConsumer task,
            AtomicLong successes,
            AtomicLong errors,
            Duration timeout)
            throws InterruptedException {
        var gate = new Semaphore(semaphorePermits);
        var done = new CountDownLatch(N);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < N; i++) {
                final int idx = i;
                exec.submit(
                        () -> {
                            gate.acquireUninterruptibly();
                            try {
                                task.accept(idx);
                                successes.incrementAndGet();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            } finally {
                                gate.release();
                                done.countDown();
                            }
                        });
            }
            done.await(timeout.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** Checked-exception–friendly {@code IntConsumer} used by {@link #runMillion}. */
    @FunctionalInterface
    interface IntConsumer {
        void accept(int idx) throws Exception;
    }

    // ── Per-test cleanup ─────────────────────────────────────────────────────

    @AfterEach
    void resetRegistry() {
        ProcessRegistry.reset();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test 1 — AcquisitionSupervisor: 1 M addSamples() across 1 K PDA procs
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName(
            "1 M addSamples() → 1 K ONE_FOR_ONE supervised PDA procs — zero loss, totalSamples == 1 M")
    void millionTelemetrySamples_acquisitionSupervisor_zeroLoss() throws Exception {
        // Build 1 K distinct parameter/channel pairs — one PDA proc per pair
        var params = new ArrayList<SqlRaceParameter>(PARAM_COUNT);
        var channels = new ArrayList<SqlRaceChannel>(PARAM_COUNT);
        var paramIds = new String[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            var ch = SqlRaceChannel.periodic(
                    i + 1L, "ch" + i, 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
            var p = SqlRaceParameter.of("p" + i, "Chassis", i + 1L, 0.0, 400.0, "kph");
            channels.add(ch);
            params.add(p);
            paramIds[i] = p.identifier(); // e.g. "p0:Chassis"
        }

        try (var acq = AcquisitionSupervisor.start(params, channels)) {
            var successes = new AtomicLong(0);
            var errors = new AtomicLong(0);

            // 1 M VTs — each fires one live ECU sample at a PDA proc (round-robin across params)
            runMillion(
                    CONCURRENCY,
                    idx ->
                            acq.addSamples(
                                    paramIds[idx % PARAM_COUNT],
                                    new long[] {idx},
                                    new double[] {idx % 400.0}), // in [0, 399] — within range
                    successes,
                    errors,
                    Duration.ofSeconds(180));

            assertThat(errors.get()).as("no addSamples() should throw").isZero();
            assertThat(successes.get()).as("all 1 M VTs must complete").isEqualTo(N);

            // Parallel drain via PdaMsg.GetStats — FIFO mailbox guarantees every AddSamples
            // is processed before GetStats for each individual PDA proc.
            // var captures ProcRef<ParameterDataAccess.State, PdaMsg> via type inference;
            // ParameterDataAccess.State is package-private but accessible through var.
            var drainFutures = new ArrayList<CompletableFuture<Long>>(PARAM_COUNT);
            for (var entry : acq.allRefs().entrySet()) {
                var ref = entry.getValue();
                var statFuture = new CompletableFuture<PdaStats>();
                ref.tell(new PdaMsg.GetStats(statFuture));
                drainFutures.add(statFuture.thenApply(s -> (long) s.totalSamples()));
            }

            // Java 25 Gatherers.fold — sum totalSamples() across all 1 K PDA procs
            long totalSamples =
                    drainFutures.stream()
                            .map(CompletableFuture::join)
                            .gather(Gatherers.fold(() -> 0L, Long::sum))
                            .findFirst()
                            .orElse(0L);

            assertThat(totalSamples)
                    .as("every ECU sample must be recorded — no loss across 1 M VTs and 1 K procs")
                    .isEqualTo(N);
            assertThat(acq.isRunning())
                    .as("AcquisitionSupervisor must still be running after 1 M samples")
                    .isTrue();
        }
        // AutoCloseable.close() calls supervisor.shutdown() — all PDA procs stopped cleanly
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test 2 — ProcessRegistry: 1 M concurrent lookups, 1 K PdaMsg-typed procs
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName(
            "1 M ProcessRegistry.whereis() → PdaMsg.AddSamples — no stale entries, total == 1 M")
    @SuppressWarnings("unchecked")
    void processRegistry_millionLookups_allPdaMsgDelivered() throws Exception {
        // 1 K Proc<AtomicLong, PdaMsg>: counts AddSamples; silently ignores other PdaMsg variants.
        // AtomicLong state avoids exposing the package-private ParameterDataAccess.State.
        Proc<AtomicLong, PdaMsg>[] procs = new Proc[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            procs[i] = new Proc<>(
                    new AtomicLong(0),
                    (count, msg) -> {
                        if (msg instanceof PdaMsg.AddSamples) {
                            count.incrementAndGet();
                        }
                        return count;
                    });
            ProcessRegistry.register("pda-" + i, procs[i]);
        }

        var successes = new AtomicLong(0);
        var errors = new AtomicLong(0);
        var empties = new AtomicLong(0);

        // 1 M VTs — each looks up a proc by McLaren SQL Race identifier and fires AddSamples
        runMillion(
                CONCURRENCY,
                idx -> {
                    var ref =
                            ProcessRegistry.<AtomicLong, PdaMsg>whereis(
                                    "pda-" + (idx % PARAM_COUNT));
                    if (ref.isEmpty()) {
                        empties.incrementAndGet();
                        return;
                    }
                    ref.get()
                            .tell(
                                    new PdaMsg.AddSamples(
                                            new long[] {idx}, new double[] {1.0}));
                },
                successes,
                errors,
                Duration.ofSeconds(120));

        assertThat(errors.get()).as("no lookup should throw").isZero();
        assertThat(empties.get())
                .as("every PDA proc must be found — ProcessRegistry must return no stale entries")
                .isZero();

        // Drain: ask(Clear) enqueued AFTER all AddSamples — FIFO guarantees exact count.
        // ask() completes with the proc's current AtomicLong state after Clear is processed.
        List<CompletableFuture<AtomicLong>> drains =
                IntStream.range(0, PARAM_COUNT)
                        .mapToObj(i -> procs[i].ask(new PdaMsg.Clear()))
                        .toList();

        // Java 25 Gatherers.fold — sum AtomicLong.get() across all 1 K procs
        long totalDelivered =
                drains.stream()
                        .map(CompletableFuture::join)
                        .gather(Gatherers.fold(() -> 0L, (acc, al) -> acc + al.get()))
                        .findFirst()
                        .orElse(0L);

        assertThat(totalDelivered)
                .as("ProcessRegistry must route all 1 M PdaMsg.AddSamples to live procs")
                .isEqualTo(N);

        for (var p : procs) p.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test 3 — SqlRaceSession: 1 K live sessions, 1 M concurrent AddLap events
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName(
            "1 M AddLap events across 1 K SqlRaceSession state machines — all laps recorded, total == 1 M")
    void millionAddLap_sqlRaceSessions_allLapsRecorded() throws Exception {
        // Shared Configure event — SqlRaceSessionData.withConfiguration() calls List.copyOf(),
        // so sharing this immutable record across 1 K sessions is safe.
        var configure =
                new SqlRaceSessionEvent.Configure(
                        List.of(SHARED_PARAM), List.of(SHARED_CHANNEL), List.of(SHARED_CONV));

        // Create 1 K sessions and send Configure to transition each Initializing → Live
        List<SqlRaceSession> sessions =
                IntStream.range(0, SESSION_COUNT)
                        .mapToObj(
                                i -> {
                                    var s = SqlRaceSession.create("stress-session-" + i);
                                    s.send(configure);
                                    return s;
                                })
                        .toList();

        // Wait for every session to reach Live before firing lap events
        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                sessions.stream()
                                        .allMatch(
                                                s ->
                                                        s.state()
                                                                instanceof SqlRaceSessionState.Live));

        var successes = new AtomicLong(0);
        var errors = new AtomicLong(0);

        // 1 M VTs — each sends one AddLap to a session (round-robin).
        // flyingLap(ts, num ≥ 1) is validated by SqlRaceLap's compact constructor.
        runMillion(
                CONCURRENCY,
                idx ->
                        sessions.get(idx % SESSION_COUNT)
                                .send(
                                        new SqlRaceSessionEvent.AddLap(
                                                SqlRaceLap.flyingLap(
                                                        System.nanoTime(),
                                                        (idx / SESSION_COUNT) + 1))),
                successes,
                errors,
                Duration.ofSeconds(180));

        assertThat(errors.get()).as("no send() should throw").isZero();

        // Drain: call(SessionSaved) is FIFO — all prior AddLap events are processed first,
        // then the session transitions Live → Closing and returns final SqlRaceSessionData.
        // Fan out across all 1 K sessions in parallel on virtual threads.
        try (var drainExec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SqlRaceSessionData>> drains =
                    sessions.stream()
                            .map(
                                    s ->
                                            CompletableFuture.supplyAsync(
                                                    () ->
                                                            s.call(
                                                                    new SqlRaceSessionEvent
                                                                            .SessionSaved()),
                                                    drainExec))
                            .toList();

            // Java 25 Gatherers.fold — sum laps.size() across all 1 K sessions
            long totalLaps =
                    drains.stream()
                            .map(CompletableFuture::join)
                            .gather(
                                    Gatherers.fold(
                                            () -> 0L,
                                            (acc, data) -> acc + data.laps().size()))
                            .findFirst()
                            .orElse(0L);

            assertThat(totalLaps)
                    .as("every AddLap must be recorded — 1 M events across 1 K sessions")
                    .isEqualTo(N);
        }

        for (var s : sessions) s.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test 4 — SessionEventBus: 1 M notify(AddLap), all 10 handlers receive every event
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName(
            "1 M SessionEventBus.notify(AddLap) — all 10 gen_event handlers receive every lap alert")
    void millionLapAlerts_sessionEventBus_allHandlersReceiveAll() throws Exception {
        int handlerCount = 10;
        var bus = SessionEventBus.start();
        var handlerCounters = new AtomicLong[handlerCount];

        // Register 10 handlers — each counts AddLap events received.
        // A crashing handler would be removed without affecting the bus (gen_event isolation).
        for (int i = 0; i < handlerCount; i++) {
            final var counter = handlerCounters[i] = new AtomicLong(0);
            bus.addHandler(event -> counter.incrementAndGet());
        }

        var successes = new AtomicLong(0);
        var errors = new AtomicLong(0);

        // 1 M VTs — each fires one async AddLap notification into the SessionEventBus mailbox
        runMillion(
                CONCURRENCY,
                idx ->
                        bus.notify(
                                new SqlRaceSessionEvent.AddLap(
                                        SqlRaceLap.flyingLap(System.nanoTime(), 1))),
                successes,
                errors,
                Duration.ofSeconds(120));

        assertThat(errors.get()).as("no notify() should throw").isZero();

        // Awaitility drain — EventManager processes events on a single virtual thread.
        // 10 handlers × 1 M events at ~10 ns/call → ~100 ms total; 120 s is extremely generous.
        await().atMost(Duration.ofSeconds(120))
                .untilAsserted(
                        () -> {
                            for (var counter : handlerCounters) {
                                assertThat(counter.get())
                                        .as("each handler must receive all 1 M lap alerts")
                                        .isGreaterThanOrEqualTo(N);
                            }
                        });

        // Java 25 Gatherers.fold — compute minimum across all 10 handler counters (slowest wins)
        long minHandled =
                Arrays.stream(handlerCounters)
                        .map(AtomicLong::get)
                        .gather(Gatherers.fold(() -> Long.MAX_VALUE, Math::min))
                        .findFirst()
                        .orElse(0L);

        assertThat(minHandled)
                .as("the slowest handler must have processed every lap alert")
                .isGreaterThanOrEqualTo(N);

        bus.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test 5 — Supervisor storm: 1 M mixed PdaMsg.AddSamples, 10 % poison crashes
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName(
            "1 M PdaMsg.AddSamples (10 % poison) → ONE_FOR_ONE restarts all, root supervisor stays alive")
    @SuppressWarnings("unchecked")
    void supervisorStorm_millionPoisonSamples_rootStaysAlive() throws Exception {
        // maxRestarts = 10 K per proc (>> ~100 expected crashes each); window = 1 h (no expiry)
        var supervisor =
                new Supervisor("atlas-storm", Strategy.ONE_FOR_ONE, 10_000, Duration.ofHours(1));

        // 1 K supervised procs — each counts valid AddSamples; crashes on negative values
        ProcRef<AtomicLong, PdaMsg>[] refs = new ProcRef[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            refs[i] =
                    supervisor.supervise(
                            "storm-pda-" + i,
                            new AtomicLong(0),
                            AtlasOtpStressTest::stormHandler);
        }

        var successes = new AtomicLong(0);
        var errors = new AtomicLong(0);

        // 1 M VTs — idx % 10 == 0 → poison AddSamples (negative g-force, 10 %);
        //            otherwise → valid AddSamples (positive g-force, 90 %)
        runMillion(
                CONCURRENCY,
                idx -> {
                    int target = idx % PARAM_COUNT;
                    if (idx % 10 == 0) {
                        // Poison: negative value → stormHandler throws → proc crashes →
                        // ONE_FOR_ONE restarts it immediately with fresh AtomicLong(0)
                        refs[target].tell(
                                new PdaMsg.AddSamples(new long[] {idx}, new double[] {-1.0}));
                    } else {
                        // Normal: valid accelerometer reading → counter incremented
                        refs[target].tell(
                                new PdaMsg.AddSamples(new long[] {idx}, new double[] {1.0}));
                    }
                },
                successes,
                errors,
                Duration.ofSeconds(180));

        assertThat(errors.get()).as("no tell() should throw under storm load").isZero();

        // Wait for the supervisor to quiesce — all pending crash/restart cycles complete
        await().atMost(Duration.ofSeconds(60))
                .until(() -> supervisor.isRunning() && supervisor.fatalError() == null);

        assertThat(supervisor.isRunning())
                .as("atlas-storm supervisor must survive ~100 K simulated ECU faults")
                .isTrue();
        assertThat(supervisor.fatalError())
                .as("restart budget (10 K per proc) must not have been exceeded")
                .isNull();

        // Java 25 Gatherers.windowFixed — batch-validate all 1 K refs still accept messages
        var aliveRefs = new ArrayList<>(Arrays.asList(refs));
        aliveRefs.stream()
                .gather(Gatherers.windowFixed(100))
                .forEach(
                        batch ->
                                batch.forEach(
                                        ref ->
                                                assertThatNoException()
                                                        .isThrownBy(
                                                                () ->
                                                                        ref.tell(
                                                                                new PdaMsg
                                                                                        .AddSamples(
                                                                                        new long[] {
                                                                                            0L
                                                                                        },
                                                                                        new double[] {
                                                                                            1.0
                                                                                        })))));

        supervisor.shutdown();
    }

    // ── Storm proc handler — OTP "let it crash" for negative ECU sensor values ──────────────────

    /**
     * Message handler for Test 5 supervisor storm procs.
     *
     * <p>Simulates a real McLaren Atlas fault condition: a corrupt or out-of-range ECU reading
     * (negative g-force) causes the process to crash. The ONE_FOR_ONE supervisor restarts it within
     * milliseconds with fresh state — exactly OTP's "let it crash" contract.
     *
     * <p>Valid readings ({@code values[0] ≥ 0}) increment the sample counter.
     */
    private static AtomicLong stormHandler(AtomicLong count, PdaMsg msg) {
        return switch (msg) {
            case PdaMsg.AddSamples s when s.values().length > 0 && s.values()[0] < 0 ->
                // Crash — ONE_FOR_ONE supervisor restarts this proc immediately
                throw new RuntimeException(
                        "deliberate crash: negative ECU reading simulates sensor fault");
            case PdaMsg.AddSamples s -> {
                count.incrementAndGet();
                yield count;
            }
            default -> count;
        };
    }
}
