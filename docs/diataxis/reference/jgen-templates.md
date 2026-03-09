# jgen Template Catalog — API Reference

**Tool:** `bin/jgen` (wrapper for [seanchatmangpt/ggen](https://github.com/seanchatmangpt/ggen))
**Template count:** 72 templates across 9 categories
**Pattern count:** 108 patterns

---

## CLI Reference

```bash
bin/jgen generate -t <category/name> -n <ClassName> -p <package>
bin/jgen list
bin/jgen list --category <category>
bin/jgen migrate --source <dir>
bin/jgen refactor --source <dir>
bin/jgen refactor --source <dir> --plan
bin/jgen refactor --source <dir> --score
bin/jgen verify
```

| Command | Arguments | Description |
|---|---|---|
| `generate` | `-t template`, `-n ClassName`, `-p package` | Renders the named template into `<package>/<ClassName>.java`. |
| `list` | `[--category cat]` | Lists all templates, optionally filtered to one category. |
| `migrate` | `--source <dir>` | Scans `dir` with grep-based detectors and prints legacy patterns found. |
| `refactor` | `--source <dir>` | Full analysis: modernization score + ranked `jgen generate` commands. |
| `refactor --plan` | `--source <dir>` | Writes an executable `migrate.sh` to the current directory. |
| `refactor --score` | `--source <dir>` | Prints score-only report without generating commands. |
| `verify` | — | Compile + format + test check on all dogfood output. |

---

## core/ — 14 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `core/record` | Immutable `record` with compact constructor validation and a nested `Builder` | Records (JEP 395) |
| `core/sealed-hierarchy` | `sealed interface` with 3–5 `permits` records | Sealed classes (JEP 409) |
| `core/pattern-match-switch` | Class with `switch` expression using type patterns and guards | Pattern matching for `switch` (JEP 441) |
| `core/stream-gatherer` | Class demonstrating custom `Gatherer<T,A,R>` for the Stream API | Stream Gatherers (JEP 461) |
| `core/lambda-functional` | Functional interface + higher-order utility methods | Lambda expressions, method references |
| `core/var-inference` | Method bodies using `var` for local variable type inference | Local-variable type inference (JEP 286) |
| `core/text-block` | Class with multi-line string literals (JSON, SQL, HTML) | Text blocks (JEP 378) |
| `core/record-builder` | `record` plus a hand-rolled or generated `Builder` inner class | Records + builder pattern |
| `core/sealed-result` | `sealed interface Result<T,E>` with `Success` and `Failure` records | Sealed classes + records |
| `core/pattern-guard` | `switch` expression with `when` guards on pattern cases | Guarded patterns (JEP 441) |
| `core/string-template` | Class using string template processors (`STR.`, `FMT.`) | String Templates (JEP 430, preview) |
| `core/foreign-function` | `MemorySegment` and `Linker` FFM API usage | Foreign Function & Memory API (JEP 454) |
| `core/unnamed-class` | Top-level class without explicit class declaration | Unnamed classes (JEP 445, preview) |
| `core/instance-main` | Class with `void main()` instance entry point | Instance main methods (JEP 445, preview) |

---

## concurrency/ — 5 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `concurrency/virtual-thread` | Utility class spawning and managing virtual threads via `Thread.ofVirtual()` | Virtual threads (JEP 444) |
| `concurrency/structured-concurrency` | Service class using `StructuredTaskScope` with shutdown-on-failure and shutdown-on-success | Structured Concurrency (JEP 480) |
| `concurrency/scoped-value` | Component using `ScopedValue` to propagate context without `ThreadLocal` | Scoped Values (JEP 487) |
| `concurrency/thread-local-migration` | Refactored class replacing `ThreadLocal` with `ScopedValue` | Scoped Values migration |
| `concurrency/executor-migration` | Service replacing `ExecutorService` with virtual-thread executors | `Executors.newVirtualThreadPerTaskExecutor()` |

---

## patterns/ — 17 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `patterns/builder-record` | Record with nested static `Builder` inner class | Records |
| `patterns/factory-sealed` | `sealed interface` product hierarchy + static factory method | Sealed classes + pattern matching |
| `patterns/strategy-functional` | `@FunctionalInterface` strategy + static factory methods for built-in strategies | Functional interfaces |
| `patterns/state-machine-sealed` | `sealed interface` state hierarchy driven by a `StateMachine<S,E,D>` | Sealed classes + records |
| `patterns/observer-flow` | Publisher/subscriber using `Flow.Publisher` and `Flow.Subscriber` | `java.util.concurrent.Flow` |
| `patterns/command-record` | `sealed interface Command` with one record per command variant | Sealed classes + records |
| `patterns/decorator-functional` | `Function<T,T>` composition decorator chain | Function composition |
| `patterns/template-method-functional` | Default method `template()` + functional override points | Default interface methods |
| `patterns/chain-of-responsibility` | `Function<T,Optional<R>>` chain using `Stream.reduce` | Stream + `Optional` |
| `patterns/iterator-stream` | Custom `Spliterator<T>` wrapped as a `Stream<T>` | `Spliterator`, `StreamSupport` |
| `patterns/visitor-sealed` | Sealed type hierarchy + `visit(Visitor<R>)` method | Sealed classes + records |
| `patterns/composite-record` | `sealed interface Component` with `Leaf` and `Composite` records | Sealed classes + records |
| `patterns/proxy-dynamic` | `java.lang.reflect.Proxy` with typed wrapper | Dynamic proxies |
| `patterns/adapter-functional` | Adapter via `Function<A,B>` composition | Functional interfaces |
| `patterns/facade-service` | Service class grouping subsystem APIs behind a clean facade | Standard OOP |
| `patterns/repository-generic` | Generic `Repository<T,ID>` interface + in-memory `ConcurrentHashMap` implementation | Generics + records |
| `patterns/service-layer` | Service class with `Result<T,E>` return types and dependency injection via constructor | Sealed `Result` type |

---

## api/ — 6 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `api/http-client` | Utility class wrapping `HttpClient` with synchronous and async request helpers | `java.net.http.HttpClient` (JEP 321) |
| `api/java-time` | Utility class demonstrating `LocalDate`, `ZonedDateTime`, `Duration`, `Period`, `DateTimeFormatter` | `java.time` (JSR-310) |
| `api/nio2-path` | File-operation utility using `Path`, `Files`, `WatchService` | NIO.2 (`java.nio.file`) |
| `api/process-builder` | Class wrapping `ProcessBuilder` for subprocess management | `ProcessBuilder`, `Process` |
| `api/collections-modern` | Utilities using `List.of`, `Map.of`, `Set.of`, `Map.copyOf`, `Stream.toList()` | Immutable collections factory methods |
| `api/string-methods` | Class demonstrating `String::strip`, `String::indent`, `String::formatted`, `String::isBlank`, `String::repeat` | Modern `String` API (Java 11–15+) |

---

## modules/ — 4 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `modules/module-info` | `module-info.java` with `requires`, `exports`, `opens`, `uses`, `provides` directives | JPMS (JEP 261) |
| `modules/spi-provider` | SPI `provides` declaration + `ServiceLoader`-based consumer | `ServiceLoader` |
| `modules/qualified-exports` | `exports ... to` for targeted module visibility | Qualified exports |
| `modules/multi-module-pom` | Maven multi-module `pom.xml` layout with JPMS modules | Maven + JPMS |

---

## testing/ — 12 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `testing/junit5-test` | `@ExtendWith`, `@Test`, `@BeforeEach`, lifecycle annotations | JUnit 5 |
| `testing/assertj-assertions` | Test class `implements WithAssertions` with fluent AssertJ chains | AssertJ |
| `testing/jqwik-property` | `@Property` + `@ForAll` property-based tests with arbitraries | jqwik |
| `testing/instancio-data` | Test using `Instancio.of(Model.class)` for random object generation | Instancio |
| `testing/archunit-rules` | `@AnalyzeClasses` + `@ArchTest` field rules for package structure | ArchUnit |
| `testing/awaitility-async` | Tests using `Awaitility.await().until(...)` for asynchronous assertions | Awaitility |
| `testing/mockito-mock` | `@Mock`, `@InjectMocks`, `when(...).thenReturn(...)`, `verify(...)` | Mockito |
| `testing/bdd-scenario` | JUnit 5 `@Nested` classes modeling Given/When/Then BDD structure | JUnit 5 nested tests |
| `testing/testcontainers-it` | `@Testcontainers` + `@Container` integration test with Docker | Testcontainers |
| `testing/parameterized-test` | `@ParameterizedTest` with `@CsvSource`, `@MethodSource`, `@EnumSource` | JUnit 5 parameterized |
| `testing/benchmark-jmh` | `@Benchmark`, `@BenchmarkMode`, `@State` JMH micro-benchmark | JMH |
| `testing/mutation-test` | PIT mutation testing configuration via Maven plugin | Pitest |

---

## error-handling/ — 3 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `error-handling/result-railway` | Service class returning `Result<T,E>` with `map`/`flatMap`/`fold` chaining | Sealed classes + records |
| `error-handling/functional-error` | Checked-exception-free API using `Result` and `Optional` | Sealed classes |
| `error-handling/optional-result` | Bidirectional conversion utilities between `Optional<T>` and `Result<T,E>` | Sealed classes + records |

---

## build/ — 7 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `build/pom-template` | Complete `pom.xml` for a Java 26 JPMS module with Spotless, Surefire, Failsafe | Maven 4 |
| `build/maven-wrapper` | `.mvn/wrapper/maven-wrapper.properties` and `mvnw` launcher scripts | Maven Wrapper |
| `build/spotless-config` | Spotless plugin configuration for Google Java Format (AOSP style) | Spotless Maven Plugin |
| `build/surefire-config` | Surefire plugin configuration with `--enable-preview`, JUnit 5 provider | maven-surefire-plugin |
| `build/failsafe-config` | Failsafe plugin configuration for `*IT.java` integration tests | maven-failsafe-plugin |
| `build/build-cache` | `.mvn/daemon.properties` and build-cache Maven extension configuration | Maven Daemon / build cache |
| `build/cicd-workflow` | GitHub Actions workflow: compile, test, verify, upload artifact | GitHub Actions |

---

## security/ — 4 Templates

| Template | Generated Class Shape | Key Java 26 Feature |
|---|---|---|
| `security/modern-crypto` | Utility class using `KeyPairGenerator`, `Cipher`, `MessageDigest` with modern algorithms | `java.security` |
| `security/encapsulation` | Module with `opens` restricted to specific frameworks; no reflective leaks | JPMS qualified opens |
| `security/validation-preconditions` | Precondition utility accumulating errors into `Result<T, List<String>>` | Sealed `Result` type |
| `security/jakarta-migration` | Adapter replacing `javax.*` imports with `jakarta.*` equivalents | Jakarta EE 9+ migration |

---

## Schema and Template Files

| Path | Content |
|---|---|
| `schema/*.ttl` | RDF/Turtle ontologies defining Java type system, patterns, concurrency, modules, migration rules |
| `queries/*.rq` | SPARQL queries extracting data from ontologies |
| `templates/java/**/*.tera` | Tera templates rendering Java 26 source code |
| `ggen.toml` | ggen project configuration (template root, output root, variable defaults) |
| `bin/jgen` | Shell wrapper that invokes `ggen` with Java-specific defaults |
| `bin/dogfood` | Pipeline: generate → compile → test → report |
| `bin/mvndw` | Maven Daemon wrapper (`mvnd`) |

---

## Innovation Engine Classes

| Class | Package | Role |
|---|---|---|
| `OntologyMigrationEngine` | `org.acme.dogfood.innovation` | Analyzes Java source against 12 ontology-driven migration rules; returns sealed `MigrationPlan` hierarchy |
| `ModernizationScorer` | `org.acme.dogfood.innovation` | Scores source files 0–100 across 40+ modern/legacy signal detectors; ranks by ROI |
| `TemplateCompositionEngine` | `org.acme.dogfood.innovation` | Composes multiple Tera templates into coherent features (CRUD, value objects, service layers) |
| `BuildDiagnosticEngine` | `org.acme.dogfood.innovation` | Maps compiler error output to concrete `DiagnosticFix` suggestions (10 fix subtypes) |
| `LivingDocGenerator` | `org.acme.dogfood.innovation` | Parses Java source into structured `DocElement` hierarchy; renders Markdown |
| `RefactorEngine` | `org.acme.dogfood.innovation` | Orchestrator: chains all engines into `RefactorPlan` with `toScript()` and `summary()` |

### RefactorEngine API

```java
public final class RefactorEngine {

    public static RefactorPlan analyze(Path sourceRoot);
}

public record RefactorPlan(
    List<FileScore> fileScores,
    List<JgenCommand> commands,
    MigrationPlan migrationPlan
) {
    public String summary();
    public String toScript();
}
```
