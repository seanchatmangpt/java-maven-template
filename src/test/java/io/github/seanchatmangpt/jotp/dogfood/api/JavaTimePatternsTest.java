package io.github.seanchatmangpt.jotp.dogfood.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class JavaTimePatternsTest implements WithAssertions {

    // ── LocalDate ────────────────────────────────────────────────────────────

    @Test
    void createDate_returnsCorrectDate() {
        var date = JavaTimePatterns.createDate(2024, 3, 15);
        assertThat(date.getYear()).isEqualTo(2024);
        assertThat(date.getMonthValue()).isEqualTo(3);
        assertThat(date.getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void nextBusinessDay_skipsWeekends() {
        // Friday -> Monday
        var friday = LocalDate.of(2024, 3, 15);
        var next = JavaTimePatterns.nextBusinessDay(friday);
        assertThat(next).isEqualTo(LocalDate.of(2024, 3, 18));

        // Saturday -> Monday
        var saturday = LocalDate.of(2024, 3, 16);
        next = JavaTimePatterns.nextBusinessDay(saturday);
        assertThat(next).isEqualTo(LocalDate.of(2024, 3, 18));

        // Monday -> Tuesday
        var monday = LocalDate.of(2024, 3, 18);
        next = JavaTimePatterns.nextBusinessDay(monday);
        assertThat(next).isEqualTo(LocalDate.of(2024, 3, 19));
    }

    @Test
    void lastDayOfMonth_returnsCorrectDate() {
        var date = LocalDate.of(2024, 2, 10); // Leap year
        assertThat(JavaTimePatterns.lastDayOfMonth(date)).isEqualTo(LocalDate.of(2024, 2, 29));

        date = LocalDate.of(2024, 4, 15);
        assertThat(JavaTimePatterns.lastDayOfMonth(date)).isEqualTo(LocalDate.of(2024, 4, 30));
    }

    // ── LocalDateTime ─────────────────────────────────────────────────────────

    @Test
    void createDateTime_returnsCorrectDateTime() {
        var dt = JavaTimePatterns.createDateTime(2024, 3, 15, 10, 30);
        assertThat(dt.getYear()).isEqualTo(2024);
        assertThat(dt.getMonthValue()).isEqualTo(3);
        assertThat(dt.getDayOfMonth()).isEqualTo(15);
        assertThat(dt.getHour()).isEqualTo(10);
        assertThat(dt.getMinute()).isEqualTo(30);
    }

    @Test
    void startOfHour_truncatesToHour() {
        var dt = LocalDateTime.of(2024, 3, 15, 10, 30, 45, 123_456_789);
        var truncated = JavaTimePatterns.startOfHour(dt);
        assertThat(truncated.getHour()).isEqualTo(10);
        assertThat(truncated.getMinute()).isZero();
        assertThat(truncated.getSecond()).isZero();
    }

    // ── Instant ──────────────────────────────────────────────────────────────

    @Test
    void now_returnsCurrentInstant() {
        var before = Instant.now();
        var now = JavaTimePatterns.now();
        var after = Instant.now();
        assertThat(now).isBetween(before, after);
    }

    @Test
    void now_withClock_returnsFixedInstant() {
        var fixed = Instant.parse("2024-03-15T10:30:00Z");
        var clock = Clock.fixed(fixed, ZoneId.of("UTC"));
        assertThat(JavaTimePatterns.now(clock)).isEqualTo(fixed);
    }

    @Test
    void isWithin_returnsTrueForWithinWindow() {
        var a = Instant.parse("2024-03-15T10:00:00Z");
        var b = Instant.parse("2024-03-15T10:00:05Z");
        var window = Duration.ofSeconds(10);
        assertThat(JavaTimePatterns.isWithin(a, b, window)).isTrue();
    }

    @Test
    void isWithin_returnsFalseForOutsideWindow() {
        var a = Instant.parse("2024-03-15T10:00:00Z");
        var b = Instant.parse("2024-03-15T10:01:00Z");
        var window = Duration.ofSeconds(10);
        assertThat(JavaTimePatterns.isWithin(a, b, window)).isFalse();
    }

    // ── Duration & Period ─────────────────────────────────────────────────────

    @Test
    void timeout_createsCorrectDuration() {
        var dur = JavaTimePatterns.timeout(30);
        assertThat(dur).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void subscriptionLength_createsCorrectPeriod() {
        var period = JavaTimePatterns.subscriptionLength(12);
        assertThat(period).isEqualTo(Period.ofMonths(12));
    }

    @Test
    void age_calculatesCorrectly() {
        var birth = LocalDate.of(1990, 6, 15);
        var asOf = LocalDate.of(2024, 6, 16);
        assertThat(JavaTimePatterns.age(birth, asOf)).isEqualTo(34);
    }

    @Test
    void daysBetween_calculatesCorrectly() {
        var start = LocalDate.of(2024, 3, 1);
        var end = LocalDate.of(2024, 3, 15);
        assertThat(JavaTimePatterns.daysBetween(start, end)).isEqualTo(14);
    }

    // ── ZonedDateTime & OffsetDateTime ────────────────────────────────────────

    @Test
    void scheduleIn_createsZonedDateTime() {
        var zdt = JavaTimePatterns.scheduleIn("America/New_York", 2024, 3, 15, 10, 30);
        assertThat(zdt.getZone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(zdt.getYear()).isEqualTo(2024);
    }

    @Test
    void convertTimezone_preservesInstant() {
        var ny = JavaTimePatterns.scheduleIn("America/New_York", 2024, 3, 15, 10, 30);
        var tokyo = JavaTimePatterns.convertTimezone(ny, "Asia/Tokyo");
        assertThat(ny.toInstant()).isEqualTo(tokyo.toInstant());
    }

    @Test
    void toUtcOffset_convertsToUtc() {
        var ny = JavaTimePatterns.scheduleIn("America/New_York", 2024, 3, 15, 10, 30);
        var utc = JavaTimePatterns.toUtcOffset(ny);
        assertThat(utc.getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void toZoned_convertsInstantToZone() {
        var instant = Instant.parse("2024-03-15T15:30:00Z");
        var zdt = JavaTimePatterns.toZoned(instant, "America/New_York");
        assertThat(zdt.getZone()).isEqualTo(ZoneId.of("America/New_York"));
    }

    // ── DateTimeFormatter ─────────────────────────────────────────────────────

    @Test
    void formatForDisplay_formatsCorrectly() {
        var dt = LocalDateTime.of(2024, 3, 15, 10, 30);
        var formatted = JavaTimePatterns.formatForDisplay(dt);
        assertThat(formatted).contains("March").contains("2024");
    }

    @Test
    void parseDate_parsesIsoDate() {
        var date = JavaTimePatterns.parseDate("2024-03-15");
        assertThat(date).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    @Test
    void parseDateTime_parsesIsoDateTime() {
        var dt = JavaTimePatterns.parseDateTime("2024-03-15T10:30:00");
        assertThat(dt).isEqualTo(LocalDateTime.of(2024, 3, 15, 10, 30));
    }

    @Test
    void parseZoned_parsesIsoZonedDateTime() {
        var zdt = JavaTimePatterns.parseZoned("2024-03-15T10:30:00-04:00[America/New_York]");
        assertThat(zdt.getZone()).isEqualTo(ZoneId.of("America/New_York"));
    }
}
