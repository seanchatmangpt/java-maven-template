package org.acme.dogfood.mclaren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.acme.Result;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TelemetryChannel} sealed hierarchy and {@link TelemetryProcessor}
 * validation logic — the core building block of the McLaren Atlas refactor.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Sealed channel variant construction and accessors
 *   <li>Compact-constructor validation (negative sampleRateHz, inverted range)
 *   <li>Railway-oriented sample validation (happy path, below min, above max, boundary)
 *   <li>Pattern-matching exhaustiveness over all four variants
 * </ul>
 */
class TelemetryChannelTest implements WithAssertions {

    // ── Well-known channel constants ──────────────────────────────────────────

    @Test
    void speedKphHasExpectedMetadata() {
        var ch = TelemetryChannel.SPEED_KPH;

        assertThat(ch.name()).isEqualTo("speed_kph");
        assertThat(ch.unit()).isEqualTo("kph");
        assertThat(ch.sampleRateHz()).isEqualTo(100);
        assertThat(ch.minValid()).isEqualTo(0.0);
        assertThat(ch.maxValid()).isEqualTo(400.0);
        assertThat(ch).isInstanceOf(TelemetryChannel.Kinematic.class);
    }

    @Test
    void rpmIsCriticalPowertrain() {
        var ch = (TelemetryChannel.Powertrain) TelemetryChannel.RPM;

        assertThat(ch.critical()).isTrue();
        assertThat(ch.maxValid()).isEqualTo(18_000.0);
    }

    @Test
    void throttleIsNonCriticalPowertrain() {
        var ch = (TelemetryChannel.Powertrain) TelemetryChannel.THROTTLE_PCT;

        assertThat(ch.critical()).isFalse();
    }

    @Test
    void brakeTempHasLocation() {
        var ch = (TelemetryChannel.Thermal) TelemetryChannel.BRAKE_TEMP_FL;

        assertThat(ch.location()).isEqualTo("front_left_brake");
        assertThat(ch.unit()).isEqualTo("°C");
    }

    @Test
    void drsIsAeroVariant() {
        assertThat(TelemetryChannel.DRS).isInstanceOf(TelemetryChannel.Aero.class);
        assertThat(TelemetryChannel.DRS.minValid()).isEqualTo(0.0);
        assertThat(TelemetryChannel.DRS.maxValid()).isEqualTo(1.0);
    }

    // ── Compact-constructor validation ────────────────────────────────────────

    @Test
    void kinematicRejectsNegativeSampleRate() {
        assertThatThrownBy(() -> new TelemetryChannel.Kinematic("x", "m/s", 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRateHz");
    }

    @Test
    void thermalRejectsInvertedRange() {
        assertThatThrownBy(
                        () -> new TelemetryChannel.Thermal("t", "°C", 10, 500.0, 100.0, "wheel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minValid > maxValid");
    }

    @Test
    void powertrainRejectsNullName() {
        assertThatThrownBy(() -> new TelemetryChannel.Powertrain(null, "rpm", 50, 0, 18000, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void aeroRejectsNullUnit() {
        assertThatThrownBy(() -> new TelemetryChannel.Aero("drs", null, 50, 0, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("unit");
    }

    // ── Sample validation — happy path ────────────────────────────────────────

    @Test
    void validSampleInRangeSucceeds() {
        var now = Instant.now();
        var result = TelemetryProcessor.validate(TelemetryChannel.SPEED_KPH, 235.4, now);

        assertThat(result.isSuccess()).isTrue();
        var sample = ((Result.Success<TelemetryProcessor.ValidSample, ?>) result).value();
        assertThat(sample.channelName()).isEqualTo("speed_kph");
        assertThat(sample.value()).isEqualTo(235.4);
        assertThat(sample.timestamp()).isEqualTo(now);
    }

    @Test
    void sampleAtMinBoundarySucceeds() {
        var result =
                TelemetryProcessor.validate(TelemetryChannel.SPEED_KPH, 0.0, Instant.now());

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void sampleAtMaxBoundarySucceeds() {
        var result =
                TelemetryProcessor.validate(TelemetryChannel.SPEED_KPH, 400.0, Instant.now());

        assertThat(result.isSuccess()).isTrue();
    }

    // ── Sample validation — error paths ──────────────────────────────────────

    @Test
    void sampleBelowMinFails() {
        var result =
                TelemetryProcessor.validate(TelemetryChannel.SPEED_KPH, -1.0, Instant.now());

        assertThat(result.isFailure()).isTrue();
        var error =
                ((Result.Failure<?, TelemetryProcessor.ValidationError>) result).error();
        assertThat(error)
                .isInstanceOf(TelemetryProcessor.ValidationError.OutOfRange.class);
        var oor = (TelemetryProcessor.ValidationError.OutOfRange) error;
        assertThat(oor.channelName()).isEqualTo("speed_kph");
        assertThat(oor.value()).isEqualTo(-1.0);
    }

    @Test
    void sampleAboveMaxFails() {
        var result =
                TelemetryProcessor.validate(TelemetryChannel.SPEED_KPH, 401.0, Instant.now());

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void thermalSampleOutOfRangeProducesCorrectError() {
        // Brake disc can't exceed 1200 °C
        var result =
                TelemetryProcessor.validate(
                        TelemetryChannel.BRAKE_TEMP_FL, 1500.0, Instant.now());

        assertThat(result.isFailure()).isTrue();
        var error =
                ((Result.Failure<?, TelemetryProcessor.ValidationError>) result).error();
        var oor = (TelemetryProcessor.ValidationError.OutOfRange) error;
        assertThat(oor.max()).isEqualTo(1_200.0);
    }

    // ── Pattern matching exhaustiveness ───────────────────────────────────────

    @Test
    void patternMatchOverAllVariantsIsExhaustive() {
        // If a new variant is added to the sealed hierarchy without a matching arm,
        // this switch becomes a compile error rather than a runtime surprise.
        List<TelemetryChannel> channels = List.of(
                TelemetryChannel.SPEED_KPH,
                TelemetryChannel.BRAKE_TEMP_FL,
                TelemetryChannel.RPM,
                TelemetryChannel.DRS);

        for (var ch : channels) {
            String category = switch (ch) {
                case TelemetryChannel.Kinematic k -> "kinematic";
                case TelemetryChannel.Thermal t -> "thermal";
                case TelemetryChannel.Powertrain p -> "powertrain";
                case TelemetryChannel.Aero a -> "aero";
            };
            assertThat(category).isNotEmpty();
        }
    }
}
