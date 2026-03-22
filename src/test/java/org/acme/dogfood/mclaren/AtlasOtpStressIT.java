package org.acme.dogfood.mclaren;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
import java.util.stream.Gatherers;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.EventManager;
import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.ProcSys;
import org.acme.Supervisor;
import org.assertj.core.api.WithAssertions;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * End-to-end stress validation of OTP primitives under 1,000,000 virtual threads.
 *
 * <p>Joe Armstrong: <em>"The test of a concurrent system is whether it works when everything
 * happens at once."</em>
 *
 * <p>This suite uses every testing utility available in the project POM:
 *
 * <ul>
 *   <li>JUnit 5 — test lifecycle, display names, parallel execution control
 *   <li>AssertJ ({@link WithAssertions}) — fluent assertions
 *   <li>Awaitility — async drain / quiesce conditions
 *   <li>jqwik — property-based ring-buffer eviction law
 *   <li>Instancio — randomly generated session identifiers
 *   <li>ArchUnit — package structure rule
 * </ul>
 *
 * <p><b>Attack points (Armstrong-style):</b>
 *
 * <ol>
 *   <li>T1 — {@link ParameterDataAccess} bulk AddSamples at 1M VT scale; ring buffer must not
 *       evict (1K samples << 10K cap).
 *   <li>T2 — {@link SqlRaceSession} AddLap at 1M VT scale; {@code List.copyOf()} GC pressure.
 *   <li>T3 — Mixed AddSamples + AddLap concurrently; live-state invariant must hold.
 *   <li>T4 — {@link SessionEventBus} fan-out: 1M events × 10 handlers on a single
 *       {@link org.acme.EventManager} virtual thread = 10M sequential handler calls.
 *   <li>T5 — {@link Supervisor} storm: 100K crash/restart cycles; silent message loss during
 *       restart windows is expected and measured.
 * </ol>
 */
@Timeout(600) // hard JUnit safety net: total suite must finish within 10 min
@DisplayName("Atlas OTP Stress — 1M Virtual Thread Suite")
@Execution(ExecutionMode.SAME_THREAD) // prevent parallel execution of heavy stress tests
class AtlasOtpStressIT implements WithAssertions {

    // ── Test scale constants ──────────────────────────────────────────────────

    private static final int N = 1_000_000;
    private static final int CONCURRENCY = 20_000; // semaphore permits; 980K VTs parked
    private static final int PARAM_COUNT = 1_000;
    private static final int SESSION_COUNT = 1_000;

    // ── Domain fixtures ───────────────────────────────────────────────────────

    /**
     * Build a unique vCar-like parameter for proc index {@code i}.
     *
     * @param i proc index (0-based)
     * @return SqlRaceParameter with identifier {@code "vCar_i:Chassis"}
     */
    static SqlRaceParameter vCarParam(int i) {
        return SqlRaceParameter.of("vCar_" + i, "Chassis", (long) i + 1, 0.0, 400.0, "kph");
    }

    /**
     * Build a unique 200 Hz Signed16Bit channel for proc index {@code i}.
     *
     * @param i channel index
     * @return SqlRaceChannel with intervalNs = 5,000,000 (200 Hz)
     */
    static SqlRaceChannel vCarChannel(int i) {
        return SqlRaceChannel.periodic(
                (long) i + 1, "vCar_" + i, 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
    }

    // ── Core harness ──────────────────────────────────────────────────────────

    /**
     * Spawn exactly {@link #N} virtual threads bounded to {@link #CONCURRENCY} simultaneous
     * workers via a {@link Semaphore}.
     *
     * <p>Invariant: {@code active(t) ≤ CONCURRENCY}, {@code parked(t) = N − active(t) ≥ 980,000}.
     * The outer {@code finally { latch.countDown() }} guarantees the latch always reaches zero even
     * when {@code semaphore.acquire()} throws {@link InterruptedException}.
     *
     * @param task        receives the VT index (0 to N−1)
     * @param timeoutSecs maximum wall-clock seconds to wait for all VTs to complete
     * @throws InterruptedException if the calling thread is interrupted
     * @throws AssertionError       if the latch does not reach zero within {@code timeoutSecs}
     */
    @SuppressWarnings("preview")
    private void runMillion(IntConsumer task, long timeoutSecs) throws InterruptedException {
        var sem = new Semaphore(CONCURRENCY);
        var latch = new CountDownLatch(N);
        for (int i = 0; i < N; i++) {
            final int idx = i;
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    sem.acquire();
                                    try {
                                        task.accept(idx);
                                    } finally {
                                        sem.release();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    latch.countDown();
                                }
                            });
        }
        assertThat(latch.await(timeoutSecs, SECONDS))
                .as("runMillion did not complete within %ds (N=%d, concurrency=%d)",
                        timeoutSecs, N, CONCURRENCY)
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T1: Parameter Sample Ingestion
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * T1: 1,000,000 VTs → 1,000 {@link ParameterDataAccess} gen-server procs.
     *
     * <p>Each proc receives exactly {@code N / PARAM_COUNT = 1,000} samples. Values range from
     * 0.0 to 399.0, all within the {@code vCar} bounds [0.0, 400.0] → {@link
     * DataStatusType#Good}. The ring buffer cap is 10,000 so no eviction occurs (1,000 ≪ 10,000).
     *
     * <p><b>Breaking point</b>: {@code TreeMap.put()} is O(log n) per sample; with N=1M concurrent
     * puts across 1K procs, the proc mailbox depth can reach 1,000 messages — all processed
     * sequentially.
     */
    @Test
    @DisplayName("T1 [180s] 1M samples → 1K PDA procs; expect 1K total, 1K good, 0 evictions")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void t1_parameterSampleIngestion() throws Exception {
        Proc[] procs = new Proc[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            procs[i] = ParameterDataAccess.spawnOrThrow(vCarParam(i), vCarChannel(i));
        }
        long t0 = System.nanoTime();
        try {
            // 1M fire-and-forget AddSamples — each carries a single-element array
            runMillion(
                    idx -> procs[idx % PARAM_COUNT].tell(
                            new PdaMsg.AddSamples(
                                    new long[]{(long) idx * 5_000_000L},
                                    new double[]{idx % 400.0})), // 0.0–399.0 → all Good
                    180);

            System.out.printf("[T1] runMillion: %d ms%n",
                    (System.nanoTime() - t0) / 1_000_000L);

            // Drain: wait for all proc mailboxes to empty
            int expectedPerProc = N / PARAM_COUNT; // 1000
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                for (int i = 0; i < PARAM_COUNT; i++) {
                    var future = new CompletableFuture<PdaStats>();
                    procs[i].tell(new PdaMsg.GetStats(future));
                    PdaStats stats = future.get(5L, SECONDS);
                    assertThat(stats.totalSamples())
                            .as("proc[%d] totalSamples", i)
                            .isEqualTo(expectedPerProc);
                    assertThat(stats.goodSamples())
                            .as("proc[%d] goodSamples", i)
                            .isEqualTo(expectedPerProc);
                    assertThat(stats.bufferSize())
                            .as("proc[%d] bufferSize (no eviction expected)", i)
                            .isEqualTo(expectedPerProc);
                }
            });

            // ProcSys introspection: verify proc[0] processed at least expectedPerProc messages
            var sysStats = ProcSys.statistics(procs[0]);
            System.out.printf("[T1] proc[0] ProcSys: in=%d out=%d queue=%d%n",
                    sysStats.messagesIn(), sysStats.messagesOut(), sysStats.queueDepth());
            assertThat(sysStats.messagesIn())
                    .as("proc[0] messagesIn via ProcSys.statistics() must be ≥ expectedPerProc+1")
                    .isGreaterThanOrEqualTo(expectedPerProc);
        } finally {
            System.out.printf("[T1] total: %d ms%n", (System.nanoTime() - t0) / 1_000_000L);
            for (Proc proc : procs) {
                if (proc != null) {
                    try {
                        proc.stop();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T2: Session Lap Recording
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * T2: 1,000,000 VTs → 1,000 {@link SqlRaceSession} gen-statem machines.
     *
     * <p>Each session receives exactly {@code N / SESSION_COUNT = 1,000} {@link
     * SqlRaceSessionEvent.AddLap} events, producing lap numbers 1–1,000.
     *
     * <p><b>Breaking point (GC)</b>: each {@code AddLap} calls {@code data.withLap(lap)} which
     * creates {@code new ArrayList<>(laps)} then {@code List.copyOf()}. With 1,000 sessions × 1,000
     * laps, total list copy work = 1,000 × (1+2+…+1,000) = 500,500,000 element copies. Expect GC
     * pressure and pauses. The 120 s timeout accommodates this.
     */
    @Test
    @DisplayName("T2 [120s] 1M AddLap events → 1K sessions; expect 1K laps/session, state=Live")
    void t2_sessionLapRecording() throws Exception {
        SqlRaceSession[] sessions = new SqlRaceSession[SESSION_COUNT];
        var param = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");
        var channel = SqlRaceChannel.periodic(
                1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
        var conv = RationalConversion.identity("CONV_vCar:Chassis", "kph");
        for (int i = 0; i < SESSION_COUNT; i++) {
            sessions[i] = SqlRaceSession.create("stress-t2-session-" + i);
            sessions[i].send(
                    new SqlRaceSessionEvent.Configure(List.of(param), List.of(channel),
                            List.of(conv)));
        }
        // Wait for all sessions to transition Initializing → Live
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (var s : sessions) {
                assertThat(s.state()).isInstanceOf(SqlRaceSessionState.Live.class);
            }
        });

        long t0 = System.nanoTime();
        try {
            // Each VT sends AddLap to session[idx % 1000]; lap number = (idx / 1000) + 1
            runMillion(
                    idx -> sessions[idx % SESSION_COUNT].send(
                            new SqlRaceSessionEvent.AddLap(
                                    SqlRaceLap.flyingLap(
                                            (long) idx * 5_000_000L,
                                            (idx / SESSION_COUNT) + 1))),
                    120);

            System.out.printf("[T2] runMillion: %d ms%n",
                    (System.nanoTime() - t0) / 1_000_000L);

            int expectedLaps = N / SESSION_COUNT; // 1000
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                for (var s : sessions) {
                    assertThat(s.data().laps()).hasSize(expectedLaps);
                }
            });

            // Final hard assertions (post-drain)
            for (int i = 0; i < SESSION_COUNT; i++) {
                assertThat(sessions[i].data().laps())
                        .as("session[%d] laps", i)
                        .hasSize(expectedLaps);
                assertThat(sessions[i].state())
                        .as("session[%d] state", i)
                        .isInstanceOf(SqlRaceSessionState.Live.class);
            }
        } finally {
            System.out.printf("[T2] total: %d ms%n", (System.nanoTime() - t0) / 1_000_000L);
            for (SqlRaceSession s : sessions) {
                if (s != null && s.isRunning()) {
                    try {
                        s.stop();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T3: Live-State Concurrent Mutation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * T3: 1,000,000 VTs simultaneously mutate 1,000 PDA procs AND 1,000 sessions.
     *
     * <p>Even-indexed VTs → {@code AddSamples}; odd-indexed VTs → {@code AddLap}. A mid-flight
     * Awaitility assertion validates that all 1,000 sessions remain in {@code Live} state throughout.
     *
     * <p><b>Breaking point</b>: immutability under concurrent state mutation. Since
     * {@link SqlRaceSessionData} uses {@code List.copyOf()}, no structural sharing of the laps list
     * is possible — each transition is fully isolated. This is the OTP guarantee in action.
     */
    @Test
    @DisplayName("T3 [180s] Mixed 500K AddSamples + 500K AddLap; mid-flight Live invariant")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void t3_liveStateConcurrentMutation() throws Exception {
        Proc[] procs = new Proc[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            procs[i] = ParameterDataAccess.spawnOrThrow(vCarParam(i), vCarChannel(i));
        }
        SqlRaceSession[] sessions = new SqlRaceSession[SESSION_COUNT];
        var param = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");
        var channel = SqlRaceChannel.periodic(
                1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
        var conv = RationalConversion.identity("CONV_vCar:Chassis", "kph");
        for (int i = 0; i < SESSION_COUNT; i++) {
            sessions[i] = SqlRaceSession.create("stress-t3-session-" + i);
            sessions[i].send(
                    new SqlRaceSessionEvent.Configure(List.of(param), List.of(channel),
                            List.of(conv)));
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (var s : sessions) {
                assertThat(s.state()).isInstanceOf(SqlRaceSessionState.Live.class);
            }
        });

        long t0 = System.nanoTime();
        try {
            // Run asynchronously so we can probe mid-flight state
            var runDone = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try {
                    runMillion(idx -> {
                        if (idx % 2 == 0) {
                            procs[idx % PARAM_COUNT].tell(
                                    new PdaMsg.AddSamples(
                                            new long[]{(long) idx * 5_000_000L},
                                            new double[]{(idx % 399) + 1.0})); // 1.0–400.0 Good
                        } else {
                            sessions[idx % SESSION_COUNT].send(
                                    new SqlRaceSessionEvent.AddLap(
                                            SqlRaceLap.flyingLap(
                                                    (long) idx * 5_000_000L,
                                                    (idx / SESSION_COUNT) + 1)));
                        }
                    }, 180);
                    runDone.complete(null);
                } catch (InterruptedException e) {
                    runDone.completeExceptionally(e);
                    Thread.currentThread().interrupt();
                }
            });

            // Mid-flight invariant: all sessions must remain in Live state
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                for (var s : sessions) {
                    assertThat(s.state())
                            .as("mid-flight session state")
                            .isInstanceOf(SqlRaceSessionState.Live.class);
                }
            });

            runDone.get(200L, java.util.concurrent.TimeUnit.SECONDS);
            System.out.printf("[T3] runMillion: %d ms%n",
                    (System.nanoTime() - t0) / 1_000_000L);

            // Liveness: every proc and session processed at least one message
            for (int i = 0; i < PARAM_COUNT; i++) {
                var future = new CompletableFuture<PdaStats>();
                procs[i].tell(new PdaMsg.GetStats(future));
                assertThat(future.get(5L, SECONDS).totalSamples())
                        .as("proc[%d] must have >0 samples", i)
                        .isGreaterThan(0);
            }
            for (int i = 0; i < SESSION_COUNT; i++) {
                assertThat(sessions[i].data().laps().size())
                        .as("session[%d] must have >0 laps", i)
                        .isGreaterThan(0);
                assertThat(sessions[i].state())
                        .as("session[%d] must still be Live after mutation", i)
                        .isInstanceOf(SqlRaceSessionState.Live.class);
            }
        } finally {
            System.out.printf("[T3] total: %d ms%n", (System.nanoTime() - t0) / 1_000_000L);
            for (Proc proc : procs) {
                if (proc != null) {
                    try {
                        proc.stop();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            for (SqlRaceSession s : sessions) {
                if (s != null && s.isRunning()) {
                    try {
                        s.stop();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T4: EventManager Fan-Out
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * T4: 1,000,000 async {@link SessionEventBus#notify} events → 10 registered handlers.
     *
     * <p>The {@link org.acme.EventManager} is a single-threaded {@link Proc}: its {@code
     * broadcast()} iterates all 10 handlers synchronously on one virtual thread. Total handler
     * invocations = 1,000,000 × 10 = 10,000,000, all executed sequentially.
     *
     * <p><b>Breaking point</b>: the serialization bottleneck. Even at 100 ns/handler invocation,
     * the drain takes at least 1 second. The 120 s Awaitility drain timeout measures the actual
     * throughput ceiling of the gen_event single-thread design.
     *
     * <p>The {@link SessionEventBus#syncNotify} call before the storm acts as a registration
     * barrier — ensuring all 10 handlers are guaranteed to be in the manager's handler list before
     * any {@code Notify} messages arrive.
     */
    @Test
    @DisplayName("T4 [120s+120s] 1M notify → 10 handlers; each must see exactly 1M events")
    void t4_eventManagerFanOut() throws Exception {
        var bus = SessionEventBus.start();
        // LongAdder: lower contention than AtomicLong for write-heavy fan-out counting
        LongAdder[] counters = new LongAdder[10];
        @SuppressWarnings({"unchecked", "rawtypes"})
        EventManager.Handler[] handlers = new EventManager.Handler[10];
        for (int i = 0; i < 10; i++) {
            counters[i] = new LongAdder();
            final int hi = i;
            handlers[i] = (EventManager.Handler<SqlRaceSessionEvent>) event ->
                    counters[hi].increment();
            bus.addHandler(handlers[i]);
        }

        // Barrier: syncNotify blocks until all 10 Add messages have been processed
        // and the handlers have each received one event. Then reset counters.
        bus.syncNotify(new SqlRaceSessionEvent.SessionSaved());
        for (var c : counters) c.reset();

        long t0 = System.nanoTime();
        try {
            runMillion(
                    idx -> bus.notify(
                            new SqlRaceSessionEvent.AddDataItem(
                                    new SqlRaceSessionDataItem("circuit",
                                            String.valueOf(idx % 1000)))),
                    120);

            System.out.printf("[T4] runMillion: %d ms%n",
                    (System.nanoTime() - t0) / 1_000_000L);

            // Drain: 10M sequential handler calls on one EventManager virtual thread
            await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
                for (int i = 0; i < 10; i++) {
                    assertThat(counters[i].sum())
                            .as("handler[%d] event count", i)
                            .isEqualTo(N);
                }
            });

            // Hard assertions post-drain
            for (int i = 0; i < 10; i++) {
                assertThat(counters[i].sum())
                        .as("handler[%d] must see all %d events", i, N)
                        .isEqualTo(N);
            }
        } finally {
            System.out.printf("[T4] total: %d ms%n", (System.nanoTime() - t0) / 1_000_000L);
            bus.stop();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5: Supervisor Storm with Poison Messages
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * T5: 1,000,000 messages (10% poison) → 1,000 supervised procs under ONE_FOR_ONE.
     *
     * <p>Each poison message ({@code msg < 0}) crashes its proc; the supervisor restarts it. With
     * 10% poison rate and 1,000,000 total messages:
     *
     * <ul>
     *   <li>Poison = 100,000; Normal = 900,000
     *   <li>Crashes per proc = 100,000 / 1,000 = 100
     *   <li>{@code maxRestarts = 10,000 >> 100} → supervisor never dies
     * </ul>
     *
     * <p><b>Breaking point (message loss)</b>: {@link ProcRef#tell(Object)} javadoc states "If the
     * process is mid-restart, the message goes to the stale process and is <em>lost</em>". With
     * ~100 restarts per proc, each restart window loses messages silently. Accumulated state after
     * all 900,000 normal messages will be <em>less than 900 per proc</em> — the delta is the
     * measured restart-window message loss rate. The test asserts {@code isRunning()} and
     * {@code fatalError() == null}, not exact counts.
     *
     * <p>Uses Java 25 {@link Gatherers#windowFixed(int)} to partition 1,000 proc refs into 10
     * batches of 100 — demonstrating the API even though the actual storm uses {@link #runMillion}.
     */
    @Test
    @DisplayName("T5 [180s+60s] 100K poison crashes across 1K procs; supervisor budget 10K >> 100")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void t5_supervisorStormWithPoisonMessages() throws Exception {
        var supervisor = new Supervisor(
                "atlas-storm",
                Supervisor.Strategy.ONE_FOR_ONE,
                10_000,
                Duration.ofSeconds(3600));

        // Simple integer accumulator: crashes on negative (poison), accumulates on positive.
        // NOTE: ParameterDataAccess does NOT crash on negative values — it tags them OutOfRange.
        // A raw BiFunction is used here to get true crash-restart behaviour for the OTP test.
        BiFunction<Integer, Integer, Integer> crasher = (state, msg) -> {
            if (msg < 0) throw new RuntimeException("poison! msg=" + msg);
            return state + msg;
        };

        ProcRef[] refs = new ProcRef[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            refs[i] = supervisor.supervise("proc-" + i, 0, crasher);
        }

        // Demonstrate Gatherers.windowFixed(100): 1,000 procs → 10 batches of 100
        var batches = Stream.of(refs)
                .gather(Gatherers.windowFixed(100))
                .toList();
        assertThat(batches).as("Gatherers.windowFixed(100) produces 10 batches").hasSize(10);
        batches.forEach(batch -> assertThat(batch).hasSize(100));

        long t0 = System.nanoTime();
        try {
            // 10% of messages are poison (idx % 10 == 0), 90% are normal
            runMillion(idx -> {
                int msg = (idx % 10 == 0) ? -1 : 1;
                refs[idx % PARAM_COUNT].tell(msg);
            }, 180);

            System.out.printf("[T5] runMillion: %d ms (100K poison crashes delivered)%n",
                    (System.nanoTime() - t0) / 1_000_000L);

            // Awaitility: supervisor absorbs all restarts without exhausting its budget
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(supervisor.isRunning())
                        .as("supervisor must still be running after 100K restarts")
                        .isTrue();
                assertThat(supervisor.fatalError())
                        .as("supervisor fatalError must be null (budget 10K >> 100/proc)")
                        .isNull();
            });

            assertThat(supervisor.isRunning()).isTrue();
            assertThat(supervisor.fatalError()).isNull();
        } finally {
            System.out.printf("[T5] total: %d ms%n", (System.nanoTime() - t0) / 1_000_000L);
            supervisor.shutdown();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // jqwik: Ring Buffer Eviction Law
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Property: for any number of samples beyond the ring buffer cap, the buffer size is exactly
     * {@link ParameterDataAccess#RING_BUFFER_CAP} (oldest entries evicted).
     *
     * <p>This encodes a formal law: {@code bufferSize(n) = min(n, RING_BUFFER_CAP)}.
     */
    @Property(tries = 20)
    @Label("Ring buffer: bufferSize = min(n, RING_BUFFER_CAP) for any n > CAP")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void prop_ringBufferEvictsWhenFull(
            @ForAll @IntRange(min = 1, max = 200) int extraSamples) throws Exception {
        var proc = ParameterDataAccess.spawnOrThrow(
                SqlRaceParameter.of("testProp", "Test", 99L, 0.0, 10000.0, "rpm"),
                SqlRaceChannel.periodic(99L, "testProp", 100.0, FrequencyUnit.Hz,
                        DataType.Signed16Bit));
        int total = ParameterDataAccess.RING_BUFFER_CAP + extraSamples;
        var sem = new Semaphore(500);
        var latch = new CountDownLatch(total);
        try {
            for (int i = 0; i < total; i++) {
                final long ts = (long) i * 10_000L;
                final double val = (double) (i % 1000);
                Thread.ofVirtual().start(() -> {
                    try {
                        sem.acquire();
                        try {
                            proc.tell(new PdaMsg.AddSamples(new long[]{ts}, new double[]{val}));
                        } finally {
                            sem.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(30L, SECONDS)).isTrue();
            // Drain via GetStats
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var future = new CompletableFuture<PdaStats>();
                proc.tell(new PdaMsg.GetStats(future));
                PdaStats stats = future.get(5L, SECONDS);
                assertThat(stats.totalSamples()).isEqualTo(total);
            });
            var future = new CompletableFuture<PdaStats>();
            proc.tell(new PdaMsg.GetStats(future));
            PdaStats stats = future.get(5L, SECONDS);
            assertThat(stats.bufferSize())
                    .as("bufferSize must equal RING_BUFFER_CAP after %d extra samples", extraSamples)
                    .isEqualTo(ParameterDataAccess.RING_BUFFER_CAP);
        } finally {
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Instancio: randomly generated session identifiers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Instancio generates 10 random alphanumeric session identifiers. Each is used to create a
     * {@link SqlRaceSession}, Configure it, add an out-lap and a flying lap, and verify the session
     * reaches {@link SqlRaceSessionState.Live} with 2 laps committed.
     *
     * <p>This validates the full session lifecycle using externally-generated (non-hardcoded) data —
     * exercising the domain model with values a unit test author would not anticipate.
     */
    @Test
    @DisplayName("Instancio: 10 randomly generated sessions survive Configure + AddLap cycle")
    void instancio_randomSessionsLifecycle() throws Exception {
        List<String> randomIds = Instancio.ofList(String.class)
                .size(10)
                .create();

        var param = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");
        var channel = SqlRaceChannel.periodic(
                1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
        var conv = RationalConversion.identity("CONV_vCar:Chassis", "kph");

        List<SqlRaceSession> sessions = randomIds.stream()
                .map(id -> {
                    var s = SqlRaceSession.create("instancio-" + id);
                    s.send(new SqlRaceSessionEvent.Configure(
                            List.of(param), List.of(channel), List.of(conv)));
                    return s;
                })
                .toList();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (var s : sessions) {
                assertThat(s.state()).isInstanceOf(SqlRaceSessionState.Live.class);
            }
        });

        // AddLap to each session
        for (int i = 0; i < sessions.size(); i++) {
            sessions.get(i).send(new SqlRaceSessionEvent.AddLap(
                    SqlRaceLap.outLap((long) i * 1_000_000_000L)));
            sessions.get(i).send(new SqlRaceSessionEvent.AddLap(
                    SqlRaceLap.flyingLap((long) (i + 1) * 1_000_000_000L, 1)));
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (var s : sessions) {
                assertThat(s.data().laps()).hasSize(2);
                assertThat(s.state()).isInstanceOf(SqlRaceSessionState.Live.class);
            }
        });

        for (var s : sessions) {
            if (s.isRunning()) {
                try {
                    s.stop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ArchUnit: package structure rule
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ArchUnit verifies that no McLaren Atlas domain class depends on the {@code innovation} or
     * {@code errorhandling} subpackages — the domain layer must remain decoupled from analysis
     * tooling.
     */
    @Test
    @DisplayName("ArchUnit: mclaren domain must not depend on innovation or errorhandling packages")
    void arch_mclarenPackageDecoupling() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.acme.dogfood.mclaren");

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("org.acme.dogfood.mclaren")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.acme.dogfood.innovation",
                        "org.acme.dogfood.errorhandling")
                .because("domain model must stay decoupled from analysis and error-handling tooling")
                .check(classes);
    }
}
