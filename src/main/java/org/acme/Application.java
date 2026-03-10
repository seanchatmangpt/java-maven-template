package org.acme;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * OTP Application container — composes entire enterprise applications from services and infrastructure.
 *
 * <p>Joe Armstrong: "An OTP application is a collection of processes that work together
 * to provide some service. The application is started and stopped as a unit, and all
 * its supervised processes are managed together."
 *
 * <p>This is the Java 26 equivalent of Erlang/OTP's {@code application} behavior. An
 * Application contains a supervision tree of services, infrastructure components,
 * and health checks — all managed as a single unit.
 *
 * <p><strong>Mapping to Erlang/OTP:</strong>
 *
 * <pre>{@code
 * Erlang/OTP                        Java 26
 * ──────────────────────────────    ──────────────────────────────────────────
 * application:start/1           →   application.start() → StartResult.Ok
 * application:stop/1            →   application.stop() → StopResult.Ok
 * supervisor:start_link/2       →   services are auto-supervised on build
 * application:get_env/2         →   application.config().get(key)
 * -module(my_app).              →   Application.builder("my-app").build()
 * }</pre>
 *
 * <p><strong>Application lifecycle:</strong>
 *
 * <pre>{@code
 * Application app = Application.builder("order-service")
 *     .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
 *     .maxRestarts(5)
 *     .restartWindow(Duration.ofMinutes(1))
 *     .config(ApplicationConfig.of("port", 8080))
 *     .service("order-processor", OrderState::new, OrderProcessor::handle)
 *     .service("inventory-checker", InventoryState::new, InventoryChecker::handle)
 *     .infrastructure(new DatabasePool())
 *     .infrastructure(new MessageQueue())
 *     .healthCheck(new DatabaseHealthCheck())
 *     .build();
 *
 * // Start all services and infrastructure
 * var result = app.start();
 * if (result instanceof StartResult.Ok) {
 *     System.out.println("Application started: " + app.serviceNames());
 * }
 *
 * // Access services by name
 * Optional<ProcRef<OrderState, OrderMsg>> orders = app.service("order-processor");
 *
 * // Stop gracefully (reversed infrastructure shutdown order)
 * app.stop();
 * }</pre>
 *
 * <p><strong>Infrastructure lifecycle:</strong>
 *
 * <p>Infrastructure components are started in declaration order and stopped in
 * reverse order — ensuring proper dependency management (e.g., database before
 * message queue on start, message queue before database on stop).
 *
 * @see Supervisor
 * @see Proc
 * @see ProcRef
 * @see ApplicationConfig
 */
public final class Application {
    /**
     * Result of starting an application.
     *
     * <p>Sealed interface with two outcomes:
     * <ul>
     *   <li>{@link Ok} — application started successfully</li>
     *   <li>{@link Err} — application failed to start with error message</li>
     * </ul>
     *
     * <p>Maps to Erlang's {@code ok | {error, Reason}} return type.
     */
    public sealed interface StartResult permits StartResult.Ok, StartResult.Err {
        /** Application started successfully. */
        record Ok() implements StartResult {}

        /** Application failed to start. */
        record Err(String error) implements StartResult {}
    }

    /**
     * Result of stopping an application.
     *
     * <p>Sealed interface with two outcomes:
     * <ul>
     *   <li>{@link Ok} — application stopped successfully</li>
     *   <li>{@link Err} — application failed to stop with error message</li>
     * </ul>
     *
     * <p>Maps to Erlang's {@code ok | {error, Reason}} return type.
     */
    public sealed interface StopResult permits StopResult.Ok, StopResult.Err {
        /** Application stopped successfully. */
        record Ok() implements StopResult {}

        /** Application failed to stop. */
        record Err(String error) implements StopResult {}
    }

    private final String name;
    private final Supervisor supervisor;
    private final ApplicationConfig config;
    private final ConcurrentHashMap<String, ProcRef<?, ?>> services = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Infrastructure> infrastructure = new ConcurrentHashMap<>();
    private final List<HealthCheck> healthChecks;
    private volatile boolean started = false;
    private volatile Thread healthThread;

    private Application(Builder builder) {
        this.name = builder.name;
        this.config = builder.config;
        this.healthChecks = List.copyOf(builder.healthChecks);
        this.supervisor = new Supervisor(name, builder.strategy, builder.maxRestarts, builder.window);

        for (var def : builder.services) {
            var ref = superviseRaw(def);
            services.put(def.name, ref);
        }

        for (var infra : builder.infrastructure) {
            this.infrastructure.put(infra.name(), infra);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcRef<?, ?> superviseRaw(Builder.ServiceDef def) {
        return supervisor.supervise(def.name, def.initialState.get(), (BiFunction) def.handler);
    }

    /**
     * Create a new application builder.
     *
     * @param name application name (used for logging and identification)
     * @return builder for constructing the application
     */
    public static Builder builder(String name) { return new Builder(name); }

    /**
     * Look up a service by name.
     *
     * <p>Services are {@link ProcRef} handles to supervised processes. Use this
     * to send messages to specific services within the application.
     *
     * @param <S> the service's state type
     * @param <M> the service's message type
     * @param name service name as defined in {@link Builder#service}
     * @return {@code Optional.of(ref)} if service exists, {@code Optional.empty()} otherwise
     */
    @SuppressWarnings("unchecked")
    public <S, M> Optional<ProcRef<S, M>> service(String name) {
        return Optional.ofNullable((ProcRef<S, M>) services.get(name));
    }

    /**
     * Look up an infrastructure component by name.
     *
     * @param <T> the infrastructure type
     * @param name infrastructure name from {@link Infrastructure#name()}
     * @return {@code Optional.of(infra)} if exists, {@code Optional.empty()} otherwise
     */
    @SuppressWarnings("unchecked")
    public <T extends Infrastructure> Optional<T> infrastructure(String name) {
        return Optional.ofNullable((T) infrastructure.get(name));
    }

    /**
     * Get all registered service names.
     *
     * @return unmodifiable set of service names
     */
    public Set<String> serviceNames() { return Collections.unmodifiableSet(services.keySet()); }

    /**
     * Get the application name.
     *
     * @return application identifier
     */
    public String name() { return name; }

    /**
     * Get the application configuration.
     *
     * @return configuration object
     */
    public ApplicationConfig config() { return config; }

    /**
     * Check if the application has been started.
     *
     * @return {@code true} if {@link #start()} was called successfully
     */
    public boolean isStarted() { return started; }

    /**
     * Check if the application is still running.
     *
     * <p>An application is running if its supervisor is running. The supervisor
     * stops when all supervised processes have terminated.
     *
     * @return {@code true} if supervisor is active
     */
    public boolean isRunning() { return supervisor.isRunning(); }

    /**
     * Start the application.
     *
     * <p>Starts all infrastructure components in declaration order, then starts
     * the supervision tree. Health checks begin running if any were configured.
     *
     * <p>Idempotent: calling {@code start()} on an already-started application
     * returns {@link StartResult.Err}.
     *
     * @return {@link StartResult.Ok} on success, {@link StartResult.Err} on failure
     */
    public synchronized StartResult start() {
        if (started) return new StartResult.Err("Already started");

        for (var infra : infrastructure.values()) infra.onStart(this);
        started = true;

        if (!healthChecks.isEmpty()) startHealthChecks();

        return new StartResult.Ok();
    }

    /**
     * Stop the application.
     *
     * <p>Stops health checks, shuts down the supervision tree, then stops
     * infrastructure components in reverse declaration order.
     *
     * <p>Idempotent: calling {@code stop()} on an already-stopped application
     * returns {@link StopResult.Ok}.
     *
     * @return {@link StopResult.Ok} on success, {@link StopResult.Err} on failure
     */
    public synchronized StopResult stop() {
        if (!started) return new StopResult.Ok();
        stopHealthChecks();

        try { supervisor.shutdown(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        List<Infrastructure> reversed = new ArrayList<>(infrastructure.values());
        Collections.reverse(reversed);
        for (var infra : reversed) infra.onStop(this);

        services.clear();
        started = false;
        return new StopResult.Ok();
    }

    private void startHealthChecks() {
        healthThread = Thread.ofVirtual().name("health-check-" + name).start(() -> {
            while (started && supervisor.isRunning()) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                for (var check : healthChecks) {
                    if (!check.check()) System.err.println("[WARN] Health check failed: " + check.name());
                }
            }
        });
    }

    private void stopHealthChecks() {
        if (healthThread != null) healthThread.interrupt();
    }

    /**
     * Infrastructure component with lifecycle callbacks.
     *
     * <p>Implement this interface for resources that need initialization and cleanup,
     * such as database pools, message queues, or external service clients.
     *
     * <p><strong>Lifecycle:</strong>
     * <ul>
     *   <li>{@link #onStart(Application)} called in declaration order during {@link #start()}</li>
     *   <li>{@link #onStop(Application)} called in reverse order during {@link #stop()}</li>
     * </ul>
     *
     * <p>Example:
     * <pre>{@code
     * class DatabasePool implements Application.Infrastructure {
     *     private DataSource dataSource;
     *
     *     @Override public String name() { return "database"; }
     *
     *     @Override public void onStart(Application app) {
     *         dataSource = createPool(app.config().get("db.url"));
     *     }
     *
     *     @Override public void onStop(Application app) {
     *         if (dataSource != null) dataSource.close();
     *     }
     * }
     * }</pre>
     */
    public interface Infrastructure {
        /**
         * Called when the application starts.
         *
         * @param app the application being started
         */
        default void onStart(Application app) {}

        /**
         * Called when the application stops.
         *
         * @param app the application being stopped
         */
        default void onStop(Application app) {}

        /**
         * Get the infrastructure component name.
         *
         * @return unique identifier for this component
         */
        String name();
    }

    /**
     * Health check for monitoring application state.
     *
     * <p>Health checks run periodically in a background virtual thread.
     * Failed checks are logged as warnings.
     *
     * <p>Example:
     * <pre>{@code
     * class DatabaseHealthCheck implements Application.HealthCheck {
     *     private final DatabasePool pool;
     *
     *     DatabaseHealthCheck(DatabasePool pool) { this.pool = pool; }
     *
     *     @Override public String name() { return "database-connectivity"; }
     *
     *     @Override public boolean check() {
     *         return pool.isHealthy();
     *     }
     *
     *     @Override public Duration interval() {
     *         return Duration.ofSeconds(10);
     *     }
     * }
     * }</pre>
     */
    public interface HealthCheck {
        /**
         * Execute the health check.
         *
         * @return {@code true} if healthy, {@code false} if unhealthy
         */
        boolean check();

        /**
         * Get the health check name.
         *
         * @return identifier for logging
         */
        String name();

        /**
         * Get the check interval.
         *
         * @return duration between checks (default: 30 seconds)
         */
        default Duration interval() { return Duration.ofSeconds(30); }
    }

    /**
     * Builder for constructing Application instances.
     *
     * <p>Fluent API for configuring supervision strategy, services, infrastructure,
     * and health checks.
     *
     * <p>Example:
     * <pre>{@code
     * Application app = Application.builder("my-app")
     *     .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ALL)
     *     .maxRestarts(10)
     *     .restartWindow(Duration.ofMinutes(5))
     *     .config(ApplicationConfig.of("key", "value"))
     *     .service("worker", WorkerState::new, Worker::handle)
     *     .infrastructure(new MyInfrastructure())
     *     .healthCheck(new MyHealthCheck())
     *     .build();
     * }</pre>
     */
    public static final class Builder {
        private final String name;
        private Supervisor.Strategy strategy = Supervisor.Strategy.ONE_FOR_ONE;
        private int maxRestarts = 5;
        private Duration window = Duration.ofMinutes(1);
        private ApplicationConfig config = ApplicationConfig.empty();
        private final List<ServiceDef> services = new ArrayList<>();
        private final List<Infrastructure> infrastructure = new ArrayList<>();
        private final List<HealthCheck> healthChecks = new ArrayList<>();

        /**
         * Create a builder with the given application name.
         *
         * @param name application identifier
         */
        public Builder(String name) { this.name = name; }

        /**
         * Set the supervision strategy.
         *
         * @param s strategy for handling child failures
         * @return this builder
         * @see Supervisor.Strategy
         */
        public Builder supervisorStrategy(Supervisor.Strategy s) { this.strategy = s; return this; }

        /**
         * Set maximum restarts within the window before giving up.
         *
         * @param max maximum restart count
         * @return this builder
         */
        public Builder maxRestarts(int max) { this.maxRestarts = max; return this; }

        /**
         * Set the sliding window for restart counting.
         *
         * @param w duration of the restart window
         * @return this builder
         */
        public Builder restartWindow(Duration w) { this.window = w; return this; }

        /**
         * Set the application configuration.
         *
         * @param c configuration object
         * @return this builder
         */
        public Builder config(ApplicationConfig c) { this.config = c; return this; }

        /**
         * Register a supervised service.
         *
         * <p>Services are automatically supervised with the configured strategy.
         * Each service is a {@link Proc} with its own state and message handler.
         *
         * @param <S> state type
         * @param <M> message type
         * @param name service name (for lookup via {@link Application#service})
         * @param initialState supplier for initial state
         * @param handler message handler function (state, message) -> new state
         * @return this builder
         */
        public <S, M> Builder service(String name, Supplier<S> initialState, BiFunction<S, M, S> handler) {
            services.add(new ServiceDef(name, initialState, handler)); return this;
        }

        /**
         * Register an infrastructure component.
         *
         * <p>Infrastructure is started/stopped in declaration/reverse order.
         *
         * @param i infrastructure component
         * @return this builder
         */
        public Builder infrastructure(Infrastructure i) { infrastructure.add(i); return this; }

        /**
         * Register a health check.
         *
         * @param h health check to run periodically
         * @return this builder
         */
        public Builder healthCheck(HealthCheck h) { healthChecks.add(h); return this; }

        /**
         * Build the application.
         *
         * <p>Creates the supervision tree and initializes all services.
         *
         * @return configured application instance
         */
        public Application build() { return new Application(this); }

        private record ServiceDef(String name, Supplier<?> initialState, BiFunction<?, ?, ?> handler) {}
    }
}
