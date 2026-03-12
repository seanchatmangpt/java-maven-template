package io.github.seanchatmangpt.jotp;

import java.util.concurrent.CompletableFuture;

/**
 * OTP {@code sys} module — process introspection and control without stopping the process.
 *
 * <p>Joe Armstrong: "You must be able to look inside a running process without stopping it. A
 * process you cannot inspect is a black box you cannot trust in production."
 *
 * <p>OTP's {@code sys} module is what makes {@code gen_server}/{@code gen_statem} processes
 * <em>observable</em> at runtime. This class provides the Java 26 equivalents:
 *
 * <ul>
 *   <li>{@code sys:get_state(Pid)} → {@link #getState(Proc)} — snapshot current state asynchronously
 *   <li>{@code sys:suspend(Pid)} → {@link #suspend(Proc)} — pause message processing
 *   <li>{@code sys:resume(Pid)} → {@link #resume(Proc)} — resume message processing
 *   <li>{@code sys:statistics(Pid, get)} → {@link #statistics(Proc)} — message throughput snapshot
 * </ul>
 *
 * <p>All operations are non-blocking for the caller. {@link #getState} returns a future that
 * completes after the process finishes its current message — guaranteeing the snapshot is
 * consistent. {@link #suspend} and {@link #resume} take effect before the next message is
 * dequeued.
 */
public final class ProcSys {

    private ProcSys() {}

    /**
     * OTP {@code sys:statistics(Pid, get)} snapshot.
     *
     * @param messagesIn total messages received since process start
     * @param messagesOut total messages processed (state transitions) since process start
     * @param queueDepth current number of messages waiting in the mailbox
     */
    public record Stats(long messagesIn, long messagesOut, int queueDepth) {}

    /**
     * Asynchronously fetch the current state of {@code proc} — mirrors OTP {@code
     * sys:get_state(Pid)}.
     *
     * <p>The future completes after the process finishes processing its current in-flight message,
     * ensuring a consistent state snapshot. If the process is already terminated, the future
     * completes exceptionally with {@link IllegalStateException}.
     *
     * @param proc the target process
     * @param <S> state type
     * @param <M> message type
     * @return future completing with the process state
     */
    @SuppressWarnings("unchecked")
    public static <S, M> CompletableFuture<S> getState(Proc<S, M> proc) {
        if (!proc.thread().isAlive()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("process is not alive"));
        }
        var future = new CompletableFuture<Object>();
        proc.sysGetState.offer(future);
        return future.thenApply(s -> (S) s);
    }

    /**
     * Pause message processing — mirrors OTP {@code sys:suspend(Pid)}.
     *
     * <p>After this call returns, the process will not dequeue any further messages until {@link
     * #resume} is called. Messages continue to accumulate in the mailbox. Used in OTP for
     * hot-code upgrades: suspend → swap code → resume.
     *
     * @param proc the process to suspend
     */
    public static void suspend(Proc<?, ?> proc) {
        proc.suspendProc();
    }

    /**
     * Resume message processing after a {@link #suspend} — mirrors OTP {@code sys:resume(Pid)}.
     *
     * @param proc the process to resume
     */
    public static void resume(Proc<?, ?> proc) {
        proc.resumeProc();
    }

    /**
     * Get a point-in-time statistics snapshot — mirrors OTP {@code sys:statistics(Pid, get)}.
     *
     * <p>Reads atomic counters maintained by the process loop. The snapshot is not transactionally
     * consistent (counters are read independently), but is accurate within one message processing
     * cycle.
     *
     * @param proc the process to inspect
     * @return statistics snapshot
     */
    public static Stats statistics(Proc<?, ?> proc) {
        return new Stats(
                proc.messagesIn.sum(), proc.messagesOut.sum(), proc.mailboxSize());
    }
}
