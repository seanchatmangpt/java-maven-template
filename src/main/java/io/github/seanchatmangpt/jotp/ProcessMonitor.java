package io.github.seanchatmangpt.jotp;

import java.util.function.Consumer;

/**
 * OTP process monitoring — unilateral DOWN notifications when a target process terminates.
 *
 * <p>Unlike {@link ProcessLink} (which kills the monitoring process on crash), a monitor is
 * <em>unidirectional</em>: when the monitored process terminates for any reason — normal or
 * abnormal — the monitoring side receives a {@code DOWN} notification carrying the exit reason.
 * The monitoring process itself is never killed.
 *
 * <p>This is the mechanism {@code GenServer.call/3} uses to implement call timeouts: it monitors
 * the target, sends a request, and either receives a reply or a {@code DOWN} notification first.
 *
 * <p>Java 26 mapping:
 *
 * <ul>
 *   <li>OTP {@code monitor(process, Pid)} → {@link #monitor(Proc, Consumer)}: installs a
 *       termination callback; returns an opaque {@link MonitorRef}
 *   <li>OTP {@code demonitor(MonitorRef)} → {@link #demonitor(MonitorRef)}: removes the callback
 *   <li>OTP {@code {'DOWN', Ref, process, Pid, Reason}} → {@code Consumer<Throwable>}: {@code null}
 *       reason = normal exit; non-null = the exception that killed the process
 * </ul>
 *
 * <p><strong>Composability:</strong> A process can be simultaneously supervised (via
 * {@link Supervisor}), linked (via {@link ProcessLink}), and monitored (via this class). All three
 * are orthogonal; they use separate callback lists on {@link Proc}.
 */
public final class ProcessMonitor {

    private ProcessMonitor() {}

    /**
     * Opaque handle returned by {@link #monitor} — pass to {@link #demonitor} to cancel.
     *
     * @param <S> target process state type
     * @param <M> target process message type
     */
    public record MonitorRef<S, M>(Proc<S, M> target, Consumer<Throwable> callback) {}

    /**
     * Start monitoring {@code target}. When {@code target} terminates — for any reason — {@code
     * downHandler} is invoked with:
     *
     * <ul>
     *   <li>{@code null} → target stopped normally (via {@link Proc#stop()})
     *   <li>non-null {@link Throwable} → target crashed with this unhandled exception
     * </ul>
     *
     * <p>The {@code downHandler} is called on the target's virtual thread, so it must not block.
     * Use it to enqueue a message into the monitoring process's mailbox if needed.
     *
     * <p>Mirrors Erlang's {@code monitor(process, Pid)} BIF.
     *
     * @param target process to monitor
     * @param downHandler called when target terminates; argument is {@code null} for normal exit
     * @return a {@link MonitorRef} — keep it to cancel via {@link #demonitor}
     */
    public static <S, M> MonitorRef<S, M> monitor(Proc<S, M> target, Consumer<Throwable> downHandler) {
        target.addTerminationCallback(downHandler);
        return new MonitorRef<>(target, downHandler);
    }

    /**
     * Cancel a monitor. After this call, the {@code downHandler} will not be invoked even if the
     * target subsequently terminates.
     *
     * <p>Mirrors Erlang's {@code demonitor(MonitorRef)} BIF. Safe to call after the target has
     * already terminated (no-op).
     *
     * @param ref the monitor reference returned by {@link #monitor}
     */
    public static void demonitor(MonitorRef<?, ?> ref) {
        ref.target().removeTerminationCallback(ref.callback());
    }
}
