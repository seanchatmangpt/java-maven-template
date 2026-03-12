package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: tests for StructuredTaskScopePatterns generated from concurrency/structured-task-scope.tera.
 */
@DisplayName("StructuredTaskScopePatterns")
class StructuredTaskScopePatternsTest implements WithAssertions {

    @Test
    @DisplayName("runBoth returns pair when both tasks succeed")
    void runBoth_returnsPair() throws Exception {
        var result = StructuredTaskScopePatterns.runBoth(
                () -> "hello",
                () -> 42);
        assertThat(result.first()).isEqualTo("hello");
        assertThat(result.second()).isEqualTo(42);
    }

    @Test
    @DisplayName("runBoth throws when first task fails")
    void runBoth_throwsOnFirstFailure() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.runBoth(
                () -> { throw new RuntimeException("fail"); },
                () -> 42))
            .isInstanceOf(Exception.class); // StructuredTaskScope$FailedException
    }

    @Test
    @DisplayName("runBoth throws when second task fails")
    void runBoth_throwsOnSecondFailure() {
        assertThatThrownBy(() -> StructuredTaskScopePatterns.runBoth(
                () -> "hello",
                () -> { throw new RuntimeException("fail"); }))
            .isInstanceOf(Exception.class); // StructuredTaskScope$FailedException
    }

    @Test
    @DisplayName("runAll returns triple when all tasks succeed")
    void runAll_returnsTriple() throws Exception {
        var result = StructuredTaskScopePatterns.runAll(
                () -> "a",
                () -> "b",
                () -> "c");
        assertThat(result.first()).isEqualTo("a");
        assertThat(result.second()).isEqualTo("b");
        assertThat(result.third()).isEqualTo("c");
    }

    @Test
    @DisplayName("raceForFirst returns a successful result")
    void raceForFirst_returnsFirstSuccessful() throws Exception {
        List<Callable<String>> tasks = List.of(
                () -> {
                    Thread.sleep(100);
                    return "slow";
                },
                () -> "fast");
        var result = StructuredTaskScopePatterns.raceForFirst(tasks);
        // Race condition: either task can complete first depending on scheduling
        assertThat(result).isIn("fast", "slow");
    }
}
