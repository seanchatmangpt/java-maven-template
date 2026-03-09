package org.acme.dogfood.mclaren;

/**
 * Session metadata item — mirrors {@code MESL.SqlRace.Domain.SessionDataItem}.
 *
 * <p>In ATLAS, sessions carry a dictionary of key-value metadata items that describe the
 * engineering context: circuit, driver, weather conditions, chassis setup version, etc. These
 * items are attached to the session during live acquisition and searched via the Telemetry
 * Analytics API (TAPI).
 *
 * <p>Real SQL Race C# original:
 *
 * <pre>{@code
 * session.Items.Add(new SessionDataItem("Circuit", "Bahrain"));
 * session.Items.Add(new SessionDataItem("Driver", "L. Norris"));
 * session.Items.Add(new SessionDataItem("TyreCompound", "C3"));
 * }</pre>
 *
 * @param name  metadata key (e.g. {@code "Circuit"}, {@code "Driver"}, {@code "TyreCompound"})
 * @param value metadata value as a string (numeric values are also stored as strings)
 */
public record SqlRaceSessionDataItem(String name, String value) {

    /**
     * Compact constructor: validates both fields are non-null.
     *
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    public SqlRaceSessionDataItem {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SessionDataItem name must not be blank");
        }
    }
}
