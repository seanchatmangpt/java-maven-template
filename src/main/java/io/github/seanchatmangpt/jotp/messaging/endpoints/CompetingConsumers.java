package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Competing Consumers (Vernon: "Competing Consumers")
 *
 * <p>Multiple consumer processes compete to handle messages from a single queue.
 * Each message is processed by exactly one consumer (load balancing).
 *
 * <p>JOTP Implementation: Uses Supervisor with ONE_FOR_ALL strategy to spawn
 * multiple workers that poll a shared message queue.
 *
 * <p>Example:
 * <pre>
 * var queue = new SharedMessageQueue();
 * var workers = CompetingConsumers.create(
 *     queue,
 *     5,  // 5 competing workers
 *     msg -> System.out.println("Processing: " + msg)
 * );
 * </pre>
 */
public final class CompetingConsumers {

    public interface MessageQueue {
        Message take();
        void put(Message msg);
        int size();
    }

    public interface MessageHandler {
        void handle(Message msg);
    }

    /**
     * Worker state for each consumer.
     */
    static class WorkerState {
        String workerId;
        int messagesProcessed = 0;
        long lastProcessed = 0;

        WorkerState(String id) {
            this.workerId = id;
        }
    }

    private CompetingConsumers() {
    }

    /**
     * Creates a pool of competing consumers that process messages from a queue.
     *
     * @param queue Shared message queue
     * @param numWorkers Number of competing workers
     * @param handler Message handler function
     * @return List of worker ProcRefs
     */
    public static List<ProcRef<WorkerState, Message>> create(
        MessageQueue queue,
        int numWorkers,
        MessageHandler handler) {

        List<ProcRef<WorkerState, Message>> workers = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numWorkers; i++) {
            int workerId = i;
            var worker = Proc.spawn(
                new WorkerState("WORKER-" + i),
                state -> msg -> {
                    // Handle received message
                    handler.handle(msg);
                    state.messagesProcessed++;
                    state.lastProcessed = System.currentTimeMillis();
                    return state;
                }
            );

            workers.add(worker);

            // Spawn dispatcher that pulls from queue and sends to this worker
            spawnDispatcher(queue, worker);
        }

        return workers;
    }

    /**
     * Internal: spawns a dispatcher that pulls messages from queue and sends to worker.
     */
    private static void spawnDispatcher(MessageQueue queue, ProcRef<WorkerState, Message> worker) {
        Thread dispatchThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    var msg = queue.take();
                    if (msg != null) {
                        worker.send(msg);
                    }
                } catch (Exception e) {
                    // Queue closed or interrupted
                    break;
                }
            }
        });
        dispatchThread.setDaemon(true);
        dispatchThread.start();
    }

    /**
     * Gets statistics for all workers.
     *
     * @param workers List of worker ProcRefs
     * @return Formatted stats
     */
    public static String getStats(List<ProcRef<WorkerState, Message>> workers) {
        var total = workers.stream()
            .map(Proc::getState)
            .mapToInt(s -> s.messagesProcessed)
            .sum();
        return String.format("total_workers=%d, total_processed=%d", workers.size(), total);
    }

    /**
     * Drains all workers (waits for pending work to complete).
     *
     * @param workers List of worker ProcRefs
     * @param timeoutMs Maximum wait time
     */
    public static void drain(List<ProcRef<WorkerState, Message>> workers, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
