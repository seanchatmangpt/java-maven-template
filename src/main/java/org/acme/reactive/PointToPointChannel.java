package org.acme.reactive;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Point-to-point channel: exactly one consumer processes each message, in order.
 *
 * <p>Enterprise Integration Pattern: <em>Point-to-Point Channel</em> (EIP §6.1). Erlang analog: a
 * dedicated Erlang process with a mailbox — one message sender, one receiver process.
 *
 * <p>Backed by a {@link LinkedTransferQueue} (lock-free MPMC, 50–150 ns/message) and a single
 * consumer virtual thread. Producers never block; the consumer processes messages sequentially at
 * its own pace — identical to how an Erlang process drains its mailbox.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var channel = new PointToPointChannel<Order>(order -> fulfil(order));
 * channel.send(new Order("item-1", 3));
 * channel.send(new Order("item-2", 1));
 * channel.stop();
 * }</pre>
 *
 * @param <T> message type
 */
public final class PointToPointChannel<T> implements MessageChannel<T> {

    private final LinkedTransferQueue<T> queue = new LinkedTransferQueue<>();
    private final Thread consumerThread;
    private volatile boolean stopped = false;

    /**
     * Create and start a point-to-point channel.
     *
     * <p>The {@code consumer} is invoked sequentially in a single virtual thread — identical to how
     * an Erlang process handles one message at a time from its mailbox.
     *
     * @param consumer the message consumer — invoked once per message, in order
     */
    public PointToPointChannel(Consumer<T> consumer) {
        consumerThread =
                Thread.ofVirtual()
                        .name("p2p-channel")
                        .start(
                                () -> {
                                    while (!stopped || !queue.isEmpty()) {
                                        try {
                                            T msg = queue.poll(50, TimeUnit.MILLISECONDS);
                                            if (msg != null) {
                                                consumer.accept(msg);
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    }
                                });
    }

    /**
     * Enqueue a message for the consumer — non-blocking, fire-and-forget.
     *
     * @param message the message to enqueue
     */
    @Override
    public void send(T message) {
        queue.add(message);
    }

    /**
     * Stop accepting new messages, drain the queue, then shut down the consumer thread.
     *
     * @throws InterruptedException if interrupted while waiting for the consumer to finish
     */
    @Override
    public void stop() throws InterruptedException {
        stopped = true;
        consumerThread.interrupt();
        consumerThread.join();
    }

    /** Current number of messages waiting to be processed. */
    public int queueDepth() {
        return queue.size();
    }
}
