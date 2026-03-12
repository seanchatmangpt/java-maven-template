package io.github.seanchatmangpt.jotp.dogfood.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Predicate;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for Java 26 Gatherer patterns in {@link GathererPatterns}.
 *
 * <p>Validates custom stream intermediate operations:
 *
 * <ul>
 *   <li>Fixed-size batching
 *   <li>Sliding window operations
 *   <li>Running scan (prefix sums)
 *   <li>Fold as intermediate operation
 *   <li>Concurrent mapping
 *   <li>Custom deduplication gatherers
 *   <li>Gatherer chaining
 * </ul>
 *
 * @see GathererPatterns
 */
class GathererPatternsTest implements WithAssertions {

    // ── Pattern 1: Fixed-size batching ────────────────────────────────────────

    @Test
    void batch_createsFixedSizedBatches() {
        var items = List.of(1, 2, 3, 4, 5, 6, 7);
        var batches = GathererPatterns.batch(items, 3);
        assertThat(batches).containsExactly(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7));
    }

    @Test
    void batch_emptyList_returnsEmpty() {
        var batches = GathererPatterns.batch(List.of(), 3);
        assertThat(batches).isEmpty();
    }

    @Test
    void batch_exactFit_returnsFullBatches() {
        var items = List.of(1, 2, 3, 4, 5, 6);
        var batches = GathererPatterns.batch(items, 2);
        assertThat(batches).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5, 6));
    }

    // ── Pattern 2: Sliding window ─────────────────────────────────────────────

    @Test
    void slidingWindow_createsOverlappingWindows() {
        var items = List.of(1, 2, 3, 4, 5);
        var windows = GathererPatterns.slidingWindow(items, 3);
        assertThat(windows).containsExactly(List.of(1, 2, 3), List.of(2, 3, 4), List.of(3, 4, 5));
    }

    @Test
    void movingAverage_calculatesCorrectly() {
        var values = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
        var averages = GathererPatterns.movingAverage(values, 3);
        assertThat(averages).containsExactly(20.0, 30.0, 40.0);
    }

    @Test
    void movingAverage_singleElementWindow_returnsOriginal() {
        var values = List.of(10.0, 20.0, 30.0);
        var averages = GathererPatterns.movingAverage(values, 1);
        assertThat(averages).containsExactly(10.0, 20.0, 30.0);
    }

    // ── Pattern 3: Running scan ───────────────────────────────────────────────

    @Test
    void runningSum_calculatesCorrectly() {
        var values = List.of(1, 2, 3, 4, 5);
        var sums = GathererPatterns.runningSum(values);
        assertThat(sums).containsExactly(1, 3, 6, 10, 15);
    }

    @Test
    void runningAccumulate_withStringConcatenation() {
        var items = List.of("a", "b", "c");
        var result = GathererPatterns.runningAccumulate(items, "", (acc, s) -> acc + s);
        assertThat(result).containsExactly("a", "ab", "abc");
    }

    @Test
    void runningAccumulate_emptyList_returnsEmpty() {
        var result = GathererPatterns.runningAccumulate(List.of(), 0, Integer::sum);
        assertThat(result).isEmpty();
    }

    // ── Pattern 4: Fold as intermediate operation ──────────────────────────────

    @Test
    void foldToSingle_sumsAllElements() {
        var items = List.of(1, 2, 3, 4, 5);
        var sum = GathererPatterns.foldToSingle(items, 0, Integer::sum);
        assertThat(sum).isEqualTo(15);
    }

    @Test
    void foldToSingle_emptyList_returnsIdentity() {
        var sum = GathererPatterns.foldToSingle(List.of(), 42, Integer::sum);
        assertThat(sum).isEqualTo(42);
    }

    @Test
    void foldToSingle_concatenatesStrings() {
        var items = List.of("a", "b", "c");
        var result = GathererPatterns.foldToSingle(items, "", (a, b) -> a + b);
        assertThat(result).isEqualTo("abc");
    }

    // ── Pattern 5: Concurrent mapping ─────────────────────────────────────────

    @Test
    void mapConcurrent_mapsAllElements() {
        var items = List.of(1, 2, 3, 4, 5);
        var result = GathererPatterns.mapConcurrent(items, 2, i -> i * 2);
        assertThat(result).containsExactly(2, 4, 6, 8, 10);
    }

    @Test
    void mapConcurrent_preservesOrder() {
        var items = List.of("a", "b", "c", "d");
        var result = GathererPatterns.mapConcurrent(items, 3, String::toUpperCase);
        assertThat(result).containsExactly("A", "B", "C", "D");
    }

    // ── Pattern 6: Custom gatherer - deduplicate consecutive ──────────────────

    @Test
    void deduplicateConsecutive_removesConsecutiveDuplicates() {
        var items = List.of(1, 1, 2, 2, 2, 3, 2, 2, 4);
        var deduped = items.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();
        assertThat(deduped).containsExactly(1, 2, 3, 2, 4);
    }

    @Test
    void deduplicateConsecutive_allSame_returnsSingle() {
        var items = List.of(5, 5, 5, 5);
        var deduped = items.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();
        assertThat(deduped).containsExactly(5);
    }

    @Test
    void deduplicateConsecutive_noDuplicates_returnsOriginal() {
        var items = List.of(1, 2, 3, 4);
        var deduped = items.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();
        assertThat(deduped).containsExactly(1, 2, 3, 4);
    }

    // ── Pattern 7: Custom gatherer - take while with count limit ──────────────

    @Test
    void takeWhileMax_takesWhilePredicateTrueAndUnderLimit() {
        var items = List.of(2, 4, 6, 8, 10, 3, 12);
        Predicate<Integer> isEven = i -> i % 2 == 0;
        var result = items.stream().gather(GathererPatterns.takeWhileMax(isEven, 3)).toList();
        assertThat(result).containsExactly(2, 4, 6);
    }

    @Test
    void takeWhileMax_stopsAtFirstNonMatching() {
        var items = List.of(2, 4, 6, 3, 8);
        Predicate<Integer> isEven = i -> i % 2 == 0;
        var result = items.stream().gather(GathererPatterns.takeWhileMax(isEven, 10)).toList();
        assertThat(result).containsExactly(2, 4, 6);
    }

    // ── Pattern 8: Custom gatherer - group consecutive by classifier ──────────

    @Test
    void groupConsecutiveBy_groupsAdjacentSameKey() {
        var items = List.of(1, 1, 2, 2, 2, 1, 3, 3);
        var groups = items.stream().gather(GathererPatterns.groupConsecutiveBy(i -> i % 2)).toList();
        // 1%2=1, 1%2=1, 2%2=0, 2%2=0, 2%2=0, 1%2=1, 3%2=1, 3%2=1
        // Groups: [1,1] (mod 1), [2,2,2] (mod 0), [1,3,3] (mod 1)
        assertThat(groups).containsExactly(List.of(1, 1), List.of(2, 2, 2), List.of(1, 3, 3));
    }

    @Test
    void groupConsecutiveBy_allSameKey_returnsOneGroup() {
        var items = List.of(2, 4, 6, 8);
        var groups = items.stream().gather(GathererPatterns.groupConsecutiveBy(i -> "even")).toList();
        assertThat(groups).containsExactly(List.of(2, 4, 6, 8));
    }

    // ── Pattern 9: Chaining gatherers ─────────────────────────────────────────

    @Test
    void batchAndDeduplicate_chainsGatherers() {
        var items = List.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
        var result = GathererPatterns.batchAndDeduplicate(items, 2);
        // Dedupe first: [1, 2, 3, 4, 5], then batch: [[1, 2], [3, 4], [5]]
        assertThat(result).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
    }
}
