package org.acme.dogfood.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class StructuredTaskScopePatternsTest implements WithAssertions {

    // ── Pattern 1: All-must-succeed — two tasks ────────────────────────────────

    @Test
    void runBoth_bothSucceed_returnsPair() throws Exception {
        var result = StructuredTaskScopePatterns.runBoth(() -> "A", () -> 42);
        assertThat(result.first()).isEqualTo("A");
        assertThat(result.second()).isEqualTo(42);
    }

    @Test
    void runBoth_firstFails_throws() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.runBoth(
                        () -> {
                            throw new RuntimeException("fail");
                        },
                        () -> 42))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void runBoth_secondFails_throws() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.runBoth(
                        () -> "A",
                        () -> {
                            throw new RuntimeException("fail");
                        }))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ── Pattern 2: All-must-succeed — three tasks ──────────────────────────────

    @Test
    void runAll_allSucceed_returnsTriple() throws Exception {
        var result = StructuredTaskScopePatterns.runAll(() -> "A", () -> "B", () -> "C");
        assertThat(result.first()).isEqualTo("A");
        assertThat(result.second()).isEqualTo("B");
        assertThat(result.third()).isEqualTo("C");
    }

    @Test
    void runAll_anyFails_throws() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.runAll(
                        () -> "A",
                        () -> {
                            throw new RuntimeException("fail");
                        },
                        () -> "C"))
                .isInstanceOf(ExecutionException.class);
    }

    // ── Pattern 3: Race — first result wins ────────────────────────────────────

    @Test
    void raceForFirst_returnsFirstSuccessful() throws Exception {
        var tasks = List.of(
                () -> {
                    Thread.sleep(100);
                    return "slow";
                },
                () -> "fast");
        var result = StructuredTaskScopePatterns.raceForFirst(tasks);
        assertThat(result).isEqualTo("fast");
    }

    @Test
    void raceForFirst_allFail_throws() {
        var tasks = List.<java.util.concurrent.Callable<String>>of(
                () -> {
                    throw new RuntimeException("fail1");
                },
                () -> {
                    throw new RuntimeException("fail2");
                });
        assertThatThrownBy(() -> StructuredTaskScopePatterns.raceForFirst(tasks))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void raceForFirst_emptyList_throws() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.raceForFirst(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No tasks provided");
    }

    // ── Pattern 4: Timeout with joinUntil ──────────────────────────────────────

    @Test
    void runWithTimeout_completesInTime_returnsResult() throws Exception {
        var result = StructuredTaskScopePatterns.runWithTimeout(() -> "done", Duration.ofSeconds(5));
        assertThat(result).isEqualTo("done");
    }

    @Test
    void runWithTimeout_exceedsTimeout_throws() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.runWithTimeout(
                        () -> {
                            Thread.sleep(5000);
                            return "done";
                        },
                        Duration.ofMillis(100)))
                .isInstanceOf(TimeoutException.class);
    }

    // ── Pattern 5: Fan-out / scatter-gather ────────────────────────────────────

    @Test
    void fanOut_processesAllItems() throws Exception {
        var items = List.of(1, 2, 3, 4, 5);
        var result = StructuredTaskScopePatterns.fanOut(items, i -> i * 2);
        assertThat(result).containsExactly(2, 4, 6, 8, 10);
    }

    @Test
    void fanOut_preservesOrder() throws Exception {
        var items = List.of("a", "b", "c", "d");
        // Even though tasks run concurrently, results are in input order
        var result = StructuredTaskScopePatterns.fanOut(items, s -> s.toUpperCase());
        assertThat(result).containsExactly("A", "B", "C", "D");
    }

    @Test
    void fanOut_emptyList_returnsEmpty() throws Exception {
        var result = StructuredTaskScopePatterns.fanOut(List.of(), i -> i);
        assertThat(result).isEmpty();
    }

    @Test
    void fanOut_anyFails_throws() {
        var items = List.of(1, 2, 3);
        assertThatThrownBy(() -> StructuredTaskScopePatterns.fanOut(
                        items,
                        i -> {
                            if (i == 2) throw new RuntimeException("fail");
                            return i;
                        }))
                .isInstanceOf(ExecutionException.class);
    }

    // ── Pattern 6: Fan-out with timeout ────────────────────────────────────────

    @Test
    void fanOutWithTimeout_completesInTime_returnsResults() throws Exception {
        var items = List.of(1, 2, 3);
        var result = StructuredTaskScopePatterns.fanOutWithTimeout(items, i -> i * 10, Duration.ofSeconds(5));
        assertThat(result).containsExactly(10, 20, 30);
    }

    @Test
    void fanOutWithTimeout_exceedsTimeout_throws() {
        var items = List.of(1, 2, 3);
        assertThatThrownBy(() -> StructuredTaskScopePatterns.fanOutWithTimeout(
                        items,
                        i -> {
                            Thread.sleep(5000);
                            return i;
                        },
                        Duration.ofMillis(100)))
                .isInstanceOf(TimeoutException.class);
    }

    // ── Pattern 7: Nested scopes ───────────────────────────────────────────────

    @Test
    void nestedFanOut_processesBothGroups() throws Exception {
        var groupA = List.of(1, 2);
        var groupB = List.of(3, 4);
        var result = StructuredTaskScopePatterns.nestedFanOut(groupA, groupB, i -> i * 100);
        assertThat(result.first()).containsExactly(100, 200);
        assertThat(result.second()).containsExactly(300, 400);
    }

    @Test
    void nestedFanOut_emptyGroups_returnsEmptyLists() throws Exception {
        var result = StructuredTaskScopePatterns.nestedFanOut(List.of(), List.of(), i -> i);
        assertThat(result.first()).isEmpty();
        assertThat(result.second()).isEmpty();
    }
}
