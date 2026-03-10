package org.acme.dogfood.mclaren;

/**
 * Channel data source type — mirrors {@code MESL.SqlRace.Domain.ChannelDataSourceType}.
 *
 * <p>In the SQL Race data model a {@link SqlRaceChannel} is not just defined by its data type but
 * also by how samples arrive. This enum drives how the ATLAS recorder allocates ring-buffer pages
 * and how the {@link ParameterDataAccess} process retrieves data via
 * {@code GetNextSamples/GoTo}.
 *
 * <ul>
 *   <li>{@code Periodic} — fixed-rate sample stream from a continuously running ECU function
 *       (e.g. 1 kHz throttle position).
 *   <li>{@code Triggered} — sample emitted only on a specific event (gear shift, pit entry).
 *   <li>{@code RowData} — variable-length binary payload; used for CAN bus logs, SECU packets.
 *   <li>{@code Unbuffered} — single live value; no historical ring-buffer maintained.
 * </ul>
 */
public enum ChannelDataSourceType {
    /** Fixed-rate periodic stream; the most common type for F1 ECU channels. */
    Periodic,

    /** Triggered on discrete events; sparse samples with variable inter-sample gaps. */
    Triggered,

    /** Raw variable-length binary rows; consumed by custom decoders via the Atlas C++ API. */
    RowData,

    /** Stateless single-value channel; read once per request, no history buffered. */
    Unbuffered,
}
