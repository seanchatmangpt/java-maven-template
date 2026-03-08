package org.acme;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;
import java.util.function.BiFunction;

/**
 * Lightweight actor with a virtual-thread mailbox, derived from YAWL's actor model design.
 *
 * <p>Joe Armstrong: "Processes share nothing, communicate only by message passing. A process is the
 * unit of concurrency."
 *
 * <p>Mapping to Java 21:
 *
 * <ul>
 *   <li>Erlang lightweight process → virtual thread (one per actor, ~1 KB heap)
 *   <li>Erlang mailbox → {@link LinkedTransferQueue} (lock-free MPMC, 50–150 ns/message)
 *   <li>Erlang message → {@code M} (use a {@code Record} or sealed-Record hierarchy for
 *       immutability by construction)
 *   <li>Shared-nothing state → {@code S} held privately, never returned by reference
 * </ul>
 *
 * @param <S> actor state (immutable value type recommended)
 * @param <M> message type — use a {@code Record} or sealed interface of Records
 */
public final class Actor<S, M> {

    /** Internal envelope carrying both the message and an optional reply handle. */
    private record Envelope<M>(M msg, CompletableFuture<Object> reply) {}

    private final TransferQueue<Envelope<M>> mailbox = new LinkedTransferQueue<>();
    private final Thread thread;
    private volatile boolean stopped = false;

    /**
     * Create and start an actor.
     *
     * @param initial initial state
     * @param handler {@code (state, message) -> nextState} — pure function, no side-effects
     */
    public Actor(S initial, BiFunction<S, M, S> handler) {
        thread =
                Thread.ofVirtual()
                        .name("actor")
                        .start(
                                () -> {
                                    S state = initial;
                                    while (!stopped || !mailbox.isEmpty()) {
                                        try {
                                            Envelope<M> env = mailbox.take();
                                            state = handler.apply(state, env.msg());
                                            if (env.reply() != null) {
                                                env.reply().complete(state);
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    }
                                });
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without waiting for processing.
     *
     * <p>Armstrong: "tell" is the primary mode — caller never blocks, actor processes at its own
     * pace.
     */
    public void tell(M msg) {
        mailbox.add(new Envelope<>(msg, null));
    }

    /**
     * Request-reply: send {@code msg} and return a future that completes with the actor's state
     * after the message is processed.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<S> ask(M msg) {
        var future = new CompletableFuture<Object>();
        mailbox.add(new Envelope<>(msg, future));
        return future.thenApply(s -> (S) s);
    }

    /**
     * Graceful shutdown: signal the actor to stop after draining remaining messages, then wait for
     * the virtual thread to finish.
     */
    public void stop() throws InterruptedException {
        stopped = true;
        thread.interrupt();
        thread.join();
    }

    /** Package-private: allows {@link Supervisor} to install an uncaught exception handler. */
    Thread thread() {
        return thread;
    }
}
