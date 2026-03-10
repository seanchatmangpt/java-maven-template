# Dogfood Validation

This document describes the dogfood validation system that proves ggen/jgen templates produce compilable, testable Java code.

---

## Overview

"Dogfooding" means using your own products. The dogfood system validates that every ggen template produces valid Java code by:

1. **Generating** sample code from each template
2. **Compiling** the generated code
3. **Testing** the code with comprehensive test suites
4. **Reporting** coverage metrics

---

## Commands

```bash
# Check all dogfood files exist
bin/dogfood generate

# Show template coverage report
bin/dogfood report

# Full verification: check + compile + test + report
bin/dogfood verify

# Via Maven
./mvnw verify -Ddogfood
```

---

## Coverage by Category

### Core Templates (7 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `core/record` | `Person.java` | `PersonTest.java`, `PersonProperties.java` |
| `core/gatherer` | `GathererPatterns.java` | `GathererPatternsTest.java` |
| `core/pattern-matching-switch` | `PatternMatchingPatterns.java` | `PatternMatchingPatternsTest.java` |

**Patterns validated:**
- Records with validation and builders
- Gatherer operations (batch, window, scan, fold)
- Exhaustive switch over sealed types
- Guarded patterns (when clause)

### API Templates (4 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `api/string-methods` | `StringMethodPatterns.java` | `StringMethodPatternsTest.java` |
| `api/java-time` | `JavaTimePatterns.java` | `JavaTimePatternsTest.java` |

**Patterns validated:**
- String methods (strip, repeat, indent, transform)
- java.time API (LocalDate, Instant, Duration, ZonedDateTime)
- DateTimeFormatter patterns

### Concurrency Templates (6 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `concurrency/virtual-thread` | `VirtualThreadPatterns.java` | `VirtualThreadPatternsTest.java` |
| `concurrency/scoped-value` | `ScopedValuePatterns.java` | `ScopedValuePatternsTest.java` |
| `concurrency/structured-task-scope` | `StructuredTaskScopePatterns.java` | `StructuredTaskScopePatternsTest.java` |

**Patterns validated:**
- Virtual thread creation and management
- ScopedValue for immutable context
- StructuredTaskScope for concurrent operations
- Nested scopes and context inheritance

### Error Handling Templates (2 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `error-handling/result-railway` | `ResultRailway.java` | `ResultRailwayTest.java` |

**Patterns validated:**
- Result<T,E> sealed interface
- Railway-oriented programming
- map, flatMap, fold, recover operations

### Patterns Templates (2 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `patterns/strategy-functional` | `TextTransformStrategy.java` | `TextTransformStrategyTest.java` |

**Patterns validated:**
- Functional strategy pattern
- Sealed interface for strategies
- Pattern matching dispatch

### Security Templates (2 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `security/input-validation` | `InputValidation.java` | `InputValidationTest.java` |

**Patterns validated:**
- Preconditions and validation
- Error accumulation
- Defensive programming

### Messaging Templates (6 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `messaging/message-bus` | `MessageBusPatterns.java` | `MessageBusPatternsTest.java` |
| `messaging/content-based-router` | `RouterPatterns.java` | — |
| `messaging/pub-sub` | `PubSubPatterns.java` | — |
| `messaging/scatter-gather` | `ScatterGatherPatterns.java` | — |
| `messaging/correlation-identifier` | `CorrelationPatterns.java` | — |

**Patterns validated:**
- Message Bus (subscribe/publish/unsubscribe)
- Content-Based Router (predicate routing)
- Publish-Subscribe (topic-based)
- Scatter-Gather (fan-out + aggregate)
- Correlation Identifier (request-reply)

### Innovation Engine Templates (12 dogfood files)

| Template | Dogfood Class | Test |
|----------|---------------|------|
| `patterns/builder-record` | `OntologyMigrationEngine.java` | `OntologyMigrationEngineTest.java` |
| `patterns/state-machine-sealed` | `ModernizationScorer.java` | `ModernizationScorerTest.java` |
| `patterns/visitor-exhaustive-switch` | `TemplateCompositionEngine.java` | `TemplateCompositionEngineTest.java` |
| `patterns/chain-of-responsibility-sealed` | `BuildDiagnosticEngine.java` | `BuildDiagnosticEngineTest.java` |
| `patterns/observer-flow` | `LivingDocGenerator.java` | `LivingDocGeneratorTest.java` |
| `patterns/strategy-functional` | `RefactorEngine.java` | `RefactorEngineTest.java` |

**Engines validated:**
- OntologyMigrationEngine — 12 migration rules
- ModernizationScorer — 40+ signal detectors
- TemplateCompositionEngine — multi-template composition
- BuildDiagnosticEngine — compiler error mapping
- LivingDocGenerator — structured documentation
- RefactorEngine — orchestrated pipeline

---

## Implicit Validation

Some categories are implicitly validated through project structure:

### Build Templates
- Validated via `pom.xml` configuration
- Maven wrapper, Spotless, Surefire/Failsafe all functional

### Modules Templates
- Validated via `module-info.java`
- JPMS module compiles and runs correctly

### Enterprise Templates
- Validated via project structure
- Dockerfiles build successfully

---

## Test Patterns

Each dogfood test follows the JUnit 5 pattern from `testing/junit5-test.tera`:

```java
class ExamplePatternsTest implements WithAssertions {

    @Test
    void methodUnderTest_expectedBehavior_givenCondition() {
        // Arrange
        var input = ...;

        // Act
        var result = ExamplePatterns.method(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }
}
```

Property-based tests use jqwik:

```java
class PersonProperties implements WithAssertions {

    @Property
    void person_nameNeverBlank_afterConstruction(
        @ForAll("names") String name
    ) {
        var person = new Person(name, 25);
        assertThat(person.name()).isNotBlank();
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(50);
    }
}
```

---

## Coverage Metrics

Run `bin/dogfood report` to see:

```
Dogfood Coverage Report
========================================

  Templates:  35 / 96 exercised
  Categories: 8 / 11 covered

  ● core/          (7/14)
  ● api/           (4/6)
  ● concurrency/   (6/5)
  ● error-handling/(2/3)
  ● patterns/      (2/17)
  ● security/      (2/4)
  ● messaging/     (6/17)
  ● innovation/    (12/6)

  Note: build/ and modules/ categories are implicitly validated
  through pom.xml and module-info.java (they ARE the dogfood).
```

---

## Adding New Dogfood

To add a new dogfood entry:

1. **Create the source file** in `src/main/java/org/acme/dogfood/{category}/`
2. **Create the test file** in `src/test/java/org/acme/dogfood/{category}/`
3. **Update `bin/dogfood`** to add the mapping:
   ```bash
   "category|category/template.tera|category/ClassName.java|main"
   "testing|testing/junit5-test.tera|category/ClassNameTest.java|test"
   ```

---

## Verification Checklist

Before releasing templates, verify:

- [ ] `bin/dogfood generate` shows all ✓
- [ ] `bin/dogfood verify` passes (compile + test)
- [ ] `bin/dogfood report` shows improved coverage
- [ ] All new templates have corresponding dogfood classes
- [ ] All dogfood classes have corresponding tests
