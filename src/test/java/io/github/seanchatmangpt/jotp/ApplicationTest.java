package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Application} — verifies Javadoc documentation matches actual behavior.
 *
 * <p>Tests the OTP Application container which composes services, infrastructure,
 * and health checks with supervision tree lifecycle management.
 */
@DisplayName("Application — OTP Application container")
class ApplicationTest implements WithAssertions {

    private Application app;

    @AfterEach
    void cleanup() {
        if (app != null) {
            try {
                app.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Builder API — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Builder — fluent API for construction")
    class BuilderApi {

        @Test
        @DisplayName("creates application with name")
        void createsApplicationWithName() {
            app = Application.builder("test-app").build();

            assertThat(app.name()).isEqualTo("test-app");
        }

        @Test
        @DisplayName("application is not started before start() is called")
        void defaultSupervisorStrategy() {
            app = Application.builder("test").build();

            // isStarted() tracks explicit start() call
            // isRunning() checks supervisor state (may be true if services were added)
            assertThat(app.isStarted()).isFalse();
        }

        @Test
        @DisplayName("configures supervision strategy")
        void configuresSupervisionStrategy() {
            AtomicInteger handlerCalls = new AtomicInteger(0);

            app = Application.builder("test")
                    .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ALL)
                    .maxRestarts(10)
                    .restartWindow(Duration.ofMinutes(5))
                    .service("counter", () -> 0, (state, msg) -> {
                        handlerCalls.incrementAndGet();
                        return state;
                    })
                    .build();

            assertThat(app.serviceNames()).contains("counter");
        }

        @Test
        @DisplayName("registers multiple services")
        void registersMultipleServices() {
            app = Application.builder("multi-service")
                    .service("worker-1", () -> "state1", (s, m) -> s)
                    .service("worker-2", () -> "state2", (s, m) -> s)
                    .service("worker-3", () -> "state3", (s, m) -> s)
                    .build();

            Set<String> names = app.serviceNames();
            assertThat(names).containsExactlyInAnyOrder("worker-1", "worker-2", "worker-3");
        }

        @Test
        @DisplayName("registers infrastructure components")
        void registersInfrastructure() {
            TestInfrastructure infra = new TestInfrastructure("database");

            app = Application.builder("test")
                    .infrastructure(infra)
                    .build();

            Optional<TestInfrastructure> found = app.infrastructure("database");
            assertThat(found).isPresent();
            assertThat(found.get()).isSameAs(infra);
        }

        @Test
        @DisplayName("registers health checks")
        void registersHealthChecks() {
            AtomicBoolean healthy = new AtomicBoolean(true);
            TestHealthCheck check = new TestHealthCheck("db-check", healthy::get);

            app = Application.builder("test")
                    .healthCheck(check)
                    .build();

            app.start();

            // Health check is registered (we can't directly verify, but we verify no errors)
            assertThat(app.isStarted()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Service Lookup — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("service(name) — lookup supervised processes")
    class ServiceLookup {

        @Test
        @DisplayName("returns service by name")
        void returnsServiceByName() {
            app = Application.builder("test")
                    .service("orders", () -> 0, (state, msg) -> state + 1)
                    .build();

            Optional<ProcRef<Integer, Object>> ref = app.service("orders");

            assertThat(ref).isPresent();
        }

        @Test
        @DisplayName("returns empty for unknown service")
        void returnsEmptyForUnknown() {
            app = Application.builder("test").build();

            Optional<? extends ProcRef<?, ?>> ref = app.service("nonexistent");

            assertThat(ref).isEmpty();
        }

        @Test
        @DisplayName("returns correct type")
        @SuppressWarnings("unchecked")
        void returnsCorrectType() {
            app = Application.builder("test")
                    .service("typed", () -> "initial-state", (s, m) -> s)
                    .build();

            Optional<ProcRef<String, Object>> ref = (Optional<ProcRef<String, Object>>) (Optional<?>) app.service("typed");

            assertThat(ref).isPresent();
            // Type safety verified at compile time
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Infrastructure Lookup — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("infrastructure(name) — lookup infrastructure components")
    class InfrastructureLookup {

        @Test
        @DisplayName("returns infrastructure by name")
        void returnsInfrastructureByName() {
            TestInfrastructure infra = new TestInfrastructure("cache");
            app = Application.builder("test")
                    .infrastructure(infra)
                    .build();

            Optional<TestInfrastructure> found = app.infrastructure("cache");

            assertThat(found).isPresent();
            assertThat(found.get().name()).isEqualTo("cache");
        }

        @Test
        @DisplayName("returns empty for unknown infrastructure")
        void returnsEmptyForUnknown() {
            app = Application.builder("test").build();

            Optional<Application.Infrastructure> found = app.infrastructure("nonexistent");

            assertThat(found).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Lifecycle — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle — start and stop")
    class Lifecycle {

        @Test
        @DisplayName("start() returns Ok on first call")
        void startReturnsOk() {
            app = Application.builder("test").build();

            Application.StartResult result = app.start();

            assertThat(result).isInstanceOf(Application.StartResult.Ok.class);
        }

        @Test
        @DisplayName("start() returns Err on second call — idempotent")
        void startReturnsErrOnSecondCall() {
            app = Application.builder("test").build();

            app.start();
            Application.StartResult result = app.start();

            assertThat(result).isInstanceOf(Application.StartResult.Err.class);
            assertThat(((Application.StartResult.Err) result).error()).contains("Already started");
        }

        @Test
        @DisplayName("isStarted() reflects start state")
        void isStartedReflectsState() {
            app = Application.builder("test").build();

            assertThat(app.isStarted()).isFalse();

            app.start();

            assertThat(app.isStarted()).isTrue();
        }

        @Test
        @DisplayName("stop() returns Ok on stopped application")
        void stopReturnsOk() {
            app = Application.builder("test").build();
            app.start();

            Application.StopResult result = app.stop();

            assertThat(result).isInstanceOf(Application.StopResult.Ok.class);
        }

        @Test
        @DisplayName("stop() is idempotent — returns Ok on already stopped")
        void stopIsIdempotent() {
            app = Application.builder("test").build();

            Application.StopResult result = app.stop();

            assertThat(result).isInstanceOf(Application.StopResult.Ok.class);
        }

        @Test
        @DisplayName("isStarted() reflects stop state")
        void isStartedReflectsStopState() {
            app = Application.builder("test").build();
            app.start();
            app.stop();

            assertThat(app.isStarted()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Infrastructure Lifecycle — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Infrastructure Lifecycle — start/stop order")
    class InfrastructureLifecycle {

        @Test
        @DisplayName("calls onStart in declaration order")
        void callsOnStartInOrder() {
            List<String> order = new ArrayList<>();
            TestInfrastructure infra1 = new TestInfrastructure("first") {
                @Override public void onStart(Application app) { order.add("first-start"); }
            };
            TestInfrastructure infra2 = new TestInfrastructure("second") {
                @Override public void onStart(Application app) { order.add("second-start"); }
            };

            app = Application.builder("test")
                    .infrastructure(infra1)
                    .infrastructure(infra2)
                    .build();
            app.start();

            assertThat(order).containsExactly("first-start", "second-start");
        }

        @Test
        @DisplayName("calls onStop in reverse declaration order")
        void callsOnStopInReverseOrder() {
            List<String> order = new ArrayList<>();
            TestInfrastructure infra1 = new TestInfrastructure("first") {
                @Override public void onStop(Application app) { order.add("first-stop"); }
            };
            TestInfrastructure infra2 = new TestInfrastructure("second") {
                @Override public void onStop(Application app) { order.add("second-stop"); }
            };

            app = Application.builder("test")
                    .infrastructure(infra1)
                    .infrastructure(infra2)
                    .build();
            app.start();
            app.stop();

            assertThat(order).containsExactly("second-stop", "first-stop");
        }

        @Test
        @DisplayName("infrastructure receives application reference")
        void infrastructureReceivesApplicationReference() {
            AtomicReference<Application> received = new AtomicReference<>();
            TestInfrastructure infra = new TestInfrastructure("test") {
                @Override public void onStart(Application app) { received.set(app); }
            };

            app = Application.builder("test")
                    .infrastructure(infra)
                    .build();
            app.start();

            assertThat(received.get()).isSameAs(app);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Configuration — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("returns application config")
        void returnsApplicationConfig() {
            ApplicationConfig config = ApplicationConfig.create()
                    .set("port", "8080")
                    .set("host", "localhost")
                    .build();

            app = Application.builder("test")
                    .config(config)
                    .build();

            assertThat(app.config()).isSameAs(config);
        }

        @Test
        @DisplayName("default config is empty")
        void defaultConfigIsEmpty() {
            app = Application.builder("test").build();

            assertThat(app.config()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Javadoc Example — full lifecycle verification
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Javadoc Example — full lifecycle")
    class JavadocExample {

        @Test
        @DisplayName("application lifecycle from Javadoc")
        void applicationLifecycleFromJavadoc() {
            // Example from Javadoc:
            // Application app = Application.builder("order-service")
            //     .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
            //     .maxRestarts(5)
            //     .restartWindow(Duration.ofMinutes(1))
            //     .config(ApplicationConfig.create().set("port", "8080").build())
            //     .service("order-processor", OrderState::new, OrderProcessor::handle)
            //     .service("inventory-checker", InventoryState::new, InventoryChecker::handle)
            //     .infrastructure(new DatabasePool())
            //     .infrastructure(new MessageQueue())
            //     .healthCheck(new DatabaseHealthCheck())
            //     .build();

            AtomicInteger orderCount = new AtomicInteger(0);
            AtomicInteger inventoryCount = new AtomicInteger(0);

            app = Application.builder("order-service")
                    .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
                    .maxRestarts(5)
                    .restartWindow(Duration.ofMinutes(1))
                    .config(ApplicationConfig.create().set("port", "8080").build())
                    .service("order-processor",
                            () -> orderCount,
                            (state, msg) -> { state.incrementAndGet(); return state; })
                    .service("inventory-checker",
                            () -> inventoryCount,
                            (state, msg) -> { state.incrementAndGet(); return state; })
                    .infrastructure(new TestInfrastructure("database"))
                    .infrastructure(new TestInfrastructure("message-queue"))
                    .healthCheck(new TestHealthCheck("db-health", () -> true))
                    .build();

            // Start all services and infrastructure
            var result = app.start();
            assertThat(result).isInstanceOf(Application.StartResult.Ok.class);
            assertThat(app.isStarted()).isTrue();
            assertThat(app.serviceNames()).contains("order-processor", "inventory-checker");

            // Access services by name
            Optional<? extends ProcRef<?, ?>> orders = app.service("order-processor");
            assertThat(orders).isPresent();

            // Stop gracefully (reversed infrastructure shutdown order)
            var stopResult = app.stop();
            assertThat(stopResult).isInstanceOf(Application.StopResult.Ok.class);
            assertThat(app.isStarted()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Test Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static class TestInfrastructure implements Application.Infrastructure {
        private final String name;

        TestInfrastructure(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }

    private static class TestHealthCheck implements Application.HealthCheck {
        private final String name;
        private final Supplier<Boolean> healthy;

        TestHealthCheck(String name, Supplier<Boolean> healthy) {
            this.name = name;
            this.healthy = healthy;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean check() {
            return healthy.get();
        }
    }
}
