package org.acme;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global process name registry — OTP's {@code register/2}, {@code whereis/1}, and {@code
 * unregister/1}.
 *
 * <p>In OTP, every process can be registered under a unique atom name. Other processes look up the
 * name via {@code whereis/1} to obtain a Pid without needing to pass the Pid explicitly through the
 * call stack. Names are automatically de-registered when the process terminates.
 *
 * <p>Java 26 mapping:
 *
 * <ul>
 *   <li>OTP {@code register(Name, Pid)} → {@link #register(String, Proc)}: registers name, auto-
 *       removes entry when process terminates (any reason)
 *   <li>OTP {@code whereis(Name)} → {@link #whereis(String)}: returns an {@link Optional} (empty
 *       if not registered)
 *   <li>OTP {@code unregister(Name)} → {@link #unregister(String)}: explicit removal
 *   <li>OTP {@code registered()} → {@link #registered()}: set of all currently registered names
 * </ul>
 *
 * <p>Registration is global (JVM-scoped, not per-supervisor). This matches OTP's single global
 * name table. For scoped name spaces, use separate {@code Map<String, Proc<?,?>>} instances.
 */
public final class ProcessRegistry {

    private ProcessRegistry() {}

    private static final ConcurrentHashMap<String, Proc<?, ?>> REGISTRY = new ConcurrentHashMap<>();

    /**
     * Register {@code proc} under {@code name}.
     *
     * <p>The registration is automatically removed when {@code proc} terminates — normal or
     * abnormal — so {@link #whereis} will return empty once the process is gone.
     *
     * @throws IllegalStateException if {@code name} is already registered to a living process
     */
    public static void register(String name, Proc<?, ?> proc) {
        if (REGISTRY.putIfAbsent(name, proc) != null) {
            throw new IllegalStateException("Name already registered: " + name);
        }
        // Auto-deregister on any termination (mirrors OTP's automatic cleanup)
        proc.addTerminationCallback(_ -> REGISTRY.remove(name, proc));
    }

    /**
     * Look up a process by name.
     *
     * @return the registered {@link Proc}, or empty if no process is registered under {@code name}
     */
    @SuppressWarnings("unchecked")
    public static <S, M> Optional<Proc<S, M>> whereis(String name) {
        Proc<S, M> proc = (Proc<S, M>) REGISTRY.get(name);
        if (proc != null && !proc.thread().isAlive()) {
            // OTP: dead process is invisible — eagerly remove the stale entry
            REGISTRY.remove(name, proc);
            return Optional.empty();
        }
        return Optional.ofNullable(proc);
    }

    /**
     * Explicitly remove a name from the registry. The process itself is not stopped.
     *
     * <p>Mirrors Erlang's {@code unregister(Name)} BIF. Safe to call even if the name is not
     * registered.
     */
    public static void unregister(String name) {
        REGISTRY.remove(name);
    }

    /**
     * Returns the set of all currently registered names — mirrors Erlang's {@code registered()}.
     *
     * @return a snapshot (not live) of registered names
     */
    public static java.util.Set<String> registered() {
        return java.util.Set.copyOf(REGISTRY.keySet());
    }

    /** Clear all registrations — for use in tests only. */
    public static void reset() {
        REGISTRY.clear();
    }
}
