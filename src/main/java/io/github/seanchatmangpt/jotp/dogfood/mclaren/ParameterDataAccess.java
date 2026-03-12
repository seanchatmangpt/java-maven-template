package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import io.github.seanchatmangpt.jotp.CrashRecovery;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Result;

/**
 * Parameter data access process — OTP {@code gen_server} mapped to SQL Race
 * {@code IParameterDataAccess} / {@code ParameterDataAccessBase}.
 *
 * <p>One virtual-thread {@link Proc} is spawned per parameter. The process owns the parameter's
 * in-memory ring buffer (capped at {@value #RING_BUFFER_CAP} samples) and answers
 * {@code GoTo/GetNextSamples} queries in O(log n) via a {@link TreeMap}.
 *
 * <h2>OTP "let it crash" vs. domain errors</h2>
 *
 * <ul>
 *   <li><b>Crash</b>: lost ECU connection (null message), corrupt framing (exception in decode).
 *       The {@link org.acme.Supervisor} in {@link AcquisitionSupervisor} restarts the process
 *       within 50 ms — the ring buffer is fresh, but acquisition resumes immediately.
 *   <li><b>Domain error</b>: out-of-range value, non-finite double. Handled via
 *       {@link DataStatusType} tagging and {@link Result} — the process stays alive and continues
 *       recording. The erroneous sample is stored with the appropriate flag so analysts can
 *       investigate.
 * </ul>
 *
 * <h2>Real SQL Race mapping</h2>
 *
 * <pre>{@code
 * // C# ATLAS client:
 * var pda = session.CreateParameterDataAccess("vCar:Chassis");
 * pda.GoTo(lapStartTimestamp);
 * var samples = pda.GetNextSamples(1000, StepDirection.Forward);
 * }</pre>
 */
public final class ParameterDataAccess {

    /** Maximum ring-buffer size per parameter (matches ATLAS default page depth for 1 kHz). */
    public static final int RING_BUFFER_CAP = 10_000;

    private ParameterDataAccess() {}

    // ---------------------------------------------------------------------------
    // Mutable per-process state (owned by the virtual thread)
    // ---------------------------------------------------------------------------

    /** Internal mutable state — never shared across threads. */
    public static final class State {
        final SqlRaceParameter param;
        final SqlRaceChannel channel;
        /** Ring buffer: nanosecond timestamp → {value, status.ordinal()}. */
        final TreeMap<Long, double[]> buffer = new TreeMap<>();

        long cursorNs = 0;
        int totalSamples = 0;
        int goodSamples = 0;

        State(SqlRaceParameter param, SqlRaceChannel channel) {
            this.param = param;
            this.channel = channel;
        }
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    /**
     * Spawn a parameter data access process.
     *
     * <p>Uses {@link CrashRecovery#retry(int, java.util.function.Supplier)} to wrap the initial
     * process spawn — if the virtual thread fails to start (e.g. due to resource exhaustion),
     * up to 3 attempts are made before returning a {@link Result.Failure}.
     *
     * @param param   parameter definition
     * @param channel backing data channel
     * @return {@code Result.success(proc)} on success; {@code Result.failure(e)} if all retries
     *         exhausted
     */
    public static Result<Proc<State, PdaMsg>, Exception> spawn(
            SqlRaceParameter param, SqlRaceChannel channel) {
        return CrashRecovery.retry(3, () -> new Proc<>(new State(param, channel),
                ParameterDataAccess::handle));
    }

    /**
     * Spawn a parameter data access process, throwing on failure.
     *
     * @param param   parameter definition
     * @param channel backing data channel
     * @return running {@link Proc}
     * @throws RuntimeException if process cannot be started after retries
     */
    public static Proc<State, PdaMsg> spawnOrThrow(SqlRaceParameter param, SqlRaceChannel channel) {
        return spawn(param, channel).orElseThrow();
    }

    // ---------------------------------------------------------------------------
    // Message handler (pure function — runs on virtual thread)
    // ---------------------------------------------------------------------------

    static State handle(State state, PdaMsg msg) {
        return switch (msg) {
            case PdaMsg.AddSamples(var ts, var vals) -> addSamples(state, ts, vals, null);
            case PdaMsg.AddSamplesWithStatus(var ts, var vals, var status) ->
                addSamples(state, ts, vals, status);
            case PdaMsg.GoTo(var tsNs) -> {
                state.cursorNs = tsNs;
                yield state;
            }
            case PdaMsg.GetNextSamples(var n, var dir, var reply) -> {
                reply.complete(getSamples(state, n, dir));
                yield state;
            }
            case PdaMsg.GetStats(var reply) -> {
                reply.complete(new PdaStats(
                        state.param.identifier(),
                        state.totalSamples,
                        state.goodSamples,
                        state.buffer.size(),
                        state.cursorNs));
                yield state;
            }
            case PdaMsg.Clear() -> {
                state.buffer.clear();
                state.cursorNs = 0;
                yield state;
            }
        };
    }

    private static State addSamples(
            State state, long[] timestamps, double[] values, DataStatusType[] preTagged) {
        for (int i = 0; i < timestamps.length; i++) {
            DataStatusType status =
                    (preTagged != null) ? preTagged[i] : state.param.classify(values[i]);
            state.buffer.put(timestamps[i], new double[] {values[i], status.ordinal()});
            state.totalSamples++;
            if (status == DataStatusType.Good) {
                state.goodSamples++;
            }
            // Evict oldest entry when ring buffer exceeds cap
            if (state.buffer.size() > RING_BUFFER_CAP) {
                state.buffer.pollFirstEntry();
            }
        }
        return state;
    }

    private static ParameterValues getSamples(State state, int count, StepDirection direction) {
        if (state.buffer.isEmpty()) {
            return ParameterValues.empty();
        }
        NavigableMap<Long, double[]> view =
                (direction == StepDirection.Forward)
                        ? state.buffer.tailMap(state.cursorNs, true)
                        : state.buffer.headMap(state.cursorNs, true).descendingMap();

        List<Long> ts = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<DataStatusType> statuses = new ArrayList<>();

        for (var entry : view.entrySet()) {
            if (ts.size() >= count) break;
            ts.add(entry.getKey());
            vals.add(entry.getValue()[0]);
            statuses.add(DataStatusType.values()[(int) entry.getValue()[1]]);
        }

        if (!ts.isEmpty()) {
            // Advance cursor past the last returned sample
            state.cursorNs = ts.getLast() + 1;
        }

        long[] tsArr = new long[ts.size()];
        double[] vArr = new double[ts.size()];
        DataStatusType[] sArr = new DataStatusType[ts.size()];
        for (int i = 0; i < ts.size(); i++) {
            tsArr[i] = ts.get(i);
            vArr[i] = vals.get(i);
            sArr[i] = statuses.get(i);
        }
        return new ParameterValues(tsArr, vArr, sArr);
    }

    // ---------------------------------------------------------------------------
    // Convenience: synchronous get-next-samples helper for tests / tooling
    // ---------------------------------------------------------------------------

    /**
     * Synchronous helper: ask the process for the next {@code count} samples.
     *
     * @param proc      a running {@code ParameterDataAccess} process
     * @param count     number of samples to request
     * @param direction traversal direction
     * @return parameter values (may be empty)
     */
    public static ParameterValues getNextSamples(
            Proc<State, PdaMsg> proc, int count, StepDirection direction) {
        var future = new java.util.concurrent.CompletableFuture<ParameterValues>();
        proc.tell(new PdaMsg.GetNextSamples(count, direction, future));
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("getNextSamples failed", e);
        }
    }

    /**
     * Synchronous helper: query statistics for a running process.
     *
     * @param proc a running {@code ParameterDataAccess} process
     * @return current {@link PdaStats}
     */
    public static PdaStats getStats(Proc<State, PdaMsg> proc) {
        var future = new java.util.concurrent.CompletableFuture<PdaStats>();
        proc.tell(new PdaMsg.GetStats(future));
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("getStats failed", e);
        }
    }
}
