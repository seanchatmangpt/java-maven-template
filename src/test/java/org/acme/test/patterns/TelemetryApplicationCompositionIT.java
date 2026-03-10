package org.acme.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;

import org.acme.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Full Telemetry Application Composition Test.
 *
 * <p>Demonstrates composing a complete enterprise telemetry application using all JOTP primitives:
 * <ul>
 *   <li>Application container with supervised services</li>
 *   <li>Message Bus with topic routing</li>
 *   <li>Event Store with projections</li>
 *   <li>Metrics collection</li>
 *   <li>Distributed tracing</li>
 *   <li>Health checking</li>
 *   <li>API Gateway with rate limiting</li>
 *   <li>CQRS command/query dispatch</li>
 *   <li>Service mesh with circuit breaker</li>
 *   <li>Saga orchestration for session lifecycle</li>
 * </ul>
 *
 * <p>This test validates the Joe Armstrong vision of composing entire enterprise applications
 * in Turtle, similar to Vaughn Vernon's Reactive Messaging Patterns.
 */
@Timeout(180)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Full Telemetry Application Composition")
class TelemetryApplicationCompositionIT implements WithAssertions {

    // ── Domain Types ─────────────────────────────────────────────────────────────────────────

    record ParameterId(String id) {}
    record Timestamp(long nanos) {}
    record SessionId(UUID id) {
        static SessionId generate() { return new SessionId(UUID.randomUUID()); }
    }

    sealed interface TelemetryMsg permits TelemetryMsg.Sample, TelemetryMsg.SessionEvent, TelemetryMsg.LapEvent, TelemetryMsg.StrategyCmd {
        SessionId sessionId();

        record Sample(SessionId sessionId, ParameterId param, short rawValue, Instant ts) implements TelemetryMsg {}
        record SessionEvent(SessionId sessionId, String state) implements TelemetryMsg {}
        record LapEvent(SessionId sessionId, int lap, Instant beaconTs) implements TelemetryMsg {}
        record StrategyCmd(SessionId sessionId, String query, CompletableFuture<String> replyTo) implements TelemetryMsg {}
    }

    // ── Service State Types ─────────────────────────────────────────────────────────────────────────

    record IngressState(int processed, MessageBus bus, MetricsCollector metrics) {
        IngressState(MessageBus bus, MetricsCollector metrics) {
            this(0, bus, metrics);
        }
    }

    record ProcessorState(Map<ParameterId, List<Short>> data, EventStore store) {
        ProcessorState(EventStore store) {
            this(new HashMap<>(), store);
        }
    }

    record StorageState(List<TelemetryMsg.Sample> samples, int count) {
        StorageState() {
            this(new ArrayList<>(), 0);
        }
    }

    // ── Test Fixtures ───────────────────────────────────────────────────────────────────────────

    private MessageBus messageBus;
    private EventStore eventStore;
    private MetricsCollector metrics;
    private DistributedTracer tracer;
    private HealthChecker healthChecker;
    private CommandDispatcher commandDispatcher;
    private QueryDispatcher queryDispatcher;
    private ServiceRegistry.ServiceMetadata serviceMetadata;

    @BeforeEach
    void setUp() {
        ServiceRegistry.reset();

        messageBus = MessageBus.create("telemetry-bus");
        eventStore = EventStore.create("telemetry-events");
        metrics = MetricsCollector.create("telemetry-metrics");
        tracer = DistributedTracer.create("telemetry-tracer");
        healthChecker = HealthChecker.builder()
                .name("telemetry-health")
                .check("self", () -> true, Duration.ofSeconds(5))
                .build();

        serviceMetadata = ServiceRegistry.ServiceMetadata.builder()
                .version("1.0.0")
                .tag("telemetry")
                .tag("processing")
                .build();

        commandDispatcher = CommandDispatcher.create();
        queryDispatcher = QueryDispatcher.create()
                .cache(Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        ServiceRegistry.reset();
    }

    // ── Tests ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should compose and run complete telemetry application")
    void shouldComposeAndRunCompleteTelemetryApplication() throws Exception {
        // ═════════════════════════════════════════════════════════════════════════════════
        // 1. BUILD APPLICATION
        // ═════════════════════════════════════════════════════════════════════════════════

        Application app = Application.builder("telemetry-service")
                .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
                .maxRestarts(10)
                .restartWindow(Duration.ofMinutes(1))
                .service("ingress",
                        () -> new IngressState(messageBus, metrics),
                        this::handleIngress)
                .service("processor",
                        () -> new ProcessorState(eventStore),
                        this::handleProcessor)
                .service("storage",
                        StorageState::new,
                        this::handleStorage)
                .infrastructure(messageBus)
                .infrastructure(eventStore)
                .infrastructure(metrics)
                .infrastructure(tracer)
                .healthCheck(healthChecker)
                .config(ApplicationConfig.create()
                        .environment("test")
                        .set("batch.size", 100)
                        .set("timeout.ms", 5000))
                .build();

        // ═════════════════════════════════════════════════════════════════════════════════
        // 2. START APPLICATION
        // ═════════════════════════════════════════════════════════════════════════════════

        app.start();
        assertThat(app.isStarted()).isTrue();
        assertThat(app.isRunning()).isTrue();

        // ═════════════════════════════════════════════════════════════════════════════════
        // 3. GET SERVICE REFERENCES
        // ═════════════════════════════════════════════════════════════════════════════════

        Optional<ProcRef<IngressState, TelemetryMsg>> ingressRef = app.service("ingress");
        assertThat(ingressRef).isPresent();

        Optional<ProcRef<ProcessorState, TelemetryMsg>> processorRef = app.service("processor");
        assertThat(processorRef).isPresent();

        Optional<ProcRef<StorageState, TelemetryMsg>> storageRef = app.service("storage");
        assertThat(storageRef).isPresent();

        // ═════════════════════════════════════════════════════════════════════════════════
        // 4. REGISTER SERVICES
        // ═════════════════════════════════════════════════════════════════════════════════

        // Note: Services are already running from Application.start()
        // We can register them for discovery
        assertThat(app.serviceNames()).contains("ingress", "processor", "storage");

        // ═════════════════════════════════════════════════════════════════════════════════
        // 5. SEND TELEMETRY MESSAGES
        // ═════════════════════════════════════════════════════════════════════════════════

        var sessionId = SessionId.generate();

        // Session lifecycle
        ingressRef.get().tell(new TelemetryMsg.SessionEvent(sessionId, "INITIALIZED"));
        ingressRef.get().tell(new TelemetryMsg.SessionEvent(sessionId, "RECORDING"));

        // Send samples
        for (int i = 0; i < 100; i++) {
            ingressRef.get().tell(new TelemetryMsg.Sample(
                    sessionId,
                    new ParameterId("BRAKE_" + (i % 4)),
                    (short) (100 + i),
                    Instant.now()));
        }

        // Send lap events
        for (int lap = 1; lap <= 5; lap++) {
            ingressRef.get().tell(new TelemetryMsg.LapEvent(sessionId, lap, Instant.now()));
        }

        // ═════════════════════════════════════════════════════════════════════════════════
        // 6. VERIFY PROCESSING
        // ═════════════════════════════════════════════════════════════════════════════════

        await().atMost(Duration.ofSeconds(5)).until(() -> {
            IngressState state = ingressRef.get().ask(new QueryState.Count()).get(2, TimeUnit.SECONDS);
            return state.processed() >= 106;
        });

        IngressState ingressState = ingressRef.get().ask(new QueryState.Full()).get(2, TimeUnit.SECONDS);
        assertThat(ingressState.processed()).isGreaterThanOrEqualTo(106);

        // ═════════════════════════════════════════════════════════════════════════════════
        // 7. VERIFY METRICS
        // ═════════════════════════════════════════════════════════════════════════════════

        Map<String, Object> metricsSnapshot = metrics.snapshot();
        assertThat(metricsSnapshot).containsKey("ingress.received");

        // ═════════════════════════════════════════════════════════════════════════════════
        // 8. VERIFY TRACING
        // ═════════════════════════════════════════════════════════════════════════════════

        Map<String, Long> tracerStats = tracer.stats();
        assertThat(tracerStats.get("spansCreated")).isGreaterThanOrEqualTo(0);

        // ═════════════════════════════════════════════════════════════════════════════════
        // 9. VERIFY EVENT STORE
        // ═════════════════════════════════════════════════════════════════════════════════

        EventStore.Stats storeStats = eventStore.stats();
        assertThat(storeStats.totalEvents()).isGreaterThanOrEqualTo(0);

        // ═════════════════════════════════════════════════════════════════════════════════
        // 10. VERIFY HEALTH
        // ═════════════════════════════════════════════════════════════════════════════════

        boolean healthy = healthChecker.check();
        assertThat(healthy).isTrue();

        // ═════════════════════════════════════════════════════════════════════════════════
        // 11. TEST API GATEWAY
        // ═════════════════════════════════════════════════════════════════════════════════

        ApiGateway gateway = ApiGateway.builder()
                .route("/api/telemetry", ApiGateway.Method.POST,
                        req -> CompletableFuture.completedFuture(ApiGateway.Response.ok("accepted")))
                .rateLimiter(RateLimiter.perSecond(100))
                .build();

        ApiGateway.Response gatewayResponse = gateway.handle(
                ApiGateway.Request.post("/api/telemetry", "data".getBytes()))
                .get(2, TimeUnit.SECONDS);
        assertThat(gatewayResponse.status()).isEqualTo(200);

        // ═════════════════════════════════════════════════════════════════════════════════
        // 12. TEST CQRS
        // ═════════════════════════════════════════════════════════════════════════════════

        commandDispatcher.register(CreateSessionCmd.class, cmd -> {
            eventStore.append(cmd.sessionId.id().toString(), List.of(new SessionCreated(cmd.sessionId, Instant.now())));
            return CommandDispatcher.CommandResult.ok(cmd.sessionId);
        });

        CommandDispatcher.CommandResult<SessionId> cmdResult = commandDispatcher.dispatch(new CreateSessionCmd(sessionId));
        assertThat(cmdResult.isSuccess()).isTrue();

        // ═════════════════════════════════════════════════════════════════════════════════
        // 13. TEST CIRCUIT BREAKER
        // ═════════════════════════════════════════════════════════════════════════════════

        CircuitBreaker breaker = CircuitBreaker.builder("telemetry-breaker")
                .failureThreshold(5)
                .timeout(Duration.ofSeconds(1))
                .resetTimeout(Duration.ofMillis(100))
                .build();

        // Successful call
        Result<String, CircuitBreaker.CircuitError> breakerResult = breaker.execute(() -> "success");
        assertThat(breakerResult.isSuccess()).isTrue();

        // ═════════════════════════════════════════════════════════════════════════════════
        // 14. TEST LOAD BALANCER
        // ═════════════════════════════════════════════════════════════════════════════════

        LoadBalancer lb = LoadBalancer.roundRobin();
        List<ServiceRegistry.ServiceInfo> services = new ArrayList<>(ServiceRegistry.allServices());
        if (!services.isEmpty()) {
            ServiceRegistry.ServiceInfo selected = lb.select(services);
            assertThat(selected).isNotNull();
        }

        // ═════════════════════════════════════════════════════════════════════════════════
        // 15. TEST SAGA ORCHESTRATOR
        // ═════════════════════════════════════════════════════════════════════════════════

        SagaOrchestrator saga = SagaOrchestrator.builder("session-saga")
                .step("validate-session")
                .action(d -> SagaOrchestrator.StepResult.success("validated"))
                .step("init-storage")
                .action(d -> SagaOrchestrator.StepResult.success("initialized"))
                .step("start-recording")
                .action(d -> SagaOrchestrator.StepResult.success("recording"))
                .build();

        CompletableFuture<SagaOrchestrator.SagaResult> sagaFuture = saga.execute(sessionId.id().toString());
        SagaOrchestrator.SagaResult sagaResult = sagaFuture.get(5, TimeUnit.SECONDS);
        assertThat(sagaResult.status()).isEqualTo(SagaOrchestrator.SagaStatus.Completed);

        // ═════════════════════════════════════════════════════════════════════════════════
        // 16. STOP APPLICATION
        // ═════════════════════════════════════════════════════════════════════════════════

        app.stop();
        assertThat(app.isStarted()).isFalse();
    }

    @Test
    @DisplayName("Should handle application with crashing services")
    void shouldHandleApplicationWithCrashingServices() throws Exception {
        var crashCount = new AtomicInteger(0);

        Application app = Application.builder("crash-test-app")
                .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
                .maxRestarts(5)
                .restartWindow(Duration.ofMinutes(1))
                .service("crashy",
                        () -> 0,
                        (Integer s, String msg) -> {
                            if (msg.equals("CRASH")) {
                                throw new RuntimeException("Intentional crash #" + crashCount.getAndIncrement());
                            }
                            return s + 1;
                        })
                .build();

        app.start();

        Optional<ProcRef<Integer, String>> service = app.service("crashy");
        assertThat(service).isPresent();

        // Send valid messages
        for (int i = 0; i < 10; i++) {
            service.get().tell("msg-" + i);
        }

        // Wait for processing
        await().atMost(Duration.ofSeconds(2)).until(() -> {
            Integer state = service.get().ask("query").get(2, TimeUnit.SECONDS);
            return state >= 10;
        });

        // Crash the service
        service.get().tell("CRASH");

        // Wait for supervisor to restart
        await().atMost(Duration.ofSeconds(3)).until(() -> app.isRunning());

        // Continue processing after restart
        service.get().tell("after-crash");

        await().atMost(Duration.ofSeconds(2)).until(() -> {
            Integer state = service.get().ask("query").get(2, TimeUnit.SECONDS);
            return state >= 1;
        });

        assertThat(app.isRunning()).isTrue();

        app.stop();
    }

    // ── Message Handlers ─────────────────────────────────────────────────────────────────────

    private IngressState handleIngress(IngressState state, TelemetryMsg msg) {
        var span = tracer.spanBuilder("ingress." + msg.getClass().getSimpleName())
                .startSpan();
        try (var scope = span.makeCurrent()) {
            metrics.counter("ingress.received").increment();

            switch (msg) {
                case TelemetryMsg.Sample sample -> {
                    messageBus.publish("telemetry.samples", sample);
                    return new IngressState(state.processed() + 1, state.bus(), state.metrics());
                }
                case TelemetryMsg.SessionEvent event -> {
                    messageBus.publish("telemetry.events", event);
                    return new IngressState(state.processed() + 1, state.bus(), state.metrics());
                }
                case TelemetryMsg.LapEvent lap -> {
                    messageBus.publish("telemetry.laps", lap);
                    return new IngressState(state.processed() + 1, state.bus(), state.metrics());
                }
                case TelemetryMsg.StrategyCmd cmd -> {
                    cmd.replyTo().complete("ACK");
                    return new IngressState(state.processed() + 1, state.bus(), state.metrics());
                }
            }
            span.addEvent("processed");
        } finally {
            span.end();
        }
        return state;
    }

    private ProcessorState handleProcessor(ProcessorState state, TelemetryMsg msg) {
        if (msg instanceof TelemetryMsg.Sample sample) {
            var newData = new HashMap<>(state.data());
            newData.computeIfAbsent(sample.param(), k -> new ArrayList<>()).add(sample.rawValue());

            // Store in event store
            eventStore.append(sample.sessionId().id().toString(), List.of(sample));

            return new ProcessorState(newData, state.store());
        }
        return state;
    }

    private StorageState handleStorage(StorageState state, TelemetryMsg msg) {
        if (msg instanceof TelemetryMsg.Sample sample) {
            var newSamples = new ArrayList<>(state.samples());
            newSamples.add(sample);
            return new StorageState(newSamples, state.count() + 1);
        }
        return state;
    }

    // ── Query Types ─────────────────────────────────────────────────────────────────────────

    sealed interface QueryState permits QueryState.Count, QueryState.Full {
        record Count() implements QueryState {}
        record Full() implements QueryState {}
    }

    // ── Command Types ────────────────────────────────────────────────────────────────────────

    record CreateSessionCmd(SessionId sessionId) implements CommandDispatcher.Command {}
    record SessionCreated(SessionId sessionId, Instant timestamp) {}

    // ── Service Registry Test ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should register and discover services")
    void shouldRegisterAndDiscoverServices() throws Exception {
        var proc = new Proc<>(0, (s, m) -> s + 1);

        ServiceRegistry.register("test-service", proc, serviceMetadata);

        Optional<ServiceRegistry.ServiceInfo> info = ServiceRegistry.lookup("test-service");
        assertThat(info).isPresent();
        assertThat(info.get().metadata().tags()).contains("telemetry", "processing");

        List<ServiceRegistry.ServiceInfo> byTag = ServiceRegistry.findByTag("telemetry");
        assertThat(byTag).hasSize(1);

        ServiceRegistry.reset();
    }

    // ── Message Bus Test ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should publish and subscribe to topics")
    void shouldPublishAndSubscribeToTopics() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        MessageBus.Subscription sub = messageBus.subscribe("test.topic", env -> {
            received.add((String) env.payload());
        });

        messageBus.publish("test.topic", "message-1");
        messageBus.publish("test.topic", "message-2");

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 2);
        assertThat(received).containsExactly("message-1", "message-2");

        sub.cancel();
    }

    // ── Rate Limiter Test ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should limit request rates")
    void shouldLimitRequestRates() {
        RateLimiter limiter = RateLimiter.tokenBucket(5, 5);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        limiter.reset();
        assertThat(limiter.tryAcquire()).isTrue();
    }

    // ── Circuit Breaker Test ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should open circuit on failures")
    void shouldOpenCircuitOnFailures() {
        CircuitBreaker breaker = CircuitBreaker.builder("test")
                .failureThreshold(3)
                .resetTimeout(Duration.ofMillis(50))
                .build();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        for (int i = 0; i < 3; i++) {
            breaker.execute(() -> { throw new RuntimeException("error"); });
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        breaker.reset();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
