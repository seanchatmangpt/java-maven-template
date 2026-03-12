package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Rational (polynomial) conversion — mirrors {@code MESL.SqlRace.Domain.RationalConversion}.
 *
 * <p>In ATLAS every parameter carries a conversion function that maps raw ADC counts to
 * engineering units (kph, bar, °C, g). The SQL Race {@code RationalConversion} models the
 * standard Bosch / McLaren polynomial:
 *
 * <pre>
 *   y = (c1 + c2·x + c3·x²) / (c4 + c5·x + c6·x²)
 * </pre>
 *
 * <p>For the common linear case {@code y = scale·x + offset} the denominator simplifies to 1
 * ({@code c4=1, c5=0, c6=0}).
 *
 * <p>Conversion names follow the pattern {@code "CONV_<paramName>:<AppGroup>"}, matching the
 * McLaren naming convention used in real SQL Race databases:
 *
 * <pre>{@code
 * // Real SQL Race C# original:
 * config.AddConversion(new RationalConversion(
 *     "CONV_vCar:Chassis", "kph", "%5.2f",
 *     0.0, 1.0, 0.0, 0.0, 0.0, 1.0));   // identity: raw = engineering value
 * }</pre>
 *
 * @param name         conversion identifier (e.g. {@code "CONV_vCar:Chassis"})
 * @param unit         engineering unit string (e.g. {@code "kph"}, {@code "bar"}, {@code "°C"})
 * @param formatString C-style printf format for display (e.g. {@code "%5.2f"})
 * @param c1           polynomial numerator constant term
 * @param c2           polynomial numerator linear coefficient
 * @param c3           polynomial numerator quadratic coefficient
 * @param c4           polynomial denominator constant term (must be non-zero)
 * @param c5           polynomial denominator linear coefficient
 * @param c6           polynomial denominator quadratic coefficient
 */
public record RationalConversion(
        String name,
        String unit,
        String formatString,
        double c1,
        double c2,
        double c3,
        double c4,
        double c5,
        double c6) {

    /**
     * Factory for the common linear case: {@code y = scale·x + offset}.
     *
     * <pre>{@code
     * // 16-bit raw (0–32767) → 0–400 kph
     * var conv = RationalConversion.linear("CONV_vCar:Chassis", "kph", "%5.1f",
     *                                      400.0 / 32767.0, 0.0);
     * }</pre>
     *
     * @param name   conversion identifier
     * @param unit   engineering unit
     * @param scale  linear multiplier (c2)
     * @param offset additive offset (c1)
     * @return configured linear conversion
     */
    public static RationalConversion linear(
            String name, String unit, double scale, double offset) {
        return new RationalConversion(name, unit, "%5.2f", offset, scale, 0.0, 1.0, 0.0, 0.0);
    }

    /**
     * Identity conversion: {@code y = x} (raw value IS the engineering value).
     *
     * @param name conversion identifier
     * @param unit engineering unit of the raw value
     * @return identity conversion
     */
    public static RationalConversion identity(String name, String unit) {
        return linear(name, unit, 1.0, 0.0);
    }

    /**
     * Apply the rational conversion to a raw sample value.
     *
     * @param raw raw ADC / protocol integer value
     * @return engineering value in {@link #unit()}
     */
    public double apply(double raw) {
        double numerator = c1 + c2 * raw + c3 * raw * raw;
        double denominator = c4 + c5 * raw + c6 * raw * raw;
        return numerator / denominator;
    }
}
