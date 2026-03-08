package org.acme.dogfood.innovation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Self-Healing Build System: a diagnostic engine that parses javac compiler errors and Maven test
 * failure output, maps them to known fix patterns, and suggests concrete fixes.
 *
 * <p>This goes beyond simply reporting failures — it actively helps you succeed by recognizing
 * common error patterns and producing actionable {@link DiagnosticFix} suggestions.
 */
public final class BuildDiagnosticEngine {

    private BuildDiagnosticEngine() {}

    // ── Sealed fix hierarchy ────────────────────────────────────────────────

    /** A concrete, actionable fix for a build diagnostic. */
    public sealed interface DiagnosticFix {

        /** Human-readable explanation of what the fix does. */
        String description();

        /** The file path (or module/POM) that needs changing, if known. */
        String location();

        record AddImport(String filePath, String fullyQualifiedClass) implements DiagnosticFix {
            @Override
            public String description() {
                return "Add missing import: " + fullyQualifiedClass;
            }

            @Override
            public String location() {
                return filePath;
            }
        }

        record ChangeType(String filePath, String fromType, String toType) implements DiagnosticFix {
            @Override
            public String description() {
                return "Change type from " + fromType + " to " + toType;
            }

            @Override
            public String location() {
                return filePath;
            }
        }

        record AddModuleExport(String moduleName, String packageName) implements DiagnosticFix {
            @Override
            public String description() {
                return "Add 'exports " + packageName + ";' to module " + moduleName;
            }

            @Override
            public String location() {
                return "module-info.java";
            }
        }

        record EnablePreview(String filePath) implements DiagnosticFix {
            @Override
            public String description() {
                return "Enable preview features: add --enable-preview to compiler arguments";
            }

            @Override
            public String location() {
                return filePath;
            }
        }

        record AddDependency(String groupId, String artifactId, String scope)
                implements DiagnosticFix {
            @Override
            public String description() {
                return "Add Maven dependency: " + groupId + ":" + artifactId + " (scope: " + scope
                        + ")";
            }

            @Override
            public String location() {
                return "pom.xml";
            }
        }

        record AddModuleRequires(String moduleName, String requiredModule)
                implements DiagnosticFix {
            @Override
            public String description() {
                return "Add 'requires " + requiredModule + ";' to module " + moduleName;
            }

            @Override
            public String location() {
                return "module-info.java";
            }
        }

        record FixMethodSignature(String filePath, String methodName, String suggestion)
                implements DiagnosticFix {
            @Override
            public String description() {
                return "Fix method '" + methodName + "': " + suggestion;
            }

            @Override
            public String location() {
                return filePath;
            }
        }

        record CastExpression(String filePath, String targetType) implements DiagnosticFix {
            @Override
            public String description() {
                return "Add explicit cast to " + targetType;
            }

            @Override
            public String location() {
                return filePath;
            }
        }

        record UpgradeSourceLevel(String currentLevel, String requiredLevel)
                implements DiagnosticFix {
            @Override
            public String description() {
                return "Upgrade source level from " + currentLevel + " to " + requiredLevel
                        + " in pom.xml";
            }

            @Override
            public String location() {
                return "pom.xml";
            }
        }

        record AddTestDependency(String groupId, String artifactId) implements DiagnosticFix {
            @Override
            public String description() {
                return "Add test dependency: " + groupId + ":" + artifactId;
            }

            @Override
            public String location() {
                return "pom.xml";
            }
        }
    }

    // ── Diagnostic pattern: a regex paired with a fix-producing function ───

    @FunctionalInterface
    private interface FixExtractor {
        List<DiagnosticFix> extract(Matcher matcher);
    }

    private record DiagnosticPattern(Pattern regex, FixExtractor extractor) {}

    // ── Well-known class → import mappings ──────────────────────────────────

    private static final Map<String, String> KNOWN_IMPORTS = Map.ofEntries(
            Map.entry("List", "java.util.List"),
            Map.entry("Map", "java.util.Map"),
            Map.entry("Set", "java.util.Set"),
            Map.entry("Optional", "java.util.Optional"),
            Map.entry("Stream", "java.util.stream.Stream"),
            Map.entry("Collectors", "java.util.stream.Collectors"),
            Map.entry("ArrayList", "java.util.ArrayList"),
            Map.entry("HashMap", "java.util.HashMap"),
            Map.entry("IOException", "java.io.IOException"),
            Map.entry("Path", "java.nio.file.Path"),
            Map.entry("Files", "java.nio.file.Files"),
            Map.entry("Instant", "java.time.Instant"),
            Map.entry("Duration", "java.time.Duration"),
            Map.entry("Test", "org.junit.jupiter.api.Test"),
            Map.entry("Assertions", "org.junit.jupiter.api.Assertions"),
            Map.entry("WithAssertions", "org.assertj.core.api.WithAssertions"),
            Map.entry("HttpClient", "java.net.http.HttpClient"),
            Map.entry("HttpRequest", "java.net.http.HttpRequest"),
            Map.entry("HttpResponse", "java.net.http.HttpResponse"),
            Map.entry("Pattern", "java.util.regex.Pattern"),
            Map.entry("Matcher", "java.util.regex.Matcher"),
            Map.entry("Function", "java.util.function.Function"),
            Map.entry("Predicate", "java.util.function.Predicate"),
            Map.entry("Consumer", "java.util.function.Consumer"),
            Map.entry("Supplier", "java.util.function.Supplier"));

    // ── Known feature → source level mappings ───────────────────────────────

    private static final Map<String, String> FEATURE_SOURCE_LEVELS = Map.of(
            "records", "14",
            "sealed classes", "17",
            "pattern matching", "21",
            "text blocks", "15",
            "switch expressions", "14",
            "string templates", "21",
            "virtual threads", "21",
            "unnamed variables", "22");

    // ── The pattern registry (order matters: more specific patterns first) ──

    private static final List<DiagnosticPattern> PATTERNS = buildPatterns();

    private static List<DiagnosticPattern> buildPatterns() {
        List<DiagnosticPattern> patterns = new ArrayList<>();

        // 1) Cannot find symbol — missing import
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: cannot find symbol\\s*\\n"
                                + ".*\\n.*\\n"
                                + "\\s*symbol:\\s+class (\\w+)"),
                m -> {
                    String file = m.group(1);
                    String className = m.group(2);
                    String fqcn = KNOWN_IMPORTS.getOrDefault(className, null);
                    if (fqcn != null) {
                        return List.of(new DiagnosticFix.AddImport(file, fqcn));
                    }
                    return List.of(new DiagnosticFix.AddImport(
                            file, "<unknown-package>." + className));
                }));

        // 2) Package does not exist — missing dependency or module requires
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: package (\\S+) does not exist"),
                m -> {
                    String file = m.group(1);
                    String pkg = m.group(2);
                    if (pkg.startsWith("org.junit")) {
                        return List.of(new DiagnosticFix.AddTestDependency(
                                "org.junit.jupiter", "junit-jupiter"));
                    }
                    if (pkg.startsWith("org.assertj")) {
                        return List.of(new DiagnosticFix.AddTestDependency(
                                "org.assertj", "assertj-core"));
                    }
                    return List.of(new DiagnosticFix.AddDependency(
                            pkg.substring(0, Math.min(pkg.lastIndexOf('.'), pkg.length())),
                            pkg.substring(pkg.lastIndexOf('.') + 1),
                            "compile"));
                }));

        // 3) Preview feature used — need --enable-preview
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: (\\w[\\w ]*) is a preview feature"),
                m -> {
                    String file = m.group(1);
                    return List.of(new DiagnosticFix.EnablePreview(file));
                }));

        // 4) Module does not export package
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: package (\\S+) is not visible\\s*\\n"
                                + ".*\\n.*\\n.*package (\\S+) is declared in module (\\S+),"
                                + " which does not export it"),
                m -> {
                    String pkg = m.group(3);
                    String mod = m.group(4);
                    return List.of(new DiagnosticFix.AddModuleExport(mod, pkg));
                }));

        // 5) Module not found — need requires directive
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: module (\\S+) does not read module (\\S+)"),
                m -> {
                    String srcModule = m.group(2);
                    String requiredModule = m.group(3);
                    return List.of(
                            new DiagnosticFix.AddModuleRequires(srcModule, requiredModule));
                }));

        // 6) Incompatible types — type mismatch
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: incompatible types: "
                                + "(\\S+) cannot be converted to (\\S+)"),
                m -> {
                    String file = m.group(1);
                    String fromType = m.group(2);
                    String toType = m.group(3);
                    return List.of(new DiagnosticFix.ChangeType(file, fromType, toType));
                }));

        // 7) Lossy conversion — needs explicit cast
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: incompatible types: "
                                + "possible lossy conversion from \\S+ to (\\S+)"),
                m -> {
                    String file = m.group(1);
                    String targetType = m.group(2);
                    return List.of(new DiagnosticFix.CastExpression(file, targetType));
                }));

        // 8) Source level too low for feature
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: (\\w[\\w ]*) "
                                + "(?:are|is) not supported in -source (\\d+)"),
                m -> {
                    String feature = m.group(2).toLowerCase();
                    String currentLevel = m.group(3);
                    String requiredLevel = FEATURE_SOURCE_LEVELS.getOrDefault(feature, "21");
                    return List.of(
                            new DiagnosticFix.UpgradeSourceLevel(currentLevel, requiredLevel));
                }));

        // 9) Method not found — wrong argument count or types
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^(.+\\.java):\\d+: error: cannot find symbol\\s*\\n"
                                + ".*\\n.*\\n"
                                + "\\s*symbol:\\s+method (\\w+)\\(([^)]*)\\)"),
                m -> {
                    String file = m.group(1);
                    String method = m.group(2);
                    String args = m.group(3);
                    String suggestion = args.isEmpty()
                            ? "verify method exists and is accessible"
                            : "check argument types (" + args + ") match the declaration";
                    return List.of(
                            new DiagnosticFix.FixMethodSignature(file, method, suggestion));
                }));

        // 10) Maven test failure — assertion error with expected/actual
        patterns.add(new DiagnosticPattern(
                Pattern.compile(
                        "(?m)^\\[ERROR]\\s+(\\S+Test)\\.(\\w+).*?"
                                + "AssertionError|AssertionFailedError|ComparisonFailure"),
                m -> {
                    String testClass = m.group(1);
                    String testMethod = m.group(2);
                    return List.of(new DiagnosticFix.FixMethodSignature(
                            testClass + ".java",
                            testMethod,
                            "test assertion failed — check expected vs actual values"));
                }));

        return List.copyOf(patterns);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Analyzes build error output and returns a list of suggested fixes.
     *
     * @param errorOutput raw stderr/stdout from javac or Maven
     * @return an ordered list of diagnostic fixes, possibly empty
     */
    public static List<DiagnosticFix> diagnose(String errorOutput) {
        if (errorOutput == null || errorOutput.isBlank()) {
            return List.of();
        }
        return PATTERNS.stream()
                .flatMap(dp -> {
                    Matcher matcher = dp.regex().matcher(errorOutput);
                    List<DiagnosticFix> fixes = new ArrayList<>();
                    while (matcher.find()) {
                        fixes.addAll(dp.extractor().extract(matcher));
                    }
                    return fixes.stream();
                })
                .distinct()
                .toList();
    }

    /**
     * Convenience: diagnose and return only fixes of a specific type.
     *
     * @param errorOutput raw build output
     * @param fixType the class of fix to filter for
     * @return fixes matching the requested type
     */
    public static <F extends DiagnosticFix> List<F> diagnose(
            String errorOutput, Class<F> fixType) {
        return diagnose(errorOutput).stream()
                .filter(fixType::isInstance)
                .map(fixType::cast)
                .toList();
    }

    /**
     * Formats a list of fixes as a human-readable report.
     *
     * @param fixes the diagnostic fixes
     * @return multi-line report string
     */
    public static String formatReport(List<DiagnosticFix> fixes) {
        if (fixes.isEmpty()) {
            return "No actionable diagnostics found.";
        }
        var sb = new StringBuilder();
        sb.append("=== Build Diagnostic Report ===\n");
        sb.append("Found ").append(fixes.size()).append(" suggested fix(es):\n\n");
        for (int i = 0; i < fixes.size(); i++) {
            DiagnosticFix fix = fixes.get(i);
            sb.append(i + 1).append(") ");
            String category = switch (fix) {
                case DiagnosticFix.AddImport _ -> "IMPORT";
                case DiagnosticFix.ChangeType _ -> "TYPE";
                case DiagnosticFix.AddModuleExport _ -> "MODULE";
                case DiagnosticFix.EnablePreview _ -> "PREVIEW";
                case DiagnosticFix.AddDependency _ -> "DEPENDENCY";
                case DiagnosticFix.AddModuleRequires _ -> "MODULE";
                case DiagnosticFix.FixMethodSignature _ -> "METHOD";
                case DiagnosticFix.CastExpression _ -> "CAST";
                case DiagnosticFix.UpgradeSourceLevel _ -> "SOURCE";
                case DiagnosticFix.AddTestDependency _ -> "DEPENDENCY";
            };
            sb.append("[").append(category).append("] ");
            sb.append(fix.description());
            sb.append("\n   Location: ").append(fix.location()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Returns all supported diagnostic categories as a stream of descriptive names.
     */
    public static Stream<String> supportedCategories() {
        return Stream.of(
                "Missing import resolution",
                "Package/dependency not found",
                "Preview feature enablement",
                "Module export visibility",
                "Module requires directive",
                "Incompatible type conversion",
                "Lossy conversion (explicit cast)",
                "Source level upgrade",
                "Method signature mismatch",
                "Test assertion failure analysis");
    }
}
