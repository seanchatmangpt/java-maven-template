package io.github.seanchatmangpt.jotp.dogfood.mclaren;

/**
 * Sample retrieval direction — mirrors {@code MESL.SqlRace.Domain.Infrastructure.Enumerators.StepDirection}.
 *
 * <p>In the SQL Race {@code ParameterDataAccessBase} API, {@code GetNextSamples(count, direction)}
 * walks the in-memory ring buffer either forward or backward from the current cursor position.
 * Analysts use {@code Reverse} when replaying telemetry from a lap boundary back toward the
 * session start (e.g. to compute braking events working backward from the apex).
 */
public enum StepDirection {
    /** Walk forward in time from the current cursor position. */
    Forward,

    /** Walk backward in time from the current cursor position. */
    Reverse,
}
