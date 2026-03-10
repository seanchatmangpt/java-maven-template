package org.acme.dogfood.innovation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * Template Composition Engine — composes multiple Tera templates into a coherent feature.
 *
 * <p>Instead of generating one file at a time, this engine takes a {@link FeatureRecipe} that
 * chains templates (e.g., record + sealed-interface + strategy + tests) and resolves dependencies
 * between them, producing a complete, tested feature in one shot.
 *
 * <p>Supports messaging patterns via {@link #messagingPipeline(String, List)} and
 * {@link #projectFromTurtle(Path)} for Turtle-driven project generation.
 */
public final class TemplateCompositionEngine {

    /** Reference to a single Tera template with its variable bindings. */
    public record TemplateRef(String category, String name, Map<String, String> vars) {

        public TemplateRef {
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(name, "name must not be null");
            vars = Map.copyOf(vars);
            if (category.isBlank()) {
                throw new IllegalArgumentException("category must not be blank");
            }
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        /** Convenience factory with no extra variables. */
        public static TemplateRef of(String category, String name) {
            return new TemplateRef(category, name, Map.of());
        }

        /** Convenience factory with a single variable. */
        public static TemplateRef of(String category, String name, String key, String value) {
            return new TemplateRef(category, name, Map.of(key, value));
        }

        /** Returns the relative path to the .tera file under the templates root. */
        public String templatePath() {
            return "java/%s/%s.tera".formatted(category, name);
        }
    }

    /**
     * A recipe for composing multiple templates into a single feature.
     *
     * @param name short feature name (e.g. "UserManagement")
     * @param description human-readable description
     * @param templates ordered list of templates to compose
     * @param dependencies inter-template dependencies expressed as "category/name" strings
     */
    public record FeatureRecipe(
            String name,
            String description,
            List<TemplateRef> templates,
            List<String> dependencies) {

        public FeatureRecipe {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(description, "description must not be null");
            templates = List.copyOf(templates);
            dependencies = List.copyOf(dependencies);
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (templates.isEmpty()) {
                throw new IllegalArgumentException("templates must not be empty");
            }
        }
    }

    /** Outcome of composing a feature recipe. */
    public sealed interface CompositionResult {

        record Success(
                String featureName,
                List<String> generatedFiles,
                SequencedMap<String, Map<String, String>> resolvedVariables)
                implements CompositionResult {

            public Success {
                Objects.requireNonNull(featureName, "featureName must not be null");
                generatedFiles = List.copyOf(generatedFiles);
                resolvedVariables = java.util.Collections.unmodifiableSequencedMap(
                        new LinkedHashMap<>(resolvedVariables));
            }
        }

        record Failure(String featureName, List<String> errors) implements CompositionResult {

            public Failure {
                Objects.requireNonNull(featureName, "featureName must not be null");
                errors = List.copyOf(errors);
            }
        }
    }

    private final Path templatesRoot;

    /**
     * Creates an engine rooted at the given templates directory.
     *
     * @param templatesRoot path to the directory containing {@code java/} template tree
     */
    public TemplateCompositionEngine(Path templatesRoot) {
        this.templatesRoot = Objects.requireNonNull(templatesRoot, "templatesRoot must not be null");
    }

    /**
     * Validates a recipe, resolves variable dependencies, and reports the ordered list of files
     * that would be generated.
     */
    public CompositionResult compose(FeatureRecipe recipe) {
        var errors = new ArrayList<String>();

        // Phase 1: validate that every referenced template exists on disk
        for (var ref : recipe.templates()) {
            var templateFile = templatesRoot.resolve(ref.templatePath());
            if (!Files.isRegularFile(templateFile)) {
                errors.add("Template not found: %s (expected at %s)".formatted(
                        ref.category() + "/" + ref.name(), templateFile));
            }
        }

        // Phase 2: validate declared dependencies reference templates in this recipe
        var templateKeys = recipe.templates().stream()
                .map(t -> t.category() + "/" + t.name())
                .collect(Collectors.toSet());
        for (var dep : recipe.dependencies()) {
            if (!templateKeys.contains(dep)) {
                errors.add("Dependency '%s' does not match any template in the recipe".formatted(dep));
            }
        }

        if (!errors.isEmpty()) {
            return new CompositionResult.Failure(recipe.name(), errors);
        }

        // Phase 3: resolve variables — propagate shared variables across templates
        var resolvedVars = new LinkedHashMap<String, Map<String, String>>();
        var sharedContext = new HashMap<String, String>();

        // Seed shared context with all explicit variables from every template
        for (var ref : recipe.templates()) {
            sharedContext.putAll(ref.vars());
        }

        // Build per-template resolved variable maps
        for (var ref : recipe.templates()) {
            var merged = new LinkedHashMap<>(sharedContext);
            merged.putAll(ref.vars()); // template-specific vars win
            resolvedVars.put(ref.category() + "/" + ref.name(), Map.copyOf(merged));
        }

        // Phase 4: compute the ordered list of files that would be generated
        var generatedFiles = recipe.templates().stream()
                .map(ref -> outputFilePath(ref, sharedContext))
                .toList();

        return new CompositionResult.Success(recipe.name(), generatedFiles, resolvedVars);
    }

    // --------------- built-in recipe factories ---------------

    /**
     * Creates a CRUD feature recipe for the given entity: record + repository + service + DTO +
     * JUnit test + property-based test + ArchUnit test.
     */
    public static FeatureRecipe crudFeature(String entityName) {
        Objects.requireNonNull(entityName, "entityName must not be null");
        var pkg = "org.acme.domain";
        var vars = Map.of("entity_name", entityName, "package", pkg);

        return new FeatureRecipe(
                entityName + "Crud",
                "Full CRUD feature for " + entityName
                        + " with record, repository, service, DTO, and tests",
                List.of(
                        new TemplateRef("core", "record", vars),
                        new TemplateRef("patterns", "repository-generic", vars),
                        new TemplateRef("patterns", "service-layer", vars),
                        new TemplateRef("patterns", "dto-record", vars),
                        new TemplateRef("testing", "junit5-test", vars),
                        new TemplateRef("testing", "property-based-jqwik", vars),
                        new TemplateRef("testing", "archunit-rules", vars)),
                List.of("core/record", "patterns/repository-generic"));
    }

    /**
     * Creates a value-object feature recipe: record + builder + validation + JUnit test + property
     * test.
     */
    public static FeatureRecipe valueObjectFeature(String name) {
        Objects.requireNonNull(name, "name must not be null");
        var pkg = "org.acme.domain.vo";
        var vars = Map.of("name", name, "package", pkg);

        return new FeatureRecipe(
                name + "ValueObject",
                "Value object " + name + " with record, builder, validation, and tests",
                List.of(
                        new TemplateRef("core", "record", vars),
                        new TemplateRef("patterns", "value-object-record", vars),
                        new TemplateRef("patterns", "builder-record", vars),
                        new TemplateRef("testing", "junit5-test", vars),
                        new TemplateRef("testing", "property-based-jqwik", vars)),
                List.of("core/record"));
    }

    /**
     * Creates a service-layer feature recipe: service + strategy + sealed error type + JUnit test +
     * integration test + ArchUnit rules.
     */
    public static FeatureRecipe serviceLayerFeature(String name) {
        Objects.requireNonNull(name, "name must not be null");
        var pkg = "org.acme.service";
        var vars = Map.of("service_name", name, "package", pkg);

        return new FeatureRecipe(
                name + "Service",
                "Service layer for " + name
                        + " with strategy pattern, sealed error handling, and tests",
                List.of(
                        new TemplateRef("patterns", "service-layer", vars),
                        new TemplateRef("patterns", "strategy-functional", vars),
                        new TemplateRef("core", "sealed-interface", vars),
                        new TemplateRef("error-handling", "result-railway", vars),
                        new TemplateRef("testing", "junit5-test", vars),
                        new TemplateRef("testing", "integration-test", vars),
                        new TemplateRef("testing", "archunit-rules", vars)),
                List.of("patterns/service-layer", "core/sealed-interface"));
    }

    // --------------- messaging pattern recipes ---------------

    /**
     * Creates a reliable messaging pipeline: Resequencer -> Content-Based Router -> Dead Letter.
     *
     * <p>This is a complete EIP pattern chain for reliable message processing with:
     * <ul>
     *   <li>Resequencer - reorders out-of-sequence messages</li>
     *   <li>Content-Based Router - routes by message content</li>
     *   <li>Dead Letter Channel - handles failed messages</li>
     *   <li>Domain Types - canonical message types</li>
     *   <li>Tests - messaging pattern tests</li>
     * </ul>
     *
     * @param domain the domain name (e.g., "Telemetry", "Order")
     * @param messageTypes list of message type names
     */
    public static FeatureRecipe reliableMessagingPipeline(String domain, List<String> messageTypes) {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(messageTypes, "messageTypes must not be null");
        var pkg = "org.acme." + domain.toLowerCase() + ".messaging";
        var vars = Map.of(
                "name", domain,
                "package", pkg,
                "message_type", domain + "Msg");

        return new FeatureRecipe(
                domain + "ReliableMessaging",
                "Reliable messaging pipeline for " + domain + " with routing and error handling",
                List.of(
                        new TemplateRef("messaging", "domain-types", vars),
                        new TemplateRef("messaging", "canonical-message", vars),
                        new TemplateRef("messaging", "resequencer", vars),
                        new TemplateRef("messaging", "content-based-router", vars),
                        new TemplateRef("messaging", "dead-letter-channel", vars),
                        new TemplateRef("messaging", "wire-tap", vars),
                        new TemplateRef("testing", "messaging-test", vars)),
                List.of("messaging/domain-types", "messaging/canonical-message"));
    }

    /**
     * Creates a complete event-driven messaging system: Message Bus + Pub-Sub + Correlation.
     *
     * @param domain the domain name
     */
    public static FeatureRecipe eventDrivenMessaging(String domain) {
        Objects.requireNonNull(domain, "domain must not be null");
        var pkg = "org.acme." + domain.toLowerCase() + ".messaging";
        var vars = Map.of(
                "name", domain,
                "package", pkg,
                "message_type", domain + "Msg");

        return new FeatureRecipe(
                domain + "EventDriven",
                "Event-driven messaging system for " + domain,
                List.of(
                        new TemplateRef("messaging", "domain-types", vars),
                        new TemplateRef("messaging", "message-bus", vars),
                        new TemplateRef("messaging", "pub-sub", vars),
                        new TemplateRef("messaging", "correlation-identifier", vars),
                        new TemplateRef("messaging", "service-activator", vars),
                        new TemplateRef("testing", "messaging-test", vars)),
                List.of("messaging/domain-types"));
    }

    /**
     * Creates a supervised messaging system with fault tolerance.
     *
     * @param domain the domain name
     */
    public static FeatureRecipe supervisedMessaging(String domain) {
        Objects.requireNonNull(domain, "domain must not be null");
        var pkg = "org.acme." + domain.toLowerCase() + ".messaging";
        var vars = Map.of(
                "name", domain,
                "package", pkg,
                "message_type", domain + "Msg");

        return new FeatureRecipe(
                domain + "SupervisedMessaging",
                "Supervised messaging system for " + domain + " with fault tolerance",
                List.of(
                        new TemplateRef("messaging", "domain-types", vars),
                        new TemplateRef("messaging", "supervision-storm", vars),
                        new TemplateRef("messaging", "dead-letter-channel", vars),
                        new TemplateRef("messaging", "idempotent-receiver", vars),
                        new TemplateRef("messaging", "control-bus", vars),
                        new TemplateRef("testing", "messaging-test", vars)),
                List.of("messaging/domain-types", "messaging/supervision-storm"));
    }

    /**
     * Creates an orchestration messaging system with Process Manager and Scatter-Gather.
     *
     * @param domain the domain name
     */
    public static FeatureRecipe orchestrationMessaging(String domain) {
        Objects.requireNonNull(domain, "domain must not be null");
        var pkg = "org.acme." + domain.toLowerCase() + ".messaging";
        var vars = Map.of(
                "name", domain,
                "package", pkg,
                "message_type", domain + "Msg");

        return new FeatureRecipe(
                domain + "Orchestration",
                "Orchestration messaging system for " + domain + " with saga management",
                List.of(
                        new TemplateRef("messaging", "domain-types", vars),
                        new TemplateRef("messaging", "process-manager", vars),
                        new TemplateRef("messaging", "correlation-identifier", vars),
                        new TemplateRef("messaging", "routing-slip", vars),
                        new TemplateRef("messaging", "scatter-gather", vars),
                        new TemplateRef("testing", "messaging-test", vars)),
                List.of("messaging/domain-types", "messaging/process-manager"));
    }

    /**
     * Creates a messaging feature from a Turtle specification file.
     *
     * <p>Reads the Turtle file, extracts patterns, resolves dependencies,
     * and creates a feature recipe with all required templates.
     *
     * @param turtleFile path to the .ttl specification file
     */
    public static FeatureRecipe messagingFromTurtle(Path turtleFile) {
        Objects.requireNonNull(turtleFile, "turtleFile must not be null");

        var patterns = TurtleParser.extractPatterns(turtleFile);
        var resolved = TurtleParser.resolveDependencies(patterns);

        var templates = new ArrayList<TemplateRef>();
        var deps = new ArrayList<String>();

        for (var pattern : resolved) {
            var category = pattern.category() != null ? pattern.category() : "messaging";
            var templateName = extractTemplateName(pattern.template());
            var vars = Map.of(
                    "name", pattern.name().replaceAll("\\s+", ""),
                    "package", "org.acme.messaging",
                    "message_type", "Object");

            templates.add(new TemplateRef(category, templateName, vars));

            if (pattern.dependencies() != null && !pattern.dependencies().isEmpty()) {
                for (var dep : pattern.dependencies()) {
                    deps.add(category + "/" + dep.toLowerCase().replace("-", ""));
                }
            }
        }

        // Add test template
        templates.add(new TemplateRef("testing", "messaging-test", Map.of(
                "name", "Generated",
                "package", "org.acme.messaging.test")));

        return new FeatureRecipe(
                "TurtleMessaging",
                "Messaging system generated from Turtle specification",
                templates,
                deps);
    }

    /**
     * Creates a complete project from a Turtle specification file.
     *
     * <p>Reads the Turtle file, extracts project structure and patterns,
     * and creates a comprehensive feature recipe for the entire project.
     *
     * @param turtleFile path to the .ttl specification file
     */
    public static FeatureRecipe projectFromTurtle(Path turtleFile) {
        Objects.requireNonNull(turtleFile, "turtleFile must not be null");

        var project = TurtleParser.extractProject(turtleFile);
        var templates = new ArrayList<TemplateRef>();
        var deps = new ArrayList<String>();

        // Add module-info template
        templates.add(new TemplateRef("modules", "module-info", Map.of(
                "module_name", project.name().replace("-", "."),
                "exports", project.groupId() + "." + project.name())));

        // Add patterns from all modules
        for (var module : project.modules()) {
            var pkg = module.packageName() != null ? module.packageName() : project.groupId();
            var vars = Map.of("package", pkg);

            for (var pattern : module.patterns()) {
                var templateName = extractTemplateName(pattern.template());
                templates.add(new TemplateRef("messaging", templateName, vars));
            }
        }

        // Add build templates
        templates.add(new TemplateRef("build", "pom-java26", Map.of(
                "project_name", project.name(),
                "group_id", project.groupId(),
                "version", project.version())));

        return new FeatureRecipe(
                project.name(),
                project.description() != null ? project.description() : "Project from Turtle",
                templates,
                deps);
    }

    // --------------- private helpers ---------------

    private static String extractTemplateName(String templatePath) {
        if (templatePath == null) return "unknown";
        // Extract "template-name" from "messaging/template-name.tera"
        var parts = templatePath.split("/");
        if (parts.length >= 2) {
            var fileName = parts[parts.length - 1];
            return fileName.replace(".tera", "");
        }
        return templatePath.replace(".tera", "");
    }

    /**
     * Derives the output file path for a template reference. The path encodes category and
     * template name, qualified by entity/service name from variables.
     */
    private String outputFilePath(TemplateRef ref, Map<String, String> context) {
        var qualifiedName = java.util.stream.Stream.of("entity_name", "name", "service_name")
                .filter(context::containsKey)
                .findFirst()
                .map(context::get)
                .orElse("Generated");

        var suffix = deriveSuffix(ref);
        var pkgPath = context.getOrDefault("package", "org.acme").replace('.', '/');

        return "src/main/java/%s/%s%s.java".formatted(pkgPath, qualifiedName, suffix);
    }

    /**
     * Maps a template category/name combination to a meaningful file-name suffix so that multiple
     * templates composing a feature do not collide.
     */
    private String deriveSuffix(TemplateRef ref) {
        return switch (ref.category()) {
            case "core" -> switch (ref.name()) {
                case "record" -> "";
                case "sealed-interface" -> "Error";
                case "sealed-class" -> "Type";
                default -> capitalize(ref.name());
            };
            case "patterns" -> switch (ref.name()) {
                case "repository-generic" -> "Repository";
                case "service-layer" -> "Service";
                case "dto-record" -> "Dto";
                case "value-object-record" -> "Value";
                case "builder-record" -> "Builder";
                case "strategy-functional" -> "Strategy";
                case "factory-sealed" -> "Factory";
                default -> capitalize(ref.name());
            };
            case "messaging" -> switch (ref.name()) {
                case "message-bus" -> "Bus";
                case "content-based-router" -> "Router";
                case "dead-letter-channel" -> "DeadLetter";
                case "wire-tap" -> "WireTap";
                case "routing-slip" -> "RoutingSlip";
                case "process-manager" -> "ProcessManager";
                case "correlation-identifier" -> "Correlation";
                case "pub-sub" -> "PubSub";
                case "scatter-gather" -> "ScatterGather";
                case "supervision-storm" -> "Supervisor";
                case "control-bus" -> "ControlBus";
                case "domain-types" -> "Domain";
                case "canonical-message" -> "Message";
                case "resequencer" -> "Resequencer";
                case "service-activator" -> "Activator";
                case "idempotent-receiver" -> "Idempotent";
                case "durable-subscriber" -> "DurableSubscriber";
                case "datatype-channel" -> "Channel";
                default -> capitalize(ref.name());
            };
            case "testing" -> switch (ref.name()) {
                case "junit5-test", "junit5-nested", "junit5-parameterized" -> "Test";
                case "property-based-jqwik" -> "PropertyTest";
                case "archunit-rules" -> "ArchTest";
                case "integration-test" -> "IT";
                case "assertj-assertions" -> "Assertions";
                case "instancio-data" -> "DataFactory";
                case "messaging-test" -> "MessagingTest";
                default -> capitalize(ref.name()) + "Test";
            };
            case "error-handling" -> switch (ref.name()) {
                case "result-railway" -> "Result";
                default -> capitalize(ref.name());
            };
            default -> capitalize(ref.name());
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
