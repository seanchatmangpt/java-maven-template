# Java Maven Template Documentation

Welcome to the Java Maven Template documentation. This template provides a production-ready **Java 26 JPMS library** with Erlang/OTP-style concurrency primitives, comprehensive code generation (ggen/jgen), enterprise messaging patterns, and multi-cloud deployment capabilities.

## Quick Start

```bash
# Clone and build
git clone https://github.com/seanchatmangpt/java-maven-template.git
cd java-maven-template

# Run tests
./mvnw test

# Full verification (tests + quality checks)
./mvnw verify

# Run dogfood validation
./mvnw verify -Ddogfood
```

---

## Core Features

### Joe Armstrong / Erlang/OTP Patterns in Pure Java

This project implements **15 OTP primitives** in `org.acme`, proving that Java 26 can express all meaningful Erlang/OTP patterns without the BEAM VM:

| Primitive | Java Class | OTP Equivalent |
|-----------|------------|----------------|
| Lightweight Processes | `Proc<S,M>` | `spawn/3` |
| Process References | `ProcRef<S,M>` | Pid |
| Supervision Trees | `Supervisor` | `supervisor` |
| Crash Recovery | `CrashRecovery` | "let it crash" |
| State Machine | `StateMachine<S,E,D>` | `gen_statem` |
| Process Links | `ProcessLink` | `link/1` |
| Parallel Execution | `Parallel` | `pmap` |
| Process Monitors | `ProcessMonitor` | `monitor/2` |
| Process Registry | `ProcessRegistry` | `register/2` |
| Timers | `ProcTimer` | `timer:send_after/3` |
| Exit Signals | `ExitSignal` | exit signals |
| Process Introspection | `ProcSys` | `sys:get_state/1` |
| Startup Handshake | `ProcLib` | `proc_lib:start_link/3` |
| Event Manager | `EventManager<E>` | `gen_event` |
| Railway Error Handling | `Result<T,E>` | `{:ok, val} \| {:error, reason}` |

### Enterprise Messaging Patterns

The `org.acme.dogfood.messaging` package implements 5 Enterprise Integration Patterns:

| Pattern | Class | Description |
|---------|-------|-------------|
| Message Bus | `MessageBusPatterns` | Pub/sub with subscribe/publish/unsubscribe |
| Content-Based Router | `RouterPatterns` | Route messages by predicates |
| Publish-Subscribe | `PubSubPatterns` | Topic-based subscription |
| Scatter-Gather | `ScatterGatherPatterns` | Fan-out + aggregate responses |
| Correlation Identifier | `CorrelationPatterns` | Request-reply with correlation IDs |

---

## Code Generation (ggen / jgen)

This project wraps [seanchatmangpt/ggen](https://github.com/seanchatmangpt/ggen) as a code generation engine for Java 26.

### Installation

```bash
cargo install ggen-cli --features paas,ai
```

### jgen CLI

```bash
# Generate from templates
bin/jgen generate -t core/record -n Person -p com.example.model

# List all 96 templates
bin/jgen list
bin/jgen list --category patterns

# Analyze legacy codebase
bin/jgen refactor --source ./legacy
bin/jgen refactor --source ./legacy --plan   # Saves migrate.sh
bin/jgen refactor --source ./legacy --score  # Modernization report

# Verify generated code
bin/jgen verify
```

### Template Categories (96 templates, 11 categories)

| Category | Templates | Examples |
|----------|-----------|----------|
| `core/` | 14 | records, sealed types, pattern matching, gatherers |
| `concurrency/` | 5 | virtual threads, scoped values, structured concurrency |
| `patterns/` | 17 | builder, factory, strategy, state machine, visitor |
| `api/` | 6 | HttpClient, java.time, NIO.2, collections |
| `modules/` | 4 | JPMS module-info, SPI, qualified exports |
| `testing/` | 12 | JUnit 5, AssertJ, jqwik, Instancio, ArchUnit |
| `error-handling/` | 3 | Result<T,E> railway, Optional↔Result |
| `build/` | 7 | POM, Maven wrapper, Spotless, Surefire/Failsafe |
| `security/` | 4 | modern crypto, encapsulation, validation |
| `messaging/` | 17 | EIP patterns (router, pub-sub, scatter-gather) |
| `enterprise/` | 7 | project structure, Docker, CI/CD |

### Innovation Engines

Six coordinated analysis engines power the automated refactor pipeline:

| Engine | Purpose |
|--------|---------|
| `OntologyMigrationEngine` | Analyzes Java source against 12 ontology-driven migration rules |
| `ModernizationScorer` | Scores source files 0-100 across 40+ modern/legacy signal detectors |
| `TemplateCompositionEngine` | Composes multiple Tera templates into coherent features |
| `BuildDiagnosticEngine` | Maps compiler errors to concrete fix suggestions |
| `LivingDocGenerator` | Parses Java source into structured documentation |
| `RefactorEngine` | **Orchestrator**: chains all engines into a single `RefactorPlan` |

---

## Dogfood Validation

The `bin/dogfood` script validates that all ggen templates produce compilable, testable Java code.

### Commands

```bash
bin/dogfood generate   # Check all dogfood files exist
bin/dogfood report     # Show template coverage report
bin/dogfood verify     # Full pipeline: check + compile + test + report
```

### Dogfood Coverage

| Category | Source Files | Test Files |
|----------|-------------|------------|
| Core | `Person`, `GathererPatterns`, `PatternMatchingPatterns` | Tests for all |
| API | `StringMethodPatterns`, `JavaTimePatterns` | Tests for all |
| Concurrency | `VirtualThreadPatterns`, `ScopedValuePatterns`, `StructuredTaskScopePatterns` | Tests for all |
| Error Handling | `ResultRailway` | Test |
| Patterns | `TextTransformStrategy` | Test |
| Security | `InputValidation` | Test |
| Messaging | `MessageBusPatterns`, `RouterPatterns`, `PubSubPatterns`, `ScatterGatherPatterns`, `CorrelationPatterns` | `MessageBusPatternsTest` |
| Innovation | 6 engine classes | 6 test classes |

---

## Documentation Sections

### Cloud Deployment

Multi-cloud deployment documentation following the [Diátaxis framework](https://docs.diataxis.fr/):

- **[Tutorials](cloud/tutorials/index.md)** - Learning-oriented guides for getting started with each cloud provider
- **[How-to Guides](cloud/how-to/index.md)** - Problem-oriented solutions for specific deployment tasks
- **[Reference](cloud/reference/index.md)** - Information-oriented technical specifications
- **[Explanation](cloud/explanation/index.md)** - Understanding-oriented conceptual guides

### Quick Links

| Cloud Provider | Tutorial | Deploy Guide |
|----------------|----------|--------------|
| AWS | [Getting Started](cloud/tutorials/aws-getting-started.md) | [Deploy](cloud/how-to/deploy-to-aws.md) |
| Azure | [Getting Started](cloud/tutorials/azure-getting-started.md) | [Deploy](cloud/how-to/deploy-to-azure.md) |
| GCP | [Getting Started](cloud/tutorials/gcp-getting-started.md) | [Deploy](cloud/how-to/deploy-to-gcp.md) |
| OCI | [Getting Started](cloud/tutorials/oci-getting-started.md) | [Deploy](cloud/how-to/deploy-to-oci.md) |
| IBM Cloud | [Getting Started](cloud/tutorials/ibm-cloud-getting-started.md) | [Deploy](cloud/how-to/deploy-to-ibm-cloud.md) |
| OpenShift | [Getting Started](cloud/tutorials/openshift-getting-started.md) | [Deploy](cloud/how-to/deploy-to-openshift.md) |

---

## Project Structure

```
java-maven-template/
├── src/main/java/org/acme/
│   ├── Proc.java                  # OTP lightweight processes
│   ├── Supervisor.java            # OTP supervision trees
│   ├── EventManager.java          # OTP gen_event
│   ├── Result.java                # Railway error handling
│   ├── dogfood/                   # Template-generated examples
│   │   ├── core/                  # Core patterns
│   │   ├── api/                   # API patterns
│   │   ├── concurrency/           # Concurrency patterns
│   │   ├── messaging/             # Enterprise messaging patterns
│   │   ├── innovation/            # Refactor engines
│   │   └── ...
│   └── ...
├── src/test/java/org/acme/        # Unit tests (*Test.java)
├── templates/java/                # 96 Tera templates
│   ├── core/                      # Records, sealed types, gatherers
│   ├── concurrency/               # Virtual threads, structured concurrency
│   ├── patterns/                  # GoF patterns reimagined
│   ├── messaging/                 # Enterprise Integration Patterns
│   └── ...
├── schema/*.ttl                   # OWL ontologies for Java patterns
├── queries/*.rq                   # SPARQL queries for migration
├── bin/
│   ├── jgen                       # Code generation CLI
│   ├── dogfood                    # Template validation script
│   └── mvndw                      # Maven Daemon wrapper
├── docs/                          # Documentation
│   ├── cloud/                     # Multi-cloud deployment docs
│   ├── phd-thesis-otp-java26.md   # Formal OTP↔Java equivalence
│   └── ...
└── pom.xml                        # Maven configuration
```

---

## Build Commands

```bash
./mvnw test              # Run unit tests
./mvnw verify            # Run all tests + quality checks
./mvnw spotless:apply    # Format code
./mvnw package -Dshade   # Build fat JAR
./mvnw verify -Ddogfood  # Run dogfood validation

# Maven Daemon (faster)
bin/mvndw verify
bin/mvndw test -Dtest=MathsTest
```

### Build Commands (dx.sh)

```bash
./dx.sh compile          # Compile changed modules
./dx.sh test             # Run tests
./dx.sh all              # Full build + validation (guards)
./dx.sh validate         # Run guard validation only
./dx.sh deploy           # Deploy to cloud (OCI default)
```

### Guard Validation

The guard system detects forbidden patterns:

| Pattern | Description | Fix |
|---------|-------------|-----|
| H_TODO | Deferred work markers | Implement or remove |
| H_MOCK | Mock/stub/fake implementations | Delete or implement |
| H_STUB | Empty/placeholder returns | Throw UnsupportedOperationException |

---

## Containerization

| Containerfile | Purpose | Base Image |
|--------------|---------|------------|
| `Containerfile` | Production multi-stage build | `maven:4.0.0-rc-5-eclipse-temurin-25` |
| `Containerfile.full` | CI/CD with mvnd support | Maven 4 + Java 25 + mvnd |
| `Containerfile.dev` | Development environment | Maven 4 + Java 25 + mvnd |
| `Containerfile.minimal` | Pre-built JAR deployment | `eclipse-temurin:25-jre-alpine` |

```bash
# Production build
docker build -f Containerfile -t java-maven-template:latest .

# CI build with mvnd
docker build -f Containerfile.full -t java-maven-template:ci .
```

---

## Supported Cloud Providers

| Provider | Packer Support | Terraform Provider | Local Simulation |
|----------|---------------|-------------------|------------------|
| AWS | amazon-ebs | hashicorp/aws | LocalStack |
| Azure | azure-arm | hashicorp/azurerm | Azurite |
| GCP | googlecompute | hashicorp/google | - |
| OCI | oracle-oci | oracle/oci | - |
| IBM Cloud | ibmcloud | IBM-Cloud/ibm | - |
| OpenShift | - | openshift/openshift | crc |

---

## Research & Publications

- **[OTP 28 in Pure Java 26](phd-thesis-otp-java26.md)** - Formal equivalence proof between OTP primitives and Java 26
- **[Phase Change Thesis](jotp-phase-change-thesis.md)** - JOTP as industry phase change catalyst
- **[Atlas Message Patterns](phd-thesis-atlas-message-patterns.md)** - Enterprise messaging patterns
- **[Turtle Application Composition](TURTLE_APPLICATION_COMPOSITION.md)** - RDF-based composition

---

## Architecture

**Java 26 JPMS library** (`org.acme` module) with preview features enabled.

- **JDK**: GraalVM Community CE 25.0.2 (Java 26 EA builds)
- **Test Framework**: JUnit 5 with full parallel execution
- **Test Libraries**: AssertJ, jqwik (property-based), Instancio, ArchUnit, Awaitility
- **Formatting**: Spotless with Google Java Format (AOSP style)
- **Error Handling**: `Result<T,E>` sealed interface with railway-oriented programming
