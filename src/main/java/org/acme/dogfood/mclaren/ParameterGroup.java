package org.acme.dogfood.mclaren;

/**
 * Logical parameter group — mirrors {@code MESL.SqlRace.Domain.ParameterGroup}.
 *
 * <p>In ATLAS, a {@code ParameterGroup} is a named container that organises related parameters
 * for display and querying. Groups are referenced by their {@link #identifier()} from
 * {@link SqlRaceParameter#parameterGroupIdentifier()}.
 *
 * <p>A typical F1 session might contain groups:
 *
 * <ul>
 *   <li>{@code "ChassisKinematicChannels"} — wheel loads, suspension travel
 *   <li>{@code "PowertrainChannels"} — RPM, torque, fuel flow
 *   <li>{@code "BrakeChannels"} — brake pressure, disc temperature, wear
 *   <li>{@code "TyreChannels"} — tyre temperature (inner/mid/outer), pressure
 * </ul>
 *
 * <p>Real SQL Race C# code:
 *
 * <pre>{@code
 * var group1 = new ParameterGroup("ChassisKinematicChannels", "Chassis kinematic channels");
 * config.AddParameterGroup(group1);
 * }</pre>
 *
 * @param identifier unique string key referenced by parameters
 * @param description human-readable label shown in the ATLAS parameter browser
 */
public record ParameterGroup(String identifier, String description) {

    /**
     * Compact constructor: validates that the identifier is non-blank.
     *
     * @throws IllegalArgumentException if {@code identifier} is null or blank
     */
    public ParameterGroup {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("ParameterGroup identifier must not be blank");
        }
    }
}
