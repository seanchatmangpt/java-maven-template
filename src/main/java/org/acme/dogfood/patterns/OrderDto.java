package org.acme.dogfood.patterns;

import java.time.Instant;
import java.util.Objects;

/**
 * Dogfood: rendered from templates/java/patterns/dto-record.tera
 *
 * <p>Data transfer object for an order — demonstrates the modern Java record-based DTO pattern.
 * Records eliminate the boilerplate of hand-written equals/hashCode/toString while also being
 * immutable by construction.
 *
 * <p>BEFORE (legacy): POJO with getters, manual equals/hashCode/toString.
 * AFTER (modern): record with compact constructor for validation.
 */
public record OrderDto(String id, String customerId, double total, Instant createdAt) {

    public OrderDto {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (customerId.isBlank()) throw new IllegalArgumentException("customerId must not be blank");
        if (total < 0) throw new IllegalArgumentException("total must be non-negative, got " + total);
    }

    /** Convenience factory: creates an order with {@code createdAt = Instant.now()}. */
    public static OrderDto of(String id, String customerId, double total) {
        return new OrderDto(id, customerId, total, Instant.now());
    }

    /** Returns a new {@code OrderDto} with the total adjusted by {@code delta}. */
    public OrderDto withTotal(double newTotal) {
        return new OrderDto(id, customerId, newTotal, createdAt);
    }

    /** {@code true} when the order has a non-zero total. */
    public boolean hasValue() {
        return total > 0;
    }
}
