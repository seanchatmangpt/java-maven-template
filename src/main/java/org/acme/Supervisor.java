package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * OTP supervision tree node — hierarchical process supervision with restart strategies.
 *
 * <p>Joe Armstrong: "Supervisors are the key to Erlang's fault tolerance. A supervisor's
 * job is to start, stop, and monitor its children. When a child crashes, the supervisor
 * decides what to do — restart it, restart all children, or give up."
 *
 * <p>In OTP, supervisors form a tree where the root supervisor starts application-level
 * supervisors, which in turn start workers. This hierarchical structure ensures that
 * failures are contained and recovered at the appropriate level.
 *
 * <p>Java 26 mapping:
 *
 * <ul>
 *   <li>OTP {@code supervisor:start_link/2} → {@link #Supervisor(Strategy, int, Duration)}
 *   <li>OTP {@code supervisor:start_child/2} → {@link #supervise(String, Object, BiFunction)}
 *   <li>OTP {@code supervisor:terminate_child/2} → stop via {@link ProcRef#stop()}
 *   <li>OTP {@code supervisor:stop/1} → {@link #shutdown()}
 * </ul>
 *
 * <p><strong>Restart Strategies:</strong>
 *
 * <ul>
 *   <li>{@link Strategy#ONE_FOR_ONE} — Only the crashed child is restarted
 *   <li>{@link Strategy#ONE_FOR_ALL} — All children are restarted when any crashes
 *   <li>{@link Strategy#REST_FOR_ONE} — The crashed child and all children started after it are restarted
 * </ul>
 *
 * <p><strong>Restart Intensity:</strong>
 *
 * <p>The {@code maxRestarts} and {@code window} parameters implement OTP's "max restarts
 * in a time window" feature. If a child crashes more than {@code maxRestarts} times within
 * {@code window}, the supervisor gives up and terminates itself (and by extension, its
 * entire subtree). This prevents infinite restart loops.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * var supervisor = new Supervisor(
 *     Supervisor.Strategy.ONE_FOR_ONE,
 *     5,
 *     Duration.ofSeconds(60)
 * );
 *
 * var worker1 = supervisor.supervise("worker-1", initialState1, handler1);
 * var worker2 = supervisor.supervise("worker-2", initialState2, handler2);
 *
 * // To stop the entire supervision tree:
 * supervisor.shutdown();
 * }</pre>
 *
 * @see Proc
 * @see ProcRef
 * @see CrashRecovery
 */
public final class Supervisor {
    /** Restart strategy for supervised children. */
    public enum Strategy {
        /** Only the crashed child is restarted. Other children are unaffected. */
        ONE_FOR_ONE,
        /** All children are restarted when any child crashes. */
        ONE_FOR_ALL,
        /** The crashed child and all children started after it are restarted. */
        REST_FOR_ONE
    }

    private sealed interface SvEvent permits SvEvent_ChildCrashed, SvEvent_Shutdown {}
    private record SvEvent_ChildCrashed(String id, Throwable cause) implements SvEvent {}
    private record SvEvent_Shutdown() implements SvEvent {}

    @SuppressWarnings("rawtypes")
    private static final class ChildEntry {
        final String id;
        final Supplier<Object> stateFactory;
        final BiFunction handler;
        volatile ProcRef ref;
        final List<Instant> crashTimes = new ArrayList<>();
        volatile boolean stopping = false;

        @SuppressWarnings("unchecked")
        ChildEntry(String id, Supplier<?> stateFactory, BiFunction<?, ?, ?> handler) {
            this.id = id; this.stateFactory = (Supplier<Object>) stateFactory; this.handler = handler;
        }
    }

    private final Strategy strategy;
    private final int maxRestarts;
    private final Duration window;
    private final LinkedTransferQueue<SvEvent> events = new LinkedTransferQueue<>();
    private final List<ChildEntry> children = new ArrayList<>();
    private final Thread supervisorThread;
    private volatile boolean running = true;
    private volatile Throwable fatalError = null;

    /**
     * Create a supervisor with the given strategy and restart limits.
     *
     * @param strategy restart strategy (ONE_FOR_ONE, ONE_FOR_ALL, or REST_FOR_ONE)
     * @param maxRestarts maximum number of restarts allowed within the window
     * @param window time window for counting restarts
     */
    public Supervisor(Strategy strategy, int maxRestarts, Duration window) {
        this.strategy = strategy; this.maxRestarts = maxRestarts; this.window = window;
        this.supervisorThread = Thread.ofVirtual().name("supervisor").start(this::eventLoop);
    }

    /**
     * Create a named supervisor with the given strategy and restart limits.
     *
     * @param name supervisor name (used for thread naming)
     * @param strategy restart strategy
     * @param maxRestarts maximum restarts within the window
     * @param window time window for counting restarts
     */
    public Supervisor(String name, Strategy strategy, int maxRestarts, Duration window) {
        this.strategy = strategy; this.maxRestarts = maxRestarts; this.window = window;
        this.supervisorThread = Thread.ofVirtual().name("supervisor-" + name).start(this::eventLoop);
    }

    /**
     * Supervise a child process with the given ID, initial state, and handler.
     *
     * <p>The child is started immediately and monitored for crashes. If the child
     * crashes, the supervisor applies its restart strategy.
     *
     * @param id unique identifier for this child (used in restart strategy)
     * @param initialState initial state for the child process
     * @param handler message handler for the child process
     * @return a {@link ProcRef} that transparently redirects to restarted processes
     * @param <S> state type
     * @param <M> message type
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <S, M> ProcRef<S, M> supervise(String id, S initialState, BiFunction<S, M, S> handler) {
        var entry = new ChildEntry(id, () -> initialState, handler);
        Proc proc = spawnProc(entry, initialState);
        ProcRef ref = new ProcRef<>(proc);
        entry.ref = ref;
        children.add(entry);
        return (ProcRef<S, M>) ref;
    }

    /**
     * Gracefully shut down the supervisor and all its children.
     *
     * <p>Children are stopped in reverse order of their creation. This method
     * blocks until all children have terminated.
     *
     * @throws InterruptedException if interrupted while waiting for children to stop
     */
    public void shutdown() throws InterruptedException {
        events.add(new SvEvent_Shutdown());
        supervisorThread.join();
    }

    /** Returns {@code true} if the supervisor is still running. */
    public boolean isRunning() { return running; }

    /**
     * Returns the fatal error that caused the supervisor to terminate, if any.
     *
     * <p>A non-null value indicates the supervisor exceeded its restart limit.
     */
    public Throwable fatalError() { return fatalError; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Proc spawnProc(ChildEntry entry, Object initialState) {
        Proc proc = new Proc(initialState, entry.handler);
        proc.addCrashCallback(() -> {
            if (!entry.stopping) events.add(new SvEvent_ChildCrashed(entry.id, proc.lastError));
        });
        return proc;
    }

    private void eventLoop() {
        try {
            while (running) {
                SvEvent ev = events.take();
                switch (ev) {
                    case SvEvent_ChildCrashed(var id, var cause) -> handleCrash(id, cause);
                    case SvEvent_Shutdown() -> { running = false; stopAll(); }
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void handleCrash(String id, Throwable cause) {
        ChildEntry entry = find(id);
        if (entry == null || entry.stopping) return;

        Instant now = Instant.now();
        entry.crashTimes.removeIf(t -> t.isBefore(now.minus(window)));
        entry.crashTimes.add(now);

        if (entry.crashTimes.size() > maxRestarts) {
            fatalError = cause; running = false; stopAll(); return;
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
                for (ChildEntry c : snapshot) { if (c == entry) { found = true; continue; } if (found) stopChild(c); }
                found = false;
                for (ChildEntry c : snapshot) { if (c == entry) found = true; if (found) restartOne(c); }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restartOne(ChildEntry entry) {
        Object freshState = entry.stateFactory.get();
        Proc newProc = spawnProc(entry, freshState);
        entry.stopping = false;
        entry.ref.swap(newProc);
    }

    private void stopChild(ChildEntry entry) {
        entry.stopping = true;
        try { entry.ref.stop(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private synchronized void stopAll() {
        List<ChildEntry> snapshot = List.copyOf(children);
        for (int i = snapshot.size() - 1; i >= 0; i--) stopChild(snapshot.get(i));
    }

    private ChildEntry find(String id) {
        return children.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }
}
