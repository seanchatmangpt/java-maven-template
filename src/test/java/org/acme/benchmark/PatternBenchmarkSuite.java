package org.acme.benchmark;

import org.acme.dogfood.core.GathererPatterns;
import org.acme.dogfood.core.PatternMatchingPatterns;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * JMH benchmark suite for ggen-generated Java development patterns.
 *
 * <p>Thesis claim: <em>ggen-generated patterns perform within 100ns per operation for core
 * language feature patterns</em>.
 *
 * <p>Also serves as the JMH runner entry point for {@code ./mvnw verify -Pbenchmark}.
 *
 * <p>Benchmarks:
 *
 * <ul>
 *   <li>Pattern matching dispatch (exhaustive switch vs. if-else chain)
 *   <li>Gatherer batching vs. manual partition
 *   <li>Gatherer sliding window vs. manual subList
 *   <li>Running sum vs. manual accumulation
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class PatternBenchmarkSuite {

    private static final List<Integer> HUNDRED_ITEMS = IntStream.range(0, 100).boxed().toList();
    private static final PatternMatchingPatterns.Payment CARD =
            new PatternMatchingPatterns.Payment.CreditCard("4111111111111111", "Alice", 123, 5000);
    private static final PatternMatchingPatterns.Payment BANK =
            new PatternMatchingPatterns.Payment.BankTransfer("DE89370400440532013000", "BIC", "Bob");
    private static final PatternMatchingPatterns.Payment CRYPTO =
            new PatternMatchingPatterns.Payment.CryptoPay("wallet", "BTC", 0.5);
    private static final PatternMatchingPatterns.Payment VOUCHER =
            new PatternMatchingPatterns.Payment.Voucher("CODE", 50.0, true);

    // =========================================================================
    // Pattern matching dispatch benchmarks
    // =========================================================================

    /**
     * Exhaustive switch dispatch over sealed Payment hierarchy. Claim: < 50ns per dispatch.
     * Baseline for the visitor/dispatcher template.
     */
    @Benchmark
    public String pattern_matching_switch_credit_card() {
        return PatternMatchingPatterns.describe(CARD);
    }

    @Benchmark
    public String pattern_matching_switch_bank_transfer() {
        return PatternMatchingPatterns.describe(BANK);
    }

    @Benchmark
    public String pattern_matching_risk_level_guarded() {
        return PatternMatchingPatterns.riskLevel(CRYPTO);
    }

    /**
     * Baseline: equivalent if-else instanceof chain (pre-Java 21 style). Measures the overhead of
     * the sealed/switch pattern vs. manual dispatch.
     */
    @Benchmark
    public String if_else_instanceof_baseline() {
        PatternMatchingPatterns.Payment p = CARD;
        if (p instanceof PatternMatchingPatterns.Payment.CreditCard c) {
            return "card:" + c.holder();
        } else if (p instanceof PatternMatchingPatterns.Payment.BankTransfer b) {
            return "bank:" + b.accountName();
        } else if (p instanceof PatternMatchingPatterns.Payment.CryptoPay c) {
            return "crypto:" + c.currency();
        } else {
            return "voucher";
        }
    }

    // =========================================================================
    // Gatherer pattern benchmarks
    // =========================================================================

    /**
     * Gatherer batching (fixed window): 100 items into batches of 10. Claim: < 5μs for 100 items.
     */
    @Benchmark
    public List<List<Integer>> gatherer_batch_100_items() {
        return GathererPatterns.batch(HUNDRED_ITEMS, 10);
    }

    /**
     * Manual batching baseline: pre-Java 22 subList loop. Measures the overhead of Gatherers API
     * vs. hand-written batching.
     */
    @Benchmark
    public List<List<Integer>> manual_batch_baseline() {
        var result = new java.util.ArrayList<List<Integer>>();
        int size = HUNDRED_ITEMS.size();
        int batchSize = 10;
        for (int i = 0; i < size; i += batchSize) {
            result.add(HUNDRED_ITEMS.subList(i, Math.min(i + batchSize, size)));
        }
        return result;
    }

    /**
     * Running sum via Gatherers.scan(). Claim: < 10μs for 100 items.
     */
    @Benchmark
    public List<Integer> gatherer_running_sum_100_items() {
        return GathererPatterns.runningSum(HUNDRED_ITEMS);
    }

    /**
     * Manual running sum baseline: pre-Java 22 loop. Measures overhead of Gatherers.scan() API.
     */
    @Benchmark
    public List<Integer> manual_running_sum_baseline() {
        var result = new java.util.ArrayList<Integer>(HUNDRED_ITEMS.size());
        int acc = 0;
        for (int v : HUNDRED_ITEMS) {
            acc += v;
            result.add(acc);
        }
        return result;
    }

    /**
     * Deduplicate consecutive via custom Gatherer. Claim: < 10μs for 100 items.
     */
    @Benchmark
    public List<Integer> gatherer_dedup_consecutive() {
        return HUNDRED_ITEMS.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();
    }

    // =========================================================================
    // JMH runner entry point (invoked by Maven benchmark profile)
    // =========================================================================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(PatternBenchmarkSuite.class.getSimpleName())
                .include(ActorBenchmark.class.getSimpleName())
                .include(ResultBenchmark.class.getSimpleName())
                .include(ParallelBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
