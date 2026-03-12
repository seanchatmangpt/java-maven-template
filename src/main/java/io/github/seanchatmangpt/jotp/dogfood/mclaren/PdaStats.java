package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Parameter data access statistics snapshot — returned by {@link PdaMsg.GetStats}.
 *
 * <p>Mirrors the information exposed by OTP's {@code sys:get_state/1} and by
 * {@code ProcSys.statistics()} in the ATLAS OTP framework.
 *
 * @param paramIdentifier SQL Race identifier of the monitored parameter (e.g. {@code "vCar:Chassis"})
 * @param totalSamples    total samples appended since process start (including tagged/invalid)
 * @param goodSamples     samples tagged {@link DataStatusType#Good}
 * @param bufferSize      current number of entries in the ring buffer
 * @param cursorNs        current cursor position in nanoseconds
 */
public record PdaStats(
        String paramIdentifier,
        int totalSamples,
        int goodSamples,
        int bufferSize,
        long cursorNs) {}
