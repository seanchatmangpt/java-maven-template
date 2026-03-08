package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * OTP-style supervision tree node.
 *
 * <p>Joe Armstrong: <em>"The key to writing reliable software in Erlang is the ability to organize
 * processes into supervision trees, where supervisors can automatically restart failed
 * children."</em>
 *
 * <p>A {@code Supervisor} owns a set of child actors. When a child crashes (its handler throws),
 * the supervisor receives a {@code ChildCrashed} event and restarts it according to the configured
 * {@link Strategy}. Callers hold {@link ActorRef} handles that transparently redirect to the new
 * actor after restart — no caller changes needed.
 *
 * <p>If a child exceeds {@code maxRestarts} within {@code window}, the supervisor terminates itself
 * (Armstrong: the supervisor crashes too, propagating failure up the tree to its own supervisor).
 *
 * <p>The supervisor is itself a virtual-thread actor: its internal event loop runs on a single
 * virtual thread, processing {@link ChildCrashed} and {@link SvShutdown} events from a {@link
 * LinkedTransferQueue}.
 */
public final class Supervisor {

    /** Erlang/OTP restart strategies. */
    public enum Strategy {
        /** Restart only the crashed child, leave all others running. */
        ONE_FOR_ONE,
        /** When any child crashes, stop and restart ALL children. */
        ONE_FOR_ALL,
        /** Restart the crashed child and every child registered after it. */
        REST_FOR_ONE
    }

    // ── Internal event types ───────────────────────────────────────────────
    private sealed interface SvEvent permits ChildCrashed, SvShutdown {}

    private record ChildCrashed(String id, Throwable cause) implements SvEvent {}

    private record SvShutdown() implements SvEvent {}

    // ── Child bookkeeping (type-erased to support heterogeneous children) ──
    @SuppressWarnings("rawtypes")
    private static final class ChildEntry {
        final String id;
        final Supplier<Object> stateFactory;
        final BiFunction handler; // BiFunction<Object, Object, Object>
        volatile ActorRef ref; // ActorRef<Object, Record>
        final List<Instant> crashTimes = new ArrayList<>();
        volatile boolean stopping = false;

        @SuppressWarnings("unchecked")
        ChildEntry(String id, Supplier<?> stateFactory, BiFunction<?, ?, ?> handler) {
            this.id = id;
            this.stateFactory = (Supplier<Object>) stateFactory;
            this.handler = handler;
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────
    private final Strategy strategy;
    private final int maxRestarts;
    private final Duration window;
    private final LinkedTransferQueue<SvEvent> events = new LinkedTransferQueue<>();
    private final List<ChildEntry> children = new ArrayList<>();
    private final Thread supervisorThread;
    private volatile boolean running = true;
    private volatile Throwable fatalError = null;

    /**
     * Create and start a supervisor.
     *
     * @param strategy what to restart when a child crashes
     * @param maxRestarts how many times a child may crash within {@code window} before the
     *     supervisor itself fails
     * @param window sliding time window for the restart count
     */
    public Supervisor(Strategy strategy, int maxRestarts, Duration window) {
        this.strategy = strategy;
        this.maxRestarts = maxRestarts;
        this.window = window;
        this.supervisorThread = Thread.ofVirtual().name("supervisor").start(this::eventLoop);
    }

    /**
     * Register and start a supervised child actor.
     *
     * @param id unique child identifier (used for logging and {@link Strategy#REST_FOR_ONE})
     * @param initialState initial (and reset) state — used on every restart
     * @param handler {@code (state, message) -> nextState}; may throw to signal a crash
     * @return stable {@link ActorRef} that survives restarts
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <S, M> ActorRef<S, M> supervise(
            String id, S initialState, BiFunction<S, M, S> handler) {
        var entry = new ChildEntry(id, () -> initialState, handler);
        Actor actor = spawnActor(entry, initialState);
        ActorRef ref = new ActorRef<>(actor);
        entry.ref = ref;
        children.add(entry);
        return (ActorRef<S, M>) ref;
    }

    /** Gracefully stop the supervisor and all supervised children. */
    public void shutdown() throws InterruptedException {
        events.add(new SvShutdown());
        supervisorThread.join();
    }

    /** {@code true} if the supervisor is still active (not crashed or shut down). */
    public boolean isRunning() {
        return running;
    }

    /**
     * The exception that caused the supervisor to fail (max restarts exceeded), or {@code null} if
     * still running.
     */
    public Throwable fatalError() {
        return fatalError;
    }

    // ── Private implementation ─────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Actor spawnActor(ChildEntry entry, Object initialState) {
        BiFunction<Object, Object, Object> wrapped =
                (state, msg) -> {
                    try {
                        return entry.handler.apply(state, msg);
                    } catch (RuntimeException e) {
                        if (!entry.stopping) {
                            events.add(new ChildCrashed(entry.id, e));
                        }
                        throw e;
                    }
                };
        Actor actor = new Actor(initialState, wrapped);
        // Swallow uncaught exceptions: crashes are handled by the supervisor's event loop,
        // not by the JVM's default uncaught-exception handler (which would pollute test output).
        actor.thread().setUncaughtExceptionHandler((t, e) -> {});
        return actor;
    }

    private void eventLoop() {
        try {
            while (running) {
                SvEvent ev = events.take();
                switch (ev) {
                    case ChildCrashed(var id, var cause) -> handleCrash(id, cause);
                    case SvShutdown() -> {
                        running = false;
                        stopAll();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void handleCrash(String id, Throwable cause) {
        ChildEntry entry = find(id);
        if (entry == null || entry.stopping) return;

        // Slide the crash window
        Instant now = Instant.now();
        entry.crashTimes.removeIf(t -> t.isBefore(now.minus(window)));
        entry.crashTimes.add(now);

        // Exceeded threshold → supervisor itself terminates (propagates up the tree)
        if (entry.crashTimes.size() > maxRestarts) {
            fatalError = cause;
            running = false;
            stopAll();
            return;
        }

        switch (strategy) {
            case ONE_FOR_ONE -> restartOne(entry);
            case ONE_FOR_ALL -> {
                List<ChildEntry> snapshot = List.copyOf(children);
                for (ChildEntry c : snapshot) if (c != entry) stopChild(c);
                for (ChildEntry c : snapshot) restartOne(c);
            }
            case REST_FOR_ONE -> {
                List<ChildEntry> snapshot = List.copyOf(children);
                boolean found = false;
                for (ChildEntry c : snapshot) {
                    if (c == entry) {
                        found = true;
                        continue;
                    }
                    if (found) stopChild(c);
                }
                found = false;
                for (ChildEntry c : snapshot) {
                    if (c == entry) found = true;
                    if (found) restartOne(c);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restartOne(ChildEntry entry) {
        Object freshState = entry.stateFactory.get();
        Actor newActor = spawnActor(entry, freshState);
        entry.stopping = false; // re-enable crash detection before swap
        entry.ref.swap(newActor);
    }

    private void stopChild(ChildEntry entry) {
        entry.stopping = true;
        try {
            entry.ref.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void stopAll() {
        List<ChildEntry> snapshot = List.copyOf(children);
        for (int i = snapshot.size() - 1; i >= 0; i--) stopChild(snapshot.get(i));
    }

    private ChildEntry find(String id) {
        return children.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }
}
