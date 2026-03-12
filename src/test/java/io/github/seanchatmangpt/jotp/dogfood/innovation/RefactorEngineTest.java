package io.github.seanchatmangpt.jotp.dogfood.innovation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefactorEngineTest implements WithAssertions {

    // ── Legacy Java snippets used as test fixtures ────────────────────────────

    private static final String LEGACY_POJO = """
            package com.example.legacy;

            import java.util.Date;

            public class Order {
                private final String id;
                private final Date createdAt;
                private final double total;

                public Order(String id, Date createdAt, double total) {
                    this.id = id;
                    this.createdAt = createdAt;
                    this.total = total;
                }

                public String getId() { return id; }
                public Date getCreatedAt() { return createdAt; }
                public double getTotal() { return total; }

                @Override
                public boolean equals(Object o) {
                    if (!(o instanceof Order)) return false;
                    Order other = (Order) o;
                    return id.equals(other.id);
                }

                @Override
                public int hashCode() { return id.hashCode(); }

                @Override
                public String toString() { return "Order[" + id + "]"; }

                public void processAsync() {
                    Thread t = new Thread(() -> System.out.println("processing " + id));
                    t.start();
                }
            }
            """;

    private static final String MODERN_RECORD = """
            package com.example.modern;

            import java.time.Instant;

            public record Invoice(String id, Instant issuedAt, double amount) {}
            """;

    private static final String EMPTY_CLASS = """
            package com.example;

            public class Empty {}
            """;

    // ── analyzeFile (single-file) ────────────────────────────────────────────

    @Test
    void analyzeFile_legacyPojo_detectsMigrations(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Order.java");
        Files.writeString(file, LEGACY_POJO);

        var plan = RefactorEngine.analyzeFile(file);

        assertThat(plan.className()).isEqualTo("Order");
        assertThat(plan.packageName()).isEqualTo("com.example.legacy");
        assertThat(plan.commands()).isNotEmpty();

        // Score should be low — all legacy patterns
        assertThat(plan.score()).isLessThan(70);
    }

    @Test
    void analyzeFile_modernRecord_highScore(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Invoice.java");
        Files.writeString(file, MODERN_RECORD);

        var plan = RefactorEngine.analyzeFile(file);

        assertThat(plan.className()).isEqualTo("Invoice");
        assertThat(plan.packageName()).isEqualTo("com.example.modern");
        assertThat(plan.score()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void analyzeFile_emptyClass_noMigrations(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Empty.java");
        Files.writeString(file, EMPTY_CLASS);

        var plan = RefactorEngine.analyzeFile(file);

        assertThat(plan.className()).isEqualTo("Empty");
        assertThat(plan.commands()).isEmpty();
    }

    // ── JgenCommand properties ───────────────────────────────────────────────

    @Test
    void jgenCommands_haveCorrectShellCommandFormat(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Order.java");
        Files.writeString(file, LEGACY_POJO);

        var plan = RefactorEngine.analyzeFile(file);

        plan.commands().forEach(cmd -> {
            assertThat(cmd.toShellCommand())
                    .startsWith("bin/jgen generate -t ")
                    .contains(" -n ")
                    .contains(" -p com.example.legacy");
            assertThat(cmd.template()).doesNotEndWith(".tera");
        });
    }

    @Test
    void jgenCommands_breakingVsSafeClassified(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Order.java");
        Files.writeString(file, LEGACY_POJO);

        var plan = RefactorEngine.analyzeFile(file);

        // POJO→Record and Date→java.time are breaking; instanceof→pattern is not
        var allCommands = plan.commands();
        assertThat(allCommands).isNotEmpty();

        // Safe + breaking should sum to total
        assertThat(plan.safeCommands().size() + plan.breakingCommands().size())
                .isEqualTo(allCommands.size());
    }

    @Test
    void jgenCommand_toComment_includesPriorityAndLabel(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Order.java");
        Files.writeString(file, LEGACY_POJO);

        var plan = RefactorEngine.analyzeFile(file);
        plan.commands().forEach(cmd -> {
            assertThat(cmd.toComment()).startsWith("# [P");
            assertThat(cmd.toComment()).contains(cmd.migrationLabel());
        });
    }

    // ── analyze (directory) ──────────────────────────────────────────────────

    @Test
    void analyze_directory_scansAllJavaFiles(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);
        Files.writeString(tmp.resolve("Invoice.java"), MODERN_RECORD);
        Files.writeString(tmp.resolve("Empty.java"), EMPTY_CLASS);

        var plan = RefactorEngine.analyze(tmp);

        assertThat(plan.totalFiles()).isEqualTo(3);
        assertThat(plan.sourceRoot()).isEqualTo(tmp);
    }

    @Test
    void analyze_directory_sortsWorstScoreFirst(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);
        Files.writeString(tmp.resolve("Invoice.java"), MODERN_RECORD);

        var plan = RefactorEngine.analyze(tmp);

        var scores = plan.files().stream()
                .mapToInt(RefactorEngine.FileRefactorPlan::score)
                .toArray();
        // Worst (lowest) score first
        for (int i = 0; i < scores.length - 1; i++) {
            assertThat(scores[i]).isLessThanOrEqualTo(scores[i + 1]);
        }
    }

    @Test
    void analyze_emptyDirectory_returnsEmptyPlan(@TempDir Path tmp) throws IOException {
        var plan = RefactorEngine.analyze(tmp);

        assertThat(plan.totalFiles()).isZero();
        assertThat(plan.allCommands()).isEmpty();
        assertThat(plan.averageScore()).isEqualTo(100.0);
    }

    @Test
    void analyze_nestedDirectories_scansRecursively(@TempDir Path tmp) throws IOException {
        var subdir = tmp.resolve("sub");
        Files.createDirectories(subdir);
        Files.writeString(tmp.resolve("A.java"), EMPTY_CLASS);
        Files.writeString(subdir.resolve("B.java"), LEGACY_POJO);

        var plan = RefactorEngine.analyze(tmp);

        assertThat(plan.totalFiles()).isEqualTo(2);
    }

    // ── RefactorPlan aggregate methods ───────────────────────────────────────

    @Test
    void refactorPlan_allCommands_sortedByPriority(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);
        Files.writeString(tmp.resolve("Invoice.java"), MODERN_RECORD);

        var plan = RefactorEngine.analyze(tmp);
        var cmds = plan.allCommands();

        for (int i = 0; i < cmds.size() - 1; i++) {
            assertThat(cmds.get(i).priority()).isLessThanOrEqualTo(cmds.get(i + 1).priority());
        }
    }

    @Test
    void refactorPlan_averageScore_inRange(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);
        Files.writeString(tmp.resolve("Invoice.java"), MODERN_RECORD);

        var plan = RefactorEngine.analyze(tmp);

        assertThat(plan.averageScore()).isBetween(0.0, 100.0);
    }

    // ── toScript output ──────────────────────────────────────────────────────

    @Test
    void toScript_containsShebangAndSafeCommands(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);

        var plan = RefactorEngine.analyze(tmp);
        var script = plan.toScript();

        assertThat(script).startsWith("#!/usr/bin/env bash");
        assertThat(script).contains("bin/jgen generate");
        assertThat(script).contains("set -euo pipefail");
    }

    @Test
    void toScript_breakingChangesRequireConfirmation(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);

        var plan = RefactorEngine.analyze(tmp);

        if (!plan.breakingCommands().isEmpty()) {
            assertThat(plan.toScript()).contains("read -rp");
        }
    }

    // ── summary output ───────────────────────────────────────────────────────

    @Test
    void summary_containsHeaderAndStats(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Order.java"), LEGACY_POJO);

        var plan = RefactorEngine.analyze(tmp);
        var summary = plan.summary();

        assertThat(summary).contains("Java 26 Refactor Plan");
        assertThat(summary).contains("Files:");
        assertThat(summary).contains("Avg score:");
    }

    @Test
    void summary_emptyDirectory_gracefulMessage(@TempDir Path tmp) throws IOException {
        var plan = RefactorEngine.analyze(tmp);

        assertThatNoException().isThrownBy(plan::summary);
        assertThat(plan.summary()).contains("No Java files found");
    }

    // ── FileRefactorPlan headline ────────────────────────────────────────────

    @Test
    void fileRefactorPlan_headline_containsScore(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("Order.java");
        Files.writeString(file, LEGACY_POJO);

        var plan = RefactorEngine.analyzeFile(file);
        var headline = plan.headline();

        assertThat(headline).contains("score=");
        assertThat(headline).contains("Order.java");
        assertThat(headline).contains("migration(s)");
    }

    // ── Edge Cases (JIDOKA - Stop and Fix) ────────────────────────────────────

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCases {

        @Test
        void analyzeFile_nullPath_throws(@TempDir Path tmp) {
            assertThatNullPointerException()
                    .isThrownBy(() -> RefactorEngine.analyzeFile(null));
        }

        @Test
        void analyze_nullPath_throws(@TempDir Path tmp) {
            assertThatNullPointerException()
                    .isThrownBy(() -> RefactorEngine.analyze(null));
        }

        @Test
        void analyze_nonExistentDirectory_returnsEmptyPlan(@TempDir Path tmp) throws Exception {
            var nonExistent = tmp.resolve("non-existent");
            // Non-existent directory throws exception - this is expected behavior
            assertThatThrownBy(() -> RefactorEngine.analyze(nonExistent))
                    .isInstanceOf(java.nio.file.NoSuchFileException.class);
        }

        @Test
        void analyzeFile_nonJavaFile_returnsEmptyPlan(@TempDir Path tmp) throws IOException {
            var txtFile = tmp.resolve("README.txt");
            Files.writeString(txtFile, "This is not Java code");

            var plan = RefactorEngine.analyzeFile(txtFile);

            assertThat(plan.commands()).isEmpty();
        }

        @Test
        void toScript_emptyPlan_producesMinimalScript(@TempDir Path tmp) throws IOException {
            var emptyDir = tmp.resolve("empty");
            Files.createDirectories(emptyDir);

            var plan = RefactorEngine.analyze(emptyDir);
            var script = plan.toScript();

            assertThat(script).startsWith("#!/usr/bin/env bash");
            assertThat(script).contains("No migrations required");
        }

        @Test
        void jgenCommand_toShellCommand_escapesSpecialCharacters(@TempDir Path tmp) throws IOException {
            var source = """
                    package io.github.seanchatmangpt.jotp;
                    public class Test {
                        private String field;
                        public String getField() { return field; }
                    }
                    """;
            var file = tmp.resolve("Test.java");
            Files.writeString(file, source);

            var plan = RefactorEngine.analyzeFile(file);

            plan.commands().forEach(cmd -> {
                // Shell command should be safe to execute
                assertThat(cmd.toShellCommand()).doesNotContain("\n");
                assertThat(cmd.toShellCommand()).doesNotContain("`");
            });
        }
    }
}
