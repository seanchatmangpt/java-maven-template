package io.github.seanchatmangpt.jotp.dogfood.innovation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Codebase Modernization Score — analyzes Java source code and produces a "modernization score"
 * (0-100) based on how many modern Java 21+ patterns it uses vs. legacy patterns.
 *
 * <p>Identifies technical debt hotspots, calculates ROI for each migration, and ranks
 * recommendations by impact.
 */
public final class ModernizationScorer {

    // ── Public API types ──────────────────────────────────────────────────────

    /** Top-level score summarizing a codebase analysis. */
    public record CodebaseScore(
            int overallScore, List<CategoryScore> categories, List<Recommendation> recommendations) {

        public CodebaseScore {
            if (overallScore < 0 || overallScore > 100) {
                throw new IllegalArgumentException(
                        "overallScore must be 0-100, got " + overallScore);
            }
            categories = List.copyOf(categories);
            recommendations = List.copyOf(recommendations);
        }
    }

    /** Per-category breakdown (e.g., "language", "concurrency"). */
    public record CategoryScore(String category, int score, int maxScore, List<Finding> findings) {

        public CategoryScore {
            if (score < 0 || score > maxScore) {
                throw new IllegalArgumentException(
                        "score must be 0-" + maxScore + ", got " + score);
            }
            findings = List.copyOf(findings);
        }
    }

    /** A finding detected during analysis — either a legacy usage or a modern usage. */
    public sealed interface Finding {
        record LegacyUsage(String pattern, String lineHint, String suggestion) implements Finding {}

        record ModernUsage(String pattern, String lineHint) implements Finding {}
    }

    /** A ranked recommendation for modernizing the codebase. */
    public record Recommendation(
            String title, String description, int impactScore, int effortScore, String templateRef) {

        public Recommendation {
            if (impactScore < 0 || impactScore > 10) {
                throw new IllegalArgumentException(
                        "impactScore must be 0-10, got " + impactScore);
            }
            if (effortScore < 0 || effortScore > 10) {
                throw new IllegalArgumentException(
                        "effortScore must be 0-10, got " + effortScore);
            }
        }

        /** ROI = impact / effort (higher is better). */
        public double roi() {
            return effortScore == 0 ? impactScore : (double) impactScore / effortScore;
        }
    }

    // ── Pattern definitions ───────────────────────────────────────────────────

    /** Category names mirroring the template taxonomy. */
    enum Category {
        LANGUAGE,
        CONCURRENCY,
        API,
        TESTING,
        SECURITY
    }

    /**
     * A single detection rule. When the regex matches, it counts as either a legacy or modern usage
     * depending on the {@code modern} flag.
     */
    private record DetectionRule(
            Category category,
            Pattern regex,
            boolean modern,
            String patternName,
            String suggestion,
            int weight,
            String templateRef) {}

    private static final List<DetectionRule> RULES = buildRules();

    private static List<DetectionRule> buildRules() {
        var rules = new ArrayList<DetectionRule>();

        // ── LANGUAGE ──

        // Legacy: raw types (List without generics)
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "(?m)(?:^|\\s)(?:List|Map|Set|Collection|Iterable)\\s+\\w+\\s*[=;]",
                        false,
                        "Raw type usage",
                        "Use parameterized types: List<String>",
                        3,
                        "core/stream-pipeline"));

        // Legacy: old-style instanceof without pattern variable
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "instanceof\\s+\\w+\\s*\\)",
                        false,
                        "instanceof without pattern matching",
                        "Use pattern matching: if (obj instanceof String s)",
                        2,
                        "core/pattern-matching-switch"));

        // Modern: pattern matching instanceof
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "instanceof\\s+\\w+(?:<[^>]*>)?\\s+\\w+",
                        true,
                        "Pattern matching instanceof",
                        null,
                        2,
                        "core/pattern-matching-switch"));

        // Modern: record declaration
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "\\brecord\\s+\\w+\\s*\\(",
                        true,
                        "Record type",
                        null,
                        3,
                        "core/record"));

        // Modern: sealed interface/class
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "\\bsealed\\s+(?:interface|class)\\s+",
                        true,
                        "Sealed type",
                        null,
                        3,
                        "core/sealed-types"));

        // Modern: switch expression (arrow form)
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "\\bswitch\\s*\\([^)]+\\)\\s*\\{[^}]*->",
                        true,
                        "Switch expression",
                        null,
                        2,
                        "core/pattern-matching-switch"));

        // Modern: var local variable
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "\\bvar\\s+\\w+\\s*=",
                        true,
                        "Local variable type inference (var)",
                        null,
                        1,
                        "core/var-inference"));

        // Modern: text block
        rules.add(
                rule(
                        Category.LANGUAGE,
                        "\"\"\"",
                        true,
                        "Text block",
                        null,
                        1,
                        "core/text-block"));

        // ── CONCURRENCY ──

        // Legacy: new Thread(...)
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "new\\s+Thread\\s*\\(",
                        false,
                        "Manual Thread creation",
                        "Use virtual threads: Thread.ofVirtual().start()",
                        3,
                        "concurrency/virtual-thread"));

        // Legacy: Executors.newFixedThreadPool / newCachedThreadPool
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "Executors\\.new(?:Fixed|Cached|Single)ThreadPool",
                        false,
                        "Platform thread pool",
                        "Use Executors.newVirtualThreadPerTaskExecutor()",
                        3,
                        "concurrency/virtual-thread"));

        // Legacy: synchronized block/method
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "\\bsynchronized\\b",
                        false,
                        "synchronized block",
                        "Use ReentrantLock or virtual-thread-friendly concurrency",
                        2,
                        "concurrency/structured-task-scope"));

        // Modern: virtual thread factory
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "Thread\\.ofVirtual\\(",
                        true,
                        "Virtual thread usage",
                        null,
                        3,
                        "concurrency/virtual-thread"));

        // Modern: newVirtualThreadPerTaskExecutor
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "newVirtualThreadPerTaskExecutor",
                        true,
                        "Virtual thread executor",
                        null,
                        3,
                        "concurrency/virtual-thread"));

        // Modern: StructuredTaskScope
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "StructuredTaskScope",
                        true,
                        "Structured concurrency",
                        null,
                        3,
                        "concurrency/structured-task-scope"));

        // Legacy: ThreadLocal — implicit shared context, should be ScopedValue
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "\\bThreadLocal\\s*<",
                        false,
                        "ThreadLocal implicit context",
                        "Use ScopedValue<T> (Java 21+): structured, bounded, immutable context"
                                + " — no leak across virtual-thread boundaries",
                        3,
                        "concurrency/scoped-value"));

        // Legacy: static Atomic* as shared mutable state
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "static\\s+(?:final\\s+)?Atomic(?:Integer|Long|Reference|Boolean)\\s+",
                        false,
                        "Static AtomicX shared state",
                        "Move to Proc<State, Msg>: a process owns its counters,"
                                + " no one else touches them",
                        2,
                        "patterns/actor"));

        // Modern: ScopedValue (Java 21+ structured context)
        rules.add(
                rule(
                        Category.CONCURRENCY,
                        "ScopedValue\\.newInstance|ScopedValue\\.where",
                        true,
                        "ScopedValue structured context",
                        null,
                        3,
                        "concurrency/scoped-value"));

        // ── API ──

        // Legacy: java.util.Date
        rules.add(
                rule(
                        Category.API,
                        "(?:java\\.util\\.Date|new\\s+Date\\s*\\()",
                        false,
                        "Legacy java.util.Date",
                        "Use java.time API: LocalDate, Instant, ZonedDateTime",
                        3,
                        "api/java-time"));

        // Legacy: SimpleDateFormat
        rules.add(
                rule(
                        Category.API,
                        "SimpleDateFormat",
                        false,
                        "Legacy SimpleDateFormat",
                        "Use DateTimeFormatter (thread-safe, immutable)",
                        3,
                        "api/java-time"));

        // Legacy: StringBuffer
        rules.add(
                rule(
                        Category.API,
                        "\\bStringBuffer\\b",
                        false,
                        "StringBuffer (synchronized)",
                        "Use StringBuilder (unsynchronized, faster)",
                        2,
                        "api/string-methods"));

        // Legacy: URL.openConnection / HttpURLConnection
        rules.add(
                rule(
                        Category.API,
                        "(?:HttpURLConnection|URL\\s*\\()",
                        false,
                        "Legacy HttpURLConnection",
                        "Use java.net.http.HttpClient",
                        3,
                        "api/http-client"));

        // Modern: java.time types
        rules.add(
                rule(
                        Category.API,
                        "(?:LocalDate|LocalDateTime|Instant|ZonedDateTime|Duration|Period)"
                                + "(?:\\.now|\\.of|\\.parse)",
                        true,
                        "java.time API",
                        null,
                        3,
                        "api/java-time"));

        // Modern: HttpClient
        rules.add(
                rule(
                        Category.API,
                        "HttpClient\\.newHttpClient|HttpClient\\.newBuilder",
                        true,
                        "Modern HttpClient",
                        null,
                        3,
                        "api/http-client"));

        // Legacy: Vector / Hashtable
        rules.add(
                rule(
                        Category.API,
                        "\\b(?:Vector|Hashtable)\\b",
                        false,
                        "Legacy synchronized collection",
                        "Use ArrayList, HashMap, or Collections.synchronizedX()",
                        2,
                        "api/collection-factories"));

        // ── TESTING ──

        // Legacy: JUnit 4 annotations
        rules.add(
                rule(
                        Category.TESTING,
                        "(?:org\\.junit\\.Test|org\\.junit\\.Before(?:Class)?|org\\.junit\\.After(?:Class)?|org\\.junit\\.Ignore)",
                        false,
                        "JUnit 4 annotations",
                        "Migrate to JUnit 5: @Test, @BeforeEach, @Disabled",
                        3,
                        "testing/junit5-test"));

        // Legacy: assertEquals without message (JUnit 4 style, static import)
        rules.add(
                rule(
                        Category.TESTING,
                        "assertEquals\\s*\\(",
                        false,
                        "JUnit assertEquals",
                        "Use AssertJ: assertThat(actual).isEqualTo(expected)",
                        2,
                        "testing/assertj-assertions"));

        // Modern: JUnit 5 annotations
        rules.add(
                rule(
                        Category.TESTING,
                        "org\\.junit\\.jupiter",
                        true,
                        "JUnit 5 usage",
                        null,
                        3,
                        "testing/junit5-test"));

        // Modern: AssertJ
        rules.add(
                rule(
                        Category.TESTING,
                        "assertThat\\s*\\(",
                        true,
                        "AssertJ assertions",
                        null,
                        2,
                        "testing/assertj-assertions"));

        // ── SECURITY ──

        // Legacy: MD5 / SHA-1
        rules.add(
                rule(
                        Category.SECURITY,
                        "MessageDigest\\.getInstance\\s*\\(\\s*\"(?:MD5|SHA-1)\"",
                        false,
                        "Weak hash algorithm",
                        "Use SHA-256 or SHA-3",
                        3,
                        "security/modern-crypto"));

        // Legacy: DES / DESede / Blowfish
        rules.add(
                rule(
                        Category.SECURITY,
                        "Cipher\\.getInstance\\s*\\(\\s*\"(?:DES|DESede|Blowfish)",
                        false,
                        "Weak cipher algorithm",
                        "Use AES-GCM (AEAD)",
                        3,
                        "security/modern-crypto"));

        // Legacy: sun.misc.Unsafe or internal API usage
        rules.add(
                rule(
                        Category.SECURITY,
                        "sun\\.misc\\.Unsafe|jdk\\.internal",
                        false,
                        "Internal API usage",
                        "Use supported public API; leverage JPMS encapsulation",
                        3,
                        "security/encapsulation"));

        // Modern: secure random with algorithm
        rules.add(
                rule(
                        Category.SECURITY,
                        "SecureRandom\\.getInstance",
                        true,
                        "SecureRandom usage",
                        null,
                        2,
                        "security/modern-crypto"));

        return List.copyOf(rules);
    }

    private static DetectionRule rule(
            Category category,
            String regex,
            boolean modern,
            String patternName,
            String suggestion,
            int weight,
            String templateRef) {
        return new DetectionRule(
                category,
                Pattern.compile(regex),
                modern,
                patternName,
                suggestion,
                weight,
                templateRef);
    }

    // ── Max scores per category ───────────────────────────────────────────────

    private static final Map<Category, Integer> MAX_SCORES =
            Map.of(
                    Category.LANGUAGE, 25,
                    Category.CONCURRENCY, 20,
                    Category.API, 25,
                    Category.TESTING, 15,
                    Category.SECURITY, 15);

    // ── Analysis ──────────────────────────────────────────────────────────────

    /** Analyze a Java source string and produce a modernization score. */
    public CodebaseScore analyze(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");

        // Handle empty/blank source gracefully
        if (javaSource.isBlank()) {
            return new CodebaseScore(50, List.of(), List.of());
        }

        // Split into lines for line-hint reporting
        String[] lines = javaSource.split("\\R");

        // Collect findings per category
        Map<Category, List<Finding>> findingsByCategory =
                Stream.of(Category.values())
                        .collect(Collectors.toMap(c -> c, c -> new ArrayList<>()));

        // Track weighted scores: legacy subtracts, modern adds
        Map<Category, Integer> modernPoints =
                Stream.of(Category.values()).collect(Collectors.toMap(c -> c, c -> 0));
        Map<Category, Integer> legacyPoints =
                Stream.of(Category.values()).collect(Collectors.toMap(c -> c, c -> 0));

        for (DetectionRule rule : RULES) {
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = rule.regex().matcher(lines[i]);
                while (matcher.find()) {
                    String lineHint = "line " + (i + 1) + ": " + lines[i].strip();
                    if (rule.modern()) {
                        findingsByCategory
                                .get(rule.category())
                                .add(new Finding.ModernUsage(rule.patternName(), lineHint));
                        modernPoints.merge(rule.category(), rule.weight(), Integer::sum);
                    } else {
                        findingsByCategory
                                .get(rule.category())
                                .add(
                                        new Finding.LegacyUsage(
                                                rule.patternName(), lineHint, rule.suggestion()));
                        legacyPoints.merge(rule.category(), rule.weight(), Integer::sum);
                    }
                }
            }
        }

        // Build category scores
        List<CategoryScore> categories =
                Stream.of(Category.values())
                        .map(
                                cat -> {
                                    int maxScore = MAX_SCORES.get(cat);
                                    int modern = modernPoints.get(cat);
                                    int legacy = legacyPoints.get(cat);

                                    // Score: start at half, gain for modern, lose for legacy
                                    int raw = (maxScore / 2) + modern - legacy;
                                    int clamped = Math.clamp(raw, 0, maxScore);

                                    return new CategoryScore(
                                            cat.name().toLowerCase(),
                                            clamped,
                                            maxScore,
                                            findingsByCategory.get(cat));
                                })
                        .toList();

        // Build recommendations from legacy findings
        List<Recommendation> recommendations =
                RULES.stream()
                        .filter(rule -> !rule.modern())
                        .filter(
                                rule ->
                                        findingsByCategory.get(rule.category()).stream()
                                                .anyMatch(
                                                        f ->
                                                                f
                                                                                instanceof
                                                                                Finding.LegacyUsage
                                                                                        lu
                                                                        && lu.pattern()
                                                                                .equals(
                                                                                        rule
                                                                                                .patternName())))
                        .map(
                                rule -> {
                                    long count =
                                            findingsByCategory.get(rule.category()).stream()
                                                    .filter(
                                                            f ->
                                                                    f
                                                                                    instanceof
                                                                                    Finding
                                                                                            .LegacyUsage
                                                                                            lu
                                                                            && lu.pattern()
                                                                                    .equals(
                                                                                            rule
                                                                                                    .patternName()))
                                                    .count();
                                    return new Recommendation(
                                            "Migrate: " + rule.patternName(),
                                            rule.suggestion()
                                                    + " ("
                                                    + count
                                                    + " occurrence"
                                                    + (count > 1 ? "s" : "")
                                                    + " found)",
                                            Math.min(rule.weight() * (int) count, 10),
                                            rule.weight(),
                                            rule.templateRef());
                                })
                        .sorted(Comparator.comparingDouble(Recommendation::roi).reversed())
                        .toList();

        // Overall score: weighted sum of category scores scaled to 0-100
        int totalMax = MAX_SCORES.values().stream().mapToInt(Integer::intValue).sum();
        int totalActual = categories.stream().mapToInt(CategoryScore::score).sum();
        int overallScore = Math.round((float) totalActual / totalMax * 100);

        return new CodebaseScore(overallScore, categories, recommendations);
    }
}
