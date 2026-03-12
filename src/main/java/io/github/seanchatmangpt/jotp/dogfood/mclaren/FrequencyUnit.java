package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Sampling frequency unit — mirrors {@code MESL.SqlRace.Domain.FrequencyUnit}.
 *
 * <p>In ATLAS, a {@code Frequency} is constructed as {@code new Frequency(value, FrequencyUnit)}
 * and then converted to a nanosecond interval via {@code .ToInterval()}. This Java equivalent
 * drives {@link SqlRaceChannel#intervalNs()} through {@link SqlRaceChannel#periodic(long, String,
 * double, FrequencyUnit, DataType)}.
 *
 * <p>F1 telemetry channels span orders of magnitude: wheel speeds sampled at 500 Hz, GPS at
 * 20 Hz, and tyre temperature scans at 50 Hz. The {@code KHz} constant is included for
 * accelerometer and vibration channels (≥ 1 000 Hz).
 */
public enum FrequencyUnit {
    /** Hertz — samples per second. Used for most ECU channels (1 Hz – 500 Hz). */
    Hz {
        @Override
        public long toIntervalNs(double frequency) {
            return (long) (1_000_000_000.0 / frequency);
        }
    },

    /** Kilohertz — thousands of samples per second. Used for vibration / IMU channels. */
    KHz {
        @Override
        public long toIntervalNs(double frequency) {
            return (long) (1_000_000.0 / frequency);
        }
    };

    /**
     * Convert a frequency value in this unit to a nanosecond sample interval.
     *
     * @param frequency must be positive and non-zero
     * @return sample interval in nanoseconds
     */
    public abstract long toIntervalNs(double frequency);
}
