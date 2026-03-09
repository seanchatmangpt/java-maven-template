package org.acme.dogfood.mclaren;

/**
 * Channel data type — mirrors {@code MESL.SqlRace.Domain.DataType} from the SQL Race API.
 *
 * <p>In ATLAS, every {@link SqlRaceChannel} declares the binary representation of its samples.
 * The choice affects both storage density and precision: an ECU transmitting 12-bit ADC readings
 * naturally fits in {@code Signed16Bit}, while a GPS-derived velocity uses {@code Float64Bit}.
 *
 * <p>Frequency naming follows McLaren convention:
 *
 * <ul>
 *   <li>{@code Signed} / {@code Unsigned} — two's-complement fixed-width integers
 *   <li>{@code Float} — IEEE 754 single ({@code Float32Bit}) or double ({@code Float64Bit})
 * </ul>
 */
public enum DataType {
    /** 16-bit signed integer; default for most ECU channels (ADC, sensor counts). */
    Signed16Bit,

    /** 16-bit unsigned integer; used for counters and raw binary protocol values. */
    Unsigned16Bit,

    /** 32-bit signed integer; high-resolution position or CAN-decoded integers. */
    Signed32Bit,

    /** 32-bit unsigned integer; large counters, CAN IDs. */
    Unsigned32Bit,

    /** 32-bit IEEE 754 float; engineering values that need fractional precision. */
    Float32Bit,

    /** 64-bit IEEE 754 double; GPS coordinates, high-precision timing. */
    Float64Bit,

    /** 8-bit unsigned; boolean flags packed as bytes, DTC codes. */
    Unsigned8Bit,

    /** 8-bit signed; small signed offsets (trim values, temperature deltas). */
    Signed8Bit,
}
