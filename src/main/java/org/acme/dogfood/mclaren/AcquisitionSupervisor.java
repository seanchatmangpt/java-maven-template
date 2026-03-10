package org.acme.dogfood.mclaren;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.acme.Parallel;
import org.acme.ProcRef;
import org.acme.ProcSys;
import org.acme.Result;
import org.acme.Supervisor;
import org.acme.Supervisor.Strategy;

/**
 * Acquisition supervisor — OTP {@code supervisor} ONE_FOR_ONE over all
 * {@link ParameterDataAccess} processes for a single SQL Race session.
 *
 * <p>In ATLAS, each ECU parameter is acquired by an independent subsystem. A hardware fault on
 * one sensor channel must never stop acquisition of all others. The OTP ONE_FOR_ONE strategy
 * maps directly to this requirement: only the crashed parameter process is restarted; the
 * remaining processes continue unaffected.
 *
 * <pre>
 *   AcquisitionSupervisor
 *   ├── ParameterDataAccess [vCar:Chassis]      ← ONE_FOR_ONE
 *   ├── ParameterDataAccess [nEngine:Chassis]
 *   ├── ParameterDataAccess [pBrakeF:Chassis]
 *   └── ParameterDataAccess [TBrakeDiscFL:BrakesByWire]
 * </pre>
 *
 * <p>The supervisor restarts up to {@value #MAX_RESTARTS} times in a {@value #WINDOW_SECS}-second
 * sliding window before giving up and crashing itself (propagating failure up the supervision
 * tree).
 *
 * <h2>Historical batch loading (Parallel)</h2>
 *
 * <p>{@link #loadHistoricalBatch(List, long, long, int)} uses {@link Parallel#all} to fan out
 * sample queries across all parameter processes concurrently, collecting results as a structured
 * task scope — exactly OTP's {@code pmap}.
 *
 * <h2>Statistics (ProcSys)</h2>
 *
 * <p>{@link #statistics()} calls {@link ProcSys#statistics} on each supervised process to collect
 * per-parameter throughput metrics without stopping any process.
 */
public final class AcquisitionSupervisor implements AutoCloseable {

    /** Max restart attempts within the sliding window before the supervisor crashes. */
    public static final int MAX_RESTARTS = 5;

    /** Sliding window duration in seconds. */
    public static final int WINDOW_SECS = 10;

    private final Supervisor supervisor;

    /**
     * Stable handles keyed by SQL Race parameter identifier (e.g. {@code "vCar:Chassis"}).
     * Each {@link ProcRef} survives supervisor restarts — callers never need to re-fetch.
     */
    private final Map<String, ProcRef<ParameterDataAccess.State, PdaMsg>> refs;

    private AcquisitionSupervisor(
            Supervisor supervisor,
            Map<String, ProcRef<ParameterDataAccess.State, PdaMsg>> refs) {
        this.supervisor = supervisor;
        this.refs = Map.copyOf(refs);
    }

    /**
     * Start an acquisition supervisor for a list of parameter/channel pairs.
     *
     * <p>Each pair is registered as a ONE_FOR_ONE child. The supervisor tolerates
     * {@value #MAX_RESTARTS} restarts within {@value #WINDOW_SECS} seconds.
     *
     * @param pairs parameter + channel pairs to supervise
     * @return running supervisor with stable {@link ProcRef} handles
     */
    public static AcquisitionSupervisor start(List<ParamChannelPair> pairs) {
        var sv = new Supervisor("AcquisitionSupervisor", Strategy.ONE_FOR_ONE, MAX_RESTARTS,
                Duration.ofSeconds(WINDOW_SECS));
        var refs = new LinkedHashMap<
                String, ProcRef<ParameterDataAccess.State, PdaMsg>>();
        for (var pair : pairs) {
            var param = pair.parameter();
            var channel = pair.channel();
            // Each child's state factory returns a fresh State on restart
            ProcRef<ParameterDataAccess.State, PdaMsg> ref =
                    sv.supervise(
                            param.identifier(),
                            new ParameterDataAccess.State(param, channel),
                            ParameterDataAccess::handle);
            refs.put(param.identifier(), ref);
        }
        return new AcquisitionSupervisor(sv, refs);
    }

    /**
     * Factory convenience: build pair list from parallel parameter and channel lists.
     *
     * @param params   parameter definitions
     * @param channels backing channels (must align by index)
     * @return running supervisor
     * @throws IllegalArgumentException if list sizes differ
     */
    public static AcquisitionSupervisor start(
            List<SqlRaceParameter> params, List<SqlRaceChannel> channels) {
        if (params.size() != channels.size()) {
            throw new IllegalArgumentException(
                    "params.size() " + params.size() + " != channels.size() " + channels.size());
        }
        var pairs = new ArrayList<ParamChannelPair>();
        for (int i = 0; i < params.size(); i++) {
            pairs.add(new ParamChannelPair(params.get(i), channels.get(i)));
        }
        return start(pairs);
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Retrieve the stable {@link ProcRef} for a parameter by SQL Race identifier.
     *
     * @param paramIdentifier e.g. {@code "vCar:Chassis"}
     * @return ref for sending messages, or {@code null} if not supervised
     */
    public ProcRef<ParameterDataAccess.State, PdaMsg> ref(String paramIdentifier) {
        return refs.get(paramIdentifier);
    }

    /** Return an unmodifiable view of all supervised parameter refs. */
    public Map<String, ProcRef<ParameterDataAccess.State, PdaMsg>> allRefs() {
        return refs;
    }

    /**
     * Push samples to a specific parameter process — fire-and-forget.
     *
     * @param paramIdentifier SQL Race identifier
     * @param timestamps      nanosecond timestamps
     * @param values          engineering values after conversion
     */
    public void addSamples(String paramIdentifier, long[] timestamps, double[] values) {
        var ref = refs.get(paramIdentifier);
        if (ref != null) {
            ref.tell(new PdaMsg.AddSamples(timestamps, values));
        }
    }

    /**
     * Load a historical time range from all supervised parameters concurrently.
     *
     * <p>Uses {@link Parallel#all} (OTP {@code pmap}) to fan out {@code GetNextSamples}
     * queries across all parameter processes simultaneously, then collects all results.
     *
     * @param paramIdentifiers   list of identifiers to query
     * @param startNs            range start (nanoseconds)
     * @param endNs              range end (nanoseconds)
     * @param samplesPerParam    maximum samples per parameter
     * @return {@code Result.success(map)} mapping identifier → values, or
     *         {@code Result.failure(e)} if any query fails
     */
    public Result<Map<String, ParameterValues>, Exception> loadHistoricalBatch(
            List<String> paramIdentifiers,
            long startNs,
            long endNs,
            int samplesPerParam) {
        List<Supplier<Map.Entry<String, ParameterValues>>> tasks = new ArrayList<>();
        for (var id : paramIdentifiers) {
            var ref = refs.get(id);
            if (ref == null) continue;
            tasks.add(() -> {
                // Seek to start, then read forward
                ref.tell(new PdaMsg.GoTo(startNs));
                var future = new java.util.concurrent.CompletableFuture<ParameterValues>();
                ref.tell(new PdaMsg.GetNextSamples(samplesPerParam, StepDirection.Forward, future));
                try {
                    return Map.entry(id, future.get(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new RuntimeException("batch load failed for " + id, e);
                }
            });
        }
        return Parallel.all(tasks)
                .map(entries -> {
                    Map<String, ParameterValues> result = new LinkedHashMap<>();
                    for (var entry : entries) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                    return result;
                });
    }

    /**
     * Collect throughput statistics for all supervised parameters.
     *
     * <p>Uses {@link ProcSys#statistics} on each process to read message counters without
     * interrupting acquisition — OTP's {@code sys:statistics/2} pattern.
     *
     * @return map of parameter identifier → {@link ProcSys.Stats}
     */
    public Map<String, ProcSys.Stats> statistics() {
        // ProcSys.statistics() requires a Proc, but Supervisor returns ProcRef (opaque).
        // We use ProcRef.ask() to request stats via the PDA process itself (PdaMsg.GetStats)
        // which is the correct OTP sys:get_state pattern without needing raw Proc access.
        var result = new LinkedHashMap<String, ProcSys.Stats>();
        for (var entry : refs.entrySet()) {
            var ref = entry.getValue();
            var future = new java.util.concurrent.CompletableFuture<PdaStats>();
            ref.tell(new PdaMsg.GetStats(future));
            try {
                var pdaStats = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
                result.put(entry.getKey(),
                        new ProcSys.Stats(pdaStats.totalSamples(), pdaStats.totalSamples(), 0));
            } catch (Exception ignored) {
                // Process may be mid-restart; skip its stats this cycle
            }
        }
        return result;
    }

    /**
     * Whether the underlying supervisor is still running (no fatal restart threshold exceeded).
     *
     * @return {@code false} if the supervisor has crashed
     */
    public boolean isRunning() {
        return supervisor.isRunning();
    }

    /**
     * Graceful shutdown: stop the supervisor and all supervised parameter processes.
     *
     * @throws InterruptedException if interrupted while waiting for shutdown
     */
    @Override
    public void close() throws InterruptedException {
        supervisor.shutdown();
    }

    // ---------------------------------------------------------------------------
    // Pair type
    // ---------------------------------------------------------------------------

    /** A parameter and its backing channel — used to configure supervised children. */
    public record ParamChannelPair(SqlRaceParameter parameter, SqlRaceChannel channel) {}
}
