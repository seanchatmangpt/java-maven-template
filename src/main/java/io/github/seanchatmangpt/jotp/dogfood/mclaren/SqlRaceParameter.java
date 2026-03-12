package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Parameter definition — mirrors {@code MESL.SqlRace.Domain.Parameter} / {@code IParameter}.
 *
 * <p>A parameter is the engineering-visible identity of a measurement channel. Its {@link
 * #identifier()} follows the McLaren SQL Race naming convention:
 *
 * <pre>
 *   {@code "<name>:<ApplicationGroup>"}
 * </pre>
 *
 * <p>Real examples from McLaren F1 sessions:
 *
 * <ul>
 *   <li>{@code "vCar:Chassis"} — car speed (kph), sourced from the Chassis ECU
 *   <li>{@code "nEngine:Chassis"} — engine RPM
 *   <li>{@code "pBrakeF:Chassis"} — front brake line pressure (bar)
 *   <li>{@code "TBrakeDiscFL:BrakesByWire"} — front-left brake disc temperature (°C)
 *   <li>{@code "rThrottle:Chassis"} — throttle pedal position (%)
 *   <li>{@code "gLat:Chassis"} — lateral acceleration (g)
 *   <li>{@code "gLong:Chassis"} — longitudinal acceleration (g)
 *   <li>{@code "TyrePresFL:Tyres"} — front-left tyre pressure (bar)
 * </ul>
 *
 * <p>Real SQL Race C# original:
 *
 * <pre>{@code
 * var param = new Parameter(
 *     "vCar:Chassis", "vCar", "Car speed",
 *     400.0, 0.0,                     // max, min
 *     1, 0, 0, 255, 0,                // conversion coefficients (legacy form)
 *     "CONV_vCar:Chassis",            // conversion function name
 *     new List<string>{ "ChassisKinematicChannels" },
 *     channelId,
 *     "Chassis");
 * }</pre>
 *
 * @param identifier              SQL Race identifier in {@code "name:AppGroup"} format
 * @param name                    short engineering name (without the application group suffix)
 * @param description             human-readable description shown in parameter browser
 * @param maxValue                engineering-unit upper bound (for {@link DataStatusType#OutOfRange}
 *                                detection)
 * @param minValue                engineering-unit lower bound
 * @param conversionFunctionName  name of the associated {@link RationalConversion}
 * @param parameterGroupIdentifier the {@link ParameterGroup} this parameter belongs to
 * @param channelId               ID of the backing {@link SqlRaceChannel}
 * @param applicationGroupName    ECU / subsystem name (the part after the colon in identifier)
 */
public record SqlRaceParameter(
        String identifier,
        String name,
        String description,
        double maxValue,
        double minValue,
        String conversionFunctionName,
        String parameterGroupIdentifier,
        long channelId,
        String applicationGroupName) {

    /**
     * Compact constructor: validates identifier format and min/max ordering.
     *
     * @throws IllegalArgumentException if identifier lacks a colon separator or min > max
     */
    public SqlRaceParameter {
        if (!identifier.contains(":")) {
            throw new IllegalArgumentException(
                    "Parameter identifier must use 'name:AppGroup' format, got: " + identifier);
        }
        if (minValue > maxValue) {
            throw new IllegalArgumentException(
                    "minValue (" + minValue + ") > maxValue (" + maxValue + ") for " + identifier);
        }
    }

    /**
     * Convenience factory — derives the identifier from name + appGroup, creates a matching
     * conversion name, and places the parameter in a default group.
     *
     * <pre>{@code
     * var vCar = SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");
     * // → identifier = "vCar:Chassis"
     * // → conversionFunctionName = "CONV_vCar:Chassis"
     * }</pre>
     *
     * @param name     short engineering name
     * @param appGroup application group (ECU name)
     * @param channelId backing channel ID
     * @param min      engineering minimum
     * @param max      engineering maximum
     * @param unit     engineering unit label (informational; drives conversion name)
     * @return fully constructed parameter
     */
    public static SqlRaceParameter of(
            String name,
            String appGroup,
            long channelId,
            double min,
            double max,
            String unit) {
        String id = name + ":" + appGroup;
        return new SqlRaceParameter(
                id,
                name,
                name + " (" + unit + ")",
                max,
                min,
                "CONV_" + id,
                appGroup + "Channels",
                channelId,
                appGroup);
    }

    /**
     * Extract the short name from the identifier (the part before the colon).
     *
     * @return short name, e.g. {@code "vCar"} from {@code "vCar:Chassis"}
     */
    public String shortName() {
        return identifier.substring(0, identifier.indexOf(':'));
    }

    /**
     * Check whether a sample value is within the declared engineering range.
     *
     * @param value engineering-unit value after conversion
     * @return {@code true} if {@code minValue ≤ value ≤ maxValue}
     */
    public boolean inRange(double value) {
        return value >= minValue && value <= maxValue;
    }

    /**
     * Determine the data status for an incoming sample value.
     *
     * @param value the engineering value after applying the conversion function
     * @return the appropriate {@link DataStatusType} tag
     */
    public DataStatusType classify(double value) {
        if (!Double.isFinite(value)) {
            return DataStatusType.InvalidData;
        }
        if (value > maxValue || value < minValue) {
            return DataStatusType.OutOfRange;
        }
        return DataStatusType.Good;
    }
}
