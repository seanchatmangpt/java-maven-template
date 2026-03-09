package org.acme.dogfood.patterns;

import java.time.Instant;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/** Tests for {@link OrderDto} — the dto-record template dogfood. */
class OrderDtoTest implements WithAssertions {

    @Test
    void create_validOrder_succeeds() {
        var order = new OrderDto("O1", "C1", 99.99, Instant.now());
        assertThat(order.id()).isEqualTo("O1");
        assertThat(order.customerId()).isEqualTo("C1");
        assertThat(order.total()).isEqualTo(99.99);
    }

    @Test
    void factory_of_setsCreatedAtNow() {
        var before = Instant.now();
        var order = OrderDto.of("O2", "C2", 10.0);
        var after = Instant.now();
        assertThat(order.createdAt()).isBetween(before, after);
    }

    @Test
    void withTotal_returnsNewRecord() {
        var order = OrderDto.of("O3", "C3", 5.0);
        var updated = order.withTotal(50.0);
        assertThat(updated.total()).isEqualTo(50.0);
        assertThat(updated.id()).isEqualTo("O3");
        assertThat(order.total()).isEqualTo(5.0); // original unchanged
    }

    @Test
    void hasValue_trueWhenTotalPositive() {
        assertThat(OrderDto.of("O4", "C4", 1.0).hasValue()).isTrue();
        assertThat(OrderDto.of("O5", "C5", 0.0).hasValue()).isFalse();
    }

    @Test
    void rejectNullId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrderDto(null, "C1", 0.0, Instant.now()));
    }

    @Test
    void rejectBlankId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderDto("  ", "C1", 0.0, Instant.now()));
    }

    @Test
    void rejectNegativeTotal() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderDto("O6", "C6", -1.0, Instant.now()));
    }

    @Test
    void recordEquality_sameParts_areEqual() {
        var ts = Instant.now();
        var a = new OrderDto("X", "Y", 5.0, ts);
        var b = new OrderDto("X", "Y", 5.0, ts);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
