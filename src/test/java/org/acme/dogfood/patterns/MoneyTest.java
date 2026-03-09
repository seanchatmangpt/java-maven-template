package org.acme.dogfood.patterns;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/** Tests for {@link Money} — the value-object-record template dogfood. */
class MoneyTest implements WithAssertions {

    @Test
    void create_validMoney_succeeds() {
        var m = Money.of(1050, "USD");
        assertThat(m.cents()).isEqualTo(1050);
        assertThat(m.currency()).isEqualTo("USD");
    }

    @Test
    void zero_isZero() {
        assertThat(Money.zero("EUR").isZero()).isTrue();
        assertThat(Money.of(1, "EUR").isZero()).isFalse();
    }

    @Test
    void add_sameCurrency_sumsCorrectly() {
        var a = Money.of(500, "USD");
        var b = Money.of(300, "USD");
        assertThat(a.add(b)).isEqualTo(Money.of(800, "USD"));
    }

    @Test
    void add_differentCurrency_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(100, "USD").add(Money.of(100, "EUR")));
    }

    @Test
    void subtract_validAmount_returnsRemainder() {
        var result = Money.of(1000, "USD").subtract(Money.of(300, "USD"));
        assertThat(result).isEqualTo(Money.of(700, "USD"));
    }

    @Test
    void subtract_tooMuch_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(100, "USD").subtract(Money.of(200, "USD")));
    }

    @Test
    void multiply_byFactor_scales() {
        assertThat(Money.of(100, "USD").multiply(3)).isEqualTo(Money.of(300, "USD"));
    }

    @Test
    void multiply_byZero_givesZero() {
        assertThat(Money.of(999, "USD").multiply(0)).isEqualTo(Money.zero("USD"));
    }

    @Test
    void multiply_negativeFactory_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(100, "USD").multiply(-1));
    }

    @Test
    void isGreaterThan_comparesCorrectly() {
        assertThat(Money.of(200, "USD").isGreaterThan(Money.of(100, "USD"))).isTrue();
        assertThat(Money.of(50, "USD").isGreaterThan(Money.of(100, "USD"))).isFalse();
    }

    @Test
    void rejectNegativeCents() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(-1, "USD"));
    }

    @Test
    void rejectInvalidCurrencyCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(0, "US")); // too short
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of(0, "USDX")); // too long
    }

    @Test
    void recordEquality_sameParts_areEqual() {
        assertThat(Money.of(100, "USD")).isEqualTo(Money.of(100, "USD"));
        assertThat(Money.of(100, "USD")).isNotEqualTo(Money.of(200, "USD"));
    }

    @Test
    void toString_formatsReadably() {
        assertThat(Money.of(1050, "USD").toString()).isEqualTo("USD 10.50");
    }
}
