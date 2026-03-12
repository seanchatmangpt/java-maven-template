package io.github.seanchatmangpt.jotp.dogfood.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Optional;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import io.github.seanchatmangpt.jotp.dogfood.core.PatternMatchingPatterns.Payment;

/**
 * Tests for Java 26 pattern matching patterns in {@link PatternMatchingPatterns}.
 *
 * <p>Validates switch expression patterns:
 *
 * <ul>
 *   <li>Exhaustive switch with record destructuring
 *   <li>Guarded patterns (when clause)
 *   <li>instanceof pattern matching
 *   <li>Switch expression as value
 *   <li>Null-safe switch
 * </ul>
 *
 * @see PatternMatchingPatterns
 */
class PatternMatchingPatternsTest implements WithAssertions {

    // ── Pattern 1: Exhaustive switch with record destructuring ────────────────

    @Test
    void describe_creditCard_masksNumber() {
        var cc = new Payment.CreditCard("1234567890123456", "Alice", 123, 5000);
        var desc = PatternMatchingPatterns.describe(cc);
        assertThat(desc).contains("Credit card");
        assertThat(desc).contains("Alice");
        assertThat(desc).contains("************3456"); // masked
        assertThat(desc).doesNotContain("1234567890123456"); // not visible
    }

    @Test
    void describe_bankTransfer_showsIbanAndBic() {
        var bt = new Payment.BankTransfer("DE89370400440532013000", "DEUTDEFF", "Alice Corp");
        var desc = PatternMatchingPatterns.describe(bt);
        assertThat(desc).contains("Bank transfer");
        assertThat(desc).contains("DE89370400440532013000");
        assertThat(desc).contains("DEUTDEFF");
    }

    @Test
    void describe_cryptoPay_masksWallet() {
        var cp = new Payment.CryptoPay("0xABCDEF1234567890", "ETH", 1.5);
        var desc = PatternMatchingPatterns.describe(cp);
        assertThat(desc).contains("0xAB...7890"); // masked (showing first 6 chars + ...)
        assertThat(desc).contains("ETH");
        assertThat(desc).contains("1.5000"); // amount with 4 decimals
    }

    @Test
    void describe_voucher_showsCodeAndValue() {
        var v = new Payment.Voucher("VOUCHER-2024", 100.0, true);
        var desc = PatternMatchingPatterns.describe(v);
        assertThat(desc).contains("VOUCHER-2024");
        assertThat(desc).contains("100.00");
        assertThat(desc).contains("single-use");
    }

    @Test
    void describe_voucherMultiUse_showsMultiUse() {
        var v = new Payment.Voucher("MULTI-2024", 50.0, false);
        var desc = PatternMatchingPatterns.describe(v);
        assertThat(desc).contains("multi-use");
    }

    // ── Pattern 2: Guarded patterns (when clause) ──────────────────────────────

    @Test
    void riskLevel_creditCardHighLimit_isHigh() {
        var cc = new Payment.CreditCard("1234", "Bob", 456, 15_000);
        assertThat(PatternMatchingPatterns.riskLevel(cc)).isEqualTo("HIGH");
    }

    @Test
    void riskLevel_creditCardMediumLimit_isMedium() {
        var cc = new Payment.CreditCard("1234", "Bob", 456, 5_000);
        assertThat(PatternMatchingPatterns.riskLevel(cc)).isEqualTo("MEDIUM");
    }

    @Test
    void riskLevel_creditCardLowLimit_isLow() {
        var cc = new Payment.CreditCard("1234", "Bob", 456, 500);
        assertThat(PatternMatchingPatterns.riskLevel(cc)).isEqualTo("LOW");
    }

    @Test
    void riskLevel_cryptoHighAmount_isHigh() {
        var cp = new Payment.CryptoPay("wallet", "BTC", 10_000);
        assertThat(PatternMatchingPatterns.riskLevel(cp)).isEqualTo("HIGH");
    }

    @Test
    void riskLevel_cryptoLowAmount_isMedium() {
        var cp = new Payment.CryptoPay("wallet", "ETH", 100);
        assertThat(PatternMatchingPatterns.riskLevel(cp)).isEqualTo("MEDIUM");
    }

    @Test
    void riskLevel_bankTransfer_isLow() {
        var bt = new Payment.BankTransfer("IBAN", "BIC", "Name");
        assertThat(PatternMatchingPatterns.riskLevel(bt)).isEqualTo("LOW");
    }

    @Test
    void riskLevel_voucherHighValue_isMedium() {
        var v = new Payment.Voucher("CODE", 600, true);
        assertThat(PatternMatchingPatterns.riskLevel(v)).isEqualTo("MEDIUM");
    }

    @Test
    void riskLevel_voucherLowValue_isLow() {
        var v = new Payment.Voucher("CODE", 50, true);
        assertThat(PatternMatchingPatterns.riskLevel(v)).isEqualTo("LOW");
    }

    // ── Pattern 3: instanceof pattern matching ─────────────────────────────────

    @Test
    void extractFee_fromDouble_returnsValue() {
        var result = PatternMatchingPatterns.extractFee(25.5);
        assertThat(result).contains(25.5);
    }

    @Test
    void extractFee_fromInteger_convertsToDouble() {
        var result = PatternMatchingPatterns.extractFee(42);
        assertThat(result).contains(42.0);
    }

    @Test
    void extractFee_fromValidString_parsesValue() {
        var result = PatternMatchingPatterns.extractFee("  123.45  ");
        assertThat(result).contains(123.45);
    }

    @Test
    void extractFee_fromNegativeDouble_returnsEmpty() {
        var result = PatternMatchingPatterns.extractFee(-10.0);
        assertThat(result).isEmpty();
    }

    @Test
    void extractFee_fromNegativeInteger_returnsEmpty() {
        var result = PatternMatchingPatterns.extractFee(-5);
        assertThat(result).isEmpty();
    }

    @Test
    void extractFee_fromInvalidString_returnsEmpty() {
        var result = PatternMatchingPatterns.extractFee("not a number");
        assertThat(result).isEmpty();
    }

    @Test
    void extractFee_fromBlankString_returnsEmpty() {
        var result = PatternMatchingPatterns.extractFee("   ");
        assertThat(result).isEmpty();
    }

    @Test
    void extractFee_fromUnsupportedType_returnsEmpty() {
        var result = PatternMatchingPatterns.extractFee(new Object());
        assertThat(result).isEmpty();
    }

    // ── Pattern 4: Switch expression as value ──────────────────────────────────

    @Test
    void processingFee_creditCardHighLimit_lowerRate() {
        var cc = new Payment.CreditCard("1234", "Alice", 123, 10_000);
        var fee = PatternMatchingPatterns.processingFee(cc, 1000);
        assertThat(fee).isEqualTo(25.0); // 2.5% rate
    }

    @Test
    void processingFee_creditCardNormalLimit_standardRate() {
        var cc = new Payment.CreditCard("1234", "Alice", 123, 1000);
        var fee = PatternMatchingPatterns.processingFee(cc, 1000);
        assertThat(fee).isEqualTo(30.0); // 3% rate
    }

    @Test
    void processingFee_bankTransfer_lowRate() {
        var bt = new Payment.BankTransfer("IBAN", "BIC", "Name");
        var fee = PatternMatchingPatterns.processingFee(bt, 1000);
        assertThat(fee).isEqualTo(1.0); // 0.1% rate
    }

    @Test
    void processingFee_cryptoBtc_lowerRate() {
        var cp = new Payment.CryptoPay("wallet", "BTC", 1);
        var fee = PatternMatchingPatterns.processingFee(cp, 1000);
        assertThat(fee).isEqualTo(10.0); // 1% rate
    }

    @Test
    void processingFee_cryptoOther_standardRate() {
        var cp = new Payment.CryptoPay("wallet", "ETH", 1);
        var fee = PatternMatchingPatterns.processingFee(cp, 1000);
        assertThat(fee).isEqualTo(15.0); // 1.5% rate
    }

    @Test
    void processingFee_voucher_noFee() {
        var v = new Payment.Voucher("CODE", 100, true);
        var fee = PatternMatchingPatterns.processingFee(v, 1000);
        assertThat(fee).isZero();
    }

    // ── Pattern 5: Null-safe switch ────────────────────────────────────────────

    @Test
    void route_nullPayment_rejected() {
        assertThat(PatternMatchingPatterns.route(null)).isEqualTo("rejected: null payment");
    }

    @Test
    void route_creditCard_cardProcessor() {
        var cc = new Payment.CreditCard("1234", "Alice", 123, 1000);
        assertThat(PatternMatchingPatterns.route(cc)).isEqualTo("card-processor");
    }

    @Test
    void route_bankTransfer_sepaGateway() {
        var bt = new Payment.BankTransfer("IBAN", "BIC", "Name");
        assertThat(PatternMatchingPatterns.route(bt)).isEqualTo("sepa-gateway");
    }

    @Test
    void route_cryptoPay_cryptoExchange() {
        var cp = new Payment.CryptoPay("wallet", "BTC", 1);
        assertThat(PatternMatchingPatterns.route(cp)).isEqualTo("crypto-exchange");
    }

    @Test
    void route_voucher_voucherService() {
        var v = new Payment.Voucher("CODE", 100, true);
        assertThat(PatternMatchingPatterns.route(v)).isEqualTo("voucher-service");
    }
}
