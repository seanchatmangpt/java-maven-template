package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.concurrent.CompletableFuture;

/**
 * Messages handled by a {@link ParameterDataAccess} process.
 *
 * <p>Maps each SQL Race {@code IParameterDataAccess} method to an OTP message:
 *
 * <ul>
 *   <li>{@link AddSamples} → live ECU data arriving from the RTA gRPC stream
 *   <li>{@link GoTo} → {@code IParameterDataAccess.GoTo(timestamp)}
 *   <li>{@link GetNextSamples} → {@code IParameterDataAccess.GetNextSamples(count, direction)}
 *   <li>{@link GetStats} → {@code sys:get_state/1} — non-blocking state snapshot
 *   <li>{@link Clear} → reset ring buffer (used on session close)
 * </ul>
 *
 * <p>Request-reply messages carry a {@link CompletableFuture} for the response. The process
 * completes the future inside the virtual-thread loop — exactly OTP's {@code gen_server:reply/2}
 * pattern. The caller blocks via {@code .join()} with a timeout outside the loop.
 */
public sealed interface PdaMsg
        permits PdaMsg.AddSamples,
                PdaMsg.AddSamplesWithStatus,
                PdaMsg.GoTo,
                PdaMsg.GetNextSamples,
                PdaMsg.GetStats,
                PdaMsg.Clear {

    /**
     * Append live ECU samples to the ring buffer. Samples are validated and tagged with
     * {@link DataStatusType} automatically by the handler.
     *
     * @param timestamps nanosecond timestamps (must be monotonically increasing)
     * @param values     engineering values after conversion
     */
    record AddSamples(long[] timestamps, double[] values) implements PdaMsg {}

    /**
     * Append samples with pre-tagged quality status (from upstream decoder or RDA filter).
     *
     * @param timestamps nanosecond timestamps
     * @param values     engineering values
     * @param status     per-sample data status
     */
    record AddSamplesWithStatus(long[] timestamps, double[] values, DataStatusType[] status)
            implements PdaMsg {}

    /**
     * Set the cursor to a specific timestamp.
     * Subsequent {@link GetNextSamples} calls start from here.
     *
     * @param timestampNs position to seek to (nanoseconds)
     */
    record GoTo(long timestampNs) implements PdaMsg {}

    /**
     * Read the next {@code count} samples from the cursor in the given direction.
     * The cursor advances past the returned samples.
     *
     * @param count     maximum number of samples to return
     * @param direction {@link StepDirection#Forward} or {@link StepDirection#Reverse}
     * @param reply     completed with the resulting {@link ParameterValues}
     */
    record GetNextSamples(
            int count, StepDirection direction, CompletableFuture<ParameterValues> reply)
            implements PdaMsg {}

    /**
     * Request a non-destructive statistics snapshot (OTP {@code sys:get_state/1}).
     *
     * @param reply completed with current {@link PdaStats}
     */
    record GetStats(CompletableFuture<PdaStats> reply) implements PdaMsg {}

    /** Reset the ring buffer and cursor. Used when a session is closed. */
    record Clear() implements PdaMsg {}
}
