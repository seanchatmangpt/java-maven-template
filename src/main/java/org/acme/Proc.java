package org.acme;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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
 * <p>Exit trapping (see {@link #trapExits}) converts incoming EXIT signals from linked processes
 * into {@link ExitSignal} messages delivered to the mailbox rather than interrupting the process —
 * mirroring OTP's {@code process_flag(trap_exit, true)}.
 *
 * <p>Process introspection is available via {@link ProcSys}: get state, suspend/resume, and
 * per-process message statistics — mirroring OTP's {@code sys} module.
 *
 * @param <S> process state (immutable value type recommended)
 * @param <M> message type — use a {@code Record} or sealed interface of Records
 */
public final class Proc<S, M> {

    /** Internal envelope carrying both the message and an optional reply handle. */
    private record Envelope<M>(M msg, CompletableFuture<Object> reply) {}

    private final TransferQueue<Envelope<M>> mailbox = new LinkedTransferQueue<>();

    /**
     * Sys channel: {@link ProcSys#getState} offers a future here; the loop completes it with the
     * current state before processing the next regular message.
     */
    final TransferQueue<CompletableFuture<Object>> sysGetState = new LinkedTransferQueue<>();

    private final Thread thread;
    private volatile boolean stopped = false;

    /** OTP: {@code process_flag(trap_exit, true)} — EXIT signals become mailbox messages. */
    private volatile boolean trappingExits = false;

    /** OTP: {@code sys:suspend} — pause message processing without killing the process. */
    private volatile boolean suspended = false;

    private final Object suspendMonitor = new Object();

    /** Counters for {@link ProcSys#statistics}. */
    final LongAdder messagesIn = new LongAdder();

    final LongAdder messagesOut = new LongAdder();

    /** The last unhandled exception; set before crash callbacks fire. */
    public volatile Throwable lastError = null;

    /** Callbacks fired on abnormal termination (exception, not graceful {@link #stop()}). */
    private final List<Runnable> crashCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Callbacks fired on <em>any</em> termination — normal or abnormal. Argument is {@code null}
     * for a normal exit, or the {@link Throwable} exit reason for an abnormal one. Used by {@link
     * ProcessMonitor} and {@link ProcessRegistry}.
     */
    private final List<Consumer<Throwable>> terminationCallbacks = new CopyOnWriteArrayList<>();

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
                                    outer:
                                    while (!stopped || !mailbox.isEmpty()) {
                                        // 1. Drain sys get-state requests (higher priority)
                                        CompletableFuture<Object> stateQ;
                                        while ((stateQ = sysGetState.poll()) != null) {
                                            stateQ.complete(state);
                                        }

                                        // 2. Suspend check — block until resumed
                                        if (suspended) {
                                            synchronized (suspendMonitor) {
                                                while (suspended) {
                                                    try {
                                                        suspendMonitor.wait();
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                        break outer;
                                                    }
                                                }
                                            }
                                            continue;
                                        }

                                        // 3. Process next message (poll with timeout so suspend
                                        //    and sys checks are re-evaluated periodically)
                                        try {
                                            Envelope<M> env =
                                                    mailbox.poll(50, TimeUnit.MILLISECONDS);
                                            if (env == null) continue;
                                            messagesIn.increment();
                                            S next = handler.apply(state, env.msg());
                                            state = next;
                                            messagesOut.increment();
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

                                    // Complete any pending getState requests exceptionally
                                    CompletableFuture<Object> pending;
                                    while ((pending = sysGetState.poll()) != null) {
                                        pending.completeExceptionally(
                                                new IllegalStateException("process terminated"));
                                    }

                                    // Also treat interrupted-with-lastError as abnormal
                                    // (deliverExitSignal sets lastError then interrupts)
                                    if (crashedAbnormally || lastError != null) {
                                        for (Runnable cb : crashCallbacks) {
                                            cb.run();
                                        }
                                    }
                                    // Fire termination callbacks (monitor semantics — always)
                                    Throwable exitReason = (crashedAbnormally || lastError != null) ? lastError : null;
                                    for (Consumer<Throwable> cb : terminationCallbacks) {
                                        cb.accept(exitReason);
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
     * Timed request-reply — mirrors OTP's {@code gen_server:call(Pid, Msg, Timeout)}.
     *
     * <p>The returned future completes exceptionally with {@link java.util.concurrent.TimeoutException}
     * if the process does not respond within {@code timeout}.
     *
     * <p>Armstrong: "An unbounded call is a latent deadlock. Every call must have a timeout."
     */
    public CompletableFuture<S> ask(M msg, Duration timeout) {
        return ask(msg).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Enable or disable exit signal trapping — mirrors OTP's {@code process_flag(trap_exit, Flag)}.
     *
     * <p>When {@code true}, EXIT signals from linked processes are delivered as {@link ExitSignal}
     * messages to this process's mailbox instead of interrupting it. The process stays alive and can
     * choose how to handle each exit reason.
     *
     * <p>When {@code false} (default), EXIT signals kill this process immediately.
     */
    public void trapExits(boolean trap) {
        this.trappingExits = trap;
    }

    /** Returns {@code true} if this process is currently trapping exit signals. */
    public boolean isTrappingExits() {
        return trappingExits;
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
    public void addCrashCallback(Runnable cb) {
        crashCallbacks.add(cb);
    }

    /**
     * Register a callback fired when this process terminates for any reason. {@code null} reason
     * means normal exit; non-null is the abnormal exit cause. Used by {@link ProcessMonitor} and
     * {@link ProcessRegistry}.
     */
    void addTerminationCallback(Consumer<Throwable> cb) {
        terminationCallbacks.add(cb);
    }

    /** Remove a previously registered termination callback (for {@link ProcessMonitor#demonitor}). */
    boolean removeTerminationCallback(Consumer<Throwable> cb) {
        return terminationCallbacks.remove(cb);
    }

    /**
     * Deliver an exit signal from a linked process. If this process is trapping exits, the signal
     * is converted to an {@link ExitSignal} message and delivered to the mailbox. Otherwise, the
     * process is interrupted immediately — OTP default behaviour.
     */
    @SuppressWarnings("unchecked")
    public void deliverExitSignal(Throwable reason) {
        if (trappingExits) {
            mailbox.add(new Envelope<>((M) new ExitSignal(reason), null));
        } else {
            interruptAbnormally(reason);
        }
    }

    /**
     * Interrupt this process and mark it as crashed — used by {@link ProcessLink} to deliver exit
     * signals from a linked process when exit trapping is off.
     */
    void interruptAbnormally(Throwable reason) {
        lastError = reason;
        thread.interrupt();
    }

    /** Public: exposes the underlying virtual thread (for joining, etc.). */
    public Thread thread() {
        return thread;
    }

    /** {@code true} if this process has been gracefully stopped or has finished. */
    boolean isStopped() {
        return stopped;
    }

    /**
     * Package-private: suspend this process — used by {@link ProcSys#suspend}.
     * The process loop will block after finishing the current message.
     */
    void suspendProc() {
        suspended = true;
    }

    /**
     * Package-private: resume this process — used by {@link ProcSys#resume}.
     * The process loop continues immediately.
     */
    void resumeProc() {
        suspended = false;
        synchronized (suspendMonitor) {
            suspendMonitor.notifyAll();
        }
    }

    /** Package-private: current mailbox depth — used by {@link ProcSys#statistics}. */
    int mailboxSize() {
        return mailbox.size();
    }
}
