package org.acme.dogfood.mclaren;

import java.util.List;

/**
 * Application group — mirrors {@code MESL.SqlRace.Domain.ApplicationGroup}.
 *
 * <p>In the SQL Race model, an {@code ApplicationGroup} is the top-level namespace for a set of
 * related channels. It maps directly to the ECU or subsystem that produces those channels. In
 * McLaren's real database, the application group name appears as the suffix in parameter
 * identifiers: {@code "vCar:<b>Chassis</b>"}, {@code "nEngine:<b>Chassis</b>"}.
 *
 * <p>Common application groups in an F1 session:
 *
 * <ul>
 *   <li>{@code "Chassis"} — primary ECU; speed, acceleration, steering angle
 *   <li>{@code "Engine"} — powertrain unit; RPM, throttle, fuel, brake-by-wire
 *   <li>{@code "BrakesByWire"} — BBW ECU; brake pressure, disc temp, bite point
 *   <li>{@code "Tyres"} — tyre data; inner/mid/outer temperature, pressure, wear
 *   <li>{@code "Aero"} — wind tunnel / CFD correlation; drag, downforce, floor wake
 * </ul>
 *
 * <p>Real SQL Race C# original:
 *
 * <pre>{@code
 * var appGroup = new ApplicationGroup("Chassis", "Chassis",
 *     new List<string> { "ChassisKinematicChannels", "PowertrainChannels" }) {
 *     SupportsRda = true
 * };
 * config.AddGroup(appGroup);
 * }</pre>
 *
 * @param identifier               unique ECU / subsystem name (e.g. {@code "Chassis"})
 * @param name                     display name (often equals the identifier)
 * @param parameterGroupIdentifiers list of {@link ParameterGroup} identifiers owned by this group
 * @param supportsRda              whether this group participates in Real-time Data Acquisition
 */
public record ApplicationGroup(
        String identifier,
        String name,
        List<String> parameterGroupIdentifiers,
        boolean supportsRda) {

    /**
     * Compact constructor: validates identifier and copies the group list defensively.
     *
     * @throws IllegalArgumentException if {@code identifier} is null or blank
     */
    public ApplicationGroup {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("ApplicationGroup identifier must not be blank");
        }
        parameterGroupIdentifiers = List.copyOf(parameterGroupIdentifiers);
    }

    /**
     * Convenience factory for an RDA-enabled application group.
     *
     * @param identifier ECU name
     * @param groupIds   parameter groups owned by this ECU
     * @return application group with {@code supportsRda = true}
     */
    public static ApplicationGroup rda(String identifier, String... groupIds) {
        return new ApplicationGroup(identifier, identifier, List.of(groupIds), true);
    }
}
