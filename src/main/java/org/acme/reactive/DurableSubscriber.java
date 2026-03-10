package org.acme.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Durable Subscriber — EIP endpoint pattern for a pub-sub subscriber that continues
 * to receive messages even when temporarily offline (paused), buffering them for delivery
 * when it comes back online.
 *
 * <p>In contrast to a transient subscriber (which misses messages published during its
 * absence), a DurableSubscriber maintains a persistent message buffer. This pattern is
 * essential for ensuring at-least-once delivery in event-driven architectures.
 *
 * <p>Implementation: messages arriving while the subscriber is paused are stored in an
 * internal {@link LinkedTransferQueue}. When the subscriber resumes, buffered messages
 * are drained and delivered to the handler before new messages are accepted, preserving
 * ordering guarantees. A dedicated virtual thread drives the delivery loop.
 *
 * <p>Erlang/OTP analogy: a process that {@code suspend}s via {@code sys:suspend/1} —
 * the mailbox accumulates messages; on {@code sys:resume/1} all buffered messages are
 * processed in order before new ones.
 *
 * @param <T> message type
 */
public final class DurableSubscriber<T> implements MessageChannel<T>, AutoCloseable {

    private final LinkedTransferQueue<T> buffer = new LinkedTransferQueue<>();
    private final Consumer<T> handler;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong received = new AtomicLong(0);
    private final AtomicLong delivered = new AtomicLong(0);
    private final Object pauseLock = new Object();
    private final Thread deliveryThread;

    /**
     * Creates a DurableSubscriber that processes messages with {@code handler} on a
     * dedicated virtual thread.
     *
     * @param handler invoked for each message; runs on a virtual thread
     */
    public DurableSubscriber(Consumer<T> handler) {
        this.handler = handler;
        this.deliveryThread = Thread.ofVirtual()
                .name("durable-subscriber")
                .start(this::deliveryLoop);
    }

    /**
     * Enqueues {@code message} for delivery. If the subscriber is paused, the message
     * is buffered. If active, it is buffered and the delivery loop picks it up promptly.
     *
     * <p>Thread-safe; never blocks the sender.
     */
    @Override
    public void send(T message) {
        received.incrementAndGet();
        buffer.offer(message);
    }

    /**
     * Pauses delivery — subsequent messages are buffered until {@link #resume()} is called.
     * Already-queued messages are not discarded.
     */
    public void pause() {
        paused.set(true);
    }

    /**
     * Resumes delivery — buffered messages are drained and delivered in arrival order,
     * then new messages flow normally.
     */
    public void resume() {
        paused.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /** Returns {@code true} if the subscriber is currently paused. */
    public boolean isPaused() {
        return paused.get();
    }

    /** Returns the total number of messages received (including those still buffered). */
    public long receivedCount() {
        return received.get();
    }

    /** Returns the number of messages delivered to the handler. */
    public long deliveredCount() {
        return delivered.get();
    }

    /** Returns the number of messages currently in the buffer awaiting delivery. */
    public int bufferSize() {
        return buffer.size();
    }

    /** Returns a snapshot of all currently buffered messages (does not remove them). */
    public List<T> peekBuffer() {
        return new ArrayList<>(buffer);
    }

    @Override
    public void stop() throws InterruptedException {
        close();
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        deliveryThread.interrupt();
        deliveryThread.join(1000);
    }

    private void deliveryLoop() {
        while (running.get()) {
            try {
                synchronized (pauseLock) {
                    while (paused.get() && running.get()) {
                        pauseLock.wait(100);
                    }
                }
                T message = buffer.poll();
                if (message != null) {
                    handler.accept(message);
                    delivered.incrementAndGet();
                } else {
                    Thread.sleep(1); // brief yield when idle
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Swallow handler exceptions — durable means the subscriber stays alive
            }
        }
    }
}
