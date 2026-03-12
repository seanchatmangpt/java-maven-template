package io.github.seanchatmangpt.jotp.dogfood.innovation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ontology-driven migration engine that analyzes legacy Java source code and identifies
 * modernization opportunities based on the RDF migration ontology ({@code schema/java-migration.ttl}).
 *
 * <p>Instead of hard-coded rules, this engine models every migration as a {@link MigrationRule}
 * record derived from the ontology's {@code jmig:MigrationRule} class. Source analysis uses
 * pattern-matching heuristics that mirror what an AI agent would extract via SPARQL queries
 * against the knowledge graph.
 *
 * <p>The sealed {@link MigrationPlan} hierarchy represents the output: a typed, exhaustive
 * description of recommended migrations that downstream code generators (Tera templates)
 * can consume.
 */
public final class OntologyMigrationEngine {

    private OntologyMigrationEngine() {}

    // -------------------------------------------------------------------------
    // Migration categories — mirrors jmig:hasCategory values
    // -------------------------------------------------------------------------

    /** Categories defined in the ontology. */
    public enum Category {
        LANGUAGE,
        CONCURRENCY,
        API,
        TESTING,
        ERROR_HANDLING,
        MIGRATION
    }

    // -------------------------------------------------------------------------
    // MigrationRule — corresponds to jmig:MigrationRule instances in the TTL
    // -------------------------------------------------------------------------

    /**
     * A migration rule extracted from the ontology. Each field corresponds to an RDF predicate:
     *
     * <ul>
     *   <li>{@code id} — the local name of the OWL individual (e.g. {@code POJOToRecord})
     *   <li>{@code label} — {@code rdfs:label}
     *   <li>{@code sourcePattern} — {@code jmig:hasSource}
     *   <li>{@code targetTemplate} — {@code jmig:hasTarget}
     *   <li>{@code category} — {@code jmig:hasCategory}
     *   <li>{@code priority} — {@code jmig:hasPriority} (1 = highest)
     *   <li>{@code breaking} — {@code jmig:isBreaking}
     * </ul>
     */
    public record MigrationRule(
            String id,
            String label,
            String sourcePattern,
            String targetTemplate,
            Category category,
            int priority,
            boolean breaking) {

        public MigrationRule {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(sourcePattern, "sourcePattern must not be null");
            Objects.requireNonNull(targetTemplate, "targetTemplate must not be null");
            Objects.requireNonNull(category, "category must not be null");
            if (priority < 1 || priority > 10) {
                throw new IllegalArgumentException("priority must be 1..10, got " + priority);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SourceSignal — evidence found in source code
    // -------------------------------------------------------------------------

    /**
     * A signal detected in the source code that triggers a migration rule. Contains the matched
     * text and its approximate line number.
     */
    public record SourceSignal(String matchedText, int lineNumber) {

        public SourceSignal {
            Objects.requireNonNull(matchedText, "matchedText must not be null");
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be >= 1, got " + lineNumber);
            }
        }
    }

    // -------------------------------------------------------------------------
    // MigrationPlan — sealed hierarchy for plan items
    // -------------------------------------------------------------------------

    /**
     * A sealed hierarchy describing a single migration opportunity. Pattern matching with {@code
     * switch} can exhaustively handle all cases.
     */
    public sealed interface MigrationPlan {

        /** The rule that produced this plan. */
        MigrationRule rule();

        /** Evidence from the source code. */
        List<SourceSignal> signals();

        /** A POJO class can be replaced with a record. */
        record RecordMigration(MigrationRule rule, List<SourceSignal> signals, List<String> fields)
                implements MigrationPlan {}

        /** An anonymous class implementing a functional interface can become a lambda. */
        record LambdaMigration(
                MigrationRule rule, List<SourceSignal> signals, String functionalInterface)
                implements MigrationPlan {}

        /** A traditional instanceof + cast can become a pattern match. */
        record PatternMatchMigration(
                MigrationRule rule, List<SourceSignal> signals, String targetType)
                implements MigrationPlan {}

        /** An if/else chain can become a switch expression. */
        record SwitchExpressionMigration(
                MigrationRule rule, List<SourceSignal> signals, int branchCount)
                implements MigrationPlan {}

        /** A platform thread or thread pool can be replaced with virtual threads. */
        record VirtualThreadMigration(
                MigrationRule rule, List<SourceSignal> signals, String threadCreationPattern)
                implements MigrationPlan {}

        /** Legacy date/calendar usage can migrate to java.time. */
        record JavaTimeMigration(
                MigrationRule rule, List<SourceSignal> signals, Set<String> legacyTypes)
                implements MigrationPlan {}

        /** Legacy java.io.File usage can migrate to java.nio.file. */
        record NioMigration(MigrationRule rule, List<SourceSignal> signals, Set<String> legacyTypes)
                implements MigrationPlan {}

        /** A for-loop that filters/maps/collects can become a stream pipeline. */
        record StreamMigration(MigrationRule rule, List<SourceSignal> signals)
                implements MigrationPlan {}

        /** A generic migration that doesn't need extra metadata. */
        record GenericMigration(MigrationRule rule, List<SourceSignal> signals, String description)
                implements MigrationPlan {}
    }

    // -------------------------------------------------------------------------
    // AnalysisResult — aggregate output
    // -------------------------------------------------------------------------

    /** The complete analysis result for a single source file. */
    public record AnalysisResult(String fileName, List<MigrationPlan> plans) {

        public AnalysisResult {
            Objects.requireNonNull(fileName, "fileName must not be null");
            plans = List.copyOf(plans);
        }

        /** Returns only non-breaking migrations. */
        public List<MigrationPlan> safeUpgrades() {
            return plans.stream().filter(p -> !p.rule().breaking()).toList();
        }

        /** Returns only breaking migrations. */
        public List<MigrationPlan> breakingChanges() {
            return plans.stream().filter(p -> p.rule().breaking()).toList();
        }

        /** Plans sorted by rule priority (1 = highest). */
        public List<MigrationPlan> byPriority() {
            return plans.stream()
                    .sorted(Comparator.comparingInt(p -> p.rule().priority()))
                    .toList();
        }

        /** Summarise the analysis for display. */
        public String summary() {
            if (plans.isEmpty()) {
                return fileName + ": no migration opportunities found.";
            }
            var sb = new StringBuilder();
            sb.append(fileName)
                    .append(": ")
                    .append(plans.size())
                    .append(" migration(s) found\n");
            for (var plan : byPriority()) {
                sb.append("  [P")
                        .append(plan.rule().priority())
                        .append(plan.rule().breaking() ? " BREAKING" : "")
                        .append("] ")
                        .append(plan.rule().label())
                        .append(" (")
                        .append(plan.signals().size())
                        .append(" signal(s), template: ")
                        .append(plan.rule().targetTemplate())
                        .append(")\n");
                descriptionForPlan(plan)
                        .ifPresent(desc -> sb.append("    -> ").append(desc).append("\n"));
            }
            return sb.toString();
        }

        private static java.util.Optional<String> descriptionForPlan(MigrationPlan plan) {
            return switch (plan) {
                case MigrationPlan.RecordMigration r ->
                        java.util.Optional.of("fields: " + String.join(", ", r.fields()));
                case MigrationPlan.LambdaMigration l ->
                        java.util.Optional.of("interface: " + l.functionalInterface());
                case MigrationPlan.PatternMatchMigration p ->
                        java.util.Optional.of("cast to: " + p.targetType());
                case MigrationPlan.SwitchExpressionMigration s ->
                        java.util.Optional.of(s.branchCount() + " branches");
                case MigrationPlan.VirtualThreadMigration v ->
                        java.util.Optional.of("pattern: " + v.threadCreationPattern());
                case MigrationPlan.JavaTimeMigration j ->
                        java.util.Optional.of("types: " + j.legacyTypes());
                case MigrationPlan.NioMigration n ->
                        java.util.Optional.of("types: " + n.legacyTypes());
                case MigrationPlan.StreamMigration ignored -> java.util.Optional.empty();
                case MigrationPlan.GenericMigration g ->
                        java.util.Optional.of(g.description());
            };
        }
    }

    // =========================================================================
    // Ontology-modelled rules (mirrors schema/java-migration.ttl individuals)
    // =========================================================================

    private static final MigrationRule POJO_TO_RECORD =
            new MigrationRule(
                    "POJOToRecord",
                    "POJO \u2192 Record",
                    "Class with fields + getters + equals + hashCode + toString",
                    "core/record.tera",
                    Category.LANGUAGE,
                    1,
                    true);

    private static final MigrationRule ANON_CLASS_TO_LAMBDA =
            new MigrationRule(
                    "AnonymousClassToLambda",
                    "Anonymous Class \u2192 Lambda",
                    "Anonymous inner class implementing functional interface",
                    "core/lambda-conversion.tera",
                    Category.LANGUAGE,
                    1,
                    false);

    private static final MigrationRule INSTANCEOF_TO_PATTERN =
            new MigrationRule(
                    "InstanceofCastToPattern",
                    "instanceof + Cast \u2192 Pattern Matching",
                    "if (x instanceof Foo) { Foo f = (Foo) x; ... }",
                    "core/instanceof-pattern.tera",
                    Category.LANGUAGE,
                    1,
                    false);

    private static final MigrationRule IF_ELSE_TO_SWITCH =
            new MigrationRule(
                    "IfElseChainToSwitch",
                    "if/else Chain \u2192 Switch Expression",
                    "if/else chain dispatching on type or value",
                    "core/pattern-matching-switch.tera",
                    Category.LANGUAGE,
                    2,
                    false);

    private static final MigrationRule FOR_LOOP_TO_STREAM =
            new MigrationRule(
                    "ForLoopToStream",
                    "For Loop \u2192 Stream Pipeline",
                    "Traditional for loop with filter/map/collect",
                    "core/stream-pipeline.tera",
                    Category.LANGUAGE,
                    2,
                    false);

    private static final MigrationRule PLATFORM_TO_VIRTUAL =
            new MigrationRule(
                    "PlatformThreadToVirtual",
                    "Platform Thread \u2192 Virtual Thread",
                    "new Thread(runnable).start(), Thread subclass",
                    "concurrency/virtual-thread.tera",
                    Category.CONCURRENCY,
                    1,
                    false);

    private static final MigrationRule THREAD_POOL_TO_VIRTUAL =
            new MigrationRule(
                    "ThreadPoolToVirtualExecutor",
                    "Thread Pool \u2192 Virtual Thread Executor",
                    "Executors.newFixedThreadPool(n)",
                    "concurrency/virtual-thread-executor.tera",
                    Category.CONCURRENCY,
                    1,
                    false);

    private static final MigrationRule DATE_TO_JAVA_TIME =
            new MigrationRule(
                    "DateCalendarToJavaTime",
                    "Date/Calendar \u2192 java.time",
                    "java.util.Date, java.util.Calendar usage",
                    "api/java-time.tera",
                    Category.API,
                    1,
                    true);

    private static final MigrationRule FILE_TO_NIO =
            new MigrationRule(
                    "FileIOToNIO2",
                    "java.io.File \u2192 java.nio.file",
                    "java.io.File, FileInputStream, FileOutputStream",
                    "api/nio2-files.tera",
                    Category.API,
                    2,
                    true);

    private static final MigrationRule JUNIT4_TO_JUNIT5 =
            new MigrationRule(
                    "JUnit4ToJUnit5",
                    "JUnit 4 \u2192 JUnit 5",
                    "@org.junit.Test, @RunWith, @Rule",
                    "testing/junit5-test.tera",
                    Category.TESTING,
                    1,
                    true);

    private static final MigrationRule JAVAX_TO_JAKARTA =
            new MigrationRule(
                    "JavaEEToJakarta",
                    "javax.* \u2192 jakarta.*",
                    "javax.servlet, javax.persistence, javax.annotation",
                    "security/jakarta-migration.tera",
                    Category.MIGRATION,
                    1,
                    true);

    private static final MigrationRule NULL_CHECK_TO_OPTIONAL =
            new MigrationRule(
                    "NullCheckToOptional",
                    "Null Check \u2192 Optional",
                    "if (x != null) chains, ternary null checks",
                    "core/optional-chain.tera",
                    Category.LANGUAGE,
                    2,
                    true);

    // ── OTP-specific rules ────────────────────────────────────────────────────

    private static final MigrationRule STATIC_STATE_TO_PROC =
            new MigrationRule(
                    "StaticStateToProc",
                    "Static Shared State \u2192 Proc",
                    "static Map/List/Set fields — shared mutable state across threads",
                    "patterns/actor",
                    Category.CONCURRENCY,
                    1,
                    true);

    private static final MigrationRule THREAD_LOCAL_TO_SCOPED_VALUE =
            new MigrationRule(
                    "ThreadLocalToScopedValue",
                    "ThreadLocal \u2192 ScopedValue",
                    "ThreadLocal<T> — implicit mutable context that leaks across virtual threads",
                    "concurrency/scoped-value",
                    Category.CONCURRENCY,
                    1,
                    true);

    private static final MigrationRule CATCH_TO_CRASH_RECOVERY =
            new MigrationRule(
                    "CatchToCrashRecovery",
                    "Swallowed Catch \u2192 CrashRecovery",
                    "catch block that logs, returns null/false, or is empty — silent failure",
                    "error-handling/result-railway",
                    Category.ERROR_HANDLING,
                    1,
                    false);

    /** All ontology rules, available for inspection and custom filtering. */
    public static List<MigrationRule> allRules() {
        return List.of(
                POJO_TO_RECORD,
                ANON_CLASS_TO_LAMBDA,
                INSTANCEOF_TO_PATTERN,
                IF_ELSE_TO_SWITCH,
                FOR_LOOP_TO_STREAM,
                PLATFORM_TO_VIRTUAL,
                THREAD_POOL_TO_VIRTUAL,
                DATE_TO_JAVA_TIME,
                FILE_TO_NIO,
                JUNIT4_TO_JUNIT5,
                JAVAX_TO_JAKARTA,
                NULL_CHECK_TO_OPTIONAL,
                STATIC_STATE_TO_PROC,
                THREAD_LOCAL_TO_SCOPED_VALUE,
                CATCH_TO_CRASH_RECOVERY);
    }

    // =========================================================================
    // Source analysis — detectors for each ontology rule
    // =========================================================================

    // Regex patterns used by detectors
    private static final Pattern PRIVATE_FIELD =
            Pattern.compile("^\\s*private\\s+(final\\s+)?\\w[\\w<>,\\s]*\\s+(\\w+)\\s*;",
                    Pattern.MULTILINE);
    private static final Pattern GETTER =
            Pattern.compile("public\\s+\\w+\\s+get(\\w+)\\s*\\(\\s*\\)");
    private static final Pattern EQUALS_METHOD =
            Pattern.compile("public\\s+boolean\\s+equals\\s*\\(");
    private static final Pattern HASHCODE_METHOD =
            Pattern.compile("public\\s+int\\s+hashCode\\s*\\(");
    private static final Pattern TOSTRING_METHOD =
            Pattern.compile("public\\s+String\\s+toString\\s*\\(");

    private static final Pattern ANON_CLASS =
            Pattern.compile("new\\s+(\\w+)\\s*\\(\\s*\\)\\s*\\{");

    private static final Pattern INSTANCEOF_CAST =
            Pattern.compile(
                    "instanceof\\s+(\\w+)\\b(?!\\s+\\w+\\s*[)&|])");

    private static final Pattern IF_ELSE_CHAIN =
            Pattern.compile("\\}\\s*else\\s+if\\s*\\(");

    private static final Pattern FOR_LOOP =
            Pattern.compile("for\\s*\\(\\s*(\\w+)\\s+\\w+\\s*:\\s*");

    private static final Pattern NEW_THREAD =
            Pattern.compile("new\\s+Thread\\s*\\(");
    private static final Pattern THREAD_SUBCLASS =
            Pattern.compile("extends\\s+Thread\\b");

    private static final Pattern FIXED_THREAD_POOL =
            Pattern.compile("Executors\\s*\\.\\s*new(Fixed|Cached|Single)ThreadPool");

    private static final Pattern LEGACY_DATE =
            Pattern.compile("\\b(java\\.util\\.)?(Date|Calendar|GregorianCalendar|SimpleDateFormat)\\b");

    private static final Pattern LEGACY_FILE =
            Pattern.compile("\\b(java\\.io\\.)?(File|FileInputStream|FileOutputStream|FileReader|FileWriter)\\b");

    private static final Pattern JUNIT4_ANNOTATION =
            Pattern.compile("@(org\\.junit\\.)?Test\\b|@RunWith\\b|@Rule\\b");
    private static final Pattern JUNIT4_IMPORT =
            Pattern.compile("import\\s+org\\.junit\\.(Test|Before|After|Rule|RunWith)\\b");

    private static final Pattern JAVAX_IMPORT =
            Pattern.compile("import\\s+javax\\.(servlet|persistence|annotation|inject|ws)\\b");

    private static final Pattern NULL_CHECK =
            Pattern.compile("(!= null|== null)");

    // OTP-rule patterns
    private static final Pattern STATIC_MUTABLE_COLLECTION =
            Pattern.compile(
                    "static\\s+(?:final\\s+)?(?:Map|HashMap|LinkedHashMap|ConcurrentHashMap"
                            + "|List|ArrayList|LinkedList|Set|HashSet)\\s*[<\\s]");

    private static final Pattern THREAD_LOCAL =
            Pattern.compile("\\bThreadLocal\\s*<");

    private static final Pattern SWALLOWED_CATCH =
            Pattern.compile(
                    "catch\\s*\\([^)]+\\)\\s*\\{[^{}]*"
                            + "(?:return\\s+(?:false|null|Optional\\.empty\\(\\))"
                            + "|(?:log|logger|LOG|LOGGER)\\.[a-z]+\\()"
                            + "|catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}",
                    Pattern.DOTALL);

    /**
     * Analyzes a Java source file (provided as a string) and returns all migration opportunities
     * found. This method models the behavior of an AI agent that reads source code, maps it onto
     * the ontology, and identifies applicable migration rules via SPARQL-like queries.
     *
     * @param fileName the source file name (for reporting)
     * @param source the full Java source code as a string
     * @return an {@link AnalysisResult} containing all discovered migration plans
     */
    public static AnalysisResult analyze(String fileName, String source) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(source, "source must not be null");

        var lines = source.split("\n", -1);
        var plans = new ArrayList<MigrationPlan>();

        detectPojoToRecord(source, lines).ifPresent(plans::add);
        detectAnonymousClassToLambda(source, lines).ifPresent(plans::add);
        detectInstanceofCast(source, lines).ifPresent(plans::add);
        detectIfElseChain(source, lines).ifPresent(plans::add);
        detectForLoopToStream(source, lines).ifPresent(plans::add);
        detectPlatformThread(source, lines).ifPresent(plans::add);
        detectThreadPool(source, lines).ifPresent(plans::add);
        detectLegacyDate(source, lines).ifPresent(plans::add);
        detectLegacyFile(source, lines).ifPresent(plans::add);
        detectJUnit4(source, lines).ifPresent(plans::add);
        detectJavaxImports(source, lines).ifPresent(plans::add);
        detectNullChecks(source, lines).ifPresent(plans::add);
        detectStaticMutableState(source, lines).ifPresent(plans::add);
        detectThreadLocal(source, lines).ifPresent(plans::add);
        detectSwallowedCatch(source).ifPresent(plans::add);

        return new AnalysisResult(fileName, plans);
    }

    /**
     * Analyzes a source file with a category filter. Only rules in the given categories are
     * checked — mirrors a SPARQL {@code FILTER} on {@code jmig:hasCategory}.
     */
    public static AnalysisResult analyze(
            String fileName, String source, Set<Category> categories) {
        var full = analyze(fileName, source);
        var filtered =
                full.plans().stream()
                        .filter(p -> categories.contains(p.rule().category()))
                        .toList();
        return new AnalysisResult(fileName, filtered);
    }

    /**
     * Formats a {@link MigrationPlan} into a human-readable description using exhaustive pattern
     * matching over the sealed hierarchy.
     */
    public static String describePlan(MigrationPlan plan) {
        return switch (plan) {
            case MigrationPlan.RecordMigration r ->
                    "Convert POJO to record with fields: " + String.join(", ", r.fields());
            case MigrationPlan.LambdaMigration l ->
                    "Replace anonymous " + l.functionalInterface() + " with lambda expression";
            case MigrationPlan.PatternMatchMigration p ->
                    "Use pattern matching for instanceof check on " + p.targetType();
            case MigrationPlan.SwitchExpressionMigration s ->
                    "Convert if/else chain (" + s.branchCount() + " branches) to switch expression";
            case MigrationPlan.VirtualThreadMigration v ->
                    "Replace " + v.threadCreationPattern() + " with virtual threads";
            case MigrationPlan.JavaTimeMigration j ->
                    "Migrate legacy types " + j.legacyTypes() + " to java.time API";
            case MigrationPlan.NioMigration n ->
                    "Migrate legacy types " + n.legacyTypes() + " to java.nio.file API";
            case MigrationPlan.StreamMigration ignored ->
                    "Convert for-each loop to stream pipeline";
            case MigrationPlan.GenericMigration g -> g.description();
        };
    }

    // =========================================================================
    // Private detectors
    // =========================================================================

    private static java.util.Optional<MigrationPlan> detectPojoToRecord(
            String source, String[] lines) {
        var fieldSignals = findSignals(lines, PRIVATE_FIELD);
        if (fieldSignals.size() < 2) return java.util.Optional.empty();

        var hasGetters = GETTER.matcher(source).find();
        var hasEquals = EQUALS_METHOD.matcher(source).find();
        var hasHashCode = HASHCODE_METHOD.matcher(source).find();
        var hasToString = TOSTRING_METHOD.matcher(source).find();

        if (!hasGetters) return java.util.Optional.empty();
        int boilerplateCount =
                Stream.of(hasEquals, hasHashCode, hasToString)
                        .mapToInt(b -> b ? 1 : 0)
                        .sum();
        if (boilerplateCount < 2) return java.util.Optional.empty();

        var fields = new ArrayList<String>();
        var matcher = PRIVATE_FIELD.matcher(source);
        while (matcher.find()) {
            fields.add(matcher.group(2));
        }

        return java.util.Optional.of(
                new MigrationPlan.RecordMigration(POJO_TO_RECORD, fieldSignals, List.copyOf(fields)));
    }

    private static java.util.Optional<MigrationPlan> detectAnonymousClassToLambda(
            String source, String[] lines) {
        var signals = findSignals(lines, ANON_CLASS);
        if (signals.isEmpty()) return java.util.Optional.empty();
        var firstMatch = ANON_CLASS.matcher(source);
        var iface = firstMatch.find() ? firstMatch.group(1) : "Unknown";
        return java.util.Optional.of(
                new MigrationPlan.LambdaMigration(ANON_CLASS_TO_LAMBDA, signals, iface));
    }

    private static java.util.Optional<MigrationPlan> detectInstanceofCast(
            String source, String[] lines) {
        var signals = findSignals(lines, INSTANCEOF_CAST);
        if (signals.isEmpty()) return java.util.Optional.empty();
        var matcher = INSTANCEOF_CAST.matcher(source);
        var targetType = matcher.find() ? matcher.group(1) : "Unknown";
        return java.util.Optional.of(
                new MigrationPlan.PatternMatchMigration(
                        INSTANCEOF_TO_PATTERN, signals, targetType));
    }

    private static java.util.Optional<MigrationPlan> detectIfElseChain(
            String source, String[] lines) {
        var signals = findSignals(lines, IF_ELSE_CHAIN);
        if (signals.size() < 2) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.SwitchExpressionMigration(
                        IF_ELSE_TO_SWITCH, signals, signals.size() + 1));
    }

    private static java.util.Optional<MigrationPlan> detectForLoopToStream(
            String source, String[] lines) {
        var signals = findSignals(lines, FOR_LOOP);
        if (signals.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.StreamMigration(FOR_LOOP_TO_STREAM, signals));
    }

    private static java.util.Optional<MigrationPlan> detectPlatformThread(
            String source, String[] lines) {
        var threadSignals = findSignals(lines, NEW_THREAD);
        var subclassSignals = findSignals(lines, THREAD_SUBCLASS);
        var combined =
                Stream.concat(threadSignals.stream(), subclassSignals.stream()).toList();
        if (combined.isEmpty()) return java.util.Optional.empty();
        var pattern =
                !threadSignals.isEmpty() ? "new Thread()" : "Thread subclass";
        return java.util.Optional.of(
                new MigrationPlan.VirtualThreadMigration(
                        PLATFORM_TO_VIRTUAL, combined, pattern));
    }

    private static java.util.Optional<MigrationPlan> detectThreadPool(
            String source, String[] lines) {
        var signals = findSignals(lines, FIXED_THREAD_POOL);
        if (signals.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.VirtualThreadMigration(
                        THREAD_POOL_TO_VIRTUAL, signals, "Executors thread pool"));
    }

    private static java.util.Optional<MigrationPlan> detectLegacyDate(
            String source, String[] lines) {
        var signals = findSignals(lines, LEGACY_DATE);
        if (signals.isEmpty()) return java.util.Optional.empty();
        var types = extractUniqueGroups(source, LEGACY_DATE, 2);
        return java.util.Optional.of(
                new MigrationPlan.JavaTimeMigration(DATE_TO_JAVA_TIME, signals, types));
    }

    private static java.util.Optional<MigrationPlan> detectLegacyFile(
            String source, String[] lines) {
        var signals = findSignals(lines, LEGACY_FILE);
        if (signals.isEmpty()) return java.util.Optional.empty();
        var types = extractUniqueGroups(source, LEGACY_FILE, 2);
        return java.util.Optional.of(
                new MigrationPlan.NioMigration(FILE_TO_NIO, signals, types));
    }

    private static java.util.Optional<MigrationPlan> detectJUnit4(
            String source, String[] lines) {
        var importSignals = findSignals(lines, JUNIT4_IMPORT);
        if (importSignals.isEmpty()) return java.util.Optional.empty();
        var annotationSignals = findSignals(lines, JUNIT4_ANNOTATION);
        var combined =
                Stream.concat(importSignals.stream(), annotationSignals.stream()).toList();
        return java.util.Optional.of(
                new MigrationPlan.GenericMigration(
                        JUNIT4_TO_JUNIT5,
                        combined,
                        "Migrate JUnit 4 annotations and assertions to JUnit 5"));
    }

    private static java.util.Optional<MigrationPlan> detectJavaxImports(
            String source, String[] lines) {
        var signals = findSignals(lines, JAVAX_IMPORT);
        if (signals.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.GenericMigration(
                        JAVAX_TO_JAKARTA,
                        signals,
                        "Rename javax.* packages to jakarta.* namespace"));
    }

    private static java.util.Optional<MigrationPlan> detectNullChecks(
            String source, String[] lines) {
        var signals = findSignals(lines, NULL_CHECK);
        if (signals.size() < 3) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.GenericMigration(
                        NULL_CHECK_TO_OPTIONAL,
                        signals,
                        "Replace " + signals.size() + " null checks with Optional chains"));
    }

    private static java.util.Optional<MigrationPlan> detectStaticMutableState(
            String source, String[] lines) {
        var signals = findSignals(lines, STATIC_MUTABLE_COLLECTION);
        if (signals.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.GenericMigration(
                        STATIC_STATE_TO_PROC,
                        signals,
                        "Replace "
                                + signals.size()
                                + " static mutable collection(s) with Proc<State, Message>"
                                + " to isolate state behind message passing"));
    }

    private static java.util.Optional<MigrationPlan> detectThreadLocal(
            String source, String[] lines) {
        var signals = findSignals(lines, THREAD_LOCAL);
        if (signals.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(
                new MigrationPlan.GenericMigration(
                        THREAD_LOCAL_TO_SCOPED_VALUE,
                        signals,
                        "Replace ThreadLocal with ScopedValue.newInstance();"
                                + " bind with ScopedValue.where(KEY, value).run(task)"));
    }

    private static java.util.Optional<MigrationPlan> detectSwallowedCatch(String source) {
        var matcher = SWALLOWED_CATCH.matcher(source);
        if (!matcher.find()) return java.util.Optional.empty();
        var matchedText = matcher.group().strip().replaceAll("\\R\\s*", " ");
        if (matchedText.length() > 60) matchedText = matchedText.substring(0, 57) + "...";
        var signal = new SourceSignal(matchedText, 1);
        return java.util.Optional.of(
                new MigrationPlan.GenericMigration(
                        CATCH_TO_CRASH_RECOVERY,
                        List.of(signal),
                        "Replace swallowed catch with Result.of(() -> ...) or"
                                + " CrashRecovery.retry() and let the supervisor decide fate"));
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private static List<SourceSignal> findSignals(String[] lines, Pattern pattern) {
        var signals = new ArrayList<SourceSignal>();
        for (int i = 0; i < lines.length; i++) {
            var matcher = pattern.matcher(lines[i]);
            while (matcher.find()) {
                signals.add(new SourceSignal(matcher.group().strip(), i + 1));
            }
        }
        return List.copyOf(signals);
    }

    private static Set<String> extractUniqueGroups(String source, Pattern pattern, int group) {
        var matcher = pattern.matcher(source);
        var results = new java.util.LinkedHashSet<String>();
        while (matcher.find()) {
            var value = matcher.group(group);
            if (value != null) {
                results.add(value);
            }
        }
        return Set.copyOf(results);
    }
}
