package org.acme.dogfood.innovation;

import java.util.Set;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dogfood: tests for OntologyMigrationEngine — ontology-driven migration analysis. */
@DisplayName("OntologyMigrationEngine")
class OntologyMigrationEngineTest implements WithAssertions {

    @Test
    @DisplayName("allRules returns all 15 built-in ontology rules")
    void allRulesReturnsBuiltInRules() {
        var rules = OntologyMigrationEngine.allRules();
        assertThat(rules).hasSize(15);
        assertThat(rules).allSatisfy(r -> {
            assertThat(r.id()).isNotBlank();
            assertThat(r.label()).isNotBlank();
            assertThat(r.priority()).isBetween(1, 10);
        });
    }

    @Test
    @DisplayName("analyze detects new Thread() as virtual thread migration opportunity")
    void analyzeDetectsNewThread() {
        var source = """
                public class LegacyService {
                    public void process() {
                        new Thread(() -> System.out.println("work")).start();
                    }
                }
                """;

        var result = OntologyMigrationEngine.analyze("LegacyService.java", source);

        assertThat(result.plans()).anyMatch(
                p -> p instanceof OntologyMigrationEngine.MigrationPlan.VirtualThreadMigration);
    }

    @Test
    @DisplayName("analyze detects Executors.newFixedThreadPool as thread pool migration")
    void analyzeDetectsThreadPool() {
        var source = """
                import java.util.concurrent.Executors;
                public class PoolService {
                    var pool = Executors.newFixedThreadPool(10);
                }
                """;

        var result = OntologyMigrationEngine.analyze("PoolService.java", source);

        assertThat(result.plans()).anyMatch(
                p -> p instanceof OntologyMigrationEngine.MigrationPlan.VirtualThreadMigration);
    }

    @Test
    @DisplayName("analyze detects legacy Date/Calendar usage")
    void analyzeDetectsLegacyDate() {
        var source = """
                import java.util.Date;
                import java.util.Calendar;
                public class DateService {
                    Date now = new Date();
                    Calendar cal = Calendar.getInstance();
                }
                """;

        var result = OntologyMigrationEngine.analyze("DateService.java", source);

        assertThat(result.plans()).anyMatch(
                p -> p instanceof OntologyMigrationEngine.MigrationPlan.JavaTimeMigration);
    }

    @Test
    @DisplayName("analyze detects JUnit 4 imports as migration opportunity")
    void analyzeDetectsJUnit4() {
        var source = """
                import org.junit.Test;
                import org.junit.Before;
                public class OldTest {
                    @Before public void setUp() {}
                    @Test public void testSomething() {}
                }
                """;

        var result = OntologyMigrationEngine.analyze("OldTest.java", source);

        assertThat(result.plans()).anyMatch(
                p -> p instanceof OntologyMigrationEngine.MigrationPlan.GenericMigration gm
                        && gm.rule().id().equals("JUnit4ToJUnit5"));
    }

    @Test
    @DisplayName("analyze returns empty plans for modern code")
    void analyzeReturnsEmptyForModernCode() {
        var source = """
                package org.acme;
                public record ModernEntity(String name, int value) {}
                """;

        var result = OntologyMigrationEngine.analyze("ModernEntity.java", source);

        assertThat(result.plans()).isEmpty();
    }

    @Test
    @DisplayName("analyze with category filter only returns matching categories")
    void analyzeWithCategoryFilter() {
        var source = """
                import java.util.Date;
                public class Mixed {
                    Date d = new Date();
                    new Thread(() -> {}).start();
                }
                """;

        var result = OntologyMigrationEngine.analyze(
                "Mixed.java", source, Set.of(OntologyMigrationEngine.Category.CONCURRENCY));

        assertThat(result.plans()).allSatisfy(
                p -> assertThat(p.rule().category())
                        .isEqualTo(OntologyMigrationEngine.Category.CONCURRENCY));
    }

    @Test
    @DisplayName("safeUpgrades excludes breaking changes")
    void safeUpgradesExcludesBreaking() {
        var source = """
                import java.util.Date;
                public class Service {
                    Date d = new Date();
                    new Thread(() -> {}).start();
                }
                """;

        var result = OntologyMigrationEngine.analyze("Service.java", source);

        assertThat(result.safeUpgrades())
                .allSatisfy(p -> assertThat(p.rule().breaking()).isFalse());
    }

    @Test
    @DisplayName("byPriority sorts plans by rule priority ascending")
    void byPrioritySortsPlans() {
        var source = """
                import java.util.Date;
                import java.io.File;
                public class Multi {
                    Date d = new Date();
                    File f = new File("test");
                    new Thread(() -> {}).start();
                }
                """;

        var result = OntologyMigrationEngine.analyze("Multi.java", source);
        var sorted = result.byPriority();

        if (sorted.size() > 1) {
            for (int i = 1; i < sorted.size(); i++) {
                assertThat(sorted.get(i).rule().priority())
                        .isGreaterThanOrEqualTo(sorted.get(i - 1).rule().priority());
            }
        }
    }

    @Test
    @DisplayName("describePlan returns human-readable description for each plan type")
    void describePlanReturnsDescription() {
        var source = """
                public class Legacy {
                    new Thread(() -> {}).start();
                }
                """;

        var result = OntologyMigrationEngine.analyze("Legacy.java", source);

        for (var plan : result.plans()) {
            var description = OntologyMigrationEngine.describePlan(plan);
            assertThat(description).isNotBlank();
        }
    }

    @Test
    @DisplayName("summary produces non-empty report when migrations found")
    void summaryProducesReport() {
        var source = """
                public class Legacy {
                    new Thread(() -> {}).start();
                }
                """;

        var result = OntologyMigrationEngine.analyze("Legacy.java", source);

        assertThat(result.summary()).contains("migration(s) found");
    }

    @Test
    @DisplayName("MigrationRule rejects invalid priority")
    void migrationRuleRejectsInvalidPriority() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OntologyMigrationEngine.MigrationRule(
                        "test", "Test", "pattern", "template",
                        OntologyMigrationEngine.Category.LANGUAGE, 0, false))
                .withMessageContaining("priority");
    }

    @Test
    @DisplayName("SourceSignal rejects invalid line number")
    void sourceSignalRejectsInvalidLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OntologyMigrationEngine.SourceSignal("text", 0))
                .withMessageContaining("lineNumber");
    }

    // ── OTP migration rules ───────────────────────────────────────────────────

    @Test
    @DisplayName("static Map detected → StaticStateToProc plan")
    void staticMapDetected() {
        var source =
                """
                package com.example;
                import java.util.Map;
                import java.util.HashMap;
                public class UserCache {
                    private static final Map<String, Object> cache = new HashMap<>();
                }
                """;

        var result = OntologyMigrationEngine.analyze("UserCache.java", source);

        assertThat(result.plans())
                .anyMatch(
                        p ->
                                p.rule().id().equals("StaticStateToProc")
                                        && p instanceof OntologyMigrationEngine.MigrationPlan.GenericMigration);
    }

    @Test
    @DisplayName("ThreadLocal detected → ThreadLocalToScopedValue plan")
    void threadLocalDetected() {
        var source =
                """
                package com.example;
                public class Context {
                    private static final ThreadLocal<String> current = new ThreadLocal<>();
                }
                """;

        var result = OntologyMigrationEngine.analyze("Context.java", source);

        assertThat(result.plans())
                .anyMatch(p -> p.rule().id().equals("ThreadLocalToScopedValue"));
    }

    @Test
    @DisplayName("swallowed catch detected → CatchToCrashRecovery plan")
    void swallowedCatchDetected() {
        var source =
                """
                package com.example;
                public class Service {
                    boolean save(Object o) {
                        try { repo.save(o); return true; }
                        catch (Exception e) { return false; }
                    }
                }
                """;

        var result = OntologyMigrationEngine.analyze("Service.java", source);

        assertThat(result.plans())
                .anyMatch(p -> p.rule().id().equals("CatchToCrashRecovery"));
    }

    @Test
    @DisplayName("allRules() includes the 3 new OTP rules")
    void allRulesIncludesNewOtpRules() {
        var ids = OntologyMigrationEngine.allRules().stream()
                .map(OntologyMigrationEngine.MigrationRule::id)
                .toList();

        assertThat(ids)
                .contains("StaticStateToProc", "ThreadLocalToScopedValue", "CatchToCrashRecovery");
    }

    @Test
    @DisplayName("OTP rules have CONCURRENCY or ERROR_HANDLING category")
    void otpRuleCategories() {
        var otpRules = OntologyMigrationEngine.allRules().stream()
                .filter(r -> r.id().startsWith("Static")
                        || r.id().startsWith("Thread")
                        || r.id().startsWith("Catch"))
                .toList();

        assertThat(otpRules)
                .allMatch(
                        r ->
                                r.category() == OntologyMigrationEngine.Category.CONCURRENCY
                                        || r.category()
                                                == OntologyMigrationEngine.Category.ERROR_HANDLING);
    }
}
