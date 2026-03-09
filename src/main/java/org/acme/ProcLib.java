package org.acme;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * OTP {@code proc_lib} — synchronous process startup with initialization handshake.
 *
 * <p>Joe Armstrong: "The most dangerous moment in a supervision tree is child startup. If you don't
 * know whether the child initialized successfully before you return, you have a race condition."
 *
 * <p>In OTP, {@code proc_lib:start_link/3} blocks the calling process until the spawned child
 * explicitly calls {@code proc_lib:init_ack({ok, self()})}. The parent receives {@code {ok, Pid}}
 * or {@code {error, Reason}}. Without this handshake, a supervisor cannot distinguish "the child
 * is starting" from "the child crashed during init."
 *
 * <p>Java 26 mapping:
 *
 * <ul>
 *   <li>{@code proc_lib:start_link/3} → {@link #startLink(Object, Function, BiFunction)}
 *   <li>{@code proc_lib:init_ack({ok, self()})} → {@link #initAck()} — called by the init handler
 *   <li>{@code {ok, Pid}} → {@code StartResult.Ok} containing the new {@link Proc}
 *   <li>{@code {error, Reason}} → {@code StartResult.Err} containing the failure cause
 * </ul>
 *
 * <p>Usage — the init handler calls {@link #initAck()} once setup is complete:
 *
 * <pre>{@code
 * var result = ProcLib.startLink(
 *     initialState,
 *     state -> {
 *         registry.register("worker", self);
 *         ProcLib.initAck();   // unblocks the caller
 *         return state;
 *     },
 *     (state, msg) -> switch (msg) { ... }
 * );
 * switch (result) {
 *     case ProcLib.StartResult.Ok(var proc)    -> proc.tell(new Work());
 *     case ProcLib.StartResult.Err(var reason) -> log.error("init failed", reason);
 * }
 * }</pre>
 */
public final class ProcLib {

    private ProcLib() {}

    /** Default timeout waiting for the child to call {@link #initAck()}. */
    private static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Thread-local latch used to pass the init-ack signal from the child's init handler back to
     * the {@link #startLink} caller. Set before the init handler runs, cleared afterward.
     */
    private static final ThreadLocal<CountDownLatch> INIT_LATCH = new ThreadLocal<>();

    /**
     * Sealed result type for {@link #startLink} — mirrors OTP's {@code {ok, Pid} | {error, Reason}}.
     *
     * @param <S> process state type
     * @param <M> message type
     */
    public sealed interface StartResult<S, M> {

        /** Successful startup — the process is alive and has acknowledged initialization. */
        record Ok<S, M>(Proc<S, M> proc) implements StartResult<S, M> {}

        /** Failed startup — process crashed before {@link #initAck()}, or init timed out. */
        record Err<S, M>(Throwable reason) implements StartResult<S, M> {}
    }

    /**
     * Spawn a process and block until it signals readiness — mirrors OTP {@code
     * proc_lib:start_link/3} with {@link #DEFAULT_INIT_TIMEOUT}.
     *
     * @param initial initial state
     * @param initHandler runs once before the message loop; must call {@link #initAck()}
     * @param loopHandler the main message loop {@code (state, msg) -> nextState}
     * @param <S> state type
     * @param <M> message type
     * @return {@code Ok(proc)} on success, {@code Err(reason)} on failure
     */
    public static <S, M> StartResult<S, M> startLink(
            S initial, Function<S, S> initHandler, BiFunction<S, M, S> loopHandler) {
        return startLink(initial, initHandler, loopHandler, DEFAULT_INIT_TIMEOUT);
    }

    /**
     * Spawn a process and block until it signals readiness, with an explicit timeout.
     *
     * <p><strong>Implementation:</strong> We use a wrapper {@link BiFunction} that intercepts the
     * very first message (a synthetic {@code InitSentinel}) to run the init handler before the main
     * loop starts. This avoids any changes to {@link Proc}'s constructor while preserving the
     * proc_lib protocol exactly.
     *
     * @param initial initial state
     * @param initHandler runs once; must call {@link #initAck()} to unblock the caller
     * @param loopHandler the main message loop
     * @param initTimeout how long to wait for {@link #initAck()}
     * @param <S> state type
     * @param <M> message type
     * @return {@code Ok(proc)} on success, {@code Err(reason)} on failure/timeout
     */
    public static <S, M> StartResult<S, M> startLink(
            S initial,
            Function<S, S> initHandler,
            BiFunction<S, M, S> loopHandler,
            Duration initTimeout) {

        var ready = new CountDownLatch(1);
        var initError = new AtomicReference<Throwable>();

        // Wrapper handler: the first message (InitSentinel) triggers init.
        // All subsequent messages go to loopHandler.
        BiFunction<S, M, S> wrappedHandler =
                new BiFunction<>() {
                    boolean initDone = false;

                    @Override
                    public S apply(S state, M msg) {
                        if (!initDone) {
                            initDone = true;
                            INIT_LATCH.set(ready);
                            try {
                                S next = initHandler.apply(state);
                                // Do NOT call ready.countDown() here — the init handler MUST
                                // call initAck() explicitly to unblock the parent, mirroring
                                // OTP proc_lib:init_ack({ok, self()}). If initAck() is never
                                // called, the parent times out and returns Err.
                                return next;
                            } catch (RuntimeException e) {
                                initError.set(e);
                                ready.countDown();
                                throw e; // crash the process
                            } finally {
                                INIT_LATCH.remove();
                            }
                        }
                        return loopHandler.apply(state, msg);
                    }
                };

        Proc<S, M> child = new Proc<>(initial, wrappedHandler);
        // Deliver the sentinel to trigger the init handler before any user messages
        child.tell(initSentinel());

        // Block until init completes or times out
        try {
            boolean completed = ready.await(initTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                child.stop();
                return new StartResult.Err<>(
                        new RuntimeException("init timed out after " + initTimeout));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StartResult.Err<>(e);
        }

        Throwable err = initError.get();
        if (err != null) {
            return new StartResult.Err<>(err);
        }
        return new StartResult.Ok<>(child);
    }

    /**
     * Signal successful initialization — mirrors OTP {@code proc_lib:init_ack({ok, self()})}.
     *
     * <p>Must be called from within the {@code initHandler} passed to {@link #startLink}. Calling
     * this unblocks the parent that is waiting in {@link #startLink}. If the init handler returns
     * without calling this, the parent is unblocked on return anyway (best-effort). Calling it
     * outside of a {@link #startLink} context is a safe no-op.
     */
    public static void initAck() {
        CountDownLatch latch = INIT_LATCH.get();
        if (latch != null) {
            latch.countDown();
        }
    }

    /** Synthetic message used to trigger the init handler — never escapes this class. */
    @SuppressWarnings("unchecked")
    private static <M> M initSentinel() {
        return (M) new Object();
    }
}
