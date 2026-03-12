package io.github.seanchatmangpt.jotp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;

/**
 * Java 26 equivalent of Erlang/OTP's {@code gen_statem} behavior.
 *
 * <p>OTP {@code gen_statem} separates three concerns that gen_server conflates:
 *
 * <ol>
 *   <li><b>State</b> — which "mode" the machine is in (e.g. {@code locked} / {@code open})
 *   <li><b>Event</b> — external stimulus (e.g. {@code push_button}, {@code lock})
 *   <li><b>Data</b> — mutable context carried across all states (e.g. entered digits)
 * </ol>
 *
 * <p>The transition function {@code (S state, E event, D data) -> Transition<S,D>} is a pure
 * function returning one of:
 *
 * <ul>
 *   <li>{@link Transition.NextState} — move to a new state, optionally with new data
 *   <li>{@link Transition.KeepState} — stay in the current state, optionally update data
 *   <li>{@link Transition.Stop} — terminate the state machine
 * </ul>
 *
 * <p>Mapping to Erlang:
 *
 * <pre>{@code
 * Erlang gen_statem return         Java Transition
 * ──────────────────────────────   ─────────────────────────────────
 * {next_state, S2, Data2}     →   Transition.nextState(s2, data2)
 * {keep_state, Data2}         →   Transition.keepState(data2)
 * keep_state_and_data         →   Transition.keepState(data)   (same ref)
 * {stop, Reason}              →   Transition.stop(reason)
 * }</pre>
 *
 * <p><b>Usage — code lock example:</b>
 *
 * <pre>{@code
 * sealed interface LockState  permits Locked, Open {}
 * record Locked() implements LockState {}
 * record Open()   implements LockState {}
 *
 * sealed interface LockEvent  permits PushButton, Lock {}
 * record PushButton(char button) implements LockEvent {}
 * record Lock()                  implements LockEvent {}
 *
 * var sm = new StateMachine<>(
 *     new Locked(),
 *     new LockData("", "1234"),
 *     (state, event, data) -> switch (state) {
 *         case Locked() -> switch (event) {
 *             case PushButton(var b) -> {
 *                 var entered = data.entered() + b;
 *                 yield entered.equals(data.code())
 *                     ? Transition.nextState(new Open(), data.withEntered(""))
 *                     : Transition.keepState(data.withEntered(entered));
 *             }
 *             default -> Transition.keepState(data);
 *         };
 *         case Open() -> switch (event) {
 *             case Lock() -> Transition.nextState(new Locked(), data.withEntered(""));
 *             default     -> Transition.keepState(data);
 *         };
 *     }
 * );
 *
 * sm.send(new PushButton('1'));
 * sm.send(new PushButton('2'));
 * sm.send(new PushButton('3'));
 * sm.send(new PushButton('4'));
 * D data = sm.call(new Lock()).join();  // waits for Lock event to be processed
 * }</pre>
 *
 * @param <S> state type — use a {@code sealed interface} of {@code record}s for exhaustive switch
 * @param <E> event type — use a {@code sealed interface} of {@code record}s
 * @param <D> data type — carried across state transitions (immutable record recommended)
 */
public final class StateMachine<S, E, D> {

    // ── Transition sealed hierarchy ──────────────────────────────────────────

    /**
     * The return type of a state machine transition function.
     *
     * <p>Mirrors OTP's transition return tuple. Obtain instances via the static factory methods:
     * {@link #nextState}, {@link #keepState}, {@link #stop}.
     */
    public sealed interface Transition<S, D>
            permits Transition.NextState, Transition.KeepState, Transition.Stop {

        /**
         * Move to {@code newState} and update data to {@code newData}.
         *
         * <p>OTP: {@code {next_state, NewState, NewData}}
         */
        static <S, D> Transition<S, D> nextState(S newState, D newData) {
            return new NextState<>(newState, newData);
        }

        /**
         * Stay in the current state, update data to {@code newData}.
         *
         * <p>OTP: {@code {keep_state, NewData}}
         */
        static <S, D> Transition<S, D> keepState(D newData) {
            return new KeepState<>(newData);
        }

        /**
         * Terminate the state machine with {@code reason}.
         *
         * <p>OTP: {@code {stop, Reason}}
         */
        static <S, D> Transition<S, D> stop(String reason) {
            return new Stop<>(reason);
        }

        /** {@code {next_state, State, Data}} */
        record NextState<S, D>(S state, D data) implements Transition<S, D> {}

        /** {@code {keep_state, Data}} */
        record KeepState<S, D>(D data) implements Transition<S, D> {}

        /** {@code {stop, Reason}} */
        record Stop<S, D>(String reason) implements Transition<S, D> {}
    }

    // ── TransitionFn ─────────────────────────────────────────────────────────

    /**
     * The pure transition function — equivalent to OTP's {@code handle_event/4} in
     * {@code handle_event_function} callback mode.
     *
     * <p>OTP: {@code handle_event(EventType, EventContent, State, Data) -> Transition}
     *
     * @param <S> state type
     * @param <E> event type
     * @param <D> data type
     */
    @FunctionalInterface
    public interface TransitionFn<S, E, D> {
        Transition<S, D> apply(S state, E event, D data);
    }

    // ── Internal envelope (same pattern as Proc) ─────────────────────────────

    private record Envelope<E>(E event, CompletableFuture<Object> reply) {}

    // ── State machine fields ──────────────────────────────────────────────────

    private volatile S currentState;
    private volatile D currentData;
    private volatile String stopReason = null;

    private final TransferQueue<Envelope<E>> mailbox = new LinkedTransferQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Create and immediately start the state machine.
     *
     * @param initialState initial state value (OTP: returned from {@code init/1} as first element)
     * @param initialData  initial data value (OTP: returned from {@code init/1} as second element)
     * @param fn           transition function
     */
    public StateMachine(S initialState, D initialData, TransitionFn<S, E, D> fn) {
        this.currentState = initialState;
        this.currentData  = initialData;
        this.thread = Thread.ofVirtual()
                .name("statem")
                .start(() -> loop(fn));
    }

    // ── Event API ─────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget: deliver {@code event} to the state machine without waiting.
     *
     * <p>OTP: {@code gen_statem:cast(Pid, Event)}
     */
    public void send(E event) {
        if (!running) return;
        mailbox.add(new Envelope<>(event, null));
    }

    /**
     * Request-reply: deliver {@code event} and return a future that completes with the machine's
     * data after the event is processed (or the machine stops).
     *
     * <p>OTP: {@code gen_statem:call(Pid, Event)}
     *
     * @return future completing with {@link D} after the transition, or failing with
     *         {@link IllegalStateException} if the machine stopped
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<D> call(E event) {
        if (!running) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("state machine stopped: " + stopReason));
        }
        var future = new CompletableFuture<Object>();
        mailbox.add(new Envelope<>(event, future));
        return future.thenApply(d -> (D) d);
    }

    // ── State/data queries ────────────────────────────────────────────────────

    /** Returns the current state. Thread-safe (volatile read). */
    public S state() {
        return currentState;
    }

    /** Returns the current data. Thread-safe (volatile read). */
    public D data() {
        return currentData;
    }

    /** Returns {@code true} if the machine is still running. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the stop reason if the machine has stopped, or {@code null} if still running.
     *
     * <p>OTP: the reason atom/term passed to {@code {stop, Reason}}.
     */
    public String stopReason() {
        return stopReason;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Graceful shutdown: stop the machine and wait for the virtual thread to finish.
     *
     * <p>OTP: {@code gen_statem:stop(Pid)}
     */
    public void stop() throws InterruptedException {
        running = false;
        thread.interrupt();
        thread.join();
    }

    // ── Internal loop ─────────────────────────────────────────────────────────

    private void loop(TransitionFn<S, E, D> fn) {
        while (running) {
            try {
                Envelope<E> env = mailbox.take();
                Transition<S, D> transition = fn.apply(currentState, env.event(), currentData);

                switch (transition) {
                    case Transition.NextState<S, D>(var newState, var newData) -> {
                        currentState = newState;
                        currentData  = newData;
                        if (env.reply() != null) env.reply().complete(newData);
                    }
                    case Transition.KeepState<S, D>(var newData) -> {
                        currentData = newData;
                        if (env.reply() != null) env.reply().complete(newData);
                    }
                    case Transition.Stop<S, D>(var reason) -> {
                        stopReason = reason;
                        running = false;
                        if (env.reply() != null) {
                            env.reply().completeExceptionally(
                                    new IllegalStateException("state machine stopped: " + reason));
                        }
                        // drain remaining mailbox, failing pending calls
                        drainMailbox();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                drainMailbox();
            }
        }
    }

    private void drainMailbox() {
        Envelope<E> env;
        while ((env = mailbox.poll()) != null) {
            if (env.reply() != null) {
                env.reply().completeExceptionally(
                        new IllegalStateException("state machine stopped: " + stopReason));
            }
        }
    }
}
