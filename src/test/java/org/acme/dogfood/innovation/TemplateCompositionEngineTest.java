package org.acme.dogfood.innovation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.acme.dogfood.innovation.TemplateCompositionEngine.CompositionResult;
import org.acme.dogfood.innovation.TemplateCompositionEngine.FeatureRecipe;
import org.acme.dogfood.innovation.TemplateCompositionEngine.TemplateRef;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TemplateCompositionEngine}.
 *
 * <p>Validates recipe composition, template existence checks against the real templates/ directory,
 * variable resolution, and the built-in recipe factories.
 */
@DisplayName("TemplateCompositionEngine")
class TemplateCompositionEngineTest implements WithAssertions {

    private static final Path TEMPLATES_ROOT =
            Path.of(System.getProperty("user.dir")).resolve("templates");

    private TemplateCompositionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TemplateCompositionEngine(TEMPLATES_ROOT);
    }

    // ---------- TemplateRef ----------

    @Nested
    @DisplayName("TemplateRef")
    class TemplateRefTests {

        @Test
        @DisplayName("should compute correct template path")
        void shouldComputeCorrectTemplatePath() {
            var ref = TemplateRef.of("core", "record");
            assertThat(ref.templatePath()).isEqualTo("java/core/record.tera");
        }

        @Test
        @DisplayName("should reject null category")
        void shouldRejectNullCategory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TemplateRef(null, "record", Map.of()));
        }

        @Test
        @DisplayName("should reject blank name")
        void shouldRejectBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TemplateRef("core", "  ", Map.of()));
        }

        @Test
        @DisplayName("should create defensive copy of vars")
        void shouldCreateDefensiveCopy() {
            var mutable = new java.util.HashMap<String, String>();
            mutable.put("key", "value");
            var ref = new TemplateRef("core", "record", mutable);
            mutable.put("extra", "sneaky");
            assertThat(ref.vars()).doesNotContainKey("extra");
        }

        @Test
        @DisplayName("convenience factory with single variable should work")
        void convenienceFactorySingleVar() {
            var ref = TemplateRef.of("core", "record", "entity_name", "Order");
            assertThat(ref.vars()).containsEntry("entity_name", "Order").hasSize(1);
        }
    }

    // ---------- FeatureRecipe ----------

    @Nested
    @DisplayName("FeatureRecipe")
    class FeatureRecipeTests {

        @Test
        @DisplayName("should reject empty templates list")
        void shouldRejectEmptyTemplates() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new FeatureRecipe(
                                            "Empty", "desc", List.of(), List.of()));
        }

        @Test
        @DisplayName("should reject blank name")
        void shouldRejectBlankName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new FeatureRecipe(
                                            " ",
                                            "desc",
                                            List.of(TemplateRef.of("core", "record")),
                                            List.of()));
        }

        @Test
        @DisplayName("should create defensive copy of templates list")
        void shouldDefensivelyCopyTemplates() {
            var mutableList = new java.util.ArrayList<>(List.of(TemplateRef.of("core", "record")));
            var recipe = new FeatureRecipe("R", "desc", mutableList, List.of());
            mutableList.add(TemplateRef.of("core", "sealed-interface"));
            assertThat(recipe.templates()).hasSize(1);
        }
    }

    // ---------- compose() ----------

    @Nested
    @DisplayName("compose")
    class ComposeTests {

        @Test
        @DisplayName("should succeed for recipe with valid templates")
        void shouldSucceedForValidTemplates() {
            var recipe = new FeatureRecipe(
                    "SimpleFeature",
                    "A minimal feature",
                    List.of(
                            new TemplateRef(
                                    "core",
                                    "record",
                                    Map.of("entity_name", "Order", "package", "org.acme.domain")),
                            new TemplateRef(
                                    "testing",
                                    "junit5-test",
                                    Map.of(
                                            "entity_name", "Order",
                                            "package", "org.acme.domain"))),
                    List.of("core/record"));

            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;
            assertThat(success.featureName()).isEqualTo("SimpleFeature");
            assertThat(success.generatedFiles()).hasSize(2);
            assertThat(success.generatedFiles().getFirst())
                    .contains("Order")
                    .endsWith(".java");
        }

        @Test
        @DisplayName("should fail when template does not exist on disk")
        void shouldFailForMissingTemplate() {
            var recipe = new FeatureRecipe(
                    "BrokenFeature",
                    "references a non-existent template",
                    List.of(TemplateRef.of("core", "does-not-exist")),
                    List.of());

            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Failure.class);
            var failure = (CompositionResult.Failure) result;
            assertThat(failure.featureName()).isEqualTo("BrokenFeature");
            assertThat(failure.errors())
                    .singleElement()
                    .asString()
                    .contains("Template not found")
                    .contains("core/does-not-exist");
        }

        @Test
        @DisplayName("should fail when dependency references unknown template")
        void shouldFailForBadDependency() {
            var recipe = new FeatureRecipe(
                    "BadDep",
                    "dependency on non-listed template",
                    List.of(TemplateRef.of("core", "record")),
                    List.of("patterns/service-layer"));

            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Failure.class);
            var failure = (CompositionResult.Failure) result;
            assertThat(failure.errors())
                    .anyMatch(e -> e.contains("patterns/service-layer"));
        }

        @Test
        @DisplayName("should collect multiple errors")
        void shouldCollectMultipleErrors() {
            var recipe = new FeatureRecipe(
                    "MultiError",
                    "multiple bad templates",
                    List.of(
                            TemplateRef.of("core", "nonexistent-a"),
                            TemplateRef.of("testing", "nonexistent-b")),
                    List.of());

            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Failure.class);
            var failure = (CompositionResult.Failure) result;
            assertThat(failure.errors()).hasSize(2);
        }

        @Test
        @DisplayName("should resolve shared variables across templates")
        void shouldResolveSharedVariables() {
            var recipe = new FeatureRecipe(
                    "VarResolution",
                    "test variable propagation",
                    List.of(
                            new TemplateRef(
                                    "core",
                                    "record",
                                    Map.of("entity_name", "Product", "package", "org.acme.shop")),
                            new TemplateRef(
                                    "patterns",
                                    "repository-generic",
                                    Map.of("repo_type", "JPA"))),
                    List.of());

            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;

            // The repository template should inherit entity_name and package from shared context
            var repoVars = success.resolvedVariables().get("patterns/repository-generic");
            assertThat(repoVars)
                    .containsEntry("entity_name", "Product")
                    .containsEntry("package", "org.acme.shop")
                    .containsEntry("repo_type", "JPA");
        }

        @Test
        @DisplayName("should generate distinct file paths for each template")
        void shouldGenerateDistinctPaths() {
            var recipe = new FeatureRecipe(
                    "DistinctPaths",
                    "no file collisions",
                    List.of(
                            new TemplateRef(
                                    "core",
                                    "record",
                                    Map.of("entity_name", "Invoice", "package", "org.acme.billing")),
                            new TemplateRef(
                                    "patterns",
                                    "repository-generic",
                                    Map.of(
                                            "entity_name", "Invoice",
                                            "package", "org.acme.billing")),
                            new TemplateRef(
                                    "testing",
                                    "junit5-test",
                                    Map.of(
                                            "entity_name", "Invoice",
                                            "package", "org.acme.billing"))),
                    List.of());

            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;
            assertThat(success.generatedFiles()).doesNotHaveDuplicates();
            assertThat(success.generatedFiles())
                    .anyMatch(f -> f.endsWith("Invoice.java"))
                    .anyMatch(f -> f.endsWith("InvoiceRepository.java"))
                    .anyMatch(f -> f.endsWith("InvoiceTest.java"));
        }
    }

    // ---------- built-in recipe factories ----------

    @Nested
    @DisplayName("built-in recipe factories")
    class RecipeFactoryTests {

        @Test
        @DisplayName("crudFeature should compose successfully")
        void crudFeatureShouldCompose() {
            var recipe = TemplateCompositionEngine.crudFeature("Customer");
            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;
            assertThat(success.featureName()).isEqualTo("CustomerCrud");
            assertThat(success.generatedFiles()).hasSize(7);
            assertThat(success.generatedFiles())
                    .anyMatch(f -> f.endsWith("Customer.java"))
                    .anyMatch(f -> f.endsWith("CustomerRepository.java"))
                    .anyMatch(f -> f.endsWith("CustomerService.java"))
                    .anyMatch(f -> f.endsWith("CustomerDto.java"))
                    .anyMatch(f -> f.endsWith("CustomerTest.java"))
                    .anyMatch(f -> f.endsWith("CustomerPropertyTest.java"))
                    .anyMatch(f -> f.endsWith("CustomerArchTest.java"));
        }

        @Test
        @DisplayName("valueObjectFeature should compose successfully")
        void valueObjectFeatureShouldCompose() {
            var recipe = TemplateCompositionEngine.valueObjectFeature("Money");
            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;
            assertThat(success.featureName()).isEqualTo("MoneyValueObject");
            assertThat(success.generatedFiles()).hasSize(5);
        }

        @Test
        @DisplayName("serviceLayerFeature should compose successfully")
        void serviceLayerFeatureShouldCompose() {
            var recipe = TemplateCompositionEngine.serviceLayerFeature("Payment");
            var result = engine.compose(recipe);

            assertThat(result).isInstanceOf(CompositionResult.Success.class);
            var success = (CompositionResult.Success) result;
            assertThat(success.featureName()).isEqualTo("PaymentService");
            assertThat(success.generatedFiles()).hasSize(7);
        }

        @Test
        @DisplayName("crudFeature should reject null entity name")
        void crudFeatureShouldRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TemplateCompositionEngine.crudFeature(null));
        }

        @Test
        @DisplayName("valueObjectFeature should reject null name")
        void valueObjectFeatureShouldRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TemplateCompositionEngine.valueObjectFeature(null));
        }

        @Test
        @DisplayName("serviceLayerFeature should reject null name")
        void serviceLayerFeatureShouldRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TemplateCompositionEngine.serviceLayerFeature(null));
        }
    }

    // ---------- CompositionResult sealed interface ----------

    @Nested
    @DisplayName("CompositionResult pattern matching")
    class CompositionResultTests {

        @Test
        @DisplayName("should exhaustively switch over sealed variants")
        void shouldExhaustivelySwitchOverVariants() {
            var recipe = TemplateCompositionEngine.crudFeature("Widget");
            var result = engine.compose(recipe);

            var message = switch (result) {
                case CompositionResult.Success s ->
                        "Generated %d files for %s".formatted(
                                s.generatedFiles().size(), s.featureName());
                case CompositionResult.Failure f ->
                        "Failed %s with %d errors".formatted(
                                f.featureName(), f.errors().size());
            };

            assertThat(message).startsWith("Generated 7 files for WidgetCrud");
        }
    }
}
