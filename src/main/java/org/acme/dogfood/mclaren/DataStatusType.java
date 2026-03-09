package org.acme.dogfood.mclaren;

/**
 * Per-sample data quality flag — mirrors {@code MAT.OCS.Core.DataStatusType}.
 *
 * <p>Every sample in a {@link ParameterValues} array carries a {@code DataStatusType} tag. ATLAS
 * display plugins and post-processing pipelines use these flags to distinguish genuine sensor
 * readings from noise, communication dropouts, and calibration artefacts.
 *
 * <p>The {@link ParameterDataAccess} process tags incoming samples without crashing:
 *
 * <pre>{@code
 * // Out-of-range → tag and continue (OTP "let it work" for recoverable errors)
 * if (value < param.minValue() || value > param.maxValue()) {
 *     return DataStatusType.OutOfRange;
 * }
 * // NaN / Infinity → hardware fault; tag and continue
 * if (!Double.isFinite(value)) {
 *     return DataStatusType.InvalidData;
 * }
 * return DataStatusType.Good;
 * }</pre>
 *
 * <p>Contrast with OTP "let it crash": we only crash the virtual-thread process for
 * <em>unrecoverable</em> faults (ECU connection lost, corrupt framing). Data quality problems are
 * not crashes — they are domain-level events that consumers must handle.
 */
public enum DataStatusType {
    /**
     * Sample is valid and within calibration range. This is the expected steady-state value for a
     * functioning sensor.
     */
    Good,

    /**
     * No sample was received for this timestamp. The ATLAS interpolation engine will either
     * hold-last-value or gap-fill depending on the parameter's configuration.
     */
    Missing,

    /**
     * Sample exceeds the ADC or sensor range ceiling; value is clamped at maximum. Common during
     * cold starts when temperature channels overshoot their calibration tables.
     */
    Saturated,

    /**
     * Sample fails a basic sanity check (NaN, infinity, or protocol-level error code). The ECU
     * transmitted a value but it cannot be trusted.
     */
    InvalidData,

    /**
     * Sample is numerically finite but outside the declared {@link SqlRaceParameter#minValue()} /
     * {@link SqlRaceParameter#maxValue()} engineering range. Logged for investigation; not
     * propagated to live display.
     */
    OutOfRange,

    /**
     * Sample was synthesised by a forward-prediction model (e.g. Kalman filter gap-fill).
     * Flagged so that statistics pipelines can exclude synthetic data from lap comparisons.
     */
    Predicted,
}
