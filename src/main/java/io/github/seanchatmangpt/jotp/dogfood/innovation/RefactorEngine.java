package io.github.seanchatmangpt.jotp.dogfood.innovation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Refactor Engine — the missing orchestrator that chains {@link OntologyMigrationEngine}, {@link
 * ModernizationScorer}, and {@link TemplateCompositionEngine} into a single "instant refactor"
 * pipeline.
 *
 * <p>Given any Java source directory, this engine:
 *
 * <ol>
 *   <li>Walks all {@code .java} files in the tree
 *   <li>Analyzes each file with the ontology-driven migration engine
 *   <li>Scores each file with the modernization scorer (0-100)
 *   <li>Maps detected {@link OntologyMigrationEngine.MigrationPlan} instances to concrete {@code
 *       jgen generate} commands
 *   <li>Produces a {@link RefactorPlan} containing per-file plans, an aggregate score, and a
 *       ready-to-run migration shell script
 * </ol>
 *
 * <p>This is the 80/20 blue-ocean innovation: all the raw analysis power was already in place; the
 * engine wires it into a zero-friction, one-command migration experience.
 *
 * <pre>{@code
 * var plan = RefactorEngine.analyze(Path.of("./legacy-project/src"));
 * System.out.println(plan.summary());
 * Files.writeString(Path.of("migrate.sh"), plan.toScript());
 * }</pre>
 */
public final class RefactorEngine {

    private RefactorEngine() {}

    // ── Package extractor ─────────────────────────────────────────────────────

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    // ── Public types ──────────────────────────────────────────────────────────

    /**
     * A concrete {@code jgen generate} command derived from a single migration plan.
     *
     * @param template the template path, e.g. {@code core/record}
     * @param className the Java type name to generate (derived from the source file name)
     * @param packageName the target package (extracted from the source file)
     * @param migrationLabel human-readable description of the migration
     * @param priority rule priority (1 = highest)
     * @param breaking whether the migration is a breaking change
     */
    public record JgenCommand(
            String template,
            String className,
            String packageName,
            String migrationLabel,
            int priority,
            boolean breaking) {

        public JgenCommand {
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(className, "className must not be null");
            Objects.requireNonNull(packageName, "packageName must not be null");
            Objects.requireNonNull(migrationLabel, "migrationLabel must not be null");
        }

        /** Returns the full shell command string for this migration. */
        public String toShellCommand() {
            return "bin/jgen generate -t %s -n %s -p %s".formatted(template, className, packageName);
        }

        /** Returns a comment summarizing this command for the generated script. */
        public String toComment() {
            return "# [P%d%s] %s".formatted(priority, breaking ? " BREAKING" : "", migrationLabel);
        }
    }

    /**
     * The complete refactor plan for a single source file.
     *
     * @param file path to the analyzed Java source file
     * @param className simple class name (derived from filename)
     * @param packageName package extracted from the source
     * @param migrationAnalysis ontology-driven migration analysis
     * @param score modernization score (0-100)
     * @param commands concrete {@code jgen generate} commands, sorted by priority
     */
    public record FileRefactorPlan(
            Path file,
            String className,
            String packageName,
            OntologyMigrationEngine.AnalysisResult migrationAnalysis,
            int score,
            List<JgenCommand> commands) {

        public FileRefactorPlan {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(className, "className must not be null");
            Objects.requireNonNull(packageName, "packageName must not be null");
            Objects.requireNonNull(migrationAnalysis, "migrationAnalysis must not be null");
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("score must be 0-100, got " + score);
            }
            commands = List.copyOf(commands);
        }

        /** Commands that are safe (non-breaking) to apply automatically. */
        public List<JgenCommand> safeCommands() {
            return commands.stream().filter(c -> !c.breaking()).toList();
        }

        /** Commands that involve breaking changes requiring manual review. */
        public List<JgenCommand> breakingCommands() {
            return commands.stream().filter(JgenCommand::breaking).toList();
        }

        /** One-line file summary: score + command count. */
        public String headline() {
            return "[score=%3d] %s — %d migration(s), %d safe / %d breaking".formatted(
                    score,
                    file.getFileName(),
                    commands.size(),
                    safeCommands().size(),
                    breakingCommands().size());
        }
    }

    /**
     * The aggregate refactor plan for an entire source tree.
     *
     * @param sourceRoot the root directory that was analyzed
     * @param files per-file refactor plans, sorted worst-score first (highest ROI first)
     */
    public record RefactorPlan(Path sourceRoot, List<FileRefactorPlan> files) {

        public RefactorPlan {
            Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
            files = List.copyOf(files);
        }

        /** Total number of files analyzed. */
        public int totalFiles() {
            return files.size();
        }

        /** Average modernization score across all files (0-100). */
        public double averageScore() {
            if (files.isEmpty()) return 100.0;
            return files.stream().mapToInt(FileRefactorPlan::score).average().orElse(100.0);
        }

        /** All distinct {@link JgenCommand} instances across the entire codebase, by priority. */
        public List<JgenCommand> allCommands() {
            return files.stream()
                    .flatMap(f -> f.commands().stream())
                    .sorted(Comparator.comparingInt(JgenCommand::priority))
                    .toList();
        }

        /** Safe (non-breaking) commands across the entire codebase, sorted by priority. */
        public List<JgenCommand> safeCommands() {
            return allCommands().stream().filter(c -> !c.breaking()).toList();
        }

        /** Breaking commands across the entire codebase, sorted by priority. */
        public List<JgenCommand> breakingCommands() {
            return allCommands().stream().filter(JgenCommand::breaking).toList();
        }

        /**
         * Produces a human-readable migration report.
         *
         * <p>Output structure:
         *
         * <pre>
         * ╔══ Java 26 Refactor Plan ═══════════════════════════════╗
         *  Source:      ./legacy/src
         *  Files:       42  |  Avg score: 34/100  |  Total migrations: 87
         * ╚═══════════════════════════════════════════════════════╝
         *
         * Per-file breakdown (worst first):
         *   [score= 12] Legacy.java — 5 migration(s), 3 safe / 2 breaking
         *   ...
         *
         * Top safe migrations (apply first):
         *   bin/jgen generate -t core/record -n Order -p com.example
         *   ...
         *
         * Breaking changes (review before applying):
         *   ...
         * </pre>
         */
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══ Java 26 Refactor Plan ══════════════════════════════════════╗\n");
            sb.append("  Source:      ").append(sourceRoot).append("\n");
            sb.append("  Files:       ").append(totalFiles());
            sb.append("  |  Avg score: ").append("%.0f".formatted(averageScore())).append("/100");
            sb.append("  |  Total migrations: ").append(allCommands().size()).append("\n");
            sb.append("╚════════════════════════════════════════════════════════════════╝\n\n");

            if (files.isEmpty()) {
                sb.append("No Java files found in ").append(sourceRoot).append("\n");
                return sb.toString();
            }

            sb.append("Per-file breakdown (worst score first):\n");
            files.forEach(f -> sb.append("  ").append(f.headline()).append("\n"));

            var safe = safeCommands();
            if (!safe.isEmpty()) {
                sb.append("\nTop safe migrations (apply immediately):\n");
                safe.stream().limit(10).forEach(cmd -> {
                    sb.append("  ").append(cmd.toComment()).append("\n");
                    sb.append("  ").append(cmd.toShellCommand()).append("\n");
                });
                if (safe.size() > 10) {
                    sb.append("  ... and ").append(safe.size() - 10).append(" more (see migrate.sh)\n");
                }
            }

            var breaking = breakingCommands();
            if (!breaking.isEmpty()) {
                sb.append("\nBreaking changes (review before applying):\n");
                breaking.stream().limit(5).forEach(cmd -> {
                    sb.append("  ").append(cmd.toComment()).append("\n");
                    sb.append("  ").append(cmd.toShellCommand()).append("\n");
                });
                if (breaking.size() > 5) {
                    sb.append("  ... and ").append(breaking.size() - 5).append(" more\n");
                }
            }

            return sb.toString();
        }

        /**
         * Produces an executable bash script that applies all migrations in priority order: safe
         * migrations first, breaking changes after a confirmation prompt.
         */
        public String toScript() {
            var sb = new StringBuilder();
            sb.append("#!/usr/bin/env bash\n");
            sb.append("# =================================================================\n");
            sb.append("# Java 26 Migration Script — auto-generated by RefactorEngine\n");
            sb.append("# Source: ").append(sourceRoot).append("\n");
            sb.append("# Files:  ").append(totalFiles()).append("\n");
            sb.append("# Score:  ").append("%.0f".formatted(averageScore())).append("/100\n");
            sb.append("# =================================================================\n");
            sb.append("set -euo pipefail\n\n");

            var safe = safeCommands();
            var breaking = breakingCommands();

            if (safe.isEmpty() && breaking.isEmpty()) {
                sb.append("echo 'No migrations required - codebase is modern!'\n");
            } else {
                if (!safe.isEmpty()) {
                    sb.append("# ── Safe migrations (non-breaking) ──────────────────────────────\n");
                    safe.forEach(cmd -> {
                        sb.append(cmd.toComment()).append("\n");
                        sb.append(cmd.toShellCommand()).append("\n\n");
                    });
                }

                if (!breaking.isEmpty()) {
                    sb.append("\n# ── Breaking changes — review before running ──────────────────\n");
                    sb.append("read -rp 'Apply breaking changes? (y/N) ' confirm\n");
                    sb.append("[[ \"$confirm\" =~ ^[Yy]$ ]] || exit 0\n\n");
                    breaking.forEach(cmd -> {
                        sb.append(cmd.toComment()).append("\n");
                        sb.append(cmd.toShellCommand()).append("\n\n");
                    });
                }

                sb.append("echo 'Migration complete. Run: bin/jgen verify'\n");
            }
            return sb.toString();
        }
    }

    // ── Core analysis API ─────────────────────────────────────────────────────

    /**
     * Analyzes all Java source files under {@code sourceRoot} and produces a complete
     * {@link RefactorPlan}.
     *
     * @param sourceRoot root directory to scan (recursively)
     * @return a refactor plan with per-file analysis and aggregate commands
     * @throws IOException if the directory cannot be read
     */
    public static RefactorPlan analyze(Path sourceRoot) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");

        var scorer = new ModernizationScorer();
        List<FileRefactorPlan> plans;

        try (var stream = Files.walk(sourceRoot)) {
            plans = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .map(file -> analyzeFile(file, scorer))
                    .sorted(Comparator.comparingInt(FileRefactorPlan::score)) // worst first
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        return new RefactorPlan(sourceRoot, plans);
    }

    /**
     * Analyzes a single Java source file without walking a directory. Useful for targeted
     * analysis or testing.
     *
     * @param file path to the {@code .java} file
     * @return a per-file refactor plan
     * @throws IOException if the file cannot be read
     */
    public static FileRefactorPlan analyzeFile(Path file) throws IOException {
        return analyzeFile(file, new ModernizationScorer());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static FileRefactorPlan analyzeFile(Path file, ModernizationScorer scorer) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }

        var fileName = file.getFileName().toString();
        var className = fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - 5)
                : fileName;
        var packageName = extractPackage(source);

        var migrationResult = OntologyMigrationEngine.analyze(fileName, source);
        var scoreResult = scorer.analyze(source);
        var commands = toJgenCommands(migrationResult, className, packageName);

        return new FileRefactorPlan(
                file, className, packageName, migrationResult, scoreResult.overallScore(), commands);
    }

    private static String extractPackage(String source) {
        var matcher = PACKAGE_DECL.matcher(source);
        return matcher.find() ? matcher.group(1) : "org.example";
    }

    /**
     * Maps each {@link OntologyMigrationEngine.MigrationPlan} to a concrete {@link JgenCommand}.
     * The template path comes directly from {@code plan.rule().targetTemplate()} — stripping the
     * {@code .tera} suffix.
     */
    private static List<JgenCommand> toJgenCommands(
            OntologyMigrationEngine.AnalysisResult analysis,
            String className,
            String packageName) {

        return analysis.byPriority().stream()
                .map(plan -> {
                    var rawTemplate = plan.rule().targetTemplate();
                    var template = rawTemplate.endsWith(".tera")
                            ? rawTemplate.substring(0, rawTemplate.length() - 5)
                            : rawTemplate;

                    // Derive a migration-specific class name where appropriate
                    var targetName = targetClassName(plan, className);

                    return new JgenCommand(
                            template,
                            targetName,
                            packageName,
                            plan.rule().label(),
                            plan.rule().priority(),
                            plan.rule().breaking());
                })
                .collect(Collectors.toList());
    }

    /**
     * Derives a generated class name from the migration plan type. For most migrations, the
     * original class name is reused. For structural migrations (e.g., POJO→Record), the original
     * name is kept because the record replaces the class in-place.
     */
    private static String targetClassName(
            OntologyMigrationEngine.MigrationPlan plan, String className) {
        return switch (plan) {
            case OntologyMigrationEngine.MigrationPlan.RecordMigration ignored -> className;
            case OntologyMigrationEngine.MigrationPlan.LambdaMigration l ->
                    className + "Lambda";
            case OntologyMigrationEngine.MigrationPlan.PatternMatchMigration ignored ->
                    className;
            case OntologyMigrationEngine.MigrationPlan.SwitchExpressionMigration ignored ->
                    className;
            case OntologyMigrationEngine.MigrationPlan.VirtualThreadMigration ignored ->
                    className + "VT";
            case OntologyMigrationEngine.MigrationPlan.JavaTimeMigration ignored ->
                    className + "Time";
            case OntologyMigrationEngine.MigrationPlan.NioMigration ignored ->
                    className + "Nio";
            case OntologyMigrationEngine.MigrationPlan.StreamMigration ignored ->
                    className + "Stream";
            case OntologyMigrationEngine.MigrationPlan.GenericMigration ignored ->
                    className + "Modern";
        };
    }
}
