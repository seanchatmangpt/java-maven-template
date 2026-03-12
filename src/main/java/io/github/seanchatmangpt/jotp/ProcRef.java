package io.github.seanchatmangpt.jotp;

import java.util.concurrent.CompletableFuture;

/**
 * Stable opaque handle to a supervised process — mirrors Erlang's {@code Pid}.
 *
 * <p>Joe Armstrong: "A process identifier should be opaque. Callers must not know or care whether
 * the process has restarted."
 *
 * <p>When a {@link Supervisor} restarts a crashed child, it atomically {@link #swap swaps} the
 * underlying {@link Proc}. All existing {@code ProcRef} handles transparently redirect to the new
 * process without any caller changes. This is Erlang location-transparency in Java.
 *
 * @param <S> process state type
 * @param <M> message type (use a {@code Record} or sealed-Record hierarchy)
 */
public final class ProcRef<S, M> {

    private volatile Proc<S, M> delegate;

    ProcRef(Proc<S, M> initial) {
        this.delegate = initial;
    }

    /** Replace the underlying process (called by {@link Supervisor} on restart). */
    void swap(Proc<S, M> next) {
        this.delegate = next;
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without blocking.
     *
     * <p>If the process is mid-restart, the message goes to the stale process and is lost — callers
     * should use {@link #ask} with Awaitility retries if delivery during restart matters.
     */
    public void tell(M msg) {
        delegate.tell(msg);
    }

    /**
     * Request-reply: returns a {@link CompletableFuture} that completes with the process's state
     * after {@code msg} is processed. Times out naturally if the process is restarting.
     */
    public CompletableFuture<S> ask(M msg) {
        return delegate.ask(msg);
    }

    /**
     * Gracefully stop this process. Does <em>not</em> notify the supervisor — use {@link
     * Supervisor#shutdown()} to stop the whole tree.
     */
    public void stop() throws InterruptedException {
        delegate.stop();
    }
}
