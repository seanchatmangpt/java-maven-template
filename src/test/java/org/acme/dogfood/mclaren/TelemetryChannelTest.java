package org.acme.dogfood.mclaren;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/**
 * Domain model tests for the SQL Race parameter and channel types — replacing the old
 * placeholder {@code TelemetryChannel} tests with real SQL Race API coverage.
 *
 * <p>Tests cover: identifier format, factory methods, conversion math, classification logic,
 * and validation contracts.
 */
class TelemetryChannelTest implements WithAssertions {

    // ── SqlRaceParameter ─────────────────────────────────────────────────────

    @Test
    void parameterIdentifierUsesColonSeparatedFormat() {
        var param = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");

        assertThat(param.identifier()).isEqualTo("vCar:Chassis");
        assertThat(param.shortName()).isEqualTo("vCar");
        assertThat(param.applicationGroupName()).isEqualTo("Chassis");
    }

    @Test
    void parameterRejectsIdentifierWithoutColon() {
        assertThatThrownBy(() -> new SqlRaceParameter(
                "vCarChassis", "vCar", "desc", 400, 0,
                "CONV_vCar:Chassis", "ChassisChannels", 1L, "Chassis"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name:AppGroup");
    }

    @Test
    void parameterRejectsInvertedRange() {
        assertThatThrownBy(() -> SqlRaceParameter.of("pBrakeF", "Chassis", 3L, 200.0, 50.0, "bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minValue");
    }

    @Test
    void classifyGoodSample() {
        var param = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");

        assertThat(param.classify(235.5)).isEqualTo(DataStatusType.Good);
    }

    @Test
    void classifyOutOfRangeSampleAboveMax() {
        var param = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");

        assertThat(param.classify(450.0)).isEqualTo(DataStatusType.OutOfRange);
    }

    @Test
    void classifyOutOfRangeSampleBelowMin() {
        var param = SqlRaceParameter.of("rThrottle", "Chassis", 2L, 0.0, 100.0, "%");

        assertThat(param.classify(-5.0)).isEqualTo(DataStatusType.OutOfRange);
    }

    @Test
    void classifyNaNasInvalidData() {
        var param = SqlRaceParameter.of("nEngine", "Chassis", 5L, 0.0, 18_000.0, "rpm");

        assertThat(param.classify(Double.NaN)).isEqualTo(DataStatusType.InvalidData);
        assertThat(param.classify(Double.POSITIVE_INFINITY)).isEqualTo(DataStatusType.InvalidData);
    }

    @Test
    void conversionFunctionNameFollowsConvention() {
        var param = SqlRaceParameter.of("TBrakeDiscFL", "BrakesByWire", 10L, 0.0, 1200.0, "°C");

        assertThat(param.conversionFunctionName()).isEqualTo("CONV_TBrakeDiscFL:BrakesByWire");
    }

    // ── SqlRaceChannel ────────────────────────────────────────────────────────

    @Test
    void periodicFactoryComputesIntervalFromHz() {
        var channel = SqlRaceChannel.periodic(1L, "vCar", 200.0, FrequencyUnit.Hz,
                DataType.Signed16Bit);

        assertThat(channel.intervalNs()).isEqualTo(5_000_000L); // 1e9 / 200 = 5ms
        assertThat(channel.dataSourceType()).isEqualTo(ChannelDataSourceType.Periodic);
        assertThat(channel.frequencyHz()).isCloseTo(200.0, within(0.001));
    }

    @Test
    void channelRejectsZeroInterval() {
        assertThatThrownBy(() -> new SqlRaceChannel(1L, "x", 0L, DataType.Float64Bit,
                ChannelDataSourceType.Periodic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalNs");
    }

    @Test
    void kHzUnitComputesCorrectInterval() {
        var channel = SqlRaceChannel.periodic(2L, "accel", 2.0, FrequencyUnit.KHz,
                DataType.Float32Bit);

        assertThat(channel.intervalNs()).isEqualTo(500_000L); // 1e6 / 2 = 500µs
    }

    // ── RationalConversion ────────────────────────────────────────────────────

    @Test
    void linearIdentityConversionPreservesValue() {
        var conv = RationalConversion.identity("CONV_vCar:Chassis", "kph");

        assertThat(conv.apply(235.5)).isCloseTo(235.5, within(1e-9));
    }

    @Test
    void linearScaleConversion() {
        // 16-bit raw 0–32767 maps to 0–400 kph
        double scale = 400.0 / 32767.0;
        var conv = RationalConversion.linear("CONV_vCar:Chassis", "kph", scale, 0.0);

        assertThat(conv.apply(0)).isCloseTo(0.0, within(1e-9));
        assertThat(conv.apply(32767)).isCloseTo(400.0, within(0.01));
    }

    @Test
    void linearOffsetConversion() {
        // Celsius to Fahrenheit: F = C × 1.8 + 32
        var conv = RationalConversion.linear("CONV_TBrakeDiscFL:BrakesByWire", "°F", 1.8, 32.0);

        assertThat(conv.apply(0.0)).isCloseTo(32.0, within(1e-9));
        assertThat(conv.apply(100.0)).isCloseTo(212.0, within(1e-9));
    }

    // ── ParameterValues ───────────────────────────────────────────────────────

    @Test
    void emptyParameterValuesHasZeroCount() {
        var pv = ParameterValues.empty();

        assertThat(pv.count()).isZero();
        assertThat(pv.goodSamples().count()).isZero();
    }

    @Test
    void singleSampleIsGoodByDefault() {
        var pv = ParameterValues.single(1_000_000L, 237.5);

        assertThat(pv.count()).isEqualTo(1);
        assertThat(pv.dataStatus()[0]).isEqualTo(DataStatusType.Good);
        assertThat(pv.data()[0]).isEqualTo(237.5);
        assertThat(pv.timestamps()[0]).isEqualTo(1_000_000L);
    }

    @Test
    void goodSamplesFiltersOutNonGood() {
        var pv = new ParameterValues(
                new long[] {1L, 2L, 3L},
                new double[] {100.0, 999.0, 200.0},
                new DataStatusType[] {DataStatusType.Good, DataStatusType.OutOfRange,
                        DataStatusType.Good});

        var good = pv.goodSamples();
        assertThat(good.count()).isEqualTo(2);
        assertThat(good.data()).containsExactly(100.0, 200.0);
    }

    @Test
    void parameterValuesRejectsArrayLengthMismatch() {
        assertThatThrownBy(() -> new ParameterValues(
                new long[] {1L, 2L},
                new double[] {1.0},
                new DataStatusType[] {DataStatusType.Good}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void parameterValuesEqualsIsValueBased() {
        var a = ParameterValues.single(100L, 42.0);
        var b = ParameterValues.single(100L, 42.0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ── SqlRaceLap ────────────────────────────────────────────────────────────

    @Test
    void outLapFactoryCreatesLapZero() {
        var lap = SqlRaceLap.outLap(1_000_000_000L);

        assertThat(lap.number()).isZero();
        assertThat(lap.isOutLap()).isTrue();
        assertThat(lap.countForFastestLap()).isFalse();
        assertThat(lap.triggerSource()).isEqualTo(SqlRaceLap.TRIGGER_BEACON);
    }

    @Test
    void flyingLapCounts() {
        var lap = SqlRaceLap.flyingLap(2_000_000_000L, 3);

        assertThat(lap.number()).isEqualTo(3);
        assertThat(lap.countForFastestLap()).isTrue();
        assertThat(lap.triggerSource()).isEqualTo(SqlRaceLap.TRIGGER_GPS);
    }

    @Test
    void lapRejectsNegativeNumber() {
        assertThatThrownBy(
                () -> new SqlRaceLap(0L, -1, SqlRaceLap.TRIGGER_GPS, "bad", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── ApplicationGroup + ParameterGroup ────────────────────────────────────

    @Test
    void applicationGroupRdaFactory() {
        var appGroup = ApplicationGroup.rda("Chassis", "ChassisKinematicChannels",
                "PowertrainChannels");

        assertThat(appGroup.identifier()).isEqualTo("Chassis");
        assertThat(appGroup.supportsRda()).isTrue();
        assertThat(appGroup.parameterGroupIdentifiers())
                .containsExactly("ChassisKinematicChannels", "PowertrainChannels");
    }

    @Test
    void parameterGroupRejectsBlankIdentifier() {
        assertThatThrownBy(() -> new ParameterGroup("", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
