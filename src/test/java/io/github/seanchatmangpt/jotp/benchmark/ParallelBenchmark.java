package io.github.seanchatmangpt.jotp.benchmark;

import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.dogfood.concurrency.StructuredTaskScopePatterns;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * JMH benchmarks for the Parallel fan-out pattern.
 *
 * <p>Thesis claim: <em>ggen-generated Parallel.all() achieves ≥ 4× speedup over sequential
 * execution on an 8-core machine with 8 tasks</em>.
 *
 * <p>Benchmarks:
 *
 * <ul>
 *   <li>{@code parallel_fanout} — Parallel.all() with N compute-bound tasks
 *   <li>{@code sequential_baseline} — sequential execution of the same N tasks
 *   <li>{@code structured_scope_fanout} — StructuredTaskScopePatterns.fanOut() for comparison
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class ParallelBenchmark {

    @Param({"4", "8", "16"})
    public int taskCount;

    private static final int WORK_UNITS = 10_000;

    /** Simulate compute-bound work: sum of squares up to N. */
    private static long computeWork(int n) {
        long sum = 0;
        for (int i = 1; i <= n; i++) sum += (long) i * i;
        return sum;
    }

    /**
     * Parallel fan-out using Parallel.all(). N tasks execute concurrently on virtual threads.
     * Measures end-to-end latency including result aggregation.
     */
    @Benchmark
    public long parallel_fanout() {
        List<Supplier<Long>> tasks =
                IntStream.range(0, taskCount)
                        .<Supplier<Long>>mapToObj(_ -> () -> computeWork(WORK_UNITS))
                        .toList();
        return Parallel.all(tasks)
                .fold(
                        results -> results.stream().mapToLong(Long::longValue).sum(),
                        _ -> -1L);
    }

    /**
     * Sequential baseline: same N tasks run one-by-one. The speedup ratio
     * (sequential_baseline / parallel_fanout) is the empirical speedup factor.
     */
    @Benchmark
    public long sequential_baseline() {
        long total = 0;
        for (int i = 0; i < taskCount; i++) {
            total += computeWork(WORK_UNITS);
        }
        return total;
    }

    /**
     * StructuredTaskScope fan-out: same tasks using the StructuredTaskScopePatterns dogfood
     * implementation. Validates the ggen template produces equivalent performance.
     */
    @Benchmark
    public long structured_scope_fanout() throws Exception {
        List<Integer> items = IntStream.range(0, taskCount).boxed().toList();
        List<Long> results = StructuredTaskScopePatterns.fanOut(items, _ -> computeWork(WORK_UNITS));
        return results.stream().mapToLong(Long::longValue).sum();
    }
}
