package org.acme.reactive;

import java.util.Comparator;
import java.util.TreeMap;
import java.util.function.Function;

import org.acme.Proc;
import org.acme.ProcSys;

/**
 * Resequencer — EIP routing pattern that collects out-of-sequence messages and
 * re-emits them in the correct order to the downstream channel.
 *
 * <p>When messages arrive out of order (due to parallel processing, network reordering,
 * or producer batching), the Resequencer buffers them until the expected next message
 * arrives, then flushes the longest contiguous in-order run to downstream.
 *
 * <p><strong>JOTP implementation:</strong> backed by a {@link Proc}{@code <State<T,K>, Entry<T,K>>}
 * where the process state holds the pending-message {@link TreeMap} and the next expected key.
 * Every submitted message is delivered to the Proc mailbox — ordered, fault-isolated, and
 * introspectable via {@link ProcSys}.
 *
 * <p>The handler function is a pure, side-effect-free state transition:
 * <ol>
 *   <li>Insert the new entry into the sorted pending map</li>
 *   <li>Flush the longest contiguous run starting at {@code nextExpected}</li>
 *   <li>Return the updated state</li>
 * </ol>
 *
 * <p>OTP analogy: a {@code gen_server} maintaining a {@code gb_trees} (sorted map) of
 * out-of-order messages, flushing contiguous sequences when gaps close — pure state machine
 * with no mutable shared data.
 *
 * @param <T> message type
 * @param <K> sequence key type; must implement {@link Comparable}
 */
public final class Resequencer<T, K extends Comparable<K>> implements AutoCloseable {

    /** An incoming message paired with its sequence key. */
    public record Entry<T, K>(K key, T message) {}

    // ── Internal Proc state ───────────────────────────────────────────────────

    /**
     * Immutable state held by the backing Proc.
     *
     * @param nextExpected the key of the next message to emit (null = first key not yet seen)
     * @param pending      sorted map of buffered out-of-order entries
     * @param resequenced  running count of messages forwarded downstream in order
     */
    record State<T, K>(K nextExpected, TreeMap<K, T> pending, long resequenced) {
        @SuppressWarnings("unchecked")
        static <T, K extends Comparable<K>> State<T, K> initial() {
            // Cast needed: Comparator.naturalOrder() infers Comparator<Object>; safe for K extends Comparable<K>
            return new State<>(null, new TreeMap<>((Comparator<K>) Comparator.naturalOrder()), 0L);
        }
    }

    // ── JOTP Proc backing this resequencer ───────────────────────────────────

    private final Function<T, K> keyExtractor;
    private final Function<K, K> nextKey;
    private final MessageChannel<T> downstream;

    /** The backing Proc whose state IS the resequencer's sorted pending map. */
    private final Proc<State<T, K>, Entry<T, K>> proc;

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

        this.proc = new Proc<>(State.initial(), (State<T, K> state, Entry<T, K> entry) -> {
            // Pure state transition: insert → flush contiguous run → return new state
            var pending = new TreeMap<>(state.pending());
            pending.put(entry.key(), entry.message());

            // Determine starting key (initialize on first message if not yet set)
            K expected = state.nextExpected() != null ? state.nextExpected() : entry.key();

            long count = state.resequenced();
            while (pending.containsKey(expected)) {
                T msg = pending.remove(expected);
                downstream.send(msg);
                count++;
                expected = nextKey.apply(expected);
            }

            return new State<>(expected, pending, count);
        });
    }

    /**
     * Submits a message to the resequencer. May be called from any thread.
     * The message is enqueued in the backing Proc's mailbox and forwarded downstream
     * when its turn arrives in sequence order.
     */
    public void send(T message) {
        K key = keyExtractor.apply(message);
        proc.tell(new Entry<>(key, message));
    }

    /**
     * Returns the count of messages forwarded downstream in correct sequence order.
     * Reads from JOTP process statistics — consistent with OTP {@code sys:statistics/1}.
     */
    public long resequencedCount() {
        try {
            return ProcSys.getState(proc).get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .resequenced();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Returns the number of messages currently buffered awaiting their expected predecessors.
     */
    public int pendingCount() {
        try {
            return ProcSys.getState(proc).get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .pending().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns the backing {@link Proc} for JOTP introspection via {@link ProcSys}.
     * Use {@link ProcSys#statistics(Proc)}, {@link ProcSys#getState(Proc)},
     * or {@link ProcSys#suspend(Proc)}/{@link ProcSys#resume(Proc)} for runtime control.
     */
    public Proc<State<T, K>, Entry<T, K>> proc() {
        return proc;
    }

    @Override
    public void close() throws InterruptedException {
        proc.stop();
    }
}
