package io.github.seanchatmangpt.jotp;

import java.util.function.BiFunction;

/**
 * OTP process links — bilateral crash propagation between two processes.
 *
 * <p>Joe Armstrong: "A link is a connection between two processes. If one process dies, the other
 * is notified. This is the fundamental building block of fault-tolerant systems."
 *
 * <p>In OTP, {@code link/1} creates a bidirectional link: when either linked process terminates
 * with a non-{@code normal} exit reason, the other receives an exit signal and is terminated too
 * (unless it is trapping exits via {@code process_flag(trap_exit, true)}).
 *
 * <p>Java 26 mapping:
 *
 * <ul>
 *   <li>OTP {@code link/1} → {@link #link(Proc, Proc)}: installs mutual crash callbacks
 *   <li>OTP {@code spawn_link/3} → {@link #spawnLink(Proc, Object, BiFunction)}: atomic spawn +
 *       link (no window between creation and link install)
 *   <li>OTP {@code normal} exit (graceful shutdown) → {@link Proc#stop()} — does NOT propagate
 *   <li>OTP non-normal exit (unhandled exception) → fires crash callbacks → interrupts linked peer
 * </ul>
 *
 * <p>Links are composable with {@link Supervisor}: a supervised child can also be linked to a peer
 * process. Both the supervisor's crash callback and the link's crash callback fire independently.
 */
public final class ProcessLink {

    private ProcessLink() {}

    /**
     * Establish a bidirectional link between {@code a} and {@code b}.
     *
     * <p>If {@code a} terminates abnormally, {@code b} is interrupted with {@code a}'s exit reason.
     * If {@code b} terminates abnormally, {@code a} is interrupted with {@code b}'s exit reason.
     * Normal {@link Proc#stop()} by either side does <em>not</em> affect the other.
     *
     * <p>Mirrors Erlang's {@code link(Pid)} BIF.
     */
    public static void link(Proc<?, ?> a, Proc<?, ?> b) {
        a.addCrashCallback(() -> b.deliverExitSignal(a.lastError));
        b.addCrashCallback(() -> a.deliverExitSignal(b.lastError));
    }

    /**
     * Atomically spawn a new process and link it to {@code parent}.
     *
     * <p>Because the link is installed before the child processes any messages, there is no window
     * in which the child could crash undetected — identical semantic guarantee to OTP's {@code
     * spawn_link/3}.
     *
     * <p>If the child crashes, {@code parent} is interrupted. If {@code parent} crashes (i.e., its
     * crash callbacks fire), the child is interrupted.
     *
     * @param parent the existing process to link to
     * @param initial child's initial state
     * @param handler child's state handler
     * @return the newly spawned, linked child process
     */
    public static <S, M> Proc<S, M> spawnLink(
            Proc<?, ?> parent, S initial, BiFunction<S, M, S> handler) {
        Proc<S, M> child = new Proc<>(initial, handler);
        link(parent, child);
        return child;
    }
}
