package org.acme.dogfood.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StringMethodPatternsTest implements WithAssertions {

    // ── isBlank / isEmpty ────────────────────────────────────────────────────

    @Test
    void isBlank_returnsTrue_forWhitespaceOnly() {
        assertThat(StringMethodPatterns.isBlankExample("   ")).isTrue();
        assertThat(StringMethodPatterns.isBlankExample("")).isTrue();
        assertThat(StringMethodPatterns.isBlankExample("  \t\n  ")).isTrue();
    }

    @Test
    void isBlank_returnsFalse_forNonBlank() {
        assertThat(StringMethodPatterns.isBlankExample("hello")).isFalse();
        assertThat(StringMethodPatterns.isBlankExample(" x ")).isFalse();
    }

    @Test
    void isEmpty_returnsTrue_onlyForEmptyString() {
        assertThat(StringMethodPatterns.isEmptyExample("")).isTrue();
        assertThat(StringMethodPatterns.isEmptyExample("   ")).isFalse(); // blank but not empty
    }

    @Test
    void normalizeBlank_returnsEmpty_forBlankInput() {
        assertThat(StringMethodPatterns.normalizeBlank("")).isEmpty();
        assertThat(StringMethodPatterns.normalizeBlank("   ")).isEmpty();
        assertThat(StringMethodPatterns.normalizeBlank(null)).isEmpty();
    }

    @Test
    void normalizeBlank_returnsValue_forNonBlank() {
        assertThat(StringMethodPatterns.normalizeBlank("hello")).contains("hello");
    }

    // ── strip / trim ─────────────────────────────────────────────────────────

    @Test
    void strip_removesUnicodeWhitespace() {
        assertThat(StringMethodPatterns.stripExample("  hello  ")).isEqualTo("hello");
    }

    @Test
    void stripLeading_removesOnlyLeading() {
        assertThat(StringMethodPatterns.stripLeadingExample("  hi  ")).isEqualTo("hi  ");
    }

    @Test
    void stripTrailing_removesOnlyTrailing() {
        assertThat(StringMethodPatterns.stripTrailingExample("  hi  ")).isEqualTo("  hi");
    }

    // ── repeat ───────────────────────────────────────────────────────────────

    @Test
    void repeat_producesCorrectOutput() {
        assertThat(StringMethodPatterns.repeatExample("ab", 3)).isEqualTo("ababab");
        assertThat(StringMethodPatterns.repeatExample("x", 0)).isEmpty();
    }

    @Test
    void horizontalRule_producesCorrectWidth() {
        var rule = StringMethodPatterns.horizontalRule(10);
        assertThat(rule).hasSize(10).isEqualTo("-".repeat(10));
    }

    @Test
    void padLeft_rightAlignsString() {
        assertThat(StringMethodPatterns.padLeft("hi", 5)).isEqualTo("   hi");
        assertThat(StringMethodPatterns.padLeft("hello", 3)).isEqualTo("hello"); // no truncation
    }

    // ── indent / stripIndent ─────────────────────────────────────────────────

    @Test
    void indentExample_addsLeadingSpaces() {
        var result = StringMethodPatterns.indentExample("hello", 4);
        assertThat(result).startsWith("    hello");
    }

    @Test
    void stripIndentExample_removesCommonLeadingWhitespace() {
        var input = "    hello\n    world";
        var result = StringMethodPatterns.stripIndentExample(input);
        assertThat(result).doesNotContain("    hello"); // leading stripped
    }

    // ── formatted ────────────────────────────────────────────────────────────

    @Test
    void formatted_interpolatesCorrectly() {
        var result = StringMethodPatterns.formattedExample("Alice", 3);
        assertThat(result).isEqualTo("Hello, Alice! You have 3 messages.");
    }

    // ── chars / codePoints ───────────────────────────────────────────────────

    @Test
    void countVowels_countsCorrectly() {
        assertThat(StringMethodPatterns.countVowels("hello world")).isEqualTo(3L);
        assertThat(StringMethodPatterns.countVowels("AEIOU")).isEqualTo(5L);
        assertThat(StringMethodPatterns.countVowels("xyz")).isZero();
    }

    @Test
    void codePointsExample_returnsOneEntryPerChar() {
        var result = StringMethodPatterns.codePointsExample("abc");
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void isAsciiPrintable_trueForAscii() {
        assertThat(StringMethodPatterns.isAsciiPrintable("Hello!")).isTrue();
        assertThat(StringMethodPatterns.isAsciiPrintable("caf\u00e9")).isFalse(); // é is non-ASCII
    }

    // ── transform ────────────────────────────────────────────────────────────

    @Test
    void transformExample_slugifiesInput() {
        var result = StringMethodPatterns.transformExample("  The Quick Brown Fox  ");
        assertThat(result).isEqualTo("quick-brown-fox");
    }

    @Test
    void transformParseExample_parsesInteger() {
        assertThat(StringMethodPatterns.transformParseExample("  42  ")).isEqualTo(42);
    }

    // ── Files integration ─────────────────────────────────────────────────────

    @Test
    void readWriteFile_roundTrip(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("test.txt");
        var content = "hello java 26";
        StringMethodPatterns.writeStringToFile(file, content);
        assertThat(StringMethodPatterns.readFileAsString(file)).isEqualTo(content);
    }

    @Test
    void readFileAsString_throwsForMissing(@TempDir Path tmp) {
        assertThatThrownBy(() -> StringMethodPatterns.readFileAsString(tmp.resolve("missing.txt")))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    // ── join ─────────────────────────────────────────────────────────────────

    @Test
    void joinExample_joinsWithComma() {
        assertThat(StringMethodPatterns.joinExample(List.of("a", "b", "c"))).isEqualTo("a, b, c");
        assertThat(StringMethodPatterns.joinExample(List.of())).isEmpty();
    }

    @Test
    void joinWithBrackets_wrapsInBrackets() {
        assertThat(StringMethodPatterns.joinWithBrackets(List.of("x", "y")))
                .isEqualTo("[x, y]");
        assertThat(StringMethodPatterns.joinWithBrackets(List.of())).isEqualTo("[]");
    }

    // ── lines ────────────────────────────────────────────────────────────────

    @Test
    void nonBlankLines_filtersBlankLines() {
        var input = "line1\n\n  \nline2\nline3\n";
        assertThat(StringMethodPatterns.nonBlankLines(input))
                .containsExactly("line1", "line2", "line3");
    }

    @Test
    void parseConfig_parsesKeyValuePairs() {
        var config = """
                # comment
                host = localhost
                port = 8080

                name = my-app
                """;
        var result = StringMethodPatterns.parseConfig(config);
        assertThat(result)
                .containsEntry("host", "localhost")
                .containsEntry("port", "8080")
                .containsEntry("name", "my-app")
                .doesNotContainKey("# comment");
    }
}
