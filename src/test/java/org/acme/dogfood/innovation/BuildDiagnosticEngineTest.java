package org.acme.dogfood.innovation;

import java.util.List;

import org.acme.dogfood.innovation.BuildDiagnosticEngine.DiagnosticFix;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link BuildDiagnosticEngine} using real-world javac and Maven error output. */
@DisplayName("BuildDiagnosticEngine")
class BuildDiagnosticEngineTest implements WithAssertions {

    // ── 1) Missing import ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Missing import detection")
    class MissingImport {

        private static final String MISSING_LIST_IMPORT =
                """
                src/main/java/com/example/App.java:10: error: cannot find symbol
                        List<String> items = new ArrayList<>();
                        ^
                  symbol:   class List
                  location: class App
                """;

        @Test
        @DisplayName("recognizes missing List import and suggests java.util.List")
        void detectsMissingListImport() {
            var fixes = BuildDiagnosticEngine.diagnose(MISSING_LIST_IMPORT);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.AddImport.class);

            var addImport = (DiagnosticFix.AddImport) fixes.getFirst();
            assertThat(addImport.fullyQualifiedClass()).isEqualTo("java.util.List");
            assertThat(addImport.filePath()).isEqualTo("src/main/java/com/example/App.java");
            assertThat(addImport.description()).contains("java.util.List");
        }

        private static final String MISSING_UNKNOWN_CLASS =
                """
                src/main/java/com/example/App.java:5: error: cannot find symbol
                        FooBarBaz x = null;
                        ^
                  symbol:   class FooBarBaz
                  location: class App
                """;

        @Test
        @DisplayName("handles unknown class with fallback package")
        void handlesUnknownClass() {
            var fixes = BuildDiagnosticEngine.diagnose(MISSING_UNKNOWN_CLASS);

            assertThat(fixes).hasSize(1);
            var addImport = (DiagnosticFix.AddImport) fixes.getFirst();
            assertThat(addImport.fullyQualifiedClass()).contains("FooBarBaz");
        }
    }

    // ── 2) Package does not exist ───────────────────────────────────────────

    @Nested
    @DisplayName("Package not found detection")
    class PackageNotFound {

        private static final String MISSING_JUNIT_PACKAGE =
                """
                src/test/java/com/example/AppTest.java:3: error: package org.junit.jupiter.api does not exist
                import org.junit.jupiter.api.Test;
                                           ^
                """;

        @Test
        @DisplayName("suggests adding JUnit 5 dependency for missing junit package")
        void detectsMissingJunitDependency() {
            var fixes = BuildDiagnosticEngine.diagnose(MISSING_JUNIT_PACKAGE);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.AddTestDependency.class);

            var dep = (DiagnosticFix.AddTestDependency) fixes.getFirst();
            assertThat(dep.groupId()).isEqualTo("org.junit.jupiter");
            assertThat(dep.artifactId()).isEqualTo("junit-jupiter");
        }

        private static final String MISSING_ASSERTJ_PACKAGE =
                """
                src/test/java/com/example/AppTest.java:4: error: package org.assertj.core.api does not exist
                import org.assertj.core.api.WithAssertions;
                                           ^
                """;

        @Test
        @DisplayName("suggests adding AssertJ dependency for missing assertj package")
        void detectsMissingAssertjDependency() {
            var fixes = BuildDiagnosticEngine.diagnose(MISSING_ASSERTJ_PACKAGE);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.AddTestDependency.class);

            var dep = (DiagnosticFix.AddTestDependency) fixes.getFirst();
            assertThat(dep.groupId()).isEqualTo("org.assertj");
            assertThat(dep.artifactId()).isEqualTo("assertj-core");
        }
    }

    // ── 3) Preview feature ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Preview feature detection")
    class PreviewFeature {

        private static final String PREVIEW_ERROR =
                """
                src/main/java/com/example/App.java:15: error: sealed classes is a preview feature and is disabled by default.
                public sealed interface Shape permits Circle, Square {}
                       ^
                  (use --enable-preview to enable sealed classes)
                """;

        @Test
        @DisplayName("detects preview feature error and suggests --enable-preview")
        void detectsPreviewFeature() {
            var fixes = BuildDiagnosticEngine.diagnose(PREVIEW_ERROR);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.EnablePreview.class);

            var fix = (DiagnosticFix.EnablePreview) fixes.getFirst();
            assertThat(fix.description()).contains("--enable-preview");
        }
    }

    // ── 4) Module does not export ───────────────────────────────────────────

    @Nested
    @DisplayName("Module export detection")
    class ModuleExport {

        private static final String MODULE_NOT_EXPORTED =
                """
                src/main/java/com/example/App.java:3: error: package com.internal.util is not visible
                import com.internal.util.Helper;
                                       ^
                  (package com.internal.util is declared in module com.internal, which does not export it)
                """;

        @Test
        @DisplayName("detects non-exported package and suggests adding export")
        void detectsModuleExportNeeded() {
            var fixes = BuildDiagnosticEngine.diagnose(MODULE_NOT_EXPORTED);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.AddModuleExport.class);

            var fix = (DiagnosticFix.AddModuleExport) fixes.getFirst();
            assertThat(fix.moduleName()).isEqualTo("com.internal");
            assertThat(fix.packageName()).isEqualTo("com.internal.util");
            assertThat(fix.location()).isEqualTo("module-info.java");
        }
    }

    // ── 5) Incompatible types ───────────────────────────────────────────────

    @Nested
    @DisplayName("Type mismatch detection")
    class TypeMismatch {

        private static final String INCOMPATIBLE_TYPES =
                """
                src/main/java/com/example/App.java:20: error: incompatible types: String cannot be converted to int
                        int x = getName();
                                ^
                """;

        @Test
        @DisplayName("detects incompatible types and suggests type change")
        void detectsIncompatibleTypes() {
            var fixes = BuildDiagnosticEngine.diagnose(INCOMPATIBLE_TYPES);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.ChangeType.class);

            var fix = (DiagnosticFix.ChangeType) fixes.getFirst();
            assertThat(fix.fromType()).isEqualTo("String");
            assertThat(fix.toType()).isEqualTo("int");
        }
    }

    // ── 6) Lossy conversion ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Lossy conversion detection")
    class LossyConversion {

        private static final String LOSSY_CONVERSION =
                """
                src/main/java/com/example/App.java:12: error: incompatible types: possible lossy conversion from double to int
                        int result = 3.14 * factor;
                                     ^
                """;

        @Test
        @DisplayName("detects lossy conversion and suggests explicit cast")
        void detectsLossyConversion() {
            var fixes = BuildDiagnosticEngine.diagnose(LOSSY_CONVERSION);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.CastExpression.class);

            var fix = (DiagnosticFix.CastExpression) fixes.getFirst();
            assertThat(fix.targetType()).isEqualTo("int");
            assertThat(fix.description()).contains("cast to int");
        }
    }

    // ── 7) Source level too low ──────────────────────────────────────────────

    @Nested
    @DisplayName("Source level detection")
    class SourceLevel {

        private static final String SOURCE_LEVEL_TOO_LOW =
                """
                src/main/java/com/example/App.java:5: error: records is not supported in -source 11
                public record Point(int x, int y) {}
                       ^
                  (use -source 14 or higher to enable records)
                """;

        @Test
        @DisplayName("detects source level too low and suggests upgrade")
        void detectsSourceLevelTooLow() {
            var fixes = BuildDiagnosticEngine.diagnose(SOURCE_LEVEL_TOO_LOW);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.UpgradeSourceLevel.class);

            var fix = (DiagnosticFix.UpgradeSourceLevel) fixes.getFirst();
            assertThat(fix.currentLevel()).isEqualTo("11");
            assertThat(fix.requiredLevel()).isEqualTo("14");
        }
    }

    // ── 8) Method not found ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Method not found detection")
    class MethodNotFound {

        private static final String METHOD_NOT_FOUND =
                """
                src/main/java/com/example/App.java:25: error: cannot find symbol
                        processor.handle(input, options);
                                 ^
                  symbol:   method handle(String,Map)
                  location: class Processor
                """;

        @Test
        @DisplayName("detects missing method and suggests checking argument types")
        void detectsMethodNotFound() {
            var fixes = BuildDiagnosticEngine.diagnose(METHOD_NOT_FOUND);

            assertThat(fixes)
                    .hasSize(1)
                    .first()
                    .isInstanceOf(DiagnosticFix.FixMethodSignature.class);

            var fix = (DiagnosticFix.FixMethodSignature) fixes.getFirst();
            assertThat(fix.methodName()).isEqualTo("handle");
            assertThat(fix.suggestion()).contains("String,Map");
        }
    }

    // ── Multiple errors in single output ────────────────────────────────────

    @Nested
    @DisplayName("Multiple error detection")
    class MultipleErrors {

        private static final String MULTI_ERROR_OUTPUT =
                """
                src/main/java/com/example/App.java:3: error: cannot find symbol
                        List<String> items = new ArrayList<>();
                        ^
                  symbol:   class List
                  location: class App
                src/main/java/com/example/App.java:10: error: incompatible types: String cannot be converted to int
                        int count = getLabel();
                                    ^
                """;

        @Test
        @DisplayName("detects multiple errors in single output")
        void detectsMultipleErrors() {
            var fixes = BuildDiagnosticEngine.diagnose(MULTI_ERROR_OUTPUT);

            assertThat(fixes).hasSizeGreaterThanOrEqualTo(2);
            assertThat(fixes)
                    .anyMatch(f -> f instanceof DiagnosticFix.AddImport)
                    .anyMatch(f -> f instanceof DiagnosticFix.ChangeType);
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("returns empty list for null input")
        void handlesNullInput() {
            assertThat(BuildDiagnosticEngine.diagnose(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list for blank input")
        void handlesBlankInput() {
            assertThat(BuildDiagnosticEngine.diagnose("")).isEmpty();
            assertThat(BuildDiagnosticEngine.diagnose("   ")).isEmpty();
        }

        @Test
        @DisplayName("returns empty list for unrecognized output")
        void handlesUnrecognizedOutput() {
            assertThat(BuildDiagnosticEngine.diagnose("BUILD SUCCESS")).isEmpty();
        }
    }

    // ── Typed diagnose method ───────────────────────────────────────────────

    @Nested
    @DisplayName("Typed diagnose filtering")
    class TypedDiagnose {

        private static final String MIXED_ERRORS =
                """
                src/main/java/com/example/App.java:3: error: cannot find symbol
                        Stream<String> s = null;
                        ^
                  symbol:   class Stream
                  location: class App
                src/main/java/com/example/App.java:7: error: sealed classes is a preview feature and is disabled by default.
                public sealed interface Shape permits Circle {}
                       ^
                """;

        @Test
        @DisplayName("filters fixes by type")
        void filtersFixesByType() {
            var imports = BuildDiagnosticEngine.diagnose(MIXED_ERRORS, DiagnosticFix.AddImport.class);
            assertThat(imports).hasSize(1);
            assertThat(imports.getFirst().fullyQualifiedClass())
                    .isEqualTo("java.util.stream.Stream");

            var previews =
                    BuildDiagnosticEngine.diagnose(MIXED_ERRORS, DiagnosticFix.EnablePreview.class);
            assertThat(previews).hasSize(1);
        }
    }

    // ── Report formatting ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Report formatting")
    class ReportFormatting {

        @Test
        @DisplayName("formats empty fix list")
        void formatsEmptyList() {
            assertThat(BuildDiagnosticEngine.formatReport(List.of()))
                    .isEqualTo("No actionable diagnostics found.");
        }

        @Test
        @DisplayName("formats fix list with categories")
        void formatsFixList() {
            List<DiagnosticFix> fixes = List.of(
                    new DiagnosticFix.AddImport("App.java", "java.util.List"),
                    new DiagnosticFix.EnablePreview("App.java"));

            String report = BuildDiagnosticEngine.formatReport(fixes);

            assertThat(report)
                    .contains("Build Diagnostic Report")
                    .contains("2 suggested fix(es)")
                    .contains("[IMPORT]")
                    .contains("[PREVIEW]")
                    .contains("java.util.List")
                    .contains("--enable-preview");
        }
    }

    // ── Supported categories ────────────────────────────────────────────────

    @Test
    @DisplayName("lists at least 8 supported diagnostic categories")
    void listsCategories() {
        assertThat(BuildDiagnosticEngine.supportedCategories().toList())
                .hasSizeGreaterThanOrEqualTo(8);
    }
}
