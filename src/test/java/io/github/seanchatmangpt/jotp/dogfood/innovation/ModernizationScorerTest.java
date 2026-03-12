package io.github.seanchatmangpt.jotp.dogfood.innovation;

import java.util.List;

import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.CategoryScore;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.CodebaseScore;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.Finding;
import io.github.seanchatmangpt.jotp.dogfood.innovation.ModernizationScorer.Recommendation;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ModernizationScorer")
class ModernizationScorerTest implements WithAssertions {

    private ModernizationScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new ModernizationScorer();
    }

    // ── Sample sources ────────────────────────────────────────────────────────

    private static final String LEGACY_SOURCE =
            """
            package com.example;

            import java.util.Date;
            import java.text.SimpleDateFormat;
            import org.junit.Test;
            import org.junit.Before;

            public class LegacyService {
                private StringBuffer buffer = new StringBuffer();
                private List results;
                private Vector items = new Vector();

                public void process(Object obj) {
                    if (obj instanceof String) {
                        String s = (String) obj;
                        buffer.append(s);
                    }
                    Date now = new Date();
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
                    synchronized (buffer) {
                        buffer.append(fmt.format(now));
                    }
                }

                public void runTask() {
                    new Thread(() -> System.out.println("running")).start();
                    Executors.newFixedThreadPool(4);
                }

                @Test
                public void testSomething() {
                    assertEquals("expected", "actual");
                }
            }
            """;

    private static final String MODERN_SOURCE =
            """
            package com.example;

            import java.time.LocalDateTime;
            import java.time.Instant;

            public sealed interface Shape permits Shape.Circle, Shape.Rect {
                record Circle(double radius) implements Shape {}
                record Rect(double w, double h) implements Shape {}
            }

            public final class ModernService {
                public String describe(Object obj) {
                    return switch (obj) {
                        case String s -> "string: " + s;
                        case Integer i -> "int: " + i;
                        default -> "unknown";
                    };
                }

                public void process() {
                    var now = Instant.now();
                    var date = LocalDateTime.now();
                    var client = HttpClient.newHttpClient();
                    Thread.ofVirtual().start(() -> System.out.println("virtual"));
                }

                void test() {
                    assertThat(result).isEqualTo("expected");
                }
            }
            """;

    private static final String MINIMAL_SOURCE =
            """
            package com.example;

            public class Empty {
            }
            """;

    private static final String SECURITY_LEGACY_SOURCE =
            """
            package com.example;

            import java.security.MessageDigest;
            import javax.crypto.Cipher;

            public class OldCrypto {
                public byte[] hash(byte[] data) throws Exception {
                    var md = MessageDigest.getInstance("MD5");
                    return md.digest(data);
                }
                public byte[] encrypt(byte[] data) throws Exception {
                    var cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
                    return cipher.doFinal(data);
                }
            }
            """;

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CodebaseScore record")
    class CodebaseScoreValidation {

        @Test
        @DisplayName("rejects score below 0")
        void rejectsScoreBelowZero() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CodebaseScore(-1, List.of(), List.of()));
        }

        @Test
        @DisplayName("rejects score above 100")
        void rejectsScoreAbove100() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CodebaseScore(101, List.of(), List.of()));
        }

        @Test
        @DisplayName("creates valid score")
        void createsValidScore() {
            var score = new CodebaseScore(75, List.of(), List.of());
            assertThat(score.overallScore()).isEqualTo(75);
        }
    }

    @Nested
    @DisplayName("Recommendation record")
    class RecommendationValidation {

        @Test
        @DisplayName("computes ROI correctly")
        void computesRoi() {
            var rec = new Recommendation("title", "desc", 8, 2, "ref");
            assertThat(rec.roi()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("ROI with zero effort returns impact")
        void roiZeroEffort() {
            var rec = new Recommendation("title", "desc", 5, 0, "ref");
            assertThat(rec.roi()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("rejects invalid impact score")
        void rejectsInvalidImpact() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Recommendation("t", "d", 11, 2, "r"));
        }
    }

    @Nested
    @DisplayName("analyze()")
    class Analyze {

        @Test
        @DisplayName("legacy code scores lower than modern code")
        void legacyScoresLowerThanModern() {
            var legacyScore = scorer.analyze(LEGACY_SOURCE);
            var modernScore = scorer.analyze(MODERN_SOURCE);

            assertThat(legacyScore.overallScore()).isLessThan(modernScore.overallScore());
        }

        @Test
        @DisplayName("legacy code produces recommendations")
        void legacyCodeProducesRecommendations() {
            var result = scorer.analyze(LEGACY_SOURCE);

            assertThat(result.recommendations()).isNotEmpty();
            assertThat(result.recommendations())
                    .allSatisfy(
                            rec -> {
                                assertThat(rec.title()).startsWith("Migrate:");
                                assertThat(rec.description()).isNotBlank();
                                assertThat(rec.templateRef()).isNotBlank();
                            });
        }

        @Test
        @DisplayName("modern code produces no recommendations")
        void modernCodeProducesNoRecommendations() {
            var result = scorer.analyze(MODERN_SOURCE);

            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("overall score is between 0 and 100")
        void overallScoreInRange() {
            var result = scorer.analyze(LEGACY_SOURCE);

            assertThat(result.overallScore()).isBetween(0, 100);
        }

        @Test
        @DisplayName("minimal code gets baseline score around 50")
        void minimalCodeGetsBaselineScore() {
            var result = scorer.analyze(MINIMAL_SOURCE);

            // No modern or legacy patterns -> half of max per category -> ~50
            assertThat(result.overallScore()).isBetween(40, 60);
        }

        @Test
        @DisplayName("has all five categories")
        void hasFiveCategories() {
            var result = scorer.analyze(LEGACY_SOURCE);

            assertThat(result.categories()).hasSize(5);
            assertThat(result.categories())
                    .extracting(CategoryScore::category)
                    .containsExactlyInAnyOrder(
                            "language", "concurrency", "api", "testing", "security");
        }

        @Test
        @DisplayName("category scores do not exceed their max")
        void categoryScoresDoNotExceedMax() {
            var result = scorer.analyze(LEGACY_SOURCE);

            assertThat(result.categories())
                    .allSatisfy(
                            cat -> {
                                assertThat(cat.score()).isBetween(0, cat.maxScore());
                            });
        }
    }

    @Nested
    @DisplayName("legacy pattern detection")
    class LegacyPatternDetection {

        @Test
        @DisplayName("detects java.util.Date usage")
        void detectsLegacyDate() {
            var result = scorer.analyze(LEGACY_SOURCE);
            var apiFindings = findingsForCategory(result, "api");

            assertThat(apiFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("Legacy java.util.Date");
        }

        @Test
        @DisplayName("detects StringBuffer usage")
        void detectsStringBuffer() {
            var result = scorer.analyze(LEGACY_SOURCE);
            var apiFindings = findingsForCategory(result, "api");

            assertThat(apiFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("StringBuffer (synchronized)");
        }

        @Test
        @DisplayName("detects synchronized blocks")
        void detectsSynchronized() {
            var result = scorer.analyze(LEGACY_SOURCE);
            var concurrencyFindings = findingsForCategory(result, "concurrency");

            assertThat(concurrencyFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("synchronized block");
        }

        @Test
        @DisplayName("detects manual Thread creation")
        void detectsManualThread() {
            var result = scorer.analyze(LEGACY_SOURCE);
            var concurrencyFindings = findingsForCategory(result, "concurrency");

            assertThat(concurrencyFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("Manual Thread creation");
        }

        @Test
        @DisplayName("detects JUnit 4 annotations")
        void detectsJunit4() {
            var result = scorer.analyze(LEGACY_SOURCE);
            var testFindings = findingsForCategory(result, "testing");

            assertThat(testFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("JUnit 4 annotations");
        }

        @Test
        @DisplayName("detects Vector usage")
        void detectsVector() {
            var result = scorer.analyze(LEGACY_SOURCE);
            var apiFindings = findingsForCategory(result, "api");

            assertThat(apiFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("Legacy synchronized collection");
        }

        @Test
        @DisplayName("detects weak hash algorithms")
        void detectsWeakHash() {
            var result = scorer.analyze(SECURITY_LEGACY_SOURCE);
            var securityFindings = findingsForCategory(result, "security");

            assertThat(securityFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("Weak hash algorithm");
        }

        @Test
        @DisplayName("detects weak cipher algorithms")
        void detectsWeakCipher() {
            var result = scorer.analyze(SECURITY_LEGACY_SOURCE);
            var securityFindings = findingsForCategory(result, "security");

            assertThat(securityFindings)
                    .filteredOn(f -> f instanceof Finding.LegacyUsage)
                    .extracting(f -> ((Finding.LegacyUsage) f).pattern())
                    .contains("Weak cipher algorithm");
        }
    }

    @Nested
    @DisplayName("modern pattern detection")
    class ModernPatternDetection {

        @Test
        @DisplayName("detects record types")
        void detectsRecords() {
            var result = scorer.analyze(MODERN_SOURCE);
            var langFindings = findingsForCategory(result, "language");

            assertThat(langFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("Record type");
        }

        @Test
        @DisplayName("detects sealed types")
        void detectsSealedTypes() {
            var result = scorer.analyze(MODERN_SOURCE);
            var langFindings = findingsForCategory(result, "language");

            assertThat(langFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("Sealed type");
        }

        @Test
        @DisplayName("detects var usage")
        void detectsVar() {
            var result = scorer.analyze(MODERN_SOURCE);
            var langFindings = findingsForCategory(result, "language");

            assertThat(langFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("Local variable type inference (var)");
        }

        @Test
        @DisplayName("detects virtual threads")
        void detectsVirtualThreads() {
            var result = scorer.analyze(MODERN_SOURCE);
            var concurrencyFindings = findingsForCategory(result, "concurrency");

            assertThat(concurrencyFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("Virtual thread usage");
        }

        @Test
        @DisplayName("detects java.time API")
        void detectsJavaTime() {
            var result = scorer.analyze(MODERN_SOURCE);
            var apiFindings = findingsForCategory(result, "api");

            assertThat(apiFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("java.time API");
        }

        @Test
        @DisplayName("detects HttpClient")
        void detectsHttpClient() {
            var result = scorer.analyze(MODERN_SOURCE);
            var apiFindings = findingsForCategory(result, "api");

            assertThat(apiFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("Modern HttpClient");
        }

        @Test
        @DisplayName("detects AssertJ assertions")
        void detectsAssertJ() {
            var result = scorer.analyze(MODERN_SOURCE);
            var testFindings = findingsForCategory(result, "testing");

            assertThat(testFindings)
                    .filteredOn(f -> f instanceof Finding.ModernUsage)
                    .extracting(f -> ((Finding.ModernUsage) f).pattern())
                    .contains("AssertJ assertions");
        }
    }

    @Nested
    @DisplayName("recommendations")
    class Recommendations {

        @Test
        @DisplayName("are sorted by ROI descending")
        void sortedByRoiDescending() {
            var result = scorer.analyze(LEGACY_SOURCE);

            var rois =
                    result.recommendations().stream()
                            .map(Recommendation::roi)
                            .toList();

            for (int i = 0; i < rois.size() - 1; i++) {
                assertThat(rois.get(i)).isGreaterThanOrEqualTo(rois.get(i + 1));
            }
        }

        @Test
        @DisplayName("include template references")
        void includeTemplateReferences() {
            var result = scorer.analyze(LEGACY_SOURCE);

            assertThat(result.recommendations())
                    .allSatisfy(rec -> assertThat(rec.templateRef()).contains("/"));
        }

        @Test
        @DisplayName("include occurrence counts in description")
        void includeOccurrenceCounts() {
            var result = scorer.analyze(LEGACY_SOURCE);

            assertThat(result.recommendations())
                    .allSatisfy(
                            rec -> assertThat(rec.description()).containsPattern("\\d+ occurrence"));
        }
    }

    @Nested
    @DisplayName("Finding sealed interface")
    class FindingTypes {

        @Test
        @DisplayName("LegacyUsage carries suggestion")
        void legacyUsageCarriesSuggestion() {
            var finding = new Finding.LegacyUsage("pattern", "line 1: code", "use modern API");

            assertThat(finding.pattern()).isEqualTo("pattern");
            assertThat(finding.suggestion()).isEqualTo("use modern API");
        }

        @Test
        @DisplayName("ModernUsage carries pattern name")
        void modernUsageCarriesPatternName() {
            var finding = new Finding.ModernUsage("Record type", "line 5: record Foo()");

            assertThat(finding.pattern()).isEqualTo("Record type");
            assertThat(finding.lineHint()).contains("line 5");
        }

        @Test
        @DisplayName("Finding is sealed with exactly two variants")
        void findingIsSealedWithTwoVariants() {
            assertThat(Finding.class.isSealed()).isTrue();
            assertThat(Finding.class.getPermittedSubclasses()).hasSize(2);
        }
    }

    // ── Enterprise patterns (GoNoGo rules) ───────────────────────────────────

    @Nested
    @DisplayName("Enterprise legacy patterns")
    class EnterprisePatterns {

        @Test
        @DisplayName("ThreadLocal detected as legacy concurrency pattern")
        void threadLocalIsLegacy() {
            var source =
                    """
                    package com.example;
                    public class RequestContext {
                        private static final ThreadLocal<String> current = new ThreadLocal<>();
                        public static void set(String v) { current.set(v); }
                    }
                    """;

            var score = scorer.analyze(source);

            var concurrencyFindings = findingsForCategory(score, "concurrency");
            assertThat(concurrencyFindings)
                    .anyMatch(
                            f ->
                                    f instanceof Finding.LegacyUsage lu
                                            && lu.pattern().contains("ThreadLocal"));
        }

        @Test
        @DisplayName("static AtomicInteger detected as legacy shared state")
        void staticAtomicIntegerIsLegacy() {
            var source =
                    """
                    package com.example;
                    import java.util.concurrent.atomic.AtomicInteger;
                    public class Counter {
                        private static final AtomicInteger count = new AtomicInteger(0);
                    }
                    """;

            var score = scorer.analyze(source);

            var concurrencyFindings = findingsForCategory(score, "concurrency");
            assertThat(concurrencyFindings)
                    .anyMatch(
                            f ->
                                    f instanceof Finding.LegacyUsage lu
                                            && lu.pattern().contains("Atomic"));
        }

        @Test
        @DisplayName("ScopedValue.newInstance detected as modern concurrency pattern")
        void scopedValueIsModern() {
            var source =
                    """
                    package com.example;
                    public class RequestContext {
                        private static final ScopedValue<String> REQUEST_ID =
                            ScopedValue.newInstance();
                    }
                    """;

            var score = scorer.analyze(source);

            var concurrencyFindings = findingsForCategory(score, "concurrency");
            assertThat(concurrencyFindings)
                    .anyMatch(
                            f ->
                                    f instanceof Finding.ModernUsage mu
                                            && mu.pattern().contains("ScopedValue"));
        }

        @Test
        @DisplayName("ThreadLocal lowers concurrency score vs ScopedValue")
        void threadLocalLowersScoreVsScopedValue() {
            var threadLocalSource =
                    "public class A { static final ThreadLocal<String> t = new ThreadLocal<>(); }";
            var scopedValueSource =
                    "public class B { static final ScopedValue<String> s = ScopedValue.newInstance(); }";

            var threadLocalScore = scorer.analyze(threadLocalSource);
            var scopedValueScore = scorer.analyze(scopedValueSource);

            assertThat(threadLocalScore.overallScore())
                    .isLessThan(scopedValueScore.overallScore());
        }
    }

    // ── Edge Cases (JIDOKA - Stop and Fix) ───────────────────────────────────────

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCases {

        @Test
        @DisplayName("null source throws NullPointerException")
        void nullSourceThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> scorer.analyze(null))
                    .withMessageContaining("javaSource");
        }

        @Test
        @DisplayName("empty source returns baseline score")
        void emptySourceReturnsBaseline() {
            var result = scorer.analyze("");

            assertThat(result.overallScore()).isEqualTo(50);
            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("blank source returns baseline score")
        void blankSourceReturnsBaseline() {
            var result = scorer.analyze("   \n\n   ");

            assertThat(result.overallScore()).isEqualTo(50);
            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("source with only comments returns near-baseline score")
        void onlyCommentsReturnsBaseline() {
            var source = """
                    // Just a comment
                    /* Block comment */
                    """;

            var result = scorer.analyze(source);

            // Comments-only source gets a near-baseline score (no modern patterns detected)
            assertThat(result.overallScore()).isBetween(45, 55);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static java.util.List<Finding> findingsForCategory(
            CodebaseScore score, String category) {
        return score.categories().stream()
                .filter(c -> c.category().equals(category))
                .findFirst()
                .orElseThrow()
                .findings();
    }
}
