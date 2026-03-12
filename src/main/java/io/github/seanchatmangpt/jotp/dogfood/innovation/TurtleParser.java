package io.github.seanchatmangpt.jotp.dogfood.innovation;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turtle/RDF Parser and SPARQL Query Executor.
 *
 * <p>Parses Turtle ontology files and extracts project specifications using SPARQL-like queries.
 * This is a lightweight implementation that does not require Apache Jena dependency.
 *
 * <p>For production use with complex ontologies, consider integrating Apache Jena ARQ.
 */
public final class TurtleParser {

    /** Extracted pattern from Turtle specification. */
    public record PatternSpec(
            String uri,
            String name,
            String category,
            String template,
            String primitive,
            int complexity,
            String intent,
            List<String> dependencies) {

        public PatternSpec {
            Objects.requireNonNull(uri, "uri must not be null");
            Objects.requireNonNull(name, "name must not be null");
            dependencies = List.copyOf(dependencies);
        }
    }

    /** Extracted module from Turtle specification. */
    public record ModuleSpec(String uri, String name, String packageName, List<PatternSpec> patterns) {

        public ModuleSpec {
            Objects.requireNonNull(uri, "uri must not be null");
            Objects.requireNonNull(name, "name must not be null");
            patterns = List.copyOf(patterns);
        }
    }

    /** Extracted project from Turtle specification. */
    public record ProjectSpec(
            String name,
            String groupId,
            String artifactId,
            String version,
            int javaVersion,
            String description,
            List<ModuleSpec> modules) {

        public ProjectSpec {
            Objects.requireNonNull(name, "name must not be null");
            modules = List.copyOf(modules);
        }
    }

    /**
     * Parse a Turtle project specification file and extract all patterns.
     *
     * @param turtleFile path to the .ttl file
     * @return list of pattern specifications
     */
    public static List<PatternSpec> extractPatterns(Path turtleFile) {
        Objects.requireNonNull(turtleFile, "turtleFile must not be null");
        var content = readFile(turtleFile);
        return parsePatterns(content);
    }

    /**
     * Parse a Turtle project specification file and extract project structure.
     *
     * @param turtleFile path to the .ttl file
     * @return project specification
     */
    public static ProjectSpec extractProject(Path turtleFile) {
        Objects.requireNonNull(turtleFile, "turtleFile must not be null");
        var content = readFile(turtleFile);
        return parseProject(content);
    }

    /**
     * Resolve pattern dependencies and return patterns in dependency order.
     *
     * @param patterns list of patterns to resolve
     * @return patterns sorted by dependency order
     */
    public static List<PatternSpec> resolveDependencies(List<PatternSpec> patterns) {
        Objects.requireNonNull(patterns, "patterns must not be null");

        // Build dependency graph
        var patternMap = patterns.stream()
                .collect(Collectors.toMap(p -> p.name(), p -> p, (a, b) -> a, LinkedHashMap::new));

        var result = new ArrayList<PatternSpec>();
        var visited = new LinkedHashSet<String>();
        var visiting = new LinkedHashSet<String>();

        for (var pattern : patterns) {
            topologicalSort(pattern, patternMap, visited, visiting, result);
        }

        return result;
    }

    /**
     * Extract all EIP messaging patterns from the messaging ontology.
     *
     * @return list of all messaging pattern specifications
     */
    public static List<PatternSpec> extractMessagingPatterns() {
        var messagingTtl = readFile(Path.of("schema/java-messaging.ttl"));
        return parsePatterns(messagingTtl);
    }

    // ── Error Types ──────────────────────────────────────────────────────

    /** Exception thrown when Turtle file cannot be parsed. */
    public static class TurtleParseException extends RuntimeException {
        public TurtleParseException(String message) {
            super(message);
        }

        public TurtleParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Exception thrown when Turtle file is not found. */
    public static final class TurtleFileNotFoundException extends TurtleParseException {
        public TurtleFileNotFoundException(Path path) {
            super("Turtle file not found: " + path.toAbsolutePath());
        }
    }

    /** Exception thrown when Turtle content is malformed. */
    public static final class MalformedTurtleException extends TurtleParseException {
        public MalformedTurtleException(String message, int lineNumber) {
            super("Malformed Turtle at line " + lineNumber + ": " + message);
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Validates that a Turtle file exists and is readable.
     *
     * @param turtleFile path to validate
     * @throws TurtleFileNotFoundException if file doesn't exist
     */
    public static void validateTurtleFile(Path turtleFile) {
        Objects.requireNonNull(turtleFile, "turtleFile must not be null");
        if (!Files.exists(turtleFile)) {
            throw new TurtleFileNotFoundException(turtleFile);
        }
        if (!Files.isRegularFile(turtleFile)) {
            throw new TurtleParseException("Not a regular file: " + turtleFile);
        }
        if (!turtleFile.toString().endsWith(".ttl")) {
            throw new TurtleParseException("File must have .ttl extension: " + turtleFile);
        }
    }

    /**
     * Validates Turtle content for basic structural integrity.
     *
     * @param content the Turtle content to validate
     * @throws MalformedTurtleException if content is malformed
     */
    public static void validateTurtleContent(String content) {
        Objects.requireNonNull(content, "content must not be null");

        if (content.isBlank()) {
            throw new MalformedTurtleException("Empty Turtle content", 0);
        }

        // Check for basic Turtle syntax elements
        var hasPrefix = content.contains("@prefix") || content.contains("@base");
        var hasTriples = content.lines()
                .anyMatch(line -> line.trim().matches("^[a-zA-Z]+:.*"));

        if (!hasPrefix && !hasTriples) {
            throw new MalformedTurtleException(
                    "Content does not appear to be valid Turtle (no prefixes or triples found)", 1);
        }

        // Check for unclosed strings
        var inString = false;
        var lineNumber = 0;
        for (var line : content.lines().toList()) {
            lineNumber++;
            var quoteCount = line.chars().filter(c -> c == '"').count();
            if (quoteCount % 2 != 0) {
                inString = !inString;
            }
        }
        if (inString) {
            throw new MalformedTurtleException("Unclosed string literal", lineNumber);
        }
    }

    // ── Safe Parsing Methods ───────────────────────────────────────────────

    /**
     * Parse patterns from a Turtle file with full validation.
     *
     * @param turtleFile path to the .ttl file
     * @return list of pattern specifications
     * @throws TurtleFileNotFoundException if file doesn't exist
     * @throws MalformedTurtleException if content is malformed
     */
    public static List<PatternSpec> extractPatternsSafe(Path turtleFile) {
        validateTurtleFile(turtleFile);
        var content = readFile(turtleFile);
        validateTurtleContent(content);
        return parsePatterns(content);
    }

    /**
     * Parse project from a Turtle file with full validation.
     *
     * @param turtleFile path to the .ttl file
     * @return project specification
     * @throws TurtleFileNotFoundException if file doesn't exist
     * @throws MalformedTurtleException if content is malformed
     */
    public static ProjectSpec extractProjectSafe(Path turtleFile) {
        validateTurtleFile(turtleFile);
        var content = readFile(turtleFile);
        validateTurtleContent(content);
        return parseProject(content);
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new TurtleFileNotFoundException(file);
        } catch (IOException e) {
            throw new TurtleParseException("Failed to read file: " + file, e);
        }
    }

    private static List<PatternSpec> parsePatterns(String content) {
        var patterns = new ArrayList<PatternSpec>();
        var lines = content.lines().toList();

        // Find all pattern definitions (eip:PatternName a eip:CategoryPattern ;)
        String currentUri = null;
        String currentName = null;
        String currentCategory = null;
        String currentTemplate = null;
        String currentPrimitive = null;
        int currentComplexity = 0;
        String currentIntent = null;
        var currentDeps = new ArrayList<String>();

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i).trim();

            // Pattern declaration: eip:PatternName a eip:CategoryPattern ;
            if (line.matches("^eip:\\w+\\s+a\\s+eip:\\w+.*")) {
                // Save previous pattern if exists
                if (currentUri != null && currentTemplate != null) {
                    patterns.add(new PatternSpec(
                            currentUri,
                            currentName != null ? currentName : currentUri,
                            currentCategory,
                            currentTemplate,
                            currentPrimitive,
                            currentComplexity,
                            currentIntent,
                            List.copyOf(currentDeps)));
                }

                // Start new pattern
                currentUri = line.split("\\s+")[0];
                currentName = null;
                currentCategory = extractCategory(line);
                currentTemplate = null;
                currentPrimitive = null;
                currentComplexity = 0;
                currentIntent = null;
                currentDeps.clear();
            }

            // Pattern properties
            if (currentUri != null) {
                if (line.contains("rdfs:label")) {
                    currentName = extractStringValue(line);
                } else if (line.contains("jpat:hasTemplate")) {
                    currentTemplate = extractStringValue(line);
                } else if (line.contains("eip:mapsToPrimitive")) {
                    currentPrimitive = extractPrimitiveValue(line);
                } else if (line.contains("eip:hasComplexity")) {
                    currentComplexity = extractIntValue(line);
                } else if (line.contains("jpat:hasIntent")) {
                    currentIntent = extractStringValue(line);
                } else if (line.contains("eip:requiresPattern")) {
                    currentDeps.add(extractPatternRef(line));
                }
            }
        }

        // Don't forget the last pattern
        if (currentUri != null && currentTemplate != null) {
            patterns.add(new PatternSpec(
                    currentUri,
                    currentName != null ? currentName : currentUri,
                    currentCategory,
                    currentTemplate,
                    currentPrimitive,
                    currentComplexity,
                    currentIntent,
                    List.copyOf(currentDeps)));
        }

        return patterns;
    }

    private static ProjectSpec parseProject(String content) {
        var lines = content.lines().toList();

        String projectName = null;
        String groupId = null;
        String artifactId = null;
        String version = "1.0.0-SNAPSHOT";
        int javaVersion = 26;
        String description = "";

        var modules = new ArrayList<ModuleSpec>();
        var modulePatterns = new HashMap<String, List<PatternSpec>>();

        String currentModule = null;
        String currentModuleName = null;
        String currentModulePackage = null;

        // Parse project-level properties
        for (var line : lines) {
            var trimmed = line.trim();

            // Project declaration
            if (trimmed.contains("a jproj:Project")) {
                // Extract project URI
            }

            // Project properties
            if (trimmed.contains("jproj:hasName")) {
                projectName = extractStringValue(trimmed);
            } else if (trimmed.contains("jproj:hasGroupId")) {
                groupId = extractStringValue(trimmed);
            } else if (trimmed.contains("jproj:hasVersion")) {
                version = extractStringValue(trimmed);
            } else if (trimmed.contains("jproj:hasJavaVersion")) {
                javaVersion = extractIntValue(trimmed);
            }

            // Module declaration
            if (trimmed.contains("a jproj:Module")) {
                if (currentModule != null && currentModuleName != null) {
                    var patterns = modulePatterns.getOrDefault(currentModule, List.of());
                    modules.add(new ModuleSpec(currentModule, currentModuleName, currentModulePackage, patterns));
                }
                currentModule = extractSubject(trimmed);
                currentModuleName = null;
                currentModulePackage = null;
            }

            if (currentModule != null) {
                if (trimmed.contains("rdfs:label") || trimmed.contains("jproj:hasName")) {
                    currentModuleName = extractStringValue(trimmed);
                } else if (trimmed.contains("jproj:hasPackageName")) {
                    currentModulePackage = extractStringValue(trimmed);
                } else if (trimmed.contains("jproj:usesPattern")) {
                    var patternRef = extractPatternRef(trimmed);
                    var patterns = modulePatterns.computeIfAbsent(currentModule, k -> new ArrayList<>());
                    // Add placeholder pattern - would need to resolve from ontology
                    patterns.add(new PatternSpec(
                            patternRef, patternRef, null, "messaging/" + patternRef + ".tera", null, 1, null, List.of()));
                }
            }
        }

        // Final module
        if (currentModule != null && currentModuleName != null) {
            var patterns = modulePatterns.getOrDefault(currentModule, List.of());
            modules.add(new ModuleSpec(currentModule, currentModuleName, currentModulePackage, patterns));
        }

        return new ProjectSpec(
                projectName != null ? projectName : "generated-project",
                groupId != null ? groupId : "io.github.seanchatmangpt",
                artifactId != null ? artifactId : projectName,
                version,
                javaVersion,
                description,
                modules);
    }

    private static void topologicalSort(
            PatternSpec pattern,
            Map<String, PatternSpec> patternMap,
            Set<String> visited,
            Set<String> visiting,
            List<PatternSpec> result) {

        if (visited.contains(pattern.name())) {
            return;
        }

        if (visiting.contains(pattern.name())) {
            throw new IllegalStateException("Circular dependency detected involving: " + pattern.name());
        }

        visiting.add(pattern.name());

        for (var dep : pattern.dependencies()) {
            var depPattern = patternMap.get(dep);
            if (depPattern != null) {
                topologicalSort(depPattern, patternMap, visited, visiting, result);
            }
        }

        visiting.remove(pattern.name());
        visited.add(pattern.name());
        result.add(pattern);
    }

    private static String extractCategory(String line) {
        if (line.contains("FoundationPattern")) return "foundation";
        if (line.contains("RoutingPattern")) return "routing";
        if (line.contains("OrchestrationPattern")) return "orchestration";
        if (line.contains("ResiliencePattern")) return "resilience";
        return "messaging";
    }

    private static String extractStringValue(String line) {
        // Extract value from: property "value" ;
        var start = line.indexOf('"');
        var end = line.lastIndexOf('"');
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        return null;
    }

    private static String extractPrimitiveValue(String line) {
        // Extract value from: property eip:Primitive ;
        var parts = line.split("\\s+");
        for (var part : parts) {
            if (part.startsWith("eip:")) {
                return part.replace("eip:", "").replaceAll("[;,.]$", "");
            }
        }
        return null;
    }

    private static String extractPatternRef(String line) {
        // Extract pattern reference from: property eip:PatternName ;
        var parts = line.split("\\s+");
        for (var part : parts) {
            if (part.startsWith("eip:")) {
                return part.replace("eip:", "").replaceAll("[;,.]$", "");
            }
        }
        return null;
    }

    private static int extractIntValue(String line) {
        // Extract integer from: property value ;
        var parts = line.split("\\s+");
        for (var part : parts) {
            try {
                return Integer.parseInt(part.replaceAll("[;,.]$", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String extractSubject(String line) {
        // Extract subject from: subject predicate ...
        var parts = line.split("\\s+");
        return parts.length > 0 ? parts[0] : null;
    }
}
