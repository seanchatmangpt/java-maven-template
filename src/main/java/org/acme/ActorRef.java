package org.acme;

import java.util.concurrent.CompletableFuture;

/**
 * Stable opaque handle to a supervised actor — mirrors Erlang's {@code Pid}.
 *
 * <p>Joe Armstrong: "A process identifier should be opaque. Callers must not know or care whether
 * the process has restarted."
 *
 * <p>When a {@link Supervisor} restarts a crashed child, it atomically {@link #swap swaps} the
 * underlying {@link Actor}. All existing {@code ActorRef} handles transparently redirect to the new
 * actor without any caller changes. This is Erlang location-transparency in Java.
 *
 * @param <S> actor state type
 * @param <M> message type (use a {@code Record} or sealed-Record hierarchy)
 */
public final class ActorRef<S, M> {

    private volatile Actor<S, M> delegate;

    ActorRef(Actor<S, M> initial) {
        this.delegate = initial;
    }

    /** Replace the underlying actor (called by {@link Supervisor} on restart). */
    void swap(Actor<S, M> next) {
        this.delegate = next;
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without blocking.
     *
     * <p>If the actor is mid-restart, the message goes to the stale actor and is lost — callers
     * should use {@link #ask} with Awaitility retries if delivery during restart matters.
     */
    public void tell(M msg) {
        delegate.tell(msg);
    }

    /**
     * Request-reply: returns a {@link CompletableFuture} that completes with the actor's state
     * after {@code msg} is processed. Times out naturally if the actor is restarting.
     */
    public CompletableFuture<S> ask(M msg) {
        return delegate.ask(msg);
    }

    /**
     * Gracefully stop this actor. Does <em>not</em> notify the supervisor — use {@link
     * Supervisor#shutdown()} to stop the whole tree.
     */
    public void stop() throws InterruptedException {
        delegate.stop();
    }
}
