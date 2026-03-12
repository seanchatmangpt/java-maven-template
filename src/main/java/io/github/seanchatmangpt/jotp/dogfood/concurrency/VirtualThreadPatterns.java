package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Dogfood: rendered from templates/java/concurrency/virtual-thread.tera
 *
 * <p>Virtual thread patterns for lightweight concurrency (Java 21+).
 */
public final class VirtualThreadPatterns {

    private VirtualThreadPatterns() {}

    /**
     * Starts a single named virtual thread.
     */
    public static Thread startNamed(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(task);
    }

    /**
     * Starts a virtual thread for a fire-and-forget task.
     */
    public static Thread startFireAndForget(Runnable task) {
        return Thread.startVirtualThread(task);
    }

    /**
     * Creates multiple named virtual threads with a common prefix.
     */
    public static List<Thread> startAll(String prefix, List<Runnable> tasks) {
        var builder = Thread.ofVirtual().name(prefix, 0);
        return tasks.stream().map(builder::start).toList();
    }

    /**
     * Processes I/O-bound items concurrently using virtual threads.
     */
    public static <T, R> List<R> processAllConcurrently(
            List<T> items, Function<T, R> processor) throws InterruptedException {

        var results = new ConcurrentLinkedQueue<R>();
        var latch = new CountDownLatch(items.size());

        items.stream()
                .map(
                        item ->
                                Thread.ofVirtual()
                                        .name("io-worker-", 0)
                                        .start(
                                                () -> {
                                                    try {
                                                        results.add(processor.apply(item));
                                                    } finally {
                                                        latch.countDown();
                                                    }
                                                }))
                .toList();

        latch.await();
        return List.copyOf(results);
    }

    /**
     * Handles incoming requests by dispatching each to its own virtual thread.
     */
    public static <T> void handleRequests(List<T> requests, Consumer<T> handler)
            throws InterruptedException {

        var latch = new CountDownLatch(requests.size());

        for (var request : requests) {
            Thread.ofVirtual()
                    .name("request-handler-", 0)
                    .start(
                            () -> {
                                try {
                                    handler.accept(request);
                                } finally {
                                    latch.countDown();
                                }
                            });
        }

        latch.await();
    }

    /**
     * Runs a task on a virtual thread with a timeout.
     */
    public static boolean runWithTimeout(Runnable task, Duration timeout)
            throws InterruptedException {

        var thread = Thread.ofVirtual().name("timeout-task").start(task);

        thread.join(timeout);

        if (thread.isAlive()) {
            thread.interrupt();
            return false;
        }
        return true;
    }
}
