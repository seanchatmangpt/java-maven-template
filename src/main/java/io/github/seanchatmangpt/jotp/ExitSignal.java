package io.github.seanchatmangpt.jotp;

/**
 * OTP exit signal delivered as a mailbox message when a process has enabled exit trapping via
 * {@link Proc#trapExits(boolean)}.
 *
 * <p>In OTP, {@code process_flag(trap_exit, true)} causes incoming EXIT signals to appear in the
 * process mailbox as {@code {'EXIT', FromPid, Reason}} tuples rather than killing the process. This
 * record is the Java 26 equivalent.
 *
 * <p>Usage — in your message handler, pattern-match on {@code ExitSignal}:
 *
 * <pre>{@code
 * sealed interface Msg permits Msg.Work, ExitSignal {}
 * // Note: ExitSignal is not sealed so it can implement any message interface
 *
 * var proc = new Proc<State, Object>(init, (state, msg) -> switch (msg) {
 *     case ExitSignal(var reason) -> handleExit(state, reason);
 *     case Msg.Work w            -> doWork(state, w);
 *     default                    -> state;
 * });
 * proc.trapExits(true);
 * }</pre>
 *
 * <p>Armstrong: "Trap exits when you need to clean up after a linked process dies, or when you want
 * to make a deliberate decision about whether to propagate the crash."
 *
 * @param reason the exit reason from the crashed linked process ({@code null} for normal exits)
 */
public record ExitSignal(Throwable reason) {}
