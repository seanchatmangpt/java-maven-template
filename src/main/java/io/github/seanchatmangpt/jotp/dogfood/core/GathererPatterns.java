package io.github.seanchatmangpt.jotp.dogfood.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Gatherer;
import java.util.stream.Gatherers;

/**
 * Gatherer patterns — custom intermediate stream operations (Java 26).
 *
 * <p>Generated from {@code templates/java/core/gatherer.tera}.
 *
 * <p>{@link Gatherer} fills the gap between built-in intermediate operations ({@code map}, {@code
 * filter}, {@code flatMap}) and terminal collectors. Gatherers can maintain state, emit multiple
 * elements, and short-circuit.
 *
 * <h2>Built-in gatherers (java.util.stream.Gatherers):</h2>
 *
 * <ul>
 *   <li>{@code Gatherers.fold()} — stateful accumulation as intermediate op
 *   <li>{@code Gatherers.scan()} — running accumulation emitting each step
 *   <li>{@code Gatherers.windowFixed(n)} — fixed-size non-overlapping windows
 *   <li>{@code Gatherers.windowSliding(n)} — overlapping sliding windows
 *   <li>{@code Gatherers.mapConcurrent(n, fn)} — concurrent mapping with virtual threads
 * </ul>
 */
public final class GathererPatterns {

    private GathererPatterns() {}

    // =========================================================================
    // PATTERN 1: Fixed-size batching / chunking
    // =========================================================================
    public static <T> List<List<T>> batch(List<T> items, int batchSize) {
        return items.stream().gather(Gatherers.windowFixed(batchSize)).toList();
    }

    // =========================================================================
    // PATTERN 2: Sliding window for rolling calculations
    // =========================================================================
    public static <T> List<List<T>> slidingWindow(List<T> items, int windowSize) {
        return items.stream().gather(Gatherers.windowSliding(windowSize)).toList();
    }

    public static List<Double> movingAverage(List<Double> values, int windowSize) {
        return values.stream()
                .gather(Gatherers.windowSliding(windowSize))
                .map(
                        window ->
                                window.stream()
                                        .mapToDouble(Double::doubleValue)
                                        .average()
                                        .orElse(0.0))
                .toList();
    }

    // =========================================================================
    // PATTERN 3: Running scan (cumulative sum, running total)
    // =========================================================================
    public static List<Integer> runningSum(List<Integer> values) {
        return values.stream().gather(Gatherers.scan(() -> 0, Integer::sum)).toList();
    }

    public static <T, R> List<R> runningAccumulate(
            List<T> items, R identity, BiFunction<R, T, R> accumulator) {
        return items.stream().gather(Gatherers.scan(() -> identity, accumulator)).toList();
    }

    // =========================================================================
    // PATTERN 4: Fold as intermediate operation
    // =========================================================================
    public static <T> T foldToSingle(
            List<T> items, T identity, java.util.function.BinaryOperator<T> op) {
        return items.stream()
                .gather(Gatherers.fold(() -> identity, op))
                .findFirst()
                .orElse(identity);
    }

    // =========================================================================
    // PATTERN 5: Concurrent mapping with bounded virtual threads
    // =========================================================================
    public static <T, R> List<R> mapConcurrent(
            List<T> items, int maxConcurrency, Function<T, R> mapper) {
        return items.stream().gather(Gatherers.mapConcurrent(maxConcurrency, mapper)).toList();
    }

    // =========================================================================
    // PATTERN 6: Custom gatherer — deduplicate consecutive duplicates
    // =========================================================================
    public static <T> Gatherer<T, ?, T> deduplicateConsecutive() {
        return Gatherer.ofSequential(
                () ->
                        new Object() {
                            T last = null;
                            boolean hasLast = false;
                        },
                (state, element, downstream) -> {
                    if (!state.hasLast || !java.util.Objects.equals(state.last, element)) {
                        state.last = element;
                        state.hasLast = true;
                        return downstream.push(element);
                    }
                    return true;
                });
    }

    // =========================================================================
    // PATTERN 7: Custom gatherer — take while with count limit
    // =========================================================================
    public static <T> Gatherer<T, ?, T> takeWhileMax(Predicate<T> predicate, int maxCount) {
        return Gatherer.ofSequential(
                () ->
                        new Object() {
                            int count = 0;
                        },
                (state, element, downstream) -> {
                    if (state.count >= maxCount || !predicate.test(element)) {
                        return false;
                    }
                    state.count++;
                    return downstream.push(element);
                });
    }

    // =========================================================================
    // PATTERN 8: Custom gatherer — group consecutive elements by classifier
    // =========================================================================
    public static <T, K> Gatherer<T, ?, List<T>> groupConsecutiveBy(Function<T, K> classifier) {
        return Gatherer.ofSequential(
                () ->
                        new Object() {
                            K currentKey = null;
                            List<T> currentGroup = new ArrayList<>();
                        },
                (state, element, downstream) -> {
                    var key = classifier.apply(element);
                    if (state.currentGroup.isEmpty()
                            || java.util.Objects.equals(key, state.currentKey)) {
                        state.currentKey = key;
                        state.currentGroup.add(element);
                    } else {
                        var group = List.copyOf(state.currentGroup);
                        state.currentGroup.clear();
                        state.currentKey = key;
                        state.currentGroup.add(element);
                        return downstream.push(group);
                    }
                    return true;
                },
                (state, downstream) -> {
                    if (!state.currentGroup.isEmpty()) {
                        downstream.push(List.copyOf(state.currentGroup));
                    }
                });
    }

    // =========================================================================
    // PATTERN 9: Chaining gatherers with andThen
    // =========================================================================
    public static <T> List<List<T>> batchAndDeduplicate(List<T> items, int batchSize) {
        Gatherer<T, ?, T> dedup = deduplicateConsecutive();
        Gatherer<T, ?, List<T>> batched = dedup.andThen(Gatherers.windowFixed(batchSize));
        return items.stream().gather(batched).toList();
    }
}
