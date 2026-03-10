package org.acme.test;

import org.acme.*;
import org.junit.jupiter.api.*;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.await;

/**
 * Tests for project generation from Turtle specifications.
 *
 * <p>Validates that the Joe Armstrong AGI vision:
 * "Write Turtle specification, press button, get complete application"
 *
 * <p>TODO: These tests require jgen-render-project script with execute permissions.
 */
@org.junit.jupiter.api.Disabled("Requires jgen-render-project script with execute permissions")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectGenerationIT implements WithAssertions {

    private static final Path EXAMPLES_DIR = Path.of("examples");
    private static final Path OUTPUT_BASE = Path.of("target/generated-projects");

    @BeforeAll
    static void setupOutputDirectory() throws IOException {
        Files.createDirectories(OUTPUT_BASE);
    }

    @Test
    @Order(1)
    @DisplayName("Should generate telemetry Application from simple Turtle spec")
    void shouldGenerateTelemetryApplication() throws Exception {
        Path specFile = EXAMPLES_DIR.resolve("telemetry-app.ttl");
        Path outputDir = OUTPUT_BASE.resolve("telemetry-app");

        // Skip if spec file doesn't exist
        Assumptions.assumeTrue(Files.exists(specFile),
                "Test requires examples/telemetry-app.ttl");

        // Generate project
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("./bin/jgen-render-project",
                "--spec", specFile.toString(),
                "--output", outputDir.toString(),
                "--validate");

        Process p = pb.start();
        boolean completed = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) && p.exitValue() == 0;

        assertThat(completed).isTrue();

        // Verify output structure
        assertThat(Files.exists(outputDir.resolve("pom.xml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("Dockerfile"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("src/main/java/module-info.java"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("src/main/java/org/generated/Application.java"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("src/test/java/org/generated/ApplicationIT.java"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("k8s/deployment.yaml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("k8s/service.yaml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("README.md"))).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("Should generate F1 Telemetry Application from complex Turtle spec")
    void shouldGenerateF1TelemetryApplication() throws Exception {
        Path specFile = EXAMPLES_DIR.resolve("f1-telemetry-application.ttl");
        Path outputDir = OUTPUT_BASE.resolve("f1-telemetry-app");

        // Skip if spec file doesn't exist
        Assumptions.assumeTrue(Files.exists(specFile),
                "Test requires examples/f1-telemetry-application.ttl");

        // Generate project (without validation for speed)
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("./bin/jgen-render-project",
                "--spec", specFile.toString(),
                "--output", outputDir.toString());

        Process p = pb.start();
        boolean completed = p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES) && p.exitValue() == 0;

        assertThat(completed).isTrue();

        // Verify output structure - should have all 9 infrastructure components
        assertThat(Files.exists(outputDir.resolve("pom.xml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("Dockerfile"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("docker-compose.yml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("k8s/deployment.yaml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("k8s/service.yaml"))).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("Generated POM should have correct Java 26 configuration")
    void generatedPomShouldHaveCorrectJavaVersion() throws Exception {
        Path specFile = EXAMPLES_DIR.resolve("telemetry-app.ttl");
        Path outputDir = OUTPUT_BASE.resolve("telemetry-pom-test");
        Path pomFile = outputDir.resolve("pom.xml");

        Assumptions.assumeTrue(Files.exists(specFile),
                "Test requires examples/telemetry-app.ttl");

        // Generate project
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("./bin/jgen-render-project",
                "--spec", specFile.toString(),
                "--output", outputDir.toString());

        Process p = pb.start();
        boolean completed = p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES);
        if (!completed) p.destroyForcibly();

        // Read and verify POM
        String pomContent = Files.readString(pomFile);

        assertThat(pomContent).contains("<maven.compiler.release>26</maven.compiler.release>");
        assertThat(pomContent).contains("<maven.compiler.enablePreview>true</maven.compiler.enablePreview>");
        assertThat(pomContent).contains("junit-jupiter");
        assertThat(pomContent).contains("assertj-core");
        assertThat(pomContent).contains("maven-shade-plugin");
    }

    @Test
    @Order(4)
    @DisplayName("Generated Dockerfile should use Java 26")
    void generatedDockerfileShouldUseJava26() throws Exception {
        Path specFile = EXAMPLES_DIR.resolve("telemetry-app.ttl");
        Path outputDir = OUTPUT_BASE.resolve("telemetry-docker-test");
        Path dockerFile = outputDir.resolve("Dockerfile");

        Assumptions.assumeTrue(Files.exists(specFile),
                "Test requires examples/telemetry-app.ttl");

        // Generate project
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("./bin/jgen-render-project",
                "--spec", specFile.toString(),
                "--output", outputDir.toString());

        Process p = pb.start();
        boolean completed = p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES);
        if (!completed) p.destroyForcibly();

        // Read and verify Dockerfile
        String dockerContent = Files.readString(dockerFile);

        assertThat(dockerContent).contains("eclipse-temurin:26");
        assertThat(dockerContent).contains("--enable-preview");
        assertThat(dockerContent).contains("EXPOSE");
    }
    @Test
    @Order(5)
    @DisplayName("Generated application should have supervisor strategy")
    void generatedApplicationShouldHaveSupervisorStrategy() throws Exception {
        Path specFile = EXAMPLES_DIR.resolve("telemetry-app.ttl");
        Path outputDir = OUTPUT_BASE.resolve("telemetry-supervisor-test");
        Path appFile = outputDir.resolve("src/main/java/org/generated/Application.java");

        Assumptions.assumeTrue(Files.exists(specFile),
                "Test requires examples/telemetry-app.ttl");

        // Generate project
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("./bin/jgen-render-project",
                "--spec", specFile.toString(),
                "--output", outputDir.toString());

        Process p = pb.start();
        boolean completed = p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES);
        if (!completed) p.destroyForcibly();

        // Read and verify Application.java
        String appContent = Files.readString(appFile);

        assertThat(appContent).contains("Supervisor");
        assertThat(appContent).contains("ONE_FOR_ONE");
    }
}
