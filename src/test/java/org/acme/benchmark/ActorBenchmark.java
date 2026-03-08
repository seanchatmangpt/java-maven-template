package org.acme.benchmark;

import org.acme.Proc;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the Actor pattern.
 *
 * <p>Thesis claim: <em>ggen-generated Actor pattern has overhead ≤ 15% vs. raw
 * LinkedTransferQueue</em>.
 *
 * <p>Benchmarks:
 *
 * <ul>
 *   <li>{@code tell_throughput} — fire-and-forget message rate (ops/μs)
 *   <li>{@code ask_latency} — request-reply round-trip latency (μs)
 *   <li>{@code raw_queue_throughput} — baseline: raw LinkedTransferQueue enqueue rate
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class ActorBenchmark {

    private Proc<Integer, Integer> countingActor;
    private Proc<Integer, Integer> echoActor;
    private LinkedTransferQueue<Integer> rawQueue;

    @Setup(Level.Iteration)
    public void setup() throws Exception {
        countingActor = new Proc<>(0, (state, msg) -> state + msg);
        echoActor = new Proc<>(0, (_, msg) -> msg);
        rawQueue = new LinkedTransferQueue<>();
        // Consumer thread for raw queue baseline
        Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { rawQueue.take(); } catch (InterruptedException e) { break; }
            }
        });
    }

    @TearDown(Level.Iteration)
    public void teardown() throws Exception {
        countingActor.stop();
        echoActor.stop();
    }

    /**
     * Baseline: raw LinkedTransferQueue enqueue. Measures the minimum overhead of concurrent
     * message passing without pattern abstractions.
     */
    @Benchmark
    public void raw_queue_throughput() {
        rawQueue.offer(42);
    }

    /**
     * Actor tell() throughput. Fire-and-forget — no blocking. Overhead vs. raw_queue measures the
     * actor abstraction cost.
     */
    @Benchmark
    public void tell_throughput() {
        countingActor.tell(1);
    }

    /**
     * Actor ask() latency. Request-reply round-trip — blocks until response. Measures the
     * end-to-end latency of the actor's virtual-thread mailbox.
     */
    @Benchmark
    public int ask_latency() throws Exception {
        return echoActor.ask(42).get();
    }
}
