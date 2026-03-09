package org.acme.dogfood.patterns;

import java.util.Objects;

/**
 * Dogfood: rendered from templates/java/patterns/value-object-record.tera
 *
 * <p>Monetary value object using a Java record. Demonstrates the Value Object pattern:
 * identity based entirely on value, not reference; immutable; self-validating on construction.
 *
 * <p>Amounts are stored as {@code long cents} to avoid floating-point rounding errors.
 *
 * <p>BEFORE (legacy): mutable class with setter-based mutation.
 * AFTER (modern): immutable record; arithmetic returns new instances.
 */
public record Money(long cents, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.length() != 3)
            throw new IllegalArgumentException(
                    "currency must be a 3-char ISO 4217 code, got: " + currency);
        if (cents < 0)
            throw new IllegalArgumentException("cents must be non-negative, got " + cents);
    }

    /** Factory method: {@code Money.of(1050, "USD")} represents $10.50. */
    public static Money of(long cents, String currency) {
        return new Money(cents, currency);
    }

    /** Zero value in the given currency. */
    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    /** Add two monetary values. Throws if currencies differ. */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.cents + other.cents, currency);
    }

    /** Subtract {@code other} from this. Throws if the result would be negative. */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        long result = this.cents - other.cents;
        if (result < 0)
            throw new IllegalArgumentException(
                    "Subtraction would produce negative money: " + this + " - " + other);
        return new Money(result, currency);
    }

    /** Multiply by a non-negative integer factor. */
    public Money multiply(int factor) {
        if (factor < 0)
            throw new IllegalArgumentException("factor must be non-negative, got " + factor);
        return new Money(this.cents * factor, currency);
    }

    /** {@code true} when the amount is zero. */
    public boolean isZero() {
        return cents == 0;
    }

    /** {@code true} if this is more than {@code other} (same currency required). */
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.cents > other.cents;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency))
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
    }

    @Override
    public String toString() {
        return "%s %.2f".formatted(currency, cents / 100.0);
    }
}
