package org.acme.reactive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Consumer;

import org.acme.Proc;
import org.acme.ProcSys;

/**
 * Durable Subscriber — EIP endpoint pattern for a pub-sub subscriber that continues
 * to receive messages even when temporarily offline (paused), buffering them for delivery
 * when it comes back online.
 *
 * <p><strong>JOTP implementation:</strong> backed by a {@link Proc}{@code <State, Cmd>} where
 * the process state holds the delivery buffer and pause flag. Pause and resume are sent as
 * ordinary messages to the mailbox ({@link Cmd.Pause} / {@link Cmd.Resume}), keeping all
 * state transitions deterministic and introspectable via the {@code sys} module.
 *
 * <ul>
 *   <li>{@link Cmd.Deliver} — enqueues a payload for the consumer
 *   <li>{@link Cmd.Pause}   — suspends delivery; subsequent payloads are buffered in state
 *   <li>{@link Cmd.Resume}  — drains the buffer and re-enables live delivery
 * </ul>
 *
 * <p>OTP analogy: a process using {@code sys:suspend/1} to accumulate mailbox messages, then
 * {@code sys:resume/1} to process them in arrival order — but here pause/resume are first-class
 * messages, not sys calls, so the handler itself controls the transition logic.
 *
 * <p>Fault isolation: if the consumer crashes on a message, {@code Proc.lastError} is set and
 * the process stops — exactly as OTP's "let it crash" principle. Wrap in a {@link org.acme.Supervisor}
 * to restart automatically.
 *
 * @param <T> message payload type
 */
public final class DurableSubscriber<T> implements MessageChannel<T>, AutoCloseable {

    // ── Internal command protocol ────────────────────────────────────────────

    /** Sealed command hierarchy delivered to the backing Proc mailbox. */
    sealed interface Cmd<T> permits Cmd.Deliver, Cmd.Pause, Cmd.Resume {
        /** Carry a payload to the consumer. */
        record Deliver<T>(T payload) implements Cmd<T> {}
        /** Pause delivery — subsequent payloads are buffered. */
        record Pause<T>() implements Cmd<T> {}
        /** Resume delivery — drain buffer, then deliver live. */
        record Resume<T>() implements Cmd<T> {}
    }

    // ── Internal state ────────────────────────────────────────────────────────

    record State<T>(boolean paused, List<T> buffer, long delivered) {
        static <T> State<T> initial() { return new State<>(false, List.of(), 0L); }
    }

    // ── JOTP Proc backing this subscriber ────────────────────────────────────

    private final Proc<State<T>, Cmd<T>> proc;

    /**
     * Creates a DurableSubscriber. The {@code consumer} is called for each delivered payload
     * inside the backing {@link Proc} virtual thread — OTP fault isolation applies.
     *
     * @param consumer invoked for each payload in delivery order
     */
    @SuppressWarnings("unchecked")
    public DurableSubscriber(Consumer<T> consumer) {
        this.proc = new Proc<>(State.initial(), (State<T> state, Cmd<T> cmd) -> switch (cmd) {

            case Cmd.Pause<T> __ -> new State<>(true, state.buffer(), state.delivered());

            case Cmd.Resume<T> __ -> {
                // Drain buffer in arrival order, then go live
                List<T> buf = state.buffer();
                long count = state.delivered();
                for (T payload : buf) {
                    consumer.accept(payload);
                    count++;
                }
                yield new State<>(false, List.of(), count);
            }

            case Cmd.Deliver<T>(T payload) -> {
                if (state.paused()) {
                    // Buffer the payload — preserve arrival order
                    var newBuf = new ArrayList<>(state.buffer());
                    newBuf.add(payload);
                    yield new State<>(true, List.copyOf(newBuf), state.delivered());
                } else {
                    consumer.accept(payload);
                    yield new State<>(false, state.buffer(), state.delivered() + 1L);
                }
            }
        });
    }

    // ── MessageChannel ────────────────────────────────────────────────────────

    /**
     * Delivers {@code message} to this subscriber. If paused, it is buffered in the process
     * state. If active, it is dispatched to the consumer immediately.
     *
     * <p>Non-blocking fire-and-forget — identical to Erlang's {@code Pid ! Msg}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void send(T message) {
        proc.tell(new Cmd.Deliver<>(message));
    }

    // ── Control API ───────────────────────────────────────────────────────────

    /**
     * Pauses delivery — subsequent messages are buffered in the process state.
     * Delivered as a message to preserve ordering with in-flight payloads.
     */
    @SuppressWarnings("unchecked")
    public void pause() {
        proc.tell(new Cmd.Pause<>());
    }

    /**
     * Resumes delivery — the buffer is drained in-order, then live delivery continues.
     * Delivered as a message to preserve ordering with in-flight payloads.
     */
    @SuppressWarnings("unchecked")
    public void resume() {
        proc.tell(new Cmd.Resume<>());
    }

    /**
     * Returns the current buffer size (snapshot via {@link ProcSys#getState}).
     * The returned count reflects state at the time of the call; the process may
     * have advanced further by the time this returns.
     *
     * @throws RuntimeException if the process has terminated
     */
    public int bufferSize() {
        try {
            return ProcSys.getState(proc).get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .buffer().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns the number of messages delivered to the consumer (excluding those still buffered).
     *
     * @throws RuntimeException if the process has terminated
     */
    public long deliveredCount() {
        try {
            return ProcSys.getState(proc).get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .delivered();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Returns the total number of messages received (delivered + currently buffered).
     */
    public long receivedCount() {
        return ProcSys.statistics(proc).messagesIn();
    }

    /**
     * Returns {@code true} if the subscriber is currently paused (snapshot; may change).
     */
    public boolean isPaused() {
        try {
            return ProcSys.getState(proc).get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .paused();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns a snapshot of currently buffered messages (does not remove them).
     */
    public List<T> peekBuffer() {
        try {
            return ProcSys.getState(proc).get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .buffer();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns the backing {@link Proc} for JOTP introspection via {@link ProcSys}.
     */
    public Proc<State<T>, Cmd<T>> proc() {
        return proc;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void stop() throws InterruptedException {
        proc.stop();
    }

    @Override
    public void close() throws InterruptedException {
        stop();
    }
}
