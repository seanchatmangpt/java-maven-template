package org.acme.reactive;

import java.util.Comparator;
import java.util.TreeMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Resequencer — EIP routing pattern that collects out-of-sequence messages and
 * re-emits them in the correct order to the downstream channel.
 *
 * <p>When messages arrive out of order (due to parallel processing, network reordering,
 * or producer batching), the Resequencer buffers them until the expected next message
 * arrives, then flushes the longest contiguous in-order run to downstream.
 *
 * <p>Key design choices:
 * <ul>
 *   <li>Sequence keys are {@link Comparable} — no external comparator needed.
 *   <li>A virtual thread runs the sequencing loop, keeping ordering off the caller thread.
 *   <li>When a gap is detected, subsequent in-order messages are held in a {@link TreeMap}
 *       until the gap is filled (unbounded buffering — callers must not create infinite gaps).
 *   <li>The initial expected key is the first key received (zero-offset support).
 * </ul>
 *
 * <p>Erlang/OTP analogy: a {@code gen_server} holding a priority queue (gb_trees) of
 * out-of-order messages, flushing contiguous sequences when gaps close.
 *
 * @param <T> message type
 * @param <K> sequence key type; must implement {@link Comparable}
 */
public final class Resequencer<T, K extends Comparable<K>> implements AutoCloseable {

    /** An incoming message paired with its sequence key. */
    public record Entry<T, K>(K key, T message) {}

    private final Function<T, K> keyExtractor;
    private final Function<K, K> nextKey;        // key → next expected key (e.g. n → n+1)
    private final MessageChannel<T> downstream;
    private final LinkedTransferQueue<Entry<T, K>> inbox = new LinkedTransferQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong resequenced = new AtomicLong(0);
    private final Thread sequencerThread;

    private volatile K nextExpected = null; // null = not yet initialized

    /**
     * Creates a Resequencer.
     *
     * @param keyExtractor extracts the sequence key from a message
     * @param nextKey      given key K, returns the key of the next expected message
     *                     (e.g. {@code n -> n + 1} for {@code Long} keys)
     * @param downstream   channel that receives messages in sequence order
     */
    public Resequencer(
            Function<T, K> keyExtractor,
            Function<K, K> nextKey,
            MessageChannel<T> downstream) {
        this.keyExtractor = keyExtractor;
        this.nextKey = nextKey;
        this.downstream = downstream;
        this.sequencerThread = Thread.ofVirtual()
                .name("resequencer")
                .start(this::sequenceLoop);
    }

    /**
     * Submits a message to the resequencer. May be called from any thread.
     * The message is buffered and forwarded downstream when its turn arrives.
     */
    public void send(T message) {
        K key = keyExtractor.apply(message);
        inbox.offer(new Entry<>(key, message));
    }

    /** Returns the count of messages forwarded downstream in correct sequence order. */
    public long resequencedCount() {
        return resequenced.get();
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        sequencerThread.interrupt();
        sequencerThread.join(1000);
    }

    private void sequenceLoop() {
        TreeMap<K, T> pending = new TreeMap<>(Comparator.naturalOrder());

        while (running.get()) {
            try {
                Entry<T, K> entry = inbox.poll();
                if (entry == null) {
                    Thread.sleep(1);
                    continue;
                }

                // Initialize expected key from first message received
                if (nextExpected == null) {
                    nextExpected = entry.key();
                }

                pending.put(entry.key(), entry.message());

                // Flush contiguous in-order run
                while (pending.containsKey(nextExpected)) {
                    T msg = pending.remove(nextExpected);
                    downstream.send(msg);
                    resequenced.incrementAndGet();
                    nextExpected = nextKey.apply(nextExpected);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Swallow to keep sequencer alive
            }
        }
    }
}
