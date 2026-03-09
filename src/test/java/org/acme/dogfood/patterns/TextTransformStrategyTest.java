package org.acme.dogfood.patterns;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: tests for TextTransformStrategy generated from patterns/strategy-functional.tera.
 */
@DisplayName("TextTransformStrategy")
class TextTransformStrategyTest implements WithAssertions {

    @Test
    @DisplayName("UPPER_CASE transforms to uppercase")
    void upperCaseTransforms() {
        assertThat(TextTransformStrategy.UPPER_CASE.execute("hello")).isEqualTo("HELLO");
    }

    @Test
    @DisplayName("LOWER_CASE transforms to lowercase")
    void lowerCaseTransforms() {
        assertThat(TextTransformStrategy.LOWER_CASE.execute("HELLO")).isEqualTo("hello");
    }

    @Test
    @DisplayName("REVERSE reverses the string")
    void reverseTransforms() {
        assertThat(TextTransformStrategy.REVERSE.execute("hello")).isEqualTo("olleh");
    }

    @Test
    @DisplayName("TRIM strips whitespace")
    void trimTransforms() {
        assertThat(TextTransformStrategy.TRIM.execute("  hello  ")).isEqualTo("hello");
    }

    @Test
    @DisplayName("lookup finds registered strategies")
    void lookupFindsStrategies() {
        assertThat(TextTransformStrategy.lookup("upper_case")).isPresent();
        assertThat(TextTransformStrategy.lookup("UPPER_CASE")).isPresent();
        assertThat(TextTransformStrategy.lookup("unknown")).isEmpty();
    }

    @Test
    @DisplayName("execute runs strategy by name")
    void executeRunsByName() {
        assertThat(TextTransformStrategy.execute("reverse", "abc")).isEqualTo("cba");
    }

    @Test
    @DisplayName("execute throws for unknown strategy")
    void executeThrowsForUnknown() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TextTransformStrategy.execute("nope", "abc"))
                .withMessageContaining("Unknown strategy");
    }

    @Test
    @DisplayName("andThen chains strategies")
    void andThenChainsStrategies() {
        var trimThenUpper = TextTransformStrategy.TRIM.andThen(TextTransformStrategy.UPPER_CASE);
        assertThat(trimThenUpper.execute("  hello  ")).isEqualTo("HELLO");
    }

    @Test
    @DisplayName("compose chains strategies in reverse order")
    void composeChainsInReverse() {
        var upperThenReverse =
                TextTransformStrategy.REVERSE.compose(TextTransformStrategy.UPPER_CASE);
        assertThat(upperThenReverse.execute("hello")).isEqualTo("OLLEH");
    }

    @Test
    @DisplayName("strategies work as Function via apply")
    void strategiesWorkAsFunction() {
        var result = java.util.List.of("Hello", "World").stream()
                .map(TextTransformStrategy.UPPER_CASE)
                .toList();
        assertThat(result).containsExactly("HELLO", "WORLD");
    }
}
