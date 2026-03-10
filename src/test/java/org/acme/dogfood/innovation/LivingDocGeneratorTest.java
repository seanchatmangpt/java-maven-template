package org.acme.dogfood.innovation;

import java.util.List;

import org.acme.dogfood.innovation.LivingDocGenerator.DocElement;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LivingDocGenerator} — the living documentation generator.
 *
 * <p>Validates regex-based parsing of records, sealed types, modules, methods, and packages, as well
 * as Markdown rendering of the extracted {@link DocElement} hierarchy.
 */
@DisplayName("LivingDocGenerator")
class LivingDocGeneratorTest implements WithAssertions {

    private LivingDocGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new LivingDocGenerator();
    }

    // -- parseSource: Records -----------------------------------------------------------------

    @Nested
    @DisplayName("record parsing")
    class RecordParsing {

        @Test
        @DisplayName("should extract a simple record with components")
        void shouldExtractSimpleRecord() {
            String source =
                    """
                    package org.example;

                    public record Person(String name, int age) {}
                    """;

            List<DocElement> elements = generator.parseSource(source);

            var records = elements.stream()
                    .filter(DocElement.RecordDoc.class::isInstance)
                    .map(DocElement.RecordDoc.class::cast)
                    .toList();

            assertThat(records).hasSize(1);
            assertThat(records.getFirst().name()).isEqualTo("Person");
            assertThat(records.getFirst().components()).hasSize(2);
            assertThat(records.getFirst().components().get(0).type()).isEqualTo("String");
            assertThat(records.getFirst().components().get(0).name()).isEqualTo("name");
            assertThat(records.getFirst().components().get(1).type()).isEqualTo("int");
            assertThat(records.getFirst().components().get(1).name()).isEqualTo("age");
        }

        @Test
        @DisplayName("should extract record with generic component types")
        void shouldExtractRecordWithGenerics() {
            String source =
                    """
                    public record Wrapper(List<String> items, Map<String, Integer> counts) {}
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var records = elements.stream()
                    .filter(DocElement.RecordDoc.class::isInstance)
                    .map(DocElement.RecordDoc.class::cast)
                    .toList();

            assertThat(records).hasSize(1);
            assertThat(records.getFirst().components()).hasSize(2);
            assertThat(records.getFirst().components().get(0).type()).isEqualTo("List<String>");
            assertThat(records.getFirst().components().get(0).name()).isEqualTo("items");
            assertThat(records.getFirst().components().get(1).type())
                    .isEqualTo("Map<String, Integer>");
            assertThat(records.getFirst().components().get(1).name()).isEqualTo("counts");
        }

        @Test
        @DisplayName("should extract javadoc from record")
        void shouldExtractJavadocFromRecord() {
            String source =
                    """
                    /** Represents an address. */
                    public record Address(String street, String city) {}
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var records = elements.stream()
                    .filter(DocElement.RecordDoc.class::isInstance)
                    .map(DocElement.RecordDoc.class::cast)
                    .toList();

            assertThat(records).hasSize(1);
            assertThat(records.getFirst().javadoc()).isEqualTo("Represents an address.");
        }
    }

    // -- parseSource: Sealed types ------------------------------------------------------------

    @Nested
    @DisplayName("sealed type parsing")
    class SealedTypeParsing {

        @Test
        @DisplayName("should extract sealed interface with permits")
        void shouldExtractSealedInterface() {
            String source =
                    """
                    public sealed interface Shape
                            permits Circle, Rectangle, Triangle {
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var sealedTypes = elements.stream()
                    .filter(DocElement.SealedTypeDoc.class::isInstance)
                    .map(DocElement.SealedTypeDoc.class::cast)
                    .toList();

            assertThat(sealedTypes).hasSize(1);
            assertThat(sealedTypes.getFirst().name()).isEqualTo("Shape");
            assertThat(sealedTypes.getFirst().kind()).isEqualTo("interface");
            assertThat(sealedTypes.getFirst().permits())
                    .containsExactly("Circle", "Rectangle", "Triangle");
        }

        @Test
        @DisplayName("should extract sealed class")
        void shouldExtractSealedClass() {
            String source =
                    """
                    sealed class Expr permits Literal, BinOp {
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var sealedTypes = elements.stream()
                    .filter(DocElement.SealedTypeDoc.class::isInstance)
                    .map(DocElement.SealedTypeDoc.class::cast)
                    .toList();

            assertThat(sealedTypes).hasSize(1);
            assertThat(sealedTypes.getFirst().name()).isEqualTo("Expr");
            assertThat(sealedTypes.getFirst().kind()).isEqualTo("class");
            assertThat(sealedTypes.getFirst().permits()).containsExactly("Literal", "BinOp");
        }

        @Test
        @DisplayName("should extract sealed interface with generics")
        void shouldExtractSealedInterfaceWithGenerics() {
            String source =
                    """
                    public sealed interface Result<T, E>
                            permits Result.Success, Result.Failure {
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var sealedTypes = elements.stream()
                    .filter(DocElement.SealedTypeDoc.class::isInstance)
                    .map(DocElement.SealedTypeDoc.class::cast)
                    .toList();

            assertThat(sealedTypes).hasSize(1);
            assertThat(sealedTypes.getFirst().name()).isEqualTo("Result");
            assertThat(sealedTypes.getFirst().permits())
                    .containsExactly("Result.Success", "Result.Failure");
        }
    }

    // -- parseSource: Modules -----------------------------------------------------------------

    @Nested
    @DisplayName("module parsing")
    class ModuleParsing {

        @Test
        @DisplayName("should extract module with exports and requires")
        void shouldExtractModule() {
            String source =
                    """
                    module org.acme {
                        requires java.base;
                        requires transitive java.logging;
                        exports org.acme;
                        exports org.acme.util;
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var modules = elements.stream()
                    .filter(DocElement.ModuleDoc.class::isInstance)
                    .map(DocElement.ModuleDoc.class::cast)
                    .toList();

            assertThat(modules).hasSize(1);
            assertThat(modules.getFirst().name()).isEqualTo("org.acme");
            assertThat(modules.getFirst().exports())
                    .containsExactly("org.acme", "org.acme.util");
            assertThat(modules.getFirst().requires())
                    .containsExactly("java.base", "java.logging");
        }

        @Test
        @DisplayName("should extract module with no requires")
        void shouldExtractModuleNoRequires() {
            String source =
                    """
                    module com.example {
                        exports com.example.api;
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var modules = elements.stream()
                    .filter(DocElement.ModuleDoc.class::isInstance)
                    .map(DocElement.ModuleDoc.class::cast)
                    .toList();

            assertThat(modules).hasSize(1);
            assertThat(modules.getFirst().requires()).isEmpty();
            assertThat(modules.getFirst().exports()).containsExactly("com.example.api");
        }
    }

    // -- parseSource: Methods -----------------------------------------------------------------

    @Nested
    @DisplayName("method parsing")
    class MethodParsing {

        @Test
        @DisplayName("should extract public method with parameters")
        void shouldExtractMethodWithParams() {
            String source =
                    """
                    package org.example;

                    public class Calculator {
                        public int add(int a, int b) {
                            return a + b;
                        }
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var methods = elements.stream()
                    .filter(DocElement.MethodDoc.class::isInstance)
                    .map(DocElement.MethodDoc.class::cast)
                    .toList();

            assertThat(methods).hasSize(1);
            assertThat(methods.getFirst().name()).isEqualTo("add");
            assertThat(methods.getFirst().returnType()).isEqualTo("int");
            assertThat(methods.getFirst().modifiers()).containsExactly("public");
            assertThat(methods.getFirst().parameters()).hasSize(2);
        }

        @Test
        @DisplayName("should extract static method")
        void shouldExtractStaticMethod() {
            String source =
                    """
                    public class Utils {
                        public static String format(String template) {
                            return template;
                        }
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var methods = elements.stream()
                    .filter(DocElement.MethodDoc.class::isInstance)
                    .map(DocElement.MethodDoc.class::cast)
                    .toList();

            assertThat(methods).hasSize(1);
            assertThat(methods.getFirst().name()).isEqualTo("format");
            assertThat(methods.getFirst().modifiers()).containsExactly("public", "static");
        }
    }

    // -- parseSource: Package -----------------------------------------------------------------

    @Nested
    @DisplayName("package parsing")
    class PackageParsing {

        @Test
        @DisplayName("should extract package declaration")
        void shouldExtractPackage() {
            String source =
                    """
                    package org.acme.dogfood.innovation;

                    public class Foo {}
                    """;

            List<DocElement> elements = generator.parseSource(source);
            var packages = elements.stream()
                    .filter(DocElement.PackageDoc.class::isInstance)
                    .map(DocElement.PackageDoc.class::cast)
                    .toList();

            assertThat(packages).hasSize(1);
            assertThat(packages.getFirst().name()).isEqualTo("org.acme.dogfood.innovation");
        }
    }

    // -- generateMarkdown ---------------------------------------------------------------------

    @Nested
    @DisplayName("Markdown generation")
    class MarkdownGeneration {

        @Test
        @DisplayName("should generate markdown for empty element list")
        void shouldHandleEmptyList() {
            String md = generator.generateMarkdown(List.of());
            assertThat(md).contains("No elements found.");
        }

        @Test
        @DisplayName("should generate markdown with record table")
        void shouldGenerateRecordMarkdown() {
            var record = new DocElement.RecordDoc(
                    "Person",
                    List.of(
                            new DocElement.Component("String", "name"),
                            new DocElement.Component("int", "age")),
                    "A person entity.");

            String md = generator.generateMarkdown(List.of(record));

            assertThat(md).contains("## Records");
            assertThat(md).contains("### `record Person`");
            assertThat(md).contains("A person entity.");
            assertThat(md).contains("| `name` | `String` |");
            assertThat(md).contains("| `age` | `int` |");
        }

        @Test
        @DisplayName("should generate markdown for sealed type")
        void shouldGenerateSealedTypeMarkdown() {
            var sealed = new DocElement.SealedTypeDoc(
                    "Shape", "interface", List.of("Circle", "Square"), "A geometric shape.");

            String md = generator.generateMarkdown(List.of(sealed));

            assertThat(md).contains("## Sealed Types");
            assertThat(md).contains("### `sealed interface Shape`");
            assertThat(md).contains("A geometric shape.");
            assertThat(md).contains("- `Circle`");
            assertThat(md).contains("- `Square`");
        }

        @Test
        @DisplayName("should generate markdown for module")
        void shouldGenerateModuleMarkdown() {
            var module = new DocElement.ModuleDoc(
                    "org.acme",
                    List.of("org.acme", "org.acme.api"),
                    List.of("java.base"));

            String md = generator.generateMarkdown(List.of(module));

            assertThat(md).contains("## Modules");
            assertThat(md).contains("### Module `org.acme`");
            assertThat(md).contains("**Exports:**");
            assertThat(md).contains("- `org.acme`");
            assertThat(md).contains("**Requires:**");
            assertThat(md).contains("- `java.base`");
        }

        @Test
        @DisplayName("should generate markdown for method")
        void shouldGenerateMethodMarkdown() {
            var method = new DocElement.MethodDoc(
                    "calculate",
                    "double",
                    List.of(new DocElement.Component("int", "x")),
                    List.of("public", "static"),
                    "Calculates a value.");

            String md = generator.generateMarkdown(List.of(method));

            assertThat(md).contains("## Methods");
            assertThat(md).contains("`public static double calculate(int x)`");
            assertThat(md).contains("Calculates a value.");
        }
    }

    // -- End-to-end ---------------------------------------------------------------------------

    @Nested
    @DisplayName("end-to-end")
    class EndToEnd {

        @Test
        @DisplayName("should parse and render the Result sealed interface source")
        void shouldParseAndRenderResultSource() {
            String source =
                    """
                    package org.acme;

                    /** A result type for railway-oriented programming. */
                    public sealed interface Result<T, E>
                            permits Result.Success, Result.Failure {

                        record Success<T, E>(T value) implements Result<T, E> {}
                        record Failure<T, E>(E error) implements Result<T, E> {}

                        static <T, E> Result<T, E> success(T value) {
                            return new Success<>(value);
                        }

                        static <T, E> Result<T, E> failure(E error) {
                            return new Failure<>(error);
                        }
                    }
                    """;

            List<DocElement> elements = generator.parseSource(source);
            String md = generator.generateMarkdown(elements);

            // Should find the sealed interface
            assertThat(md).contains("sealed interface Result");
            // Should find the inner records
            assertThat(md).contains("record Success");
            assertThat(md).contains("record Failure");
            // Should contain package
            assertThat(md).contains("`org.acme`");
            // Overall structure
            assertThat(md).startsWith("# API Documentation");
        }

        @Test
        @DisplayName("should produce valid markdown for a multi-element source file")
        void shouldProduceValidMarkdownForComplexSource() {
            String source =
                    """
                    package com.example.shapes;

                    /** Represents a 2D shape. */
                    public sealed interface Shape
                            permits Circle, Rectangle {
                    }

                    /** A circle defined by radius. */
                    public record Circle(double radius) implements Shape {}

                    /** A rectangle defined by width and height. */
                    public record Rectangle(double width, double height) implements Shape {}
                    """;

            List<DocElement> elements = generator.parseSource(source);
            String md = generator.generateMarkdown(elements);

            assertThat(md).contains("## Sealed Types");
            assertThat(md).contains("## Records");
            assertThat(md).contains("record Circle");
            assertThat(md).contains("| `radius` | `double` |");
            assertThat(md).contains("record Rectangle");
            assertThat(md).contains("| `width` | `double` |");
            assertThat(md).contains("| `height` | `double` |");
        }
    }

    // -- DocElement record validation ---------------------------------------------------------

    @Nested
    @DisplayName("DocElement validation")
    class DocElementValidation {

        @Test
        @DisplayName("RecordDoc should reject null name")
        void recordDocShouldRejectNullName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DocElement.RecordDoc(null, List.of(), null));
        }

        @Test
        @DisplayName("Component should reject null type")
        void componentShouldRejectNullType() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DocElement.Component(null, "name"));
        }

        @Test
        @DisplayName("RecordDoc should defensively copy components list")
        void recordDocShouldDefensivelyCopy() {
            var mutableList = new java.util.ArrayList<>(
                    List.of(new DocElement.Component("String", "name")));
            var rec = new DocElement.RecordDoc("Foo", mutableList, null);
            mutableList.clear();
            assertThat(rec.components()).hasSize(1);
        }

        @Test
        @DisplayName("parseSource should reject null input")
        void parseShouldRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> generator.parseSource(null));
        }

        @Test
        @DisplayName("generateMarkdown should reject null input")
        void generateMarkdownShouldRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> generator.generateMarkdown(null));
        }

        @Test
        @DisplayName("handles unbalanced angle brackets in generic types")
        void handlesUnbalancedBrackets() {
            // Source with unbalanced generic brackets (malformed but should not crash)
            var source = """
                    package org.acme;
                    public record Container(Map<String, List<Map<Integer, String>>> items) {}
                    """;

            // Should not throw
            var elements = generator.parseSource(source);
            assertThat(elements).isNotEmpty();
        }

        @Test
        @DisplayName("handles deeply nested generics")
        void handlesDeeplyNestedGenerics() {
            var source = """
                    package org.acme;
                    public record Complex(
                        Map<String, List<Map<Integer, Map<String, Optional<CompletableFuture<String>>>>>> data
                    ) {}
                    """;

            var elements = generator.parseSource(source);

            var record = elements.stream()
                    .filter(e -> e instanceof DocElement.RecordDoc)
                    .map(e -> (DocElement.RecordDoc) e)
                    .findFirst()
                    .orElseThrow();

            assertThat(record.components()).hasSize(1);
        }
    }
}
