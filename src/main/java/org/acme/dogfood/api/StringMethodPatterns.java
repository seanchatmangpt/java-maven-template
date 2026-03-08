package org.acme.dogfood.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Dogfood: rendered from templates/java/api/string-methods.tera
 *
 * <p>Modern Java String API methods and migration patterns.
 */
public final class StringMethodPatterns {

    private StringMethodPatterns() {}

    // isBlank() vs isEmpty() (Java 11+)
    public static boolean isBlankExample(String str) {
        return str.isBlank();
    }

    public static boolean isEmptyExample(String str) {
        return str.isEmpty();
    }

    public static Optional<String> normalizeBlank(String input) {
        return Optional.ofNullable(input).filter(s -> !s.isBlank());
    }

    // strip() vs trim() — Unicode-aware (Java 11+)
    public static String stripExample(String str) {
        return str.strip();
    }

    public static String stripLeadingExample(String str) {
        return str.stripLeading();
    }

    public static String stripTrailingExample(String str) {
        return str.stripTrailing();
    }

    // repeat(n) (Java 11+)
    public static String repeatExample(String str, int count) {
        return str.repeat(count);
    }

    public static String horizontalRule(int width) {
        return "-".repeat(width);
    }

    public static String padLeft(String str, int totalWidth) {
        int padding = Math.max(0, totalWidth - str.length());
        return " ".repeat(padding) + str;
    }

    // indent(n) and stripIndent() (Java 12+)
    public static String indentExample(String text, int spaces) {
        return text.indent(spaces);
    }

    public static String stripIndentExample(String text) {
        return text.stripIndent();
    }

    // formatted() (Java 15+)
    public static String formattedExample(String name, int count) {
        return "Hello, %s! You have %d messages.".formatted(name, count);
    }

    // chars() and codePoints() streams (Java 9+)
    public static long countVowels(String str) {
        return str.chars()
                .mapToObj(c -> (char) c)
                .filter(c -> "aeiouAEIOU".indexOf(c) >= 0)
                .count();
    }

    public static List<String> codePointsExample(String str) {
        return str.codePoints().mapToObj(Character::toString).toList();
    }

    public static boolean isAsciiPrintable(String str) {
        return str.codePoints().allMatch(cp -> cp >= 0x20 && cp <= 0x7E);
    }

    // transform() pipeline (Java 12+)
    public static String transformExample(String input) {
        return input.strip()
                .toLowerCase()
                .transform(s -> s.startsWith("the ") ? s.substring(4) : s)
                .transform(s -> s.replaceAll("\\s+", "-"));
    }

    public static int transformParseExample(String input) {
        return input.strip().transform(Integer::parseInt);
    }

    // Files.readString() and writeString() (Java 11+)
    public static String readFileAsString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeStringToFile(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // String.join() and Collectors.joining()
    public static String joinExample(List<String> items) {
        return String.join(", ", items);
    }

    public static String joinWithBrackets(List<String> items) {
        return items.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    // lines() — Stream of lines (Java 11+)
    public static List<String> nonBlankLines(String text) {
        return text.lines().filter(line -> !line.isBlank()).toList();
    }

    public static java.util.Map<String, String> parseConfig(String text) {
        return text.lines()
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(parts -> parts[0].strip(), parts -> parts[1].strip()));
    }
}
