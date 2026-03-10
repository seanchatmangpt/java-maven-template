package org.acme.reactive;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Polling Consumer — EIP endpoint pattern where the consumer controls when it retrieves
 * messages from a channel, rather than the channel pushing messages to it.
 *
 * <p>Models the EIP Polling Consumer: the application periodically polls a message store
 * or external queue. This pattern is appropriate when the consumer needs to control its
 * own processing rate (back-pressure), when integrating with legacy pull-based systems,
 * or when batching is required.
 *
 * <p>Implementation: runs a polling loop on a Java 25 virtual thread. The poll interval
 * governs how frequently the consumer checks for new messages. Messages arriving between
 * polls are buffered in an internal {@link LinkedBlockingQueue} (unbounded).
 *
 * <p>Erlang/OTP analogy: a process using {@code receive after Timeout} — the process
 * wakes periodically even if no messages are available, allowing it to initiate work.
 *
 * @param <T> message type
 */
public final class PollingConsumer<T> implements MessageChannel<T>, AutoCloseable {

    private final BlockingQueue<T> queue;
    private final Consumer<T> handler;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong polled = new AtomicLong(0);
    private final Thread pollerThread;

    /**
     * Creates a PollingConsumer that periodically checks for messages and processes
     * each one with {@code handler}.
     *
     * @param handler      called for each polled message
     * @param pollInterval how long to wait between polling attempts when queue is empty
     */
    public PollingConsumer(Consumer<T> handler, Duration pollInterval) {
        this.queue = new LinkedBlockingQueue<>();
        this.handler = handler;
        this.pollInterval = pollInterval;
        this.pollerThread = Thread.ofVirtual()
                .name("polling-consumer")
                .start(this::pollLoop);
    }

    /**
     * Creates a PollingConsumer backed by an external {@link BlockingQueue}, for
     * integration with existing message stores or JMS-like infrastructure.
     *
     * @param externalQueue source queue to poll
     * @param handler       called for each polled message
     * @param pollInterval  wait time between polling attempts on empty queue
     */
    public PollingConsumer(BlockingQueue<T> externalQueue, Consumer<T> handler, Duration pollInterval) {
        this.queue = externalQueue;
        this.handler = handler;
        this.pollInterval = pollInterval;
        this.pollerThread = Thread.ofVirtual()
                .name("polling-consumer")
                .start(this::pollLoop);
    }

    /**
     * Enqueues {@code message} for the poller to pick up on its next poll.
     * Non-blocking — returns immediately.
     */
    @Override
    public void send(T message) {
        queue.offer(message);
    }

    /** Returns the number of messages successfully polled and dispatched so far. */
    public long polledCount() {
        return polled.get();
    }

    /** Returns the current number of messages waiting in the poll queue. */
    public int queueSize() {
        return queue.size();
    }

    @Override
    public void stop() throws InterruptedException {
        close();
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        pollerThread.interrupt();
        pollerThread.join(pollInterval.toMillis() + 500);
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                T message = queue.poll(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (message != null) {
                    handler.accept(message);
                    polled.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Swallow handler exceptions to keep polling loop alive
            }
        }
    }
}
