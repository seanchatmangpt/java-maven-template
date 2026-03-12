package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.*;
import org.junit.jupiter.api.*;
import org.assertj.core.api.WithAssertions;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Tests for enterprise project templates.
 *
 * <p>Validates that templates can generate complete project structures.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectTemplateIT implements WithAssertions {

    private static final Path TEMPLATE_DIR = Path.of("templates/java/enterprise");

    @Test
    @Order(1)
    @DisplayName("Template directory should contain enterprise templates")
    void templateDirectoryShouldContainEnterpriseTemplates() {
        assertThat(Files.exists(TEMPLATE_DIR)).isTrue();

        // Verify essential templates exist
        assertThat(Files.exists(TEMPLATE_DIR.resolve("project-pom.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("module-info.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("application-main.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("dockerfile.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("docker-compose.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("kubernetes-deployment.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("kubernetes-service.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("application-it.tera"))).isTrue();
        assertThat(Files.exists(TEMPLATE_DIR.resolve("github-actions.tera"))).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("POM template should contain Java 26 configuration")
    void pomTemplateShouldContainJava26Configuration() throws IOException {
        Path pomTemplate = TEMPLATE_DIR.resolve("project-pom.tera");
        String content = Files.readString(pomTemplate);

        assertThat(content).contains("<maven.compiler.release>26</maven.compiler.release>");
        assertThat(content).contains("<maven.compiler.enablePreview>true</maven.compiler.enablePreview>");
        assertThat(content).contains("junit-jupiter");
        assertThat(content).contains("assertj-core");
        assertThat(content).contains("maven-shade-plugin");
    }

    @Test
    @Order(3)
    @DisplayName("Dockerfile template should use Java 26")
    void dockerfileTemplateShouldUseJava26() throws IOException {
        Path dockerTemplate = TEMPLATE_DIR.resolve("dockerfile.tera");
        String content = Files.readString(dockerTemplate);

        assertThat(content).contains("eclipse-temurin:26");
        assertThat(content).contains("--enable-preview");
    }

    @Test
    @Order(4)
    @DisplayName("Kubernetes deployment template should have proper probes")
    void kubernetesDeploymentTemplateShouldHaveProbes() throws IOException {
        Path k8sTemplate = TEMPLATE_DIR.resolve("kubernetes-deployment.tera");
        String content = Files.readString(k8sTemplate);

        assertThat(content).contains("livenessProbe");
        assertThat(content).contains("readinessProbe");
        assertThat(content).contains("/health");
        assertThat(content).contains("/ready");
    }

    @Test
    @Order(5)
    @DisplayName("Integration test template should test all infrastructure")
    void integrationTestTemplateShouldTestAllInfrastructure() throws IOException {
        Path itTemplate = TEMPLATE_DIR.resolve("application-it.tera");
        String content = Files.readString(itTemplate);

        assertThat(content).contains("Application starts and stops correctly");
        assertThat(content).contains("Supervisor");
        assertThat(content).contains("MessageBus");
        assertThat(content).contains("HealthChecker");
        assertThat(content).contains("ApiGateway");
        assertThat(content).contains("MetricsCollector");
    }

    @Test
    @Order(6)
    @DisplayName("Module-info template should export base package")
    void moduleInfoTemplateShouldExportBasePackage() throws IOException {
        Path moduleTemplate = TEMPLATE_DIR.resolve("module-info.tera");
        String content = Files.readString(moduleTemplate);

        assertThat(content).contains("exports");
        assertThat(content).contains("opens");
        assertThat(content).contains("org.junit.platform.commons");
    }

    @Test
    @Order(7)
    @DisplayName("GitHub Actions template should have CI/CD pipeline")
    void githubActionsTemplateShouldHaveCICDPipeline() throws IOException {
        Path ghTemplate = TEMPLATE_DIR.resolve("github-actions.tera");
        String content = Files.readString(ghTemplate);

        // YAML format has on: and push: on separate lines
        assertThat(content).contains("on:");
        assertThat(content).contains("push:");
        assertThat(content).contains("pull_request:");
        assertThat(content).contains("Unit Tests");
        assertThat(content).contains("Integration Tests");
        assertThat(content).contains("Docker");
        assertThat(content).contains("Kubernetes");
    }

    @Test
    @Order(8)
    @DisplayName("Application main template should use JOTP primitives")
    void applicationMainTemplateShouldUseJOTPPrimitives() throws IOException {
        Path appTemplate = TEMPLATE_DIR.resolve("application-main.tera");
        String content = Files.readString(appTemplate);

        assertThat(content).contains("org.acme");
        assertThat(content).contains("Application");
        assertThat(content).contains("Supervisor");
        assertThat(content).contains("service");  // Service registration
        assertThat(content).contains("start()");  // Lifecycle
    }

    @Test
    @Order(9)
    @DisplayName("Enterprise ontology should reference messaging patterns")
    void enterpriseOntologyShouldReferenceMessagingPatterns() throws IOException {
        Path enterpriseOntology = Path.of("schema/java-enterprise.ttl");
        String content = Files.readString(enterpriseOntology);

        assertThat(content).contains("app:Application");
        assertThat(content).contains("app:Service");
        assertThat(content).contains("app:Infrastructure");
        assertThat(content).contains("app:MessageBus");
        assertThat(content).contains("app:EventStore");
        assertThat(content).contains("app:CircuitBreaker");
        assertThat(content).contains("app:ApiGateway");
        assertThat(content).contains("app:CQRS");
    }
}
