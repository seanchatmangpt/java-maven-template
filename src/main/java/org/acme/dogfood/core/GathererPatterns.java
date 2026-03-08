package org.acme.dogfood.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Gatherer patterns — custom intermediate stream operations (Java 21-compatible).
 *
 * <p>Generated from {@code templates/java/core/gatherer.tera}.
 *
 * <p>Provides batching, windowing, scanning, folding, and concurrent mapping via list-based
 * implementations that are compatible with Java 21. The equivalent Java 22+ Gatherers API
 * ({@code java.util.stream.Gatherers}) offers a declarative stream-pipeline variant; these methods
 * deliver the same semantics without requiring Java 22.
 */
public final class GathererPatterns {

    private GathererPatterns() {}

    // =========================================================================
    // PATTERN 1: Fixed-size batching / chunking
    // =========================================================================
    public static <T> List<List<T>> batch(List<T> items, int batchSize) {
        var result = new ArrayList<List<T>>();
        for (int i = 0; i < items.size(); i += batchSize) {
            result.add(List.copyOf(items.subList(i, Math.min(i + batchSize, items.size()))));
        }
        return List.copyOf(result);
    }

    // =========================================================================
    // PATTERN 2: Sliding window for rolling calculations
    // =========================================================================
    public static <T> List<List<T>> slidingWindow(List<T> items, int windowSize) {
        var result = new ArrayList<List<T>>();
        for (int i = 0; i <= items.size() - windowSize; i++) {
            result.add(List.copyOf(items.subList(i, i + windowSize)));
        }
        return List.copyOf(result);
    }

    public static List<Double> movingAverage(List<Double> values, int windowSize) {
        return slidingWindow(values, windowSize).stream()
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
        var result = new ArrayList<Integer>(values.size());
        int sum = 0;
        for (var v : values) {
            sum += v;
            result.add(sum);
        }
        return List.copyOf(result);
    }

    public static <T, R> List<R> runningAccumulate(
            List<T> items, R identity, BiFunction<R, T, R> accumulator) {
        var result = new ArrayList<R>(items.size());
        R acc = identity;
        for (var item : items) {
            acc = accumulator.apply(acc, item);
            result.add(acc);
        }
        return List.copyOf(result);
    }

    // =========================================================================
    // PATTERN 4: Fold as intermediate operation
    // =========================================================================
    public static <T> T foldToSingle(List<T> items, T identity, BinaryOperator<T> op) {
        return items.stream().reduce(identity, op);
    }

    // =========================================================================
    // PATTERN 5: Concurrent mapping with bounded virtual threads
    // =========================================================================
    public static <T, R> List<R> mapConcurrent(
            List<T> items, int maxConcurrency, Function<T, R> mapper) {
        try (var executor =
                Executors.newFixedThreadPool(maxConcurrency, Thread.ofVirtual().factory())) {
            List<Future<R>> futures =
                    items.stream().map(item -> executor.submit(() -> mapper.apply(item))).toList();
            return futures.stream()
                    .map(
                            f -> {
                                try {
                                    return f.get();
                                } catch (InterruptedException | ExecutionException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                    .toList();
        }
    }

    // =========================================================================
    // PATTERN 6: Deduplicate consecutive duplicates
    // =========================================================================
    public static <T> List<T> deduplicateConsecutive(List<T> items) {
        var result = new ArrayList<T>();
        T last = null;
        boolean hasLast = false;
        for (var item : items) {
            if (!hasLast || !Objects.equals(last, item)) {
                last = item;
                hasLast = true;
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    // =========================================================================
    // PATTERN 7: Take while with count limit
    // =========================================================================
    public static <T> List<T> takeWhileMax(List<T> items, Predicate<T> predicate, int maxCount) {
        var result = new ArrayList<T>();
        for (var item : items) {
            if (result.size() >= maxCount || !predicate.test(item)) break;
            result.add(item);
        }
        return List.copyOf(result);
    }

    // =========================================================================
    // PATTERN 8: Group consecutive elements by classifier
    // =========================================================================
    public static <T, K> List<List<T>> groupConsecutiveBy(
            List<T> items, Function<T, K> classifier) {
        var result = new ArrayList<List<T>>();
        K currentKey = null;
        var currentGroup = new ArrayList<T>();
        for (var item : items) {
            var key = classifier.apply(item);
            if (currentGroup.isEmpty() || Objects.equals(key, currentKey)) {
                currentKey = key;
                currentGroup.add(item);
            } else {
                result.add(List.copyOf(currentGroup));
                currentGroup.clear();
                currentKey = key;
                currentGroup.add(item);
            }
        }
        if (!currentGroup.isEmpty()) result.add(List.copyOf(currentGroup));
        return List.copyOf(result);
    }

    // =========================================================================
    // PATTERN 9: Batch after deduplication
    // =========================================================================
    public static <T> List<List<T>> batchAndDeduplicate(List<T> items, int batchSize) {
        return batch(deduplicateConsecutive(items), batchSize);
    }
}
