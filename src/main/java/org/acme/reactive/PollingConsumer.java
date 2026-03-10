package org.acme.reactive;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.acme.Proc;
import org.acme.ProcSys;
import org.acme.ProcSys.Stats;

/**
 * Polling Consumer — EIP endpoint pattern where the consumer controls when it retrieves
 * messages from a channel, rather than the channel pushing messages to it.
 *
 * <p>This pattern is appropriate when the consumer needs to control its own processing rate
 * (back-pressure), when integrating with legacy pull-based systems, or when batching is required.
 *
 * <p><strong>JOTP implementation:</strong> message <em>handling</em> is delegated to a
 * {@link Proc}{@code <Long, T>} — each polled message is delivered to the process's mailbox.
 * This gives the handler full OTP fault-isolation: crashes are recorded as {@code Proc.lastError}
 * and do not kill the polling loop. A separate virtual thread runs the poll loop, pulling from
 * an internal {@link LinkedBlockingQueue} and forwarding to the {@code Proc}.
 *
 * <p>Erlang/OTP analogy: a {@code gen_server} that wakes via {@code receive after Timeout} to
 * perform work — the process controls retrieval rather than receiving pushed messages.
 *
 * <p>Introspection: use {@link ProcSys#statistics(Proc)} to inspect the handler's message count
 * and {@link ProcSys#getState(Proc)} to retrieve the current poll count at runtime.
 *
 * @param <T> message type
 */
public final class PollingConsumer<T> implements MessageChannel<T>, AutoCloseable {

    /**
     * JOTP-backed message handler — each polled message is delivered here for fault-isolated
     * processing. State is the running count of successfully polled messages.
     */
    private final Proc<Long, T> handlerProc;

    private final BlockingQueue<T> queue;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread pollerThread;

    /**
     * Creates a PollingConsumer that periodically checks for messages and processes each one
     * with {@code handler} inside a fault-isolated {@link Proc}.
     *
     * @param handler      called for each polled message (runs inside a {@link Proc} virtual thread)
     * @param pollInterval how long to wait between polling attempts when queue is empty
     */
    public PollingConsumer(Consumer<T> handler, Duration pollInterval) {
        this(new LinkedBlockingQueue<>(), handler, pollInterval);
    }

    /**
     * Creates a PollingConsumer backed by an external {@link BlockingQueue}, for
     * integration with existing message stores or JMS-like infrastructure.
     *
     * @param externalQueue source queue to poll
     * @param handler       called for each polled message (runs inside a {@link Proc})
     * @param pollInterval  wait time between polling attempts on empty queue
     */
    public PollingConsumer(BlockingQueue<T> externalQueue, Consumer<T> handler, Duration pollInterval) {
        this.queue = externalQueue;
        this.pollInterval = pollInterval;
        // Handler runs inside a Proc — OTP fault isolation: crashes don't kill the poll loop
        this.handlerProc = new Proc<>(0L, (Long count, T msg) -> {
            handler.accept(msg);
            return count + 1L;
        });
        this.pollerThread = Thread.ofVirtual()
                .name("polling-consumer-loop")
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

    /**
     * Returns the number of messages successfully polled and dispatched to the handler.
     * Reads from the JOTP process statistics — consistent with OTP {@code sys:statistics/1}.
     */
    public long polledCount() {
        return ProcSys.statistics(handlerProc).messagesIn();
    }

    /** Returns the current number of messages waiting in the poll queue. */
    public int queueSize() {
        return queue.size();
    }

    /**
     * Returns the underlying {@link Proc} handler process for JOTP introspection.
     * Use {@link ProcSys#statistics(Proc)} or {@link ProcSys#getState(Proc)} to inspect.
     */
    public Proc<Long, T> handlerProc() {
        return handlerProc;
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
        handlerProc.stop();
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                T message = queue.poll(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (message != null) {
                    handlerProc.tell(message); // deliver to Proc mailbox — OTP semantics
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
