package io.github.seanchatmangpt.jotp.dogfood.api;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * {@code java.time} API migration patterns.
 *
 * <p>Generated from {@code templates/java/api/java-time.tera}.
 *
 * <p>Demonstrates that ggen generates API modernization patterns replacing {@code java.util.Date},
 * {@code Calendar}, and {@code SimpleDateFormat} with the correct, thread-safe {@code java.time}
 * equivalents.
 *
 * <p><strong>Pattern contracts validated:</strong>
 *
 * <ul>
 *   <li>{@link LocalDate}, {@link LocalDateTime}, {@link Instant} are immutable value types —
 *       thread-safe by design.
 *   <li>All arithmetic is overflow-safe and DST-aware.
 *   <li>{@link DateTimeFormatter} instances are thread-safe (unlike {@code SimpleDateFormat}).
 *   <li>Timezone conversions preserve the same instant in time.
 * </ul>
 */
public final class JavaTimePatterns {

    private JavaTimePatterns() {}

    // =========================================================================
    // LocalDate — date without time or timezone
    // =========================================================================

    /** Creates a specific date. Month is 1-based (unlike Calendar). */
    public static LocalDate createDate(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    /** Returns the next business day (skipping weekends). */
    public static LocalDate nextBusinessDay(LocalDate from) {
        LocalDate next = from.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY
                || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    /** Returns the last day of the month for the given date. */
    public static LocalDate lastDayOfMonth(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }

    // =========================================================================
    // LocalDateTime — date + time without timezone
    // =========================================================================

    /** Creates a date-time suitable for local events (no timezone). */
    public static LocalDateTime createDateTime(
            int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    /** Truncates a date-time to the start of the hour. */
    public static LocalDateTime startOfHour(LocalDateTime dateTime) {
        return dateTime.truncatedTo(ChronoUnit.HOURS);
    }

    // =========================================================================
    // Instant — machine timestamp (epoch-based)
    // =========================================================================

    /** Returns the current instant (UTC). */
    public static Instant now() {
        return Instant.now();
    }

    /** Returns the current instant from a testable clock. */
    public static Instant now(Clock clock) {
        return Instant.now(clock);
    }

    /** Checks if an instant is within a time window of another. */
    public static boolean isWithin(Instant a, Instant b, Duration window) {
        return Duration.between(a, b).abs().compareTo(window) <= 0;
    }

    // =========================================================================
    // Duration & Period — amounts of time
    // =========================================================================

    /** Duration: time-based (hours, minutes, seconds, nanos). */
    public static Duration timeout(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    /** Period: date-based (years, months, days). */
    public static Period subscriptionLength(int months) {
        return Period.ofMonths(months);
    }

    /** Calculates age in years from a birth date. */
    public static int age(LocalDate birthDate, LocalDate asOf) {
        return Period.between(birthDate, asOf).getYears();
    }

    /** Calculates the number of days between two dates. */
    public static long daysBetween(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    // =========================================================================
    // ZonedDateTime & OffsetDateTime — timezone-aware
    // =========================================================================

    /** Creates a timezone-aware date-time for scheduling across timezones. */
    public static ZonedDateTime scheduleIn(
            String zoneId, int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of(zoneId));
    }

    /** Converts a zoned date-time to a different timezone. */
    public static ZonedDateTime convertTimezone(ZonedDateTime dateTime, String targetZone) {
        return dateTime.withZoneSameInstant(ZoneId.of(targetZone));
    }

    /** Creates an offset date-time for API/database storage. */
    public static OffsetDateTime toUtcOffset(ZonedDateTime dateTime) {
        return dateTime.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Converts an Instant to a ZonedDateTime in the given timezone. */
    public static ZonedDateTime toZoned(Instant instant, String zoneId) {
        return instant.atZone(ZoneId.of(zoneId));
    }

    // =========================================================================
    // DateTimeFormatter — thread-safe formatting and parsing
    // =========================================================================

    /** ISO 8601 format: "2024-03-15T10:30:00". */
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** Custom format: "March 15, 2024 at 10:30 AM". */
    public static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH);

    /** Compact format for filenames: "20240315_103000". */
    public static final DateTimeFormatter FILENAME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** RFC 1123 format for HTTP headers: "Fri, 15 Mar 2024 10:30:00 GMT". */
    public static final DateTimeFormatter HTTP = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** Formats a date-time using the display formatter. */
    public static String formatForDisplay(LocalDateTime dateTime) {
        return dateTime.format(DISPLAY);
    }

    /** Parses an ISO date string to LocalDate. */
    public static LocalDate parseDate(String text) {
        return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** Parses an ISO date-time string to LocalDateTime. */
    public static LocalDateTime parseDateTime(String text) {
        return LocalDateTime.parse(text, ISO);
    }

    /** Parses a zoned date-time from ISO 8601 with offset. */
    public static ZonedDateTime parseZoned(String text) {
        return ZonedDateTime.parse(text, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }
}
