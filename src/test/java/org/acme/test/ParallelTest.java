package org.acme.test;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.Parallel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class ParallelTest implements WithAssertions {

    @Test
    void allSuccessReturnsAllResults() {
        List<Supplier<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);
        var result = Parallel.all(tasks);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void emptyListReturnsEmptySuccess() {
        var result = Parallel.<Integer>all(List.of());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEmpty();
    }

    @Test
    void anyFailureReturnsFailure() {
        List<Supplier<Integer>> tasks =
                List.of(
                        () -> 1,
                        () -> {
                            throw new IllegalStateException("injected crash");
                        },
                        () -> 3);
        var result = Parallel.all(tasks);
        assertThat(result.isFailure()).isTrue();
    }

    @Property
    void allSucceedMeansSuccessResult(@ForAll @IntRange(min = 0, max = 20) int taskCount) {
        var tasks =
                IntStream.range(0, taskCount).<Supplier<Integer>>mapToObj(i -> () -> i).toList();
        var result = Parallel.all(tasks);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).hasSize(taskCount);
    }

    @Property
    void anyFailureMeansFailureResult(@ForAll @IntRange(min = 1, max = 10) int failIndex) {
        var tasks =
                IntStream.range(0, 10)
                        .<Supplier<Integer>>mapToObj(
                                i ->
                                        () -> {
                                            if (i == failIndex % 10) {
                                                throw new RuntimeException("crash at " + i);
                                            }
                                            return i;
                                        })
                        .toList();
        var result = Parallel.all(tasks);
        assertThat(result.isFailure()).isTrue();
    }
}
