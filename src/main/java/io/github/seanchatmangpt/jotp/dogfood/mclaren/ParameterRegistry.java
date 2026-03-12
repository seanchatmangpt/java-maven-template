package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.Optional;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcessRegistry;

/**
 * SQL Race parameter registry — OTP {@code ProcessRegistry} mapped to parameter identifier lookup.
 *
 * <p>In ATLAS, parameters are looked up by their SQL Race identifier
 * ({@code "name:ApplicationGroup"}) anywhere in the system — in live display plugins, in
 * post-processing pipelines, and by the Advanced Streams broker. The OTP equivalent is
 * {@code erlang:register/2} / {@code whereis/1} — a JVM-global name table that maps atom (name)
 * to process.
 *
 * <p>This wrapper translates SQL Race identifiers to {@link ProcessRegistry} keys and
 * auto-deregisters when the backing {@link ParameterDataAccess} process terminates.
 *
 * <pre>{@code
 * // Register when a session starts
 * ParameterRegistry.register(vCarParam, vCarProc);
 *
 * // Look up from anywhere in the system (e.g. a display plugin)
 * var proc = ParameterRegistry.whereis("vCar:Chassis");
 * proc.ifPresent(p -> p.tell(new PdaMsg.GoTo(lapStartNs)));
 *
 * // Unregister on session close
 * ParameterRegistry.unregister("vCar:Chassis");
 * }</pre>
 */
public final class ParameterRegistry {

    private ParameterRegistry() {}

    /**
     * Register a parameter data access process under its SQL Race identifier.
     *
     * <p>The process is automatically unregistered when it terminates (normal or crash) —
     * mirroring OTP's {@code ProcessRegistry} auto-deregister behaviour.
     *
     * @param param the parameter whose {@link SqlRaceParameter#identifier()} is used as the key
     * @param proc  the running {@link ParameterDataAccess} process
     * @throws IllegalStateException if the identifier is already registered
     */
    public static void register(
            SqlRaceParameter param, Proc<ParameterDataAccess.State, PdaMsg> proc) {
        ProcessRegistry.register(param.identifier(), proc);
    }

    /**
     * Register using an explicit identifier (for alternative naming schemes).
     *
     * @param identifier SQL Race identifier (e.g. {@code "vCar:Chassis"})
     * @param proc       the running process
     */
    public static void register(
            String identifier, Proc<ParameterDataAccess.State, PdaMsg> proc) {
        ProcessRegistry.register(identifier, proc);
    }

    /**
     * Look up a parameter process by SQL Race identifier — mirrors {@code whereis/1}.
     *
     * @param identifier full SQL Race identifier (e.g. {@code "vCar:Chassis"})
     * @return the running process, or empty if not registered
     */
    @SuppressWarnings("unchecked")
    public static Optional<Proc<ParameterDataAccess.State, PdaMsg>> whereis(String identifier) {
        return ProcessRegistry.<ParameterDataAccess.State, PdaMsg>whereis(identifier);
    }

    /**
     * Look up by short name and application group — convenience for display plugins that
     * receive the two parts separately.
     *
     * @param name     parameter short name (e.g. {@code "vCar"})
     * @param appGroup application group (e.g. {@code "Chassis"})
     * @return the running process, or empty if not registered
     */
    public static Optional<Proc<ParameterDataAccess.State, PdaMsg>> whereis(
            String name, String appGroup) {
        return whereis(name + ":" + appGroup);
    }

    /**
     * Explicitly unregister a parameter — mirrors {@code unregister/1}.
     *
     * <p>Usually not needed because the process's termination callback auto-deregisters. Call
     * explicitly only when replacing a process without restarting it.
     *
     * @param identifier SQL Race identifier
     */
    public static void unregister(String identifier) {
        ProcessRegistry.unregister(identifier);
    }

    /**
     * Return the set of all currently registered parameter identifiers — mirrors
     * {@code registered/0}.
     *
     * @return snapshot of registered names
     */
    public static java.util.Set<String> registered() {
        return ProcessRegistry.registered();
    }

    /**
     * Clear all registrations (for test isolation — mirrors {@code ProcessRegistry.reset()}).
     */
    public static void reset() {
        ProcessRegistry.reset();
    }
}
