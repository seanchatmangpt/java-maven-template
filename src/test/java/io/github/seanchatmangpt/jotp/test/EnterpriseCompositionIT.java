package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.assertj.core.api.WithAssertions;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

/**
 * Integration tests for JOTP Enterprise Application Composer.
 *
 * <p>Tests all enterprise composition primitives:
 * <ul>
 *   <li>Application container and lifecycle</li>
 *   <li>Service registry and discovery</li>
 *   <li>Load balancing strategies</li>
 *   <li>Circuit breaker pattern</li>
 *   <li>Message bus with topics</li>
 *   <li>Event store and projections</li>
 *   <li>Metrics collection</li>
 *   <li>Distributed tracing</li>
 *   <li>Health checking</li>
 *   <li>API Gateway with routing</li>
 *   <li>Rate limiting</li>
 *   <li>CQRS command/query dispatch</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnterpriseCompositionIT implements WithAssertions {

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 1: APPLICATION FRAMEWORK
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Application: should build and start application with services")
    void applicationShouldBuildAndStart() throws Exception {
        // Create infrastructure
        var messageBus = MessageBus.create("test-bus");
        var eventStore = EventStore.create("test-events");
        var metrics = MetricsCollector.create("test-metrics");

        // Build application
        Application app = Application.builder("test-app")
                .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
                .maxRestarts(5)
                .restartWindow(Duration.ofMinutes(1))
                .service("processor",
                        () -> new ProcessorState(new ArrayList<>()),
                        this::handleProcessorMessage)
                .infrastructure(messageBus)
                .infrastructure(eventStore)
                .config(ApplicationConfig.create()
                        .environment("test")
                        .set("max.items", 100)
                        .build())
                .build();

        assertThat(app.name()).isEqualTo("test-app");
        assertThat(app.isStarted()).isFalse();

        // Start application
        app.start();
        assertThat(app.isStarted()).isTrue();
        assertThat(app.isRunning()).isTrue();

        // Send a message to the service
        Optional<ProcRef<ProcessorState, ProcessorMsg>> service = app.service("processor");
        assertThat(service).isPresent();

        ProcessorState state = service.get().ask(new ProcessorMsg.Process("test-data")).get(5, TimeUnit.SECONDS);
        assertThat(state.items()).hasSize(1);
        assertThat(state.items().getFirst()).isEqualTo("test-data");

        // Stop application
        app.stop();
        assertThat(app.isStarted()).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("ApplicationConfig: should provide typed configuration access")
    void applicationConfigShouldProvideTypedAccess() {
        ApplicationConfig config = ApplicationConfig.create()
                .environment("production")
                .set("server.port", 8080)
                .set("server.host", "localhost")
                .set("cache.enabled", true)
                .set("timeout.seconds", 30L)
                .set("rate", 1.5);

        assertThat(config.environment()).isEqualTo("production");
        assertThat(config.getInt("server.port", 0)).isEqualTo(8080);
        assertThat(config.getString("server.host", "")).isEqualTo("localhost");
        assertThat(config.getBoolean("cache.enabled", false)).isTrue();
        assertThat(config.getLong("timeout.seconds", 0L)).isEqualTo(30L);
        assertThat(config.getDouble("rate", 0.0)).isEqualTo(1.5);
        assertThat(config.has("server.port")).isTrue();

        // With environment variable fallback
        assertThat(config.getString("nonexistent.key", "default")).isEqualTo("default");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 2: SERVICE MESH
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("ServiceRegistry: should register and discover services")
    void serviceRegistryShouldRegisterAndDiscover() throws Exception {
        ServiceRegistry.reset();

        // Create and register a service
        Proc<ProcessorState, ProcessorMsg> proc = new Proc<>(
                new ProcessorState(new ArrayList<>()),
                this::handleProcessorMessage);

        ServiceRegistry.ServiceMetadata metadata = ServiceRegistry.ServiceMetadata.builder()
                .version("1.0.0")
                .tag("processing")
                .tag("telemetry")
                .property("region", "us-east-1")
                .build();

        ServiceRegistry.register("telemetry-processor", proc, metadata);

        // Lookup by name
        Optional<ServiceRegistry.ServiceInfo> info = ServiceRegistry.lookup("telemetry-processor");
        assertThat(info).isPresent();
        assertThat(info.get().metadata().tags()).contains("processing", "telemetry");
        assertThat(info.get().status()).isEqualTo(ServiceRegistry.ServiceStatus.UP);

        // Find by tag
        List<ServiceRegistry.ServiceInfo> byTag = ServiceRegistry.findByTag("processing");
        assertThat(byTag).hasSize(1);

        // Heartbeat updates lastSeen
        ServiceRegistry.heartbeat("telemetry-processor");
        info = ServiceRegistry.lookup("telemetry-processor");
        assertThat(info).isPresent();
        assertThat(info.get().lastSeen()).isNotNull();

        // Cleanup
        ServiceRegistry.reset();
    }

    @Test
    @Order(4)
    @DisplayName("ServiceRouter: should route messages based on predicates")
    void serviceRouterShouldRouteMessages() throws Exception {
        ServiceRegistry.reset();

        // Create services
        Proc<String, String> highPriority = new Proc<>("high", (s, m) -> s + ":" + m);
        Proc<String, String> normalPriority = new Proc<>("normal", (s, m) -> s + ":" + m);
        Proc<String, String> fallback = new Proc<>("fallback", (s, m) -> s + ":" + m);

        ServiceRegistry.register("high-priority", highPriority);
        ServiceRegistry.register("normal-priority", normalPriority);
        ServiceRegistry.register("fallback", fallback);

        // Build router
        ServiceRouter<String> router = ServiceRouter.<String>builder()
                .route("high", msg -> msg.startsWith("URGENT:"), "high-priority", 10)
                .route("normal", msg -> true, "normal-priority", 5)
                .fallback("fallback")
                .build();

        // Test routing
        List<String> highRoute = router.matchingRoutes("URGENT:data");
        assertThat(highRoute).contains("high-priority");

        List<String> normalRoute = router.matchingRoutes("normal data");
        assertThat(normalRoute).contains("normal-priority");

        ServiceRegistry.reset();
    }

    @Test
    @Order(5)
    @DisplayName("LoadBalancer: should distribute load across services")
    void loadBalancerShouldDistributeLoad() {
        ServiceRegistry.reset();

        // Create mock services
        List<ServiceRegistry.ServiceInfo> services = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Proc<String, String> proc = new Proc<>("service-" + i, (s, m) -> s);
            ServiceRegistry.register("service-" + i, proc);
            services.add(ServiceRegistry.lookup("service-" + i).orElseThrow());
        }

        // Round-robin
        LoadBalancer rr = LoadBalancer.roundRobin();
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            selected.add(rr.select(services).name());
        }
        assertThat(selected).hasSize(3);

        // Random
        LoadBalancer random = LoadBalancer.random();
        assertThat(random.select(services)).isNotNull();

        // Least-loaded (all equal, so any will do)
        LoadBalancer leastLoaded = LoadBalancer.leastLoaded();
        assertThat(leastLoaded.select(services)).isNotNull();

        // Weighted
        Map<String, Integer> weights = Map.of("service-0", 1, "service-1", 2, "service-2", 3);
        LoadBalancer weighted = LoadBalancer.weighted(weights);
        assertThat(weighted.select(services)).isNotNull();

        ServiceRegistry.reset();
    }

    @Test
    @Order(6)
    @DisplayName("CircuitBreaker: should open and close circuit based on failures")
    void circuitBreakerShouldOpenAndClose() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.builder("test-breaker")
                .failureThreshold(3)
                .timeout(Duration.ofSeconds(1))
                .resetTimeout(Duration.ofMillis(100))
                .halfOpenRequests(2)
                .build();

        // Initially closed
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Successful calls
        Result<String, CircuitBreaker.CircuitError> result = breaker.execute(() -> "success");
        assertThat(result.isSuccess()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Failures should open circuit
        for (int i = 0; i < 3; i++) {
            breaker.execute(() -> { throw new RuntimeException("error"); });
        }

        // Circuit should be open
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Requests should fail fast
        Result<String, CircuitBreaker.CircuitError> openResult = breaker.execute(() -> "should not run");
        assertThat(openResult.isFailure()).isTrue();

        // Wait for reset timeout
        Thread.sleep(150);

        // Should transition to half-open
        assertThat(breaker.state()).isIn(CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);

        // Reset for next test
        breaker.reset();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 3: MESSAGING INFRASTRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("MessageBus: should publish and subscribe to topics")
    void messageBusShouldPublishAndSubscribe() throws Exception {
        MessageBus bus = MessageBus.create();
        List<String> received = new CopyOnWriteArrayList<>();

        // Subscribe to topic
        MessageBus.Subscription sub = bus.subscribe("test.topic", env -> {
            received.add((String) env.payload());
        });

        // Publish messages
        bus.publish("test.topic", "message-1");
        bus.publish("test.topic", "message-2");

        // Wait for delivery
        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 2);
        assertThat(received).containsExactly("message-1", "message-2");

        // Unsubscribe
        sub.cancel();
        bus.publish("test.topic", "message-3");
        Thread.sleep(100);
        assertThat(received).hasSize(2);

        // Stats
        MessageBus.Stats stats = bus.stats();
        assertThat(stats.published()).isEqualTo(3);
        assertThat(stats.delivered()).isEqualTo(2);
    }

    @Test
    @Order(8)
    @DisplayName("MessageBus: should support wildcard subscriptions")
    void messageBusShouldSupportWildcards() throws Exception {
        MessageBus bus = MessageBus.create();
        List<String> received = new CopyOnWriteArrayList<>();

        // Subscribe with wildcard
        bus.subscribePattern("telemetry.*", env -> received.add(env.topic()));

        // Publish to matching topics
        bus.publish("telemetry.samples", "data");
        bus.publish("telemetry.events", "data");
        bus.publish("other.topic", "data");

        await().atMost(Duration.ofSeconds(2)).until(() -> received.size() >= 2);
        assertThat(received).contains("telemetry.samples", "telemetry.events");
        assertThat(received).doesNotContain("other.topic");
    }

    @Test
    @Order(9)
    @DisplayName("MessageStore: should persist and retrieve messages")
    void messageStoreShouldPersistAndRetrieve() {
        MessageStore store = MessageStore.inMemory()
                .retention(Duration.ofHours(24))
                .maxSize(1000)
                .build();

        // Store messages
        for (int i = 0; i < 5; i++) {
            store.store(MessageBus.Envelope.of("test.topic", "message-" + i));
        }

        // Retrieve
        var messages = store.retrieve("test.topic", 10).toList();
        assertThat(messages).hasSize(5);

        // Current sequence
        long seq = store.currentSequence("test.topic");
        assertThat(seq).isGreaterThanOrEqualTo(5);

        // Stats
        MessageStore.Stats stats = store.stats();
        assertThat(stats.totalMessages()).isEqualTo(5);
        assertThat(stats.topics()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 4: EVENT SOURCING
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("EventStore: should append and load events")
    void eventStoreShouldAppendAndLoad() {
        EventStore store = EventStore.create();

        // Append events
        store.append("order-123", List.of(
                new OrderCreated("order-123", Instant.now()),
                new ItemAdded("order-123", "item-1", 2),
                new ItemAdded("order-123", "item-2", 1)
        ));

        // Load events
        List<EventStore.StoredEvent> events = store.load("order-123").toList();
        assertThat(events).hasSize(3);

        // Check versions
        assertThat(events.get(0).version()).isEqualTo(1);
        assertThat(events.get(1).version()).isEqualTo(2);
        assertThat(events.get(2).version()).isEqualTo(3);

        // Current version
        assertThat(store.currentVersion("order-123")).isEqualTo(3);
    }

    @Test
    @Order(11)
    @DisplayName("EventStore: should support optimistic concurrency")
    void eventStoreShouldSupportOptimisticConcurrency() {
        EventStore store = EventStore.create();

        // First append
        store.append("order-456", List.of(new OrderCreated("order-456", Instant.now())), 0);

        // Concurrent append with wrong version should fail
        assertThatThrownBy(() ->
                store.append("order-456", List.of(new ItemAdded("order-456", "item-1", 1)), 0))
                .isInstanceOf(IllegalStateException.class);

        // Correct version should succeed
        store.append("order-456", List.of(new ItemAdded("order-456", "item-1", 1)), 1);
        assertThat(store.currentVersion("order-456")).isEqualTo(2);
    }

    @Test
    @Order(12)
    @DisplayName("EventStore: should support projections")
    void eventStoreShouldSupportProjections() {
        EventStore store = EventStore.create();
        OrderSummaryProjection projection = new OrderSummaryProjection();
        store.addProjection(projection);

        // Append events - projection should be updated
        store.append("order-789", List.of(
                new OrderCreated("order-789", Instant.now()),
                new ItemAdded("order-789", "item-1", 2),
                new ItemAdded("order-789", "item-2", 1)
        ));

        assertThat(projection.totalOrders()).isEqualTo(1);
        assertThat(projection.totalItems()).isEqualTo(2);

        // Rebuild projection
        projection.reset();
        assertThat(projection.totalOrders()).isEqualTo(0);

        store.rebuildProjection("order-summary");
        assertThat(projection.totalOrders()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 5: OBSERVABILITY
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(13)
    @DisplayName("MetricsCollector: should collect counters, gauges, histograms, timers")
    void metricsCollectorShouldCollectMetrics() throws Exception {
        MetricsCollector metrics = MetricsCollector.create("test-metrics");

        // Counter
        MetricsCollector.Counter counter = metrics.counter("requests.total");
        counter.increment();
        counter.increment(5);
        assertThat(counter.count()).isEqualTo(6);

        // Gauge
        AtomicInteger gaugeValue = new AtomicInteger(42);
        MetricsCollector.Gauge gauge = metrics.gauge("queue.depth", gaugeValue::get);
        assertThat(gauge.value()).isEqualTo(42.0);

        // Histogram
        MetricsCollector.Histogram histogram = metrics.histogram("latency.ms");
        for (int i = 1; i <= 100; i++) {
            histogram.record(i);
        }
        assertThat(histogram.count()).isEqualTo(100);
        assertThat(histogram.min()).isEqualTo(1);
        assertThat(histogram.max()).isEqualTo(100);
        assertThat(histogram.percentile(50)).isBetween(45.0, 55.0);

        // Timer
        MetricsCollector.Timer timer = metrics.timer("request.duration");
        try (MetricsCollector.TimerContext ctx = timer.start()) {
            Thread.sleep(10);
        }
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.mean().toMillis()).isGreaterThanOrEqualTo(10);

        // Snapshot
        Map<String, Object> snapshot = metrics.snapshot();
        assertThat(snapshot).containsKeys("requests.total", "queue.depth", "latency.ms", "request.duration");
    }

    @Test
    @Order(14)
    @DisplayName("DistributedTracer: should create spans with context propagation")
    void distributedTracerShouldCreateSpans() throws Exception {
        DistributedTracer tracer = DistributedTracer.create("test-tracer");

        // Create root span
        DistributedTracer.Span root = tracer.spanBuilder("operation")
                .setKind(DistributedTracer.SpanKind.SERVER)
                .setAttribute("service", "test")
                .startSpan();

        try (DistributedTracer.SpanScope scope = root.makeCurrent()) {
            // Current span is available
            assertThat(tracer.getCurrentSpan()).isPresent();

            // Create child span
            DistributedTracer.Span child = tracer.spanBuilder("sub-operation")
                    .setKind(DistributedTracer.SpanKind.INTERNAL)
                    .startSpan();

            try {
                Thread.sleep(5);
                child.addEvent("processing-complete");
            } finally {
                child.end();
            }
        } finally {
            root.end();
        }

        // Export spans
        List<Map<String, Object>> exported = tracer.exportSpans();
        assertThat(exported).hasSize(2);
        assertThat(exported.getFirst().get("name")).isEqualTo("operation");
    }

    @Test
    @Order(15)
    @DisplayName("HealthChecker: should run health checks and report status")
    void healthCheckerShouldRunChecks() {
        HealthChecker checker = HealthChecker.builder()
                .name("test-health")
                .check("always-healthy", () -> true, Duration.ofSeconds(5))
                .check("always-unhealthy", () -> false, Duration.ofSeconds(5))
                .check("throwing", () -> { throw new RuntimeException("oops"); }, Duration.ofSeconds(5))
                .build();

        // Run all checks
        HealthChecker.Status status = checker.check();

        // Overall should be unhealthy (one check fails)
        assertThat(status.overall()).isEqualTo(HealthChecker.HealthStatus.UNHEALTHY);

        // Individual checks
        assertThat(status.checks()).hasSize(3);
        assertThat(status.checks().get("always-healthy").status()).isEqualTo(HealthChecker.HealthStatus.HEALTHY);
        assertThat(status.checks().get("always-unhealthy").status()).isEqualTo(HealthChecker.HealthStatus.UNHEALTHY);
        assertThat(status.checks().get("throwing").status()).isEqualTo(HealthChecker.HealthStatus.UNHEALTHY);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 6: API GATEWAY
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(16)
    @DisplayName("ApiGateway: should route requests to handlers")
    void apiGatewayShouldRouteRequests() throws Exception {
        ApiGateway gateway = ApiGateway.builder()
                .route("/api/orders", ApiGateway.Method.GET,
                        req -> CompletableFuture.completedFuture(ApiGateway.Response.ok("[]")))
                .route("/api/orders", ApiGateway.Method.POST,
                        req -> CompletableFuture.completedFuture(ApiGateway.Response.created("/api/orders/123")))
                .route("/api/health",
                        req -> CompletableFuture.completedFuture(ApiGateway.Response.ok("{\"status\":\"ok\"}")))
                .build();

        // GET request
        ApiGateway.Request getReq = ApiGateway.Request.get("/api/orders");
        ApiGateway.Response getResp = gateway.handle(getReq).get(5, TimeUnit.SECONDS);
        assertThat(getResp.status()).isEqualTo(200);

        // POST request
        ApiGateway.Request postReq = ApiGateway.Request.post("/api/orders", "{}".getBytes());
        ApiGateway.Response postResp = gateway.handle(postReq).get(5, TimeUnit.SECONDS);
        assertThat(postResp.status()).isEqualTo(201);
        assertThat(postResp.headers()).containsKey("Location");

        // Health check
        ApiGateway.Request healthReq = ApiGateway.Request.get("/api/health");
        ApiGateway.Response healthResp = gateway.handle(healthReq).get(5, TimeUnit.SECONDS);
        assertThat(healthResp.status()).isEqualTo(200);

        // Not found
        ApiGateway.Request notFound = ApiGateway.Request.get("/unknown");
        ApiGateway.Response nfResp = gateway.handle(notFound).get(5, TimeUnit.SECONDS);
        assertThat(nfResp.status()).isEqualTo(404);

        // Stats
        ApiGateway.Stats stats = gateway.stats();
        assertThat(stats.requestsTotal()).isEqualTo(4);
    }

    @Test
    @Order(17)
    @DisplayName("RateLimiter: should limit request rates")
    void rateLimiterShouldLimitRates() {
        // Token bucket - 5 burst, 5 per second
        RateLimiter limiter = RateLimiter.tokenBucket(5, 5);

        // Should allow 5 immediately
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }

        // Should deny 6th
        assertThat(limiter.tryAcquire()).isFalse();

        // Reset
        limiter.reset();
        assertThat(limiter.tryAcquire()).isTrue();

        // Sliding window
        RateLimiter sliding = RateLimiter.slidingWindow(3, Duration.ofSeconds(10));
        assertThat(sliding.tryAcquire()).isTrue();
        assertThat(sliding.tryAcquire()).isTrue();
        assertThat(sliding.tryAcquire()).isTrue();
        assertThat(sliding.tryAcquire()).isFalse();

        // Per-second convenience
        RateLimiter perSec = RateLimiter.perSecond(10);
        assertThat(perSec.availablePermits()).isEqualTo(10);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PHASE 7: CQRS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(18)
    @DisplayName("CommandDispatcher: should dispatch commands to handlers")
    void commandDispatcherShouldDispatchCommands() {
        CommandDispatcher dispatcher = CommandDispatcher.create()
                .register(CreateOrderCmd.class, cmd -> CommandDispatcher.CommandResult.ok(
                        new OrderCreated(cmd.orderId, Instant.now())))
                .register(AddItemCmd.class, cmd -> {
                    if (cmd.quantity < 0) {
                        return CommandDispatcher.CommandResult.err("Invalid quantity");
                    }
                    return CommandDispatcher.CommandResult.ok(
                            new ItemAdded(cmd.orderId, cmd.itemId, cmd.quantity));
                });

        // Dispatch valid command
        CommandDispatcher.CommandResult<OrderCreated> result1 = dispatcher.dispatch(
                new CreateOrderCmd("order-1"));
        assertThat(result1.isSuccess()).isTrue();
        assertThat(result1.orElse(null).orderId).isEqualTo("order-1");

        // Dispatch invalid command
        CommandDispatcher.CommandResult<ItemAdded> result2 = dispatcher.dispatch(
                new AddItemCmd("order-1", "item-1", -1));
        assertThat(result2.isSuccess()).isFalse();

        // Stats
        Map<String, Long> stats = dispatcher.stats();
        assertThat(stats.get("dispatched")).isEqualTo(2L);
        assertThat(stats.get("succeeded")).isEqualTo(1L);
        assertThat(stats.get("failed")).isEqualTo(1L);
    }

    @Test
    @Order(19)
    @DisplayName("QueryDispatcher: should execute queries with optional caching")
    void queryDispatcherShouldExecuteQueries() {
        AtomicInteger queryCount = new AtomicInteger(0);

        QueryDispatcher dispatcher = QueryDispatcher.create()
                .registerFunc(GetOrderQuery.class, q -> {
                    queryCount.incrementAndGet();
                    return new TestOrder(q.orderId, "Order " + q.orderId);
                })
                .cache(Duration.ofMinutes(5));

        // First query
        Optional<TestOrder> result1 = dispatcher.query(new GetOrderQuery("order-1"));
        assertThat(result1).isPresent();
        assertThat(result1.get().name).isEqualTo("Order order-1");
        assertThat(queryCount.get()).isEqualTo(1);

        // Cached query
        Optional<TestOrder> result2 = dispatcher.query(new GetOrderQuery("order-1"));
        assertThat(result2).isPresent();
        assertThat(queryCount.get()).isEqualTo(1); // Not incremented

        // Different query
        Optional<TestOrder> result3 = dispatcher.query(new GetOrderQuery("order-2"));
        assertThat(result3).isPresent();
        assertThat(queryCount.get()).isEqualTo(2);

        // Stats
        Map<String, Long> stats = dispatcher.stats();
        assertThat(stats.get("cacheHits")).isEqualTo(1L);
        assertThat(stats.get("cacheMisses")).isEqualTo(2L);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FULL APPLICATION INTEGRATION TEST
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @Order(100)
    @DisplayName("Full Application: should compose and run complete telemetry application")
    void fullApplicationShouldComposeAndRun() throws Exception {
        // Infrastructure
        MessageBus messageBus = MessageBus.create("telemetry-bus");
        EventStore eventStore = EventStore.create("telemetry-events");
        MetricsCollector metrics = MetricsCollector.create("telemetry-metrics");
        HealthChecker healthChecker = HealthChecker.builder()
                .name("telemetry-health")
                .check("self", () -> true, Duration.ofSeconds(5))
                .build();

        // Build application
        Application app = Application.builder("telemetry-service")
                .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
                .maxRestarts(10)
                .restartWindow(Duration.ofMinutes(1))
                .service("ingress",
                        () -> new IngressState(messageBus, metrics),
                        this::handleIngress)
                .service("processor",
                        () -> new ProcessorState(new ArrayList<>()),
                        this::handleProcessorMessage)
                .infrastructure(messageBus)
                .infrastructure(eventStore)
                .healthCheck(healthChecker)
                .config(ApplicationConfig.create()
                        .environment("test")
                        .set("batch.size", 100)
                        .build())
                .build();

        // Start
        app.start();
        assertThat(app.isStarted()).isTrue();

        // Get service
        Optional<ProcRef<IngressState, IngressMsg>> ingress = app.service("ingress");
        assertThat(ingress).isPresent();

        // Send message
        IngressState state = ingress.get().ask(new IngressMsg.Ingest("sample-data")).get(5, TimeUnit.SECONDS);
        assertThat(state.processed).isGreaterThan(0);

        // Check infrastructure
        assertThat(app.infrastructure("telemetry-bus")).isPresent();
        assertThat(app.infrastructure("telemetry-events")).isPresent();

        // Stop
        app.stop();
        assertThat(app.isStarted()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST SUPPORT TYPES
    // ═══════════════════════════════════════════════════════════════════════════════

    // Processor service
    record ProcessorState(List<String> items) {}

    sealed interface ProcessorMsg permits ProcessorMsg.Process {
        record Process(String data) implements ProcessorMsg {}
    }

    private ProcessorState handleProcessorMessage(ProcessorState state, ProcessorMsg msg) {
        List<String> newItems = new ArrayList<>(state.items());
        switch (msg) {
            case ProcessorMsg.Process(var data) -> newItems.add(data);
        }
        return new ProcessorState(newItems);
    }

    // Ingress service
    record IngressState(MessageBus bus, MetricsCollector metrics, long processed) {
        IngressState(MessageBus bus, MetricsCollector metrics) {
            this(bus, metrics, 0);
        }
    }

    sealed interface IngressMsg permits IngressMsg.Ingest {
        record Ingest(String data) implements IngressMsg {}
    }

    private IngressState handleIngress(IngressState state, IngressMsg msg) {
        switch (msg) {
            case IngressMsg.Ingest(var data) -> {
                state.metrics.counter("ingress.received").increment();
                state.bus.publish("telemetry.ingress", data);
                return new IngressState(state.bus, state.metrics, state.processed + 1);
            }
        }
        return state;
    }

    // Event types
    record OrderCreated(String orderId, Instant timestamp) {}
    record ItemAdded(String orderId, String itemId, int quantity) {}
    record OrderSubmitted(String orderId, Instant timestamp) {}

    // Projection
    static class OrderSummaryProjection implements EventStore.Projection {
        private int totalOrders = 0;
        private int totalItems = 0;

        @Override public String name() { return "order-summary"; }

        @Override
        public void apply(EventStore.StoredEvent event) {
            if (event.event() instanceof OrderCreated) {
                totalOrders++;
            } else if (event.event() instanceof ItemAdded) {
                totalItems++;
            }
        }

        @Override public void reset() { totalOrders = 0; totalItems = 0; }
        public int totalOrders() { return totalOrders; }
        public int totalItems() { return totalItems; }
    }

    // Order query
    record TestOrder(String id, String name) {}

    record GetOrderQuery(String orderId) implements QueryDispatcher.Query<TestOrder> {
        @Override public String cacheKey() { return "order:" + orderId; }
    }

    // Commands
    record CreateOrderCmd(String orderId) implements CommandDispatcher.Command {}
    record AddItemCmd(String orderId, String itemId, int quantity) implements CommandDispatcher.Command {}
}
