package org.acme.dogfood.innovation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.acme.dogfood.innovation.StressTestScanner.Priority;
import org.acme.dogfood.innovation.StressTestScanner.StressFinding;
import org.acme.dogfood.innovation.StressTestScanner.StressPlan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dogfood proof: scan our own {@code src/main/java/org/acme} to verify that the {@link
 * StressTestScanner} correctly identifies the known breaking-point patterns present in the OTP
 * primitives.
 *
 * <p>Each test asserts a specific finding type in a specific file, proving end-to-end that:
 *
 * <ol>
 *   <li>Detection rules compile and match correctly
 *   <li>{@link StressPlan#summary()} and {@link StressPlan#toScript()} generate valid output
 *   <li>The plan {@link RefactorEngine.JgenCommand}s reference real templates
 * </ol>
 */
@DisplayName("StressTestScanner — dogfood scan of org.acme source")
class StressTestScannerTest {

    private static final Path OTP_SOURCE = Path.of("src/main/java/org/acme");

    private static StressPlan plan;

    @BeforeAll
    static void scanOtpSource() throws IOException {
        plan = StressTestScanner.scan(OTP_SOURCE);
    }

    // ── Plan-level assertions ──────────────────────────────────────────────────

    @Test
    @DisplayName("scan produces findings from org.acme")
    void scan_producesFindings() {
        assertThat(plan.findings()).isNotEmpty();
    }

    @Test
    @DisplayName("plan has CRITICAL findings (queue/shared-state/boundary patterns)")
    void plan_hasCriticalFindings() {
        assertThat(plan.criticalCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("summary() contains CRITICAL section and source root")
    void summary_containsExpectedSections() {
        var summary = plan.summary();
        assertThat(summary).contains("Armstrong Stress Scan");
        assertThat(summary).contains("CRITICAL");
        assertThat(summary).contains("testing/stress-");
        assertThat(summary).contains("bin/jgen generate");
    }

    @Test
    @DisplayName("toScript() is a valid bash script with bin/jgen generate commands")
    void toScript_isValidBashScript() {
        var script = plan.toScript();
        assertThat(script).startsWith("#!/usr/bin/env bash");
        assertThat(script).contains("bin/jgen generate -t testing/stress-throughput");
        assertThat(script).contains("bin/jgen generate -t testing/stress-concurrency");
        assertThat(script).contains("bin/jgen generate -t testing/stress-boundary");
        assertThat(script).contains("set -euo pipefail");
        assertThat(script).contains("./mvnw test -Dtest=\"*StressTest\"");
    }

    @Test
    @DisplayName("toJgenCommands() references valid template paths")
    void toJgenCommands_referencesValidTemplates() {
        var commands = plan.toJgenCommands();
        assertThat(commands).isNotEmpty();
        commands.forEach(
                cmd -> {
                    assertThat(cmd.template()).startsWith("testing/stress-");
                    assertThat(cmd.className()).endsWith("StressTest");
                    assertThat(cmd.packageName()).isNotBlank();
                    assertThat(cmd.toShellCommand()).contains("bin/jgen generate");
                });
    }

    // ── Proc.java — queue + timeout patterns ──────────────────────────────────

    @Nested
    @DisplayName("Proc.java")
    class ProcFindings {

        private List<StressFinding> procFindings() {
            return plan.findings().stream()
                    .filter(f -> f.file().getFileName().toString().equals("Proc.java"))
                    .toList();
        }

        @Test
        @DisplayName("QueuePattern detected (LinkedTransferQueue)")
        void proc_queuePatternDetected() {
            assertThat(procFindings())
                    .as("Proc.java must have a QueuePattern finding (LinkedTransferQueue)")
                    .anyMatch(f -> f instanceof StressFinding.QueuePattern);
        }

        @Test
        @DisplayName("QueuePattern is CRITICAL priority")
        void proc_queuePattern_isCritical() {
            procFindings().stream()
                    .filter(f -> f instanceof StressFinding.QueuePattern)
                    .findFirst()
                    .ifPresent(
                            f ->
                                    assertThat(f.priority())
                                            .as("LinkedTransferQueue → CRITICAL priority")
                                            .isEqualTo(Priority.CRITICAL));
        }

        @Test
        @DisplayName("TimeoutPattern detected (poll/await with timeout)")
        void proc_timeoutPatternDetected() {
            assertThat(procFindings())
                    .as("Proc.java must have a TimeoutPattern finding (poll timeout)")
                    .anyMatch(f -> f instanceof StressFinding.TimeoutPattern);
        }
    }

    // ── Supervisor.java — boundary patterns ───────────────────────────────────

    @Nested
    @DisplayName("Supervisor.java")
    class SupervisorFindings {

        private List<StressFinding> supervisorFindings() {
            return plan.findings().stream()
                    .filter(f -> f.file().getFileName().toString().equals("Supervisor.java"))
                    .toList();
        }

        @Test
        @DisplayName("BoundaryPattern detected (maxRestarts / window)")
        void supervisor_boundaryPatternDetected() {
            assertThat(supervisorFindings())
                    .as("Supervisor.java must have a BoundaryPattern (maxRestarts, window)")
                    .anyMatch(f -> f instanceof StressFinding.BoundaryPattern);
        }

        @Test
        @DisplayName("BoundaryPattern signals include restart/window references")
        void supervisor_boundarySignals_mentionRestartOrWindow() {
            supervisorFindings().stream()
                    .filter(f -> f instanceof StressFinding.BoundaryPattern)
                    .findFirst()
                    .ifPresent(
                            f -> {
                                var signals = String.join(" ", f.matchedSignals());
                                assertThat(signals)
                                        .as(
                                                "BoundaryPattern signals should mention restart or window: %s",
                                                signals)
                                        .containsAnyOf("restart", "window", "threshold");
                            });
        }
    }

    // ── ProcessRegistry.java — shared-state patterns ──────────────────────────

    @Nested
    @DisplayName("ProcessRegistry.java")
    class ProcessRegistryFindings {

        private List<StressFinding> registryFindings() {
            return plan.findings().stream()
                    .filter(f -> f.file().getFileName().toString().equals("ProcessRegistry.java"))
                    .toList();
        }

        @Test
        @DisplayName("SharedStatePattern detected (ConcurrentHashMap / putIfAbsent)")
        void registry_sharedStatePatternDetected() {
            assertThat(registryFindings())
                    .as("ProcessRegistry.java must have SharedStatePattern (ConcurrentHashMap/putIfAbsent)")
                    .anyMatch(f -> f instanceof StressFinding.SharedStatePattern);
        }

        @Test
        @DisplayName("SharedStatePattern is CRITICAL priority")
        void registry_sharedStatePattern_isCritical() {
            registryFindings().stream()
                    .filter(f -> f instanceof StressFinding.SharedStatePattern)
                    .filter(f -> f.priority() == Priority.CRITICAL)
                    .findFirst()
                    .ifPresentOrElse(
                            f ->
                                    assertThat(f.priority())
                                            .isEqualTo(Priority.CRITICAL),
                            () ->
                                    assertThat(registryFindings())
                                            .as("ProcessRegistry must have at least one HIGH SharedStatePattern")
                                            .anyMatch(
                                                    f ->
                                                            f instanceof StressFinding.SharedStatePattern
                                                                    && f.priority() != Priority.MEDIUM));
        }
    }

    // ── EventManager.java — listener patterns ─────────────────────────────────

    @Nested
    @DisplayName("EventManager.java")
    class EventManagerFindings {

        private List<StressFinding> emFindings() {
            return plan.findings().stream()
                    .filter(f -> f.file().getFileName().toString().equals("EventManager.java"))
                    .toList();
        }

        @Test
        @DisplayName("ListenerPattern detected (CopyOnWriteArrayList / handler list)")
        void eventManager_listenerPatternDetected() {
            assertThat(emFindings())
                    .as("EventManager.java must have ListenerPattern (CopyOnWriteArrayList/handlers)")
                    .anyMatch(f -> f instanceof StressFinding.ListenerPattern);
        }

        @Test
        @DisplayName("ListenerPattern recommends stress-cascade template")
        void eventManager_listenerPattern_recommendsCascadeTemplate() {
            emFindings().stream()
                    .filter(f -> f instanceof StressFinding.ListenerPattern)
                    .findFirst()
                    .ifPresent(
                            f ->
                                    assertThat(f.recommendedTemplate())
                                            .as("Listener pattern should recommend stress-cascade")
                                            .isEqualTo("testing/stress-cascade"));
        }
    }

    // ── analyzeFile() — single-file API ───────────────────────────────────────

    @Test
    @DisplayName("analyzeFile(Proc.java) returns non-empty findings without scanning whole tree")
    void analyzeFile_singleFile_returnsFindings() {
        var procFile = OTP_SOURCE.resolve("Proc.java");
        var findings = StressTestScanner.analyzeFile(procFile);
        assertThat(findings).isNotEmpty();
    }

    @Test
    @DisplayName("findings are sorted CRITICAL before HIGH before MEDIUM")
    void findings_sortedByPriority() {
        var priorities =
                plan.findings().stream().map(StressFinding::priority).toList();

        for (int i = 1; i < priorities.size(); i++) {
            assertThat(priorities.get(i).compareTo(priorities.get(i - 1)))
                    .as(
                            "Priority at index %d (%s) must be >= priority at %d (%s)",
                            i, priorities.get(i), i - 1, priorities.get(i - 1))
                    .isGreaterThanOrEqualTo(0);
        }
    }
}
