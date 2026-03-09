package org.acme.dogfood.mclaren;

/**
 * Data channel definition — mirrors {@code MESL.SqlRace.Domain.Channel} from the SQL Race API.
 *
 * <p>In ATLAS, a {@code Channel} is the storage-level descriptor: it defines the binary width,
 * sample rate, and source type of a single data stream. Every {@link SqlRaceParameter} references
 * exactly one channel by {@link #channelId()}.
 *
 * <p>Channels are created during the {@link SqlRaceSession} configuration phase
 * ({@code Initializing → Live}) and committed atomically:
 *
 * <pre>{@code
 * // Real SQL Race pattern (C# original):
 * var channel = new Channel(channelId++, "vCar", samplingFrequency.ToInterval(),
 *                           DataType.Signed16Bit, ChannelDataSourceType.Periodic);
 * config.AddChannel(channel);
 * config.Commit();
 * }</pre>
 *
 * <p>In this Java 26 / OTP port, channels are immutable records committed via a
 * {@link SqlRaceSessionEvent.Configure} message to the session {@code StateMachine}.
 *
 * @param channelId      unique channel identifier within the session (auto-incremented)
 * @param name           short engineering name (e.g. {@code "vCar"}, {@code "nEngine"})
 * @param intervalNs     sample interval in nanoseconds; derived from frequency via
 *                       {@link FrequencyUnit#toIntervalNs(double)}
 * @param dataType       binary representation of each sample
 * @param dataSourceType how samples arrive (periodic, triggered, raw)
 */
public record SqlRaceChannel(
        long channelId,
        String name,
        long intervalNs,
        DataType dataType,
        ChannelDataSourceType dataSourceType) {

    /**
     * Compact constructor: validates that the channel has a positive interval.
     *
     * @throws IllegalArgumentException if {@code intervalNs} is not positive
     */
    public SqlRaceChannel {
        if (intervalNs <= 0) {
            throw new IllegalArgumentException(
                    "intervalNs must be positive, got " + intervalNs + " for channel " + name);
        }
    }

    /**
     * Factory for the most common case: a fixed-rate periodic channel.
     *
     * <pre>{@code
     * // 200 Hz signed-16 channel for car speed
     * var vCarChannel = SqlRaceChannel.periodic(1L, "vCar", 200.0, FrequencyUnit.Hz,
     *                                           DataType.Signed16Bit);
     * }</pre>
     *
     * @param channelId unique ID within session
     * @param name      short name
     * @param frequency sampling rate (in {@code unit})
     * @param unit      Hz or KHz
     * @param dataType  sample bit width
     * @return configured periodic channel
     */
    public static SqlRaceChannel periodic(
            long channelId,
            String name,
            double frequency,
            FrequencyUnit unit,
            DataType dataType) {
        return new SqlRaceChannel(channelId, name, unit.toIntervalNs(frequency), dataType,
                ChannelDataSourceType.Periodic);
    }

    /**
     * Compute the nominal sampling frequency in Hz from the stored interval.
     *
     * @return frequency in Hz (may be fractional for sub-Hz channels)
     */
    public double frequencyHz() {
        return 1_000_000_000.0 / intervalNs;
    }
}
