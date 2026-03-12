package io.github.seanchatmangpt.jotp.dogfood.patterns;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Dogfood: rendered from templates/java/patterns/strategy-functional.tera
 *
 * <p>Interchangeable text transformation algorithms via functional interfaces and lambdas.
 */
public final class TextTransformStrategy {

    private TextTransformStrategy() {}

    /**
     * Strategy functional interface.
     *
     * <p>BEFORE (legacy): Abstract Strategy class with concrete subclasses.
     *
     * <p>AFTER (modern): {@code @FunctionalInterface} assignable from lambdas/method refs.
     */
    @FunctionalInterface
    public interface Transform<I, O> extends Function<I, O> {

        O execute(I input);

        @Override
        default O apply(I input) {
            return execute(input);
        }

        default <V> Transform<I, V> andThen(Transform<O, V> after) {
            Objects.requireNonNull(after);
            return input -> after.execute(this.execute(input));
        }

        default <V> Transform<V, O> compose(Transform<V, I> before) {
            Objects.requireNonNull(before);
            return input -> this.execute(before.execute(input));
        }
    }

    // Built-in strategies
    public static final Transform<String, String> UPPER_CASE = String::toUpperCase;

    public static final Transform<String, String> LOWER_CASE = String::toLowerCase;

    public static final Transform<String, String> REVERSE =
            input -> new StringBuilder(input).reverse().toString();

    public static final Transform<String, String> TRIM = String::strip;

    // Strategy registry
    private static final Map<String, Transform<String, String>> REGISTRY =
            Map.ofEntries(
                    Map.entry("upper_case", UPPER_CASE),
                    Map.entry("lower_case", LOWER_CASE),
                    Map.entry("reverse", REVERSE),
                    Map.entry("trim", TRIM));

    /**
     * Looks up a strategy by name.
     */
    public static Optional<Transform<String, String>> lookup(String name) {
        return Optional.ofNullable(REGISTRY.get(name.toLowerCase()));
    }

    /**
     * Executes the named strategy on the given input.
     */
    public static String execute(String strategyName, String input) {
        return lookup(strategyName)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown strategy: "
                                                + strategyName
                                                + ". Available: "
                                                + REGISTRY.keySet()))
                .execute(input);
    }
}
