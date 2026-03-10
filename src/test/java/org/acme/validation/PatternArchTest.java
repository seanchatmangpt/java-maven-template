package org.acme.validation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit structural invariant tests for ggen-generated Java development patterns.
 *
 * <p>Thesis claim: <em>ggen generates patterns with correct structural architecture</em>.
 *
 * <p>Uses programmatic ArchUnit (not @AnalyzeClasses/@ArchTest) for full JPMS compatibility.
 *
 * <h2>Rules verified:</h2>
 *
 * <ul>
 *   <li>Core {@code org.acme} classes don't depend on dogfood implementation details
 *   <li>API dogfood classes must not use legacy {@code java.util.Date}
 *   <li>All dogfood classes must not use legacy {@code java.util.Calendar}
 *   <li>Dogfood packages have no cyclic dependencies
 * </ul>
 */
@DisplayName("Pattern Generator — Structural Architecture Rules")
class PatternArchTest {

    private static JavaClasses allClasses;
    private static JavaClasses dogfoodClasses;
    private static JavaClasses apiDogfoodClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.acme");
        dogfoodClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.acme.dogfood");
        apiDogfoodClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.acme.dogfood.api");
    }

    // =========================================================================
    // RULE 1: Core org.acme must not depend on dogfood packages
    // =========================================================================

    @Test
    @DisplayName("Core patterns must not depend on dogfood implementations")
    void coreDoesNotDependOnDogfood() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("org.acme")
                .and()
                .haveNameNotMatching("org\\.acme\\.dogfood\\..*")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.acme.dogfood..")
                .because("Core patterns must be independent of dogfood — dogfood depends on core");

        rule.allowEmptyShould(true).check(allClasses);
    }

    // =========================================================================
    // RULE 2: API dogfood must not use legacy java.util.Date
    // =========================================================================

    @Test
    @DisplayName("API modernization patterns must not use legacy java.util.Date")
    void apiClassesDoNotUseLegacyDate() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("org.acme.dogfood.api")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .because("ggen-generated API patterns use java.time, not legacy java.util.Date");

        rule.allowEmptyShould(true).check(apiDogfoodClasses);
    }

    // =========================================================================
    // RULE 3: No dogfood class may use java.util.Calendar
    // =========================================================================

    @Test
    @DisplayName("All dogfood patterns must not use legacy java.util.Calendar")
    void dogfoodClassesDoNotUseCalendar() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("org.acme.dogfood..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Calendar")
                .because("ggen generates java.time patterns, not legacy Calendar");

        rule.allowEmptyShould(true).check(dogfoodClasses);
    }

    // =========================================================================
    // RULE 4: No dogfood class may use java.util.Date
    // =========================================================================

    @Test
    @DisplayName("All dogfood patterns must not use legacy java.util.Date")
    void dogfoodClassesDoNotUseLegacyDate() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("org.acme.dogfood..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Date")
                .because("ggen generates java.time patterns, not legacy java.util.Date");

        rule.allowEmptyShould(true).check(dogfoodClasses);
    }

    // =========================================================================
    // RULE 5: No cyclic dependencies between dogfood subpackages
    // =========================================================================

    @Test
    @DisplayName("No cyclic dependencies between dogfood subpackages")
    void noCyclicDependenciesBetweenDogfoodSubpackages() {
        com.tngtech.archunit.library.dependencies.SliceRule noCycles =
                com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
                        .matching("org.acme.dogfood.(*)..")
                        .should()
                        .beFreeOfCycles();

        noCycles.allowEmptyShould(true).check(dogfoodClasses);
    }
}
