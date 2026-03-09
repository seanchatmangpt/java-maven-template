package org.acme.dogfood.mclaren;

import java.util.Objects;

/**
 * Sealed hierarchy describing a single McLaren Atlas telemetry channel.
 *
 * <p>Each variant captures the domain-specific metadata that determines how the channel is
 * acquired, validated, and archived. Using a sealed interface of records guarantees that every
 * pattern-match in the {@link TelemetryProcessor} handler is exhaustive at compile time — a
 * new channel type added without a matching case arm is a compile error, not a runtime NPE at
 * 200 mph.
 *
 * <p>Valid-range validation (see {@link TelemetryProcessor#validate}) uses {@link #minValid} and
 * {@link #maxValid}; out-of-range samples are routed through {@link
 * org.acme.Result#failure(Object)} rather than thrown.
 *
 * <pre>{@code
 * TelemetryChannel ch = TelemetryChannel.SPEED_KPH;
 * System.out.println(ch.name());         // "speed_kph"
 * System.out.println(ch.sampleRateHz()); // 100
 * System.out.println(ch.maxValid());     // 400.0
 * }</pre>
 */
public sealed interface TelemetryChannel
        permits TelemetryChannel.Kinematic,
                TelemetryChannel.Thermal,
                TelemetryChannel.Powertrain,
                TelemetryChannel.Aero {

    /** Unique channel identifier — matches the Atlas DAS channel name. */
    String name();

    /** Engineering unit string (SI preferred), e.g. {@code "kph"}, {@code "°C"}, {@code "%"}. */
    String unit();

    /** Nominal acquisition rate in Hz. Actual rate may be lower during partial dropout. */
    int sampleRateHz();

    /**
     * Minimum physically valid value. Samples below this threshold are routed to the error path
     * in {@link TelemetryProcessor}; the channel process is NOT crashed.
     */
    double minValid();

    /**
     * Maximum physically valid value. Samples above this threshold are routed to the error path.
     */
    double maxValid();

    // ── Variant: Kinematic ────────────────────────────────────────────────────

    /**
     * Kinematic channels: speed, acceleration, yaw, steering angle.
     *
     * @param name channel identifier
     * @param unit engineering unit
     * @param sampleRateHz acquisition rate
     * @param minValid minimum physically valid value
     * @param maxValid maximum physically valid value
     */
    record Kinematic(String name, String unit, int sampleRateHz, double minValid, double maxValid)
            implements TelemetryChannel {
        public Kinematic {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(unit, "unit must not be null");
            if (sampleRateHz <= 0) throw new IllegalArgumentException("sampleRateHz must be > 0");
            if (minValid > maxValid) throw new IllegalArgumentException("minValid > maxValid");
        }
    }

    // ── Variant: Thermal ──────────────────────────────────────────────────────

    /**
     * Thermal channels: coolant, oil, brake disc, tyre surface temperatures.
     *
     * @param name channel identifier
     * @param unit engineering unit (typically {@code "°C"})
     * @param sampleRateHz acquisition rate
     * @param minValid minimum physically valid value
     * @param maxValid maximum physically valid value
     * @param location physical sensor location, e.g. {@code "front_left_brake"}
     */
    record Thermal(
            String name,
            String unit,
            int sampleRateHz,
            double minValid,
            double maxValid,
            String location)
            implements TelemetryChannel {
        public Thermal {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(location, "location must not be null");
            if (sampleRateHz <= 0) throw new IllegalArgumentException("sampleRateHz must be > 0");
            if (minValid > maxValid) throw new IllegalArgumentException("minValid > maxValid");
        }
    }

    // ── Variant: Powertrain ───────────────────────────────────────────────────

    /**
     * Powertrain channels: RPM, throttle, brake pressure, gear, MGU-K/H deployment.
     *
     * @param name channel identifier
     * @param unit engineering unit
     * @param sampleRateHz acquisition rate
     * @param minValid minimum physically valid value
     * @param maxValid maximum physically valid value
     * @param critical {@code true} if a hardware fault on this channel should propagate as a
     *     supervisor crash (e.g. fuel flow sensor) vs. a recoverable validation error
     */
    record Powertrain(
            String name,
            String unit,
            int sampleRateHz,
            double minValid,
            double maxValid,
            boolean critical)
            implements TelemetryChannel {
        public Powertrain {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(unit, "unit must not be null");
            if (sampleRateHz <= 0) throw new IllegalArgumentException("sampleRateHz must be > 0");
            if (minValid > maxValid) throw new IllegalArgumentException("minValid > maxValid");
        }
    }

    // ── Variant: Aero ─────────────────────────────────────────────────────────

    /**
     * Aerodynamic channels: DRS position, ride height, downforce estimation.
     *
     * @param name channel identifier
     * @param unit engineering unit
     * @param sampleRateHz acquisition rate
     * @param minValid minimum physically valid value
     * @param maxValid maximum physically valid value
     */
    record Aero(String name, String unit, int sampleRateHz, double minValid, double maxValid)
            implements TelemetryChannel {
        public Aero {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(unit, "unit must not be null");
            if (sampleRateHz <= 0) throw new IllegalArgumentException("sampleRateHz must be > 0");
            if (minValid > maxValid) throw new IllegalArgumentException("minValid > maxValid");
        }
    }

    // ── Well-known channel constants ──────────────────────────────────────────

    /** Ground speed — 0 to 400 km/h, 100 Hz. */
    TelemetryChannel SPEED_KPH = new Kinematic("speed_kph", "kph", 100, 0.0, 400.0);

    /** Lateral g-force — ±6 g, 200 Hz. */
    TelemetryChannel LATERAL_G = new Kinematic("lateral_g", "g", 200, -6.0, 6.0);

    /** Engine RPM — 0 to 18 000, 50 Hz. */
    TelemetryChannel RPM = new Powertrain("rpm", "rpm", 50, 0.0, 18_000.0, true);

    /** Throttle pedal position — 0 to 100%, 100 Hz. */
    TelemetryChannel THROTTLE_PCT = new Powertrain("throttle_pct", "%", 100, 0.0, 100.0, false);

    /** Front-left brake disc temperature — −40 to 1 200 °C, 25 Hz. */
    TelemetryChannel BRAKE_TEMP_FL =
            new Thermal("brake_temp_fl", "°C", 25, -40.0, 1_200.0, "front_left_brake");

    /** DRS deployment — 0 (closed) to 1 (fully open), 50 Hz. */
    TelemetryChannel DRS = new Aero("drs", "position", 50, 0.0, 1.0);
}
