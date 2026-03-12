package io.github.seanchatmangpt.jotp.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Competing-consumers dispatcher: a single input channel served by a pool of worker virtual
 * threads, each racing to process the next available message.
 *
 * <p>Enterprise Integration Pattern: <em>Competing Consumers</em> (EIP §10.2). Erlang analog:
 * a supervisor tree of identical worker processes all registered to the same name or receiving from
 * the same coordinator — the OTP "worker pool" topology. Each message is processed by exactly one
 * worker; workers compete for available messages.
 *
 * <p>Useful for CPU- or I/O-intensive consumers where parallelism within the processing stage
 * improves throughput. Message ordering is <em>not</em> guaranteed across workers.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var dispatcher = MessageDispatcher.<ImageJob>builder()
 *     .worker(job -> resizeImage(job))  // worker 1
 *     .worker(job -> resizeImage(job))  // worker 2
 *     .worker(job -> resizeImage(job))  // worker 3
 *     .build();
 *
 * imageJobs.forEach(dispatcher::send);
 * dispatcher.stop();
 * }</pre>
 *
 * @param <T> message type
 */
public final class MessageDispatcher<T> implements MessageChannel<T> {

    private final LinkedTransferQueue<T> queue = new LinkedTransferQueue<>();
    private final List<Thread> workerThreads = new ArrayList<>();
    private volatile boolean stopped = false;

    /** Round-robin counter (unused for competing-consumers but retained for future use). */
    private final AtomicInteger counter = new AtomicInteger(0);

    private MessageDispatcher(List<Consumer<T>> workers) {
        for (int i = 0; i < workers.size(); i++) {
            final Consumer<T> worker = workers.get(i);
            final int idx = i;
            Thread t =
                    Thread.ofVirtual()
                            .name("dispatcher-worker-" + idx)
                            .start(
                                    () -> {
                                        while (!stopped || !queue.isEmpty()) {
                                            try {
                                                T msg = queue.poll(50, TimeUnit.MILLISECONDS);
                                                if (msg != null) {
                                                    worker.accept(msg);
                                                }
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                break;
                                            }
                                        }
                                    });
            workerThreads.add(t);
        }
    }

    /**
     * Enqueue a message for any available worker — non-blocking, fire-and-forget.
     *
     * @param message the message to dispatch
     */
    @Override
    public void send(T message) {
        queue.add(message);
    }

    /**
     * Signal all workers to stop after draining remaining messages, then join all worker threads.
     *
     * @throws InterruptedException if interrupted while waiting for workers to finish
     */
    @Override
    public void stop() throws InterruptedException {
        stopped = true;
        for (Thread t : workerThreads) {
            t.interrupt();
        }
        for (Thread t : workerThreads) {
            t.join();
        }
    }

    /** Number of messages currently waiting for a worker. */
    public int queueDepth() {
        return queue.size();
    }

    /** Number of active worker threads. */
    public int workerCount() {
        return workerThreads.size();
    }

    /** Returns a new builder. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent builder for {@link MessageDispatcher}. */
    public static final class Builder<T> {

        private final List<Consumer<T>> workers = new ArrayList<>();

        /**
         * Add a worker consumer that will compete for messages.
         *
         * <p>Each worker runs in its own virtual thread. The same {@link Consumer} lambda may be
         * added multiple times — each addition creates an independent virtual-thread worker.
         *
         * @param worker the message processing logic
         * @return this builder (fluent)
         */
        public Builder<T> worker(Consumer<T> worker) {
            workers.add(worker);
            return this;
        }

        /**
         * Add {@code count} identical workers (short-hand for calling {@link #worker} {@code count}
         * times with the same lambda).
         *
         * @param count number of worker threads to create
         * @param worker the message processing logic shared across all workers
         * @return this builder (fluent)
         */
        public Builder<T> workers(int count, Consumer<T> worker) {
            for (int i = 0; i < count; i++) {
                workers.add(worker);
            }
            return this;
        }

        /** Build the {@link MessageDispatcher}. */
        public MessageDispatcher<T> build() {
            if (workers.isEmpty()) throw new IllegalStateException("At least one worker is required");
            return new MessageDispatcher<>(workers);
        }
    }
}
