package org.acme;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;
import java.util.function.BiFunction;

/**
 * Lightweight process with a virtual-thread mailbox — Java 26 equivalent of an Erlang process.
 *
 * <p>Joe Armstrong: "Processes share nothing, communicate only by message passing. A process is the
 * unit of concurrency."
 *
 * <p>Mapping to Java 26:
 *
 * <ul>
 *   <li>Erlang lightweight process → virtual thread (one per process, ~1 KB heap)
 *   <li>Erlang mailbox → {@link LinkedTransferQueue} (lock-free MPMC, 50–150 ns/message)
 *   <li>Erlang message → {@code M} (use a {@code Record} or sealed-Record hierarchy for
 *       immutability by construction)
 *   <li>Shared-nothing state → {@code S} held privately, never returned by reference
 * </ul>
 *
 * <p>Crash callbacks (see {@link #addCrashCallback}) are fired when this process terminates
 * abnormally (unhandled exception). Normal {@link #stop()} does <em>not</em> fire them — mirroring
 * Erlang's distinction between {@code normal} and non-normal exit reasons.
 *
 * @param <S> process state (immutable value type recommended)
 * @param <M> message type — use a {@code Record} or sealed interface of Records
 */
public final class Proc<S, M> {

    /** Internal envelope carrying both the message and an optional reply handle. */
    private record Envelope<M>(M msg, CompletableFuture<Object> reply) {}

    private final TransferQueue<Envelope<M>> mailbox = new LinkedTransferQueue<>();
    private final Thread thread;
    private volatile boolean stopped = false;

    /** The last unhandled exception; set before crash callbacks fire. */
    volatile Throwable lastError = null;

    /** Callbacks fired on abnormal termination (exception, not graceful {@link #stop()}). */
    private final List<Runnable> crashCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Create and start a process.
     *
     * @param initial initial state
     * @param handler {@code (state, message) -> nextState} — pure function, no side-effects
     */
    public Proc(S initial, BiFunction<S, M, S> handler) {
        thread =
                Thread.ofVirtual()
                        .name("proc")
                        .start(
                                () -> {
                                    S state = initial;
                                    boolean crashedAbnormally = false;
                                    while (!stopped || !mailbox.isEmpty()) {
                                        try {
                                            Envelope<M> env = mailbox.take();
                                            S next = handler.apply(state, env.msg());
                                            state = next;
                                            if (env.reply() != null) {
                                                env.reply().complete(state);
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        } catch (RuntimeException e) {
                                            lastError = e;
                                            crashedAbnormally = true;
                                            break;
                                        }
                                    }
                                    if (crashedAbnormally) {
                                        for (Runnable cb : crashCallbacks) {
                                            cb.run();
                                        }
                                    }
                                });
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without waiting for processing.
     *
     * <p>Armstrong: "!" (send) is the primary mode — caller never blocks, process handles at its
     * own pace.
     */
    public void tell(M msg) {
        mailbox.add(new Envelope<>(msg, null));
    }

    /**
     * Request-reply: send {@code msg} and return a future that completes with the process's state
     * after the message is processed.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<S> ask(M msg) {
        var future = new CompletableFuture<Object>();
        mailbox.add(new Envelope<>(msg, future));
        return future.thenApply(s -> (S) s);
    }

    /**
     * Graceful shutdown: signal the process to stop after draining remaining messages, then wait
     * for the virtual thread to finish. Does <em>not</em> fire crash callbacks.
     */
    public void stop() throws InterruptedException {
        stopped = true;
        thread.interrupt();
        thread.join();
    }

    /**
     * Register a callback to be invoked when this process terminates abnormally (unhandled
     * exception). Called by {@link Supervisor} and {@link ProcessLink}.
     */
    void addCrashCallback(Runnable cb) {
        crashCallbacks.add(cb);
    }

    /**
     * Interrupt this process and mark it as crashed — used by {@link ProcessLink} to deliver exit
     * signals from a linked process.
     */
    void interruptAbnormally(Throwable reason) {
        lastError = reason;
        thread.interrupt();
    }

    /** Package-private: exposes the underlying virtual thread (for joining, etc.). */
    Thread thread() {
        return thread;
    }

    /** {@code true} if this process has been gracefully stopped or has finished. */
    boolean isStopped() {
        return stopped;
    }
}
