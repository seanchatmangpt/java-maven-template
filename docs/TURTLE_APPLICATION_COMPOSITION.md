# Composing Enterprise Applications from Turtle

This document describes how to compose complete enterprise applications using Turtle (RDF) specification files.

## Overview

The Joe Armstrong AGI vision for JOTP is to compose **entire enterprise applications from Turtle**, similar to Vaughn Vernon's Reactive Messaging Patterns for Actor Model for Scala. This approach enables:

- **Declarative application definition** - Define your application architecture in Turtle RDF
- **Code generation** - Generate Java code from Turtle specifications
- **Validation** - Validate Turtle specs against the ontology
- **Round-trip engineering** - From Turtle to Java and back

## Quick Start

```bash
# Validate a Turtle specification
bin/jgen validate examples/telemetry-app.ttl

# Generate Java code from Turtle
bin/jgen generate examples/telemetry-app.ttl --output ./generated

# Generate and compile
bin/jgen generate examples/telemetry-app.ttl --compile
```

## Turtle Specification Structure

### Application Definition

```turtle
app:TelemetryApplication a app:Application ;
    rdfs:label "Telemetry Processing Service" ;
    app:hasName "telemetry-service" ;
    app:hasSupervisorStrategy app:ONE_FOR_ONE ;
    app:hasMaxRestarts 10 ;
    app:hasRestartWindow "PT1M" .
```

### Service Definition

```turtle
app:IngressService a app:Service ;
    rdfs:label "Data Ingress" ;
    app:hasName "ingress" ;
    app:hasInitialState app:IngressState ;
    app:hasHandler app:ingressHandler ;
    app:usesPattern eip:ContentBasedRouter .
```

### Infrastructure Definition

```turtle
app:TelemetryMessageBus a app:Infrastructure ;
    rdfs:label "Message Bus" ;
    app:hasType app:MessageBus ;
    app:providesPattern eip:MessageBus .
```

### Configuration

```turtle
app:TelemetryConfig a app:ApplicationConfig ;
    app:environment "production" ;
    app:set "kafka.bootstrap.servers" "kafka:9092" ;
    app:set "batch.size" "100" .
```

## Generated Java Code

From a Turtle specification, JOTP generates:

1. **Application class** - Main application container
2. **Service classes** - Proc handlers for each service
3. **State classes** - State types for each service
4. **Message classes** - Sealed interfaces for messages
5. **Configuration class** - Type-safe configuration

### Example Generated Application

```java
// Generated from examples/telemetry-app.ttl
public final class TelemetryApplication implements Application {
    private final Supervisor supervisor;
    private final Map<String, ProcRef<?, ?>> services = new ConcurrentHashMap<>();
    private final MessageBus messageBus;
    private final EventStore eventStore;
    private final MetricsCollector metrics;

    private TelemetryApplication(TelemetryConfig config) {
        this.supervisor = new Supervisor(
            "telemetry-service",
            Supervisor.Strategy.ONE_FOR_ONE,
            10,
            Duration.ofMinutes(1)
        );
        this.messageBus = MessageBus.create();
        this.eventStore = EventStore.create();
        this.metrics = MetricsCollector.create();
        // ... initialize services
    }

    public void start() {
        messageBus.start();
        eventStore.start();
        // Start all services under supervision
        services.put("ingress", supervisor.supervise("ingress",
            new IngressState(messageBus, metrics),
            this::handleIngress));
        // ... more services
    }

    public void stop() {
        supervisor.shutdown();
        messageBus.stop();
        eventStore.stop();
    }
}
```

## Ontology Reference

The JOTP enterprise ontology (`schema/java-enterprise.ttl`) defines:

### Classes

| Class | Description |
|-------|-------------|
| `app:Application` | Root application container |
| `app:Service` | Supervised service process |
| `app:Infrastructure` | Shared infrastructure component |
| `app:HealthCheck` | Health check definition |
| `app:Command` | CQRS command |
| `app:Query` | CQRS query |
| `app:Saga` | Saga orchestrator |

### Properties

| Property | Domain | Range | Description |
|----------|--------|-------|-------------|
| `app:hasService` | Application | Service | Service component |
| `app:hasInfrastructure` | Application | Infrastructure | Infrastructure component |
| `app:usesPattern` | Service | Pattern | EIP pattern used |
| `app:hasHandler` | Service | Handler | Message handler function |

### Patterns

The ontology references EIP patterns from `schema/java-messaging.ttl`:

- `eip:ContentBasedRouter`
- `eip:MessageBus`
- `eip:EventStore`
- `eip:CircuitBreaker`
- `eip:RateLimiter`
- `eip:Saga`
- `eip:CQRS`

## Validation

Validate Turtle specifications against the ontology:

```bash
# Validate structure
bin/jgen validate examples/telemetry-app.ttl

# Output:
# ✓ Application: telemetry-service
# ✓ Services: ingress, processor, storage
# ✓ Infrastructure: MessageBus, EventStore, Metrics
# ✓ HealthChecks: 1
# ✓ Configuration: production
# ✓ Patterns: ContentBasedRouter, MessageBus, EventStore
```

## Complete Example

See `examples/f1-telemetry-application.ttl` for a complete telemetry application specification demonstrating:

- **4 services**: ingress, processor, storage, observation
- **9 infrastructure components**: MessageBus, EventStore, Metrics, Tracer, HealthChecker, ApiGateway, CommandDispatcher, QueryDispatcher, ServiceRegistry
- **Saga**: Session lifecycle with 6 steps
- **CQRS**: Command and query dispatchers
- **API Gateway**: With rate limiting and circuit breaker
- **Full configuration**: Kafka, batch sizes, timeouts

## References

- Vaughn Vernon, *Reactive Messaging Patterns for Actor Model*
- Joe Armstrong, *Programming Erlang*
- Enterprise Integration Patterns, https://www.enterpriseintegrationpatterns.com/

## Project Generation

### Generate Complete Project from Turtle

```bash
# Generate a complete, validated project
bin/render-project examples/telemetry-app.ttl ./generated/telemetry --validate

# Generate without validation (faster)
bin/render-project examples/f1-telemetry-application.ttl ./generated/f1-telemetry
```

### Generated Artifacts

| File | Description |
|------|-------------|
| `pom.xml` | Maven POM with Java 26, preview features, all dependencies |
| `src/main/java/module-info.java` | JPMS module definition |
| `src/main/java/org/generated/Application.java` | Main application class |
| `src/test/java/org/generated/ApplicationIT.java` | Integration tests |
| `Dockerfile` | Container image definition |
| `docker-compose.yml` | Local development compose |
| `k8s/deployment.yaml` | Kubernetes deployment manifest |
| `k8s/service.yaml` | Kubernetes service manifest |
| `.github/workflows/ci.yml` | GitHub Actions CI/CD pipeline |
| `README.md` | Project documentation |

### Generated Project Structure

```
generated-app/
├── pom.xml                          # Maven build configuration
├── Dockerfile                       # Container image
├── docker-compose.yml               # Local development
├── k8s/
│   ├── deployment.yaml              # Kubernetes deployment
│   └── service.yaml                 # Kubernetes service
├── .github/
│   └── workflows/
│       └── ci.yml                   # CI/CD pipeline
├── src/
│   ├── main/
│   │   └── java/
│   │       ├── module-info.java     # JPMS module
│   │       └── org/generated/
│   │           └── Application.java # Main application
│   └── test/
│       └── java/
│           └── org/generated/
│               └── ApplicationIT.java # Integration tests
└── README.md                        # Documentation
```

### Validation Pipeline

The `--validate` flag runs:
1. `mvn compile` - Compiles generated code
2. `mvn test` - Runs unit tests
3. `mvn verify` - Runs integration tests

### Joe Armstrong AGI Vision

This implementation fulfills Joe Armstrong's vision:

> "Write the specification, press a button, get a complete, production-ready application."

From a Turtle specification, you get:
- **Compilable Java 26 code** with preview features
- **Tested** with unit and integration tests
- **Containerized** with Docker
- **Deployable** to Kubernetes
- **Observable** with metrics, tracing, health checks
- **Documented** with README and inline documentation
