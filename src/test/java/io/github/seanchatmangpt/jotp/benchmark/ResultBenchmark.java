package io.github.seanchatmangpt.jotp.benchmark;

import io.github.seanchatmangpt.jotp.Result;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the Result railway pattern.
 *
 * <p>Thesis claim: <em>Result railway chaining is ≤ 2× slower than equivalent try-catch
 * chains</em>.
 *
 * <p>Benchmarks:
 *
 * <ul>
 *   <li>{@code result_chain_5maps} — 5-step railway transformation (success path)
 *   <li>{@code try_catch_5levels} — equivalent 5-level try-catch (baseline)
 *   <li>{@code result_failure_propagation} — 5-step chain where step 1 fails
 *   <li>{@code try_catch_failure_propagation} — equivalent try-catch with exception
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class ResultBenchmark {

    // =========================================================================
    // Success path: 5 transformations
    // =========================================================================

    /**
     * Result railway: 5 chained map() calls on the success path. Each map applies a simple
     * arithmetic function. This is the core "railway-oriented programming" use case.
     */
    @Benchmark
    public int result_chain_5maps() {
        return Result.<Integer, Exception>of(() -> 1)
                .map(x -> x + 1)
                .map(x -> x * 2)
                .map(x -> x - 1)
                .map(x -> x * 3)
                .map(x -> x + 10)
                .fold(x -> x, _ -> -1);
    }

    /**
     * Baseline: equivalent try-catch nesting. 5 levels of try-catch simulating the same
     * transformation sequence.
     */
    @Benchmark
    public int try_catch_5levels() {
        try {
            int v = 1;
            v = v + 1;
            v = v * 2;
            v = v - 1;
            v = v * 3;
            v = v + 10;
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================================
    // Failure path: early failure, 5 skipped operations
    // =========================================================================

    /**
     * Result railway failure propagation: first step throws, remaining 4 map()s are skipped. This
     * measures the cost of failure propagation without throwing exceptions.
     */
    @Benchmark
    public int result_failure_propagation() {
        return Result.<Integer, Exception>of(
                        () -> {
                            throw new RuntimeException("fail");
                        })
                .map(x -> x + 1)
                .map(x -> x * 2)
                .map(x -> x - 1)
                .map(x -> x * 3)
                .fold(x -> x, _ -> -1);
    }

    /**
     * Baseline: equivalent try-catch with exception thrown at step 1. The JVM must construct,
     * throw, and catch the exception — including stack trace construction.
     */
    @Benchmark
    public int try_catch_failure_propagation() {
        try {
            if (true) throw new RuntimeException("fail");
            int v = 1;
            v = v + 1;
            v = v * 2;
            v = v - 1;
            v = v * 3;
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================================
    // flatMap (monadic bind)
    // =========================================================================

    /**
     * Result flatMap chain: 3 steps where each step could theoretically fail. Validates that
     * monadic composition has acceptable overhead.
     */
    @Benchmark
    public int result_flatmap_chain() {
        return Result.<Integer, Exception>of(() -> 10)
                .flatMap(x -> Result.success(x + 5))
                .flatMap(x -> Result.success(x * 2))
                .flatMap(x -> Result.success(x - 3))
                .fold(x -> x, _ -> -1);
    }
}
