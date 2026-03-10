# Code Generation with ggen / jgen

This document describes the code generation system powered by [ggen](https://github.com/seanchatmangpt/ggen), which provides 96 templates across 11 categories for modern Java 26 development.

---

## Installation

```bash
cargo install ggen-cli --features paas,ai
```

---

## CLI Usage

### jgen CLI Wrapper

```bash
# Generate from a template
bin/jgen generate -t core/record -n Person -p com.example.model

# List all templates
bin/jgen list
bin/jgen list --category patterns

# Analyze legacy codebase
bin/jgen migrate --source ./legacy           # Detect legacy patterns
bin/jgen refactor --source ./legacy          # Full analysis + score
bin/jgen refactor --source ./legacy --plan   # Generate migrate.sh
bin/jgen refactor --source ./legacy --score  # Score-only report

# Verify generated code
bin/jgen verify                              # Compile + format + test
```

### Direct ggen CLI

```bash
ggen generate -t templates/java/core/record.tera \
  --param name=Person \
  --param package=com.example.model
```

---

## Template Categories

### Core (14 templates)

Modern Java language features:

| Template | Description |
|----------|-------------|
| `core/record` | Record with validation + builder |
| `core/sealed-interface` | Sealed interface hierarchy |
| `core/sealed-class` | Sealed class hierarchy |
| `core/pattern-matching-switch` | Exhaustive switch over sealed types |
| `core/instanceof-pattern` | `instanceof` pattern matching |
| `core/gatherer` | Custom stream gatherers |
| `core/stream-pipeline` | Modern stream patterns |
| `core/optional-chain` | Optional chaining patterns |
| `core/text-block` | Text block patterns |
| `core/var-inference` | `var` type inference patterns |
| `core/lambda-conversion` | Lambda conversion patterns |
| `core/method-reference` | Method reference patterns |
| `core/flexible-constructor` | Flexible constructor bodies |
| `core/unnamed-pattern` | Unnamed pattern matching |

### Concurrency (5 templates)

Virtual threads and structured concurrency:

| Template | Description |
|----------|-------------|
| `concurrency/virtual-thread` | Virtual thread patterns |
| `concurrency/virtual-thread-executor` | Virtual thread executor |
| `concurrency/scoped-value` | ScopedValue for context |
| `concurrency/structured-task-scope` | Structured concurrency |
| `concurrency/concurrent-gatherer` | Concurrent stream gatherer |

### Patterns (17 templates)

GoF patterns reimagined for modern Java:

| Template | Description |
|----------|-------------|
| `patterns/builder-record` | Builder pattern with records |
| `patterns/factory-sealed` | Factory with sealed types |
| `patterns/strategy-functional` | Functional strategy pattern |
| `patterns/state-machine-sealed` | State machine with sealed types |
| `patterns/visitor-exhaustive-switch` | Visitor with exhaustive switch |
| `patterns/chain-of-responsibility-sealed` | Chain with sealed commands |
| `patterns/observer-flow` | Observer with Flow API |
| `patterns/adapter-record` | Adapter with records |
| `patterns/decorator-default-method` | Decorator with default methods |
| `patterns/command-record` | Command with records |
| `patterns/singleton-enum` | Singleton with enum |
| `patterns/dto-record` | DTO with records |
| `patterns/value-object-record` | Value object with records |
| `patterns/domain-event-record` | Domain event with records |
| `patterns/repository-generic` | Generic repository |
| `patterns/service-layer` | Service layer pattern |
| `patterns/specification-functional` | Specification pattern |

### API (6 templates)

Modern Java API patterns:

| Template | Description |
|----------|-------------|
| `api/string-methods` | String methods (strip, repeat, etc.) |
| `api/java-time` | java.time API patterns |
| `api/http-client` | HttpClient patterns |
| `api/nio2-files` | NIO.2 file operations |
| `api/process-builder` | ProcessBuilder patterns |
| `api/collection-factories` | Collection factory methods |

### Modules (4 templates)

JPMS module patterns:

| Template | Description |
|----------|-------------|
| `modules/module-info` | Module declaration |
| `modules/module-exports` | Qualified exports |
| `modules/module-service-provider` | Service provider modules |
| `modules/multi-module-project` | Multi-module structure |

### Testing (12 templates)

Comprehensive testing patterns:

| Template | Description |
|----------|-------------|
| `testing/junit5-test` | JUnit 5 test class |
| `testing/junit5-nested` | Nested test classes |
| `testing/junit5-parameterized` | Parameterized tests |
| `testing/assertj-assertions` | AssertJ fluent assertions |
| `testing/property-based-jqwik` | jqwik property-based tests |
| `testing/instancio-data` | Instancio test data |
| `testing/mockito-test` | Mockito integration |
| `testing/bdd-test` | BDD-style tests |
| `testing/integration-test` | Integration test patterns |
| `testing/testcontainers-test` | Testcontainers tests |
| `testing/awaitility-async` | Async assertions |
| `testing/archunit-rules` | Architecture rules |
| `testing/stress-*` | Stress test templates |

### Error Handling (3 templates)

Railway-oriented error handling:

| Template | Description |
|----------|-------------|
| `error-handling/result-railway` | Result<T,E> sealed type |
| `error-handling/functional-error` | Functional error handling |
| `error-handling/optional-result` | Optional↔Result conversion |

### Security (4 templates)

Security best practices:

| Template | Description |
|----------|-------------|
| `security/input-validation` | Input validation patterns |
| `security/modern-crypto` | Modern cryptography |
| `security/encapsulation` | Encapsulation patterns |
| `security/jakarta-migration` | Jakarta EE migration |

### Messaging (17 templates)

Enterprise Integration Patterns:

| Template | Description |
|----------|-------------|
| `messaging/message-bus` | Message Bus pattern |
| `messaging/pub-sub` | Publish-Subscribe |
| `messaging/content-based-router` | Content-Based Router |
| `messaging/scatter-gather` | Scatter-Gather |
| `messaging/correlation-identifier` | Correlation Identifier |
| `messaging/datatype-channel` | Datatype Channel |
| `messaging/dead-letter-channel` | Dead Letter Channel |
| `messaging/wire-tap` | Wire Tap |
| `messaging/resequencer` | Resequencer |
| `messaging/routing-slip` | Routing Slip |
| `messaging/process-manager` | Process Manager |
| `messaging/control-bus` | Control Bus |
| `messaging/domain-types` | Domain types |
| `messaging/service-activator` | Service Activator |
| `messaging/idempotent-receiver` | Idempotent Receiver |
| `messaging/durable-subscriber` | Durable Subscriber |
| `messaging/canonical-message` | Canonical Message |

### Build (7 templates)

Build configuration:

| Template | Description |
|----------|-------------|
| `build/pom-java26` | POM for Java 26 |
| `build/maven-wrapper` | Maven wrapper setup |
| `build/spotless-config` | Spotless configuration |
| `build/surefire-failsafe` | Test configuration |
| `build/build-cache` | Build cache setup |
| `build/github-actions` | GitHub Actions CI |
| `build/multi-module-reactor` | Multi-module reactor |

### Enterprise (7 templates)

Enterprise project structure:

| Template | Description |
|----------|-------------|
| `enterprise/project-pom` | Enterprise POM |
| `enterprise/module-info` | Enterprise module |
| `enterprise/application-main` | Application entry point |
| `enterprise/dockerfile` | Production Dockerfile |

---

## Innovation Engines

Six coordinated analysis engines power the automated refactor pipeline:

### 1. OntologyMigrationEngine

Analyzes Java source against 12 ontology-driven migration rules and returns a sealed `MigrationPlan` hierarchy.

```java
var plan = OntologyMigrationEngine.analyze(Path.of("./legacy/src"));
// Returns: MigrationPlan with suggested transformations
```

### 2. ModernizationScorer

Scores source files 0-100 across 40+ modern/legacy signal detectors and ranks by ROI.

```java
var scores = ModernizationScorer.score(Path.of("./legacy/src"));
// Returns: Map<Path, ModernizationScore>
```

### 3. TemplateCompositionEngine

Composes multiple Tera templates into coherent features (CRUD, value objects, service layers).

```java
var composition = TemplateCompositionEngine.compose(
    List.of("core/record", "patterns/builder-record", "testing/junit5-test"),
    params
);
```

### 4. BuildDiagnosticEngine

Maps compiler error output to concrete `DiagnosticFix` suggestions (10 fix subtypes).

```java
var diagnostics = BuildDiagnosticEngine.analyze(compilerOutput);
// Returns: List<Diagnostic> with fix suggestions
```

### 5. LivingDocGenerator

Parses Java source into structured `DocElement` hierarchy and renders Markdown documentation.

```java
var docs = LivingDocGenerator.generate(Path.of("./src"));
// Returns: Structured documentation
```

### 6. RefactorEngine (Orchestrator)

Chains all engines into a single `RefactorPlan` with per-file scores, `JgenCommand` lists, `toScript()`, and `summary()`.

```java
var plan = RefactorEngine.analyze(Path.of("./legacy/src"));
System.out.println(plan.summary());
Files.writeString(Path.of("migrate.sh"), plan.toScript());
```

---

## Dogfood Validation

The `bin/dogfood` script validates that templates produce compilable, testable code:

```bash
bin/dogfood generate   # Check all files exist
bin/dogfood report     # Coverage report
bin/dogfood verify     # Full pipeline: check + compile + test
```

### Dogfood Coverage

Every template category has at least one "dogfood" example in `src/main/java/org/acme/dogfood/` with corresponding tests:

| Category | Dogfood Example | Test |
|----------|----------------|------|
| Core | `Person`, `GathererPatterns`, `PatternMatchingPatterns` | Tests for all |
| API | `StringMethodPatterns`, `JavaTimePatterns` | Tests for all |
| Concurrency | `VirtualThreadPatterns`, `ScopedValuePatterns`, `StructuredTaskScopePatterns` | Tests for all |
| Error Handling | `ResultRailway` | Test |
| Patterns | `TextTransformStrategy` | Test |
| Security | `InputValidation` | Test |
| Messaging | 5 EIP pattern classes | `MessageBusPatternsTest` |
| Innovation | 6 engine classes | 6 test classes |

---

## Ontology & Queries

The template system is backed by OWL ontologies and SPARQL queries:

### Ontologies (`schema/*.ttl`)

- `java-project.ttl` - Java project structure
- `java-enterprise.ttl` - Enterprise patterns
- `java-messaging.ttl` - Messaging patterns

### Queries (`queries/*.rq`)

- `project-extract.rq` - Extract project structure
- `pattern-dependencies.rq` - Analyze pattern dependencies

---

## Programmatic API

Use the engines directly in Java:

```java
import org.acme.dogfood.innovation.*;

// Analyze a codebase
var plan = RefactorEngine.analyze(Path.of("./legacy"));

// Get modernization scores
var scores = ModernizationScorer.score(Path.of("./legacy"));

// Generate migration script
Files.writeString(Path.of("migrate.sh"), plan.toScript());

// Print summary
System.out.println(plan.summary());
```

---

## Example: Full Refactor Workflow

```bash
# 1. Analyze legacy codebase
bin/jgen refactor --source ./legacy --plan

# 2. Review the plan
cat migrate.sh

# 3. Apply migrations
bash migrate.sh

# 4. Verify results
bin/jgen verify

# 5. Run tests
./mvnw verify
```
