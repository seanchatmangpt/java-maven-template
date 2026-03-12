package io.github.seanchatmangpt.jotp.dogfood.innovation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Armstrong Stress Test Scanner — scans any Java source tree and identifies breaking-point patterns
 * that need immediate stress testing to find where things fail.
 *
 * <p>Joe Armstrong: <em>"A system that has never been stressed is a system whose failure mode you
 * don't know."</em> This engine automates the discovery of what to stress and generates the
 * {@code bin/jgen generate} commands to scaffold the tests.
 *
 * <pre>{@code
 * var plan = StressTestScanner.scan(Path.of("./src/main/java"));
 * System.out.println(plan.summary());
 * Files.writeString(Path.of("stress-tests.sh"), plan.toScript());
 * }</pre>
 *
 * <p>Detected patterns → stress templates:
 *
 * <ul>
 *   <li>{@link StressFinding.QueuePattern} → {@code testing/stress-throughput}
 *   <li>{@link StressFinding.LockContention} → {@code testing/stress-concurrency}
 *   <li>{@link StressFinding.SharedStatePattern} → {@code testing/stress-concurrency}
 *   <li>{@link StressFinding.ListenerPattern} → {@code testing/stress-cascade}
 *   <li>{@link StressFinding.TimeoutPattern} → {@code testing/stress-timeout}
 *   <li>{@link StressFinding.BoundaryPattern} → {@code testing/stress-boundary}
 * </ul>
 */
public final class StressTestScanner {

    private StressTestScanner() {}

    // ── Public types ──────────────────────────────────────────────────────────

    /** Priority of a detected stress finding — drives script ordering. */
    public enum Priority {
        CRITICAL,
        HIGH,
        MEDIUM
    }

    /**
     * A detected breaking-point pattern in a Java source file.
     *
     * <p>Each variant maps to a specific stress test template. The {@code matchedSignals} list
     * captures the exact signal labels matched, for human review.
     */
    public sealed interface StressFinding {

        Path file();

        String className();

        String packageName();

        Priority priority();

        List<String> matchedSignals();

        String recommendedTemplate();

        /** Queue/mailbox detected — potential OOM or stall under message flood. */
        record QueuePattern(
                Path file,
                String className,
                String packageName,
                Priority priority,
                List<String> matchedSignals,
                String recommendedTemplate)
                implements StressFinding {}

        /** Lock or synchronization detected — contention, deadlock, starvation. */
        record LockContention(
                Path file,
                String className,
                String packageName,
                Priority priority,
                List<String> matchedSignals,
                String recommendedTemplate)
                implements StressFinding {}

        /** Concurrent shared-state — CAS storm, registration race, visibility failure. */
        record SharedStatePattern(
                Path file,
                String className,
                String packageName,
                Priority priority,
                List<String> matchedSignals,
                String recommendedTemplate)
                implements StressFinding {}

        /** Observer/listener list — O(N) broadcast storm, handler crash isolation. */
        record ListenerPattern(
                Path file,
                String className,
                String packageName,
                Priority priority,
                List<String> matchedSignals,
                String recommendedTemplate)
                implements StressFinding {}

        /** Timeout-sensitive operations — jitter under GC, deadline precision. */
        record TimeoutPattern(
                Path file,
                String className,
                String packageName,
                Priority priority,
                List<String> matchedSignals,
                String recommendedTemplate)
                implements StressFinding {}

        /** Limit/threshold/window semantics — off-by-one, window reset, boundary crossing. */
        record BoundaryPattern(
                Path file,
                String className,
                String packageName,
                Priority priority,
                List<String> matchedSignals,
                String recommendedTemplate)
                implements StressFinding {}
    }

    /**
     * The full stress analysis plan for a source tree.
     *
     * @param sourceRoot the root directory that was scanned
     * @param findings all detected breaking-point patterns, sorted CRITICAL → HIGH → MEDIUM
     */
    public record StressPlan(Path sourceRoot, List<StressFinding> findings) {

        public StressPlan {
            Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
            findings = List.copyOf(findings);
        }

        /** Count of CRITICAL-priority findings (data races, queue overflow risks). */
        public int criticalCount() {
            return (int) findings.stream().filter(f -> f.priority() == Priority.CRITICAL).count();
        }

        /** Count of HIGH-priority findings (lock contention, broadcast storms). */
        public int highCount() {
            return (int) findings.stream().filter(f -> f.priority() == Priority.HIGH).count();
        }

        /** All {@link RefactorEngine.JgenCommand}s to generate stress test classes. */
        public List<RefactorEngine.JgenCommand> toJgenCommands() {
            return findings.stream()
                    .map(
                            f ->
                                    new RefactorEngine.JgenCommand(
                                            f.recommendedTemplate(),
                                            f.className() + "StressTest",
                                            f.packageName(),
                                            f.getClass().getSimpleName()
                                                    + " in "
                                                    + f.file().getFileName(),
                                            priorityToInt(f.priority()),
                                            false))
                    .toList();
        }

        /**
         * Human-readable stress analysis report.
         *
         * <pre>
         * ╔══ Armstrong Stress Scan ═══════════════════════════════════════╗
         *   Source:              ./src/main/java
         *   Files with findings: 7
         *   CRITICAL: 3  |  HIGH: 2  |  MEDIUM: 2
         * ╚════════════════════════════════════════════════════════════════╝
         * </pre>
         */
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══ Armstrong Stress Scan ═══════════════════════════════════════╗\n");
            sb.append("  Source:              ").append(sourceRoot).append("\n");
            sb.append("  Files with findings: ").append(findings.size()).append("\n");
            sb.append("  CRITICAL: ").append(criticalCount());
            sb.append("  |  HIGH: ").append(highCount());
            sb.append("  |  MEDIUM: ")
                    .append(findings.size() - criticalCount() - highCount())
                    .append("\n");
            sb.append("╚════════════════════════════════════════════════════════════════╝\n\n");

            if (findings.isEmpty()) {
                sb.append("No breaking-point patterns detected in ").append(sourceRoot).append("\n");
                return sb.toString();
            }

            for (Priority p : Priority.values()) {
                var group = findings.stream().filter(f -> f.priority() == p).toList();
                if (group.isEmpty()) continue;
                sb.append("── ")
                        .append(p)
                        .append(" ────────────────────────────────────────────────\n");
                for (var f : group) {
                    sb.append("  [")
                            .append(p)
                            .append("] ")
                            .append(f.file().getFileName())
                            .append("  (")
                            .append(f.className())
                            .append(")\n");
                    sb.append("    Type:     ")
                            .append(f.getClass().getSimpleName())
                            .append("\n");
                    sb.append("    Template: ").append(f.recommendedTemplate()).append("\n");
                    sb.append("    Signals:  ");
                    var signals = f.matchedSignals().stream().limit(3).toList();
                    sb.append(String.join(", ", signals));
                    if (f.matchedSignals().size() > 3) {
                        sb.append(" (+").append(f.matchedSignals().size() - 3).append(" more)");
                    }
                    sb.append("\n");
                    sb.append("    Generate: bin/jgen generate -t ")
                            .append(f.recommendedTemplate())
                            .append(" -n ")
                            .append(f.className())
                            .append("StressTest")
                            .append(" -p ")
                            .append(f.packageName())
                            .append("\n\n");
                }
            }

            return sb.toString();
        }

        /**
         * Executable bash script that generates all stress test classes via {@code bin/jgen
         * generate}.
         */
        public String toScript() {
            var sb = new StringBuilder();
            sb.append("#!/usr/bin/env bash\n");
            sb.append("# =================================================================\n");
            sb.append("# Armstrong Stress Test Generation Script\n");
            sb.append("# Auto-generated by StressTestScanner\n");
            sb.append("# Source: ").append(sourceRoot).append("\n");
            sb.append("# Findings: ")
                    .append(findings.size())
                    .append("  (CRITICAL=")
                    .append(criticalCount())
                    .append(", HIGH=")
                    .append(highCount())
                    .append(")\n");
            sb.append("# =================================================================\n");
            sb.append("set -euo pipefail\n\n");

            for (Priority p : Priority.values()) {
                var group = findings.stream().filter(f -> f.priority() == p).toList();
                if (group.isEmpty()) continue;
                sb.append("# ── ")
                        .append(p)
                        .append(" ──────────────────────────────────────────────\n");
                for (var f : group) {
                    sb.append("# ")
                            .append(f.getClass().getSimpleName())
                            .append(" in ")
                            .append(f.file().getFileName())
                            .append("\n");
                    sb.append("bin/jgen generate")
                            .append(" -t ")
                            .append(f.recommendedTemplate())
                            .append(" -n ")
                            .append(f.className())
                            .append("StressTest")
                            .append(" -p ")
                            .append(f.packageName())
                            .append("\n\n");
                }
            }

            sb.append("echo 'Stress test generation complete.'\n");
            sb.append("echo 'Run: ./mvnw test -Dtest=\"*StressTest\"'\n");
            return sb.toString();
        }

        private static int priorityToInt(Priority p) {
            return switch (p) {
                case CRITICAL -> 1;
                case HIGH -> 2;
                case MEDIUM -> 3;
            };
        }
    }

    // ── Detection rules ───────────────────────────────────────────────────────

    private record DetectionRule(
            Pattern regex,
            Class<? extends StressFinding> findingType,
            Priority priority,
            String recommendedTemplate,
            String signalLabel) {}

    private static final List<DetectionRule> RULES = buildRules();

    private static List<DetectionRule> buildRules() {
        var rules = new ArrayList<DetectionRule>();

        // ── Queue / mailbox → stress-throughput (OOM under flood) ─────────────
        rules.add(
                rule(
                        "\\b(?:LinkedBlockingQueue|LinkedTransferQueue|ArrayBlockingQueue"
                                + "|ConcurrentLinkedQueue|TransferQueue|BlockingDeque"
                                + "|PriorityBlockingQueue)\\b",
                        StressFinding.QueuePattern.class,
                        Priority.CRITICAL,
                        "testing/stress-throughput",
                        "blocking/transfer queue"));

        rules.add(
                rule(
                        "\\b(?:LinkedList|ArrayDeque)\\b.{0,60}(?:[Qq]ueue|[Mm]ailbox)",
                        StressFinding.QueuePattern.class,
                        Priority.HIGH,
                        "testing/stress-throughput",
                        "list used as queue"));

        // ── Lock contention → stress-concurrency ──────────────────────────────
        rules.add(
                rule(
                        "\\bsynchronized\\b",
                        StressFinding.LockContention.class,
                        Priority.HIGH,
                        "testing/stress-concurrency",
                        "synchronized"));

        rules.add(
                rule(
                        "\\b(?:ReentrantLock|ReadWriteLock|StampedLock|ReentrantReadWriteLock)\\b",
                        StressFinding.LockContention.class,
                        Priority.HIGH,
                        "testing/stress-concurrency",
                        "explicit lock"));

        rules.add(
                rule(
                        "\\bSemaphore\\b",
                        StressFinding.LockContention.class,
                        Priority.MEDIUM,
                        "testing/stress-concurrency",
                        "semaphore"));

        rules.add(
                rule(
                        "\\b(?:ThreadPoolExecutor|Executors\\.new(?:Fixed|Cached|Single)ThreadPool)\\b",
                        StressFinding.LockContention.class,
                        Priority.HIGH,
                        "testing/stress-concurrency",
                        "thread pool"));

        // ── Shared state / atomic → stress-concurrency ────────────────────────
        rules.add(
                rule(
                        "\\bConcurrentHashMap\\b",
                        StressFinding.SharedStatePattern.class,
                        Priority.CRITICAL,
                        "testing/stress-concurrency",
                        "ConcurrentHashMap"));

        rules.add(
                rule(
                        "\\bputIfAbsent\\b",
                        StressFinding.SharedStatePattern.class,
                        Priority.CRITICAL,
                        "testing/stress-concurrency",
                        "putIfAbsent (registration race)"));

        rules.add(
                rule(
                        "\\bAtomicReference\\b",
                        StressFinding.SharedStatePattern.class,
                        Priority.HIGH,
                        "testing/stress-concurrency",
                        "AtomicReference"));

        rules.add(
                rule(
                        "\\bvolatile\\b\\s+\\w",
                        StressFinding.SharedStatePattern.class,
                        Priority.HIGH,
                        "testing/stress-concurrency",
                        "volatile field"));

        rules.add(
                rule(
                        "getInstance\\s*\\(\\s*\\)|\\bINSTANCE\\s*=",
                        StressFinding.SharedStatePattern.class,
                        Priority.HIGH,
                        "testing/stress-concurrency",
                        "singleton pattern"));

        // ── Listener / observer → stress-cascade ──────────────────────────────
        rules.add(
                rule(
                        "List<[^>]*(?:Handler|Listener|Observer|Callback|Subscriber|Consumer)[^>]*>",
                        StressFinding.ListenerPattern.class,
                        Priority.HIGH,
                        "testing/stress-cascade",
                        "typed listener list"));

        rules.add(
                rule(
                        "\\bCopyOnWriteArrayList\\b",
                        StressFinding.ListenerPattern.class,
                        Priority.HIGH,
                        "testing/stress-cascade",
                        "CopyOnWriteArrayList (handler store)"));

        rules.add(
                rule(
                        "\\b(?:addListener|addHandler|addObserver|subscribe|register)\\s*\\(",
                        StressFinding.ListenerPattern.class,
                        Priority.MEDIUM,
                        "testing/stress-cascade",
                        "listener registration method"));

        // ── Timeout-sensitive operations → stress-timeout ─────────────────────
        rules.add(
                rule(
                        "\\.get\\s*\\(\\s*\\d+",
                        StressFinding.TimeoutPattern.class,
                        Priority.HIGH,
                        "testing/stress-timeout",
                        "Future.get(timeout)"));

        rules.add(
                rule(
                        "orTimeout\\s*\\(",
                        StressFinding.TimeoutPattern.class,
                        Priority.HIGH,
                        "testing/stress-timeout",
                        "orTimeout()"));

        rules.add(
                rule(
                        "\\b(?:poll|await)\\s*\\(\\s*\\d+",
                        StressFinding.TimeoutPattern.class,
                        Priority.HIGH,
                        "testing/stress-timeout",
                        "poll/await(timeout)"));

        rules.add(
                rule(
                        "\\bCompletableFuture\\b",
                        StressFinding.TimeoutPattern.class,
                        Priority.MEDIUM,
                        "testing/stress-timeout",
                        "CompletableFuture"));

        // ── Boundary / threshold / limit → stress-boundary ────────────────────
        rules.add(
                rule(
                        "\\b(?:maxRestarts|maxRetries|maxAttempts)\\b",
                        StressFinding.BoundaryPattern.class,
                        Priority.CRITICAL,
                        "testing/stress-boundary",
                        "restart/retry limit"));

        rules.add(
                rule(
                        "\\b(?:threshold|windowMs|windowDuration|slidingWindow|windowSeconds)\\b",
                        StressFinding.BoundaryPattern.class,
                        Priority.HIGH,
                        "testing/stress-boundary",
                        "threshold/window"));

        rules.add(
                rule(
                        "\\b(?:maxSize|maxCapacity|capacity)\\s*[=<>]",
                        StressFinding.BoundaryPattern.class,
                        Priority.MEDIUM,
                        "testing/stress-boundary",
                        "capacity/size limit"));

        return List.copyOf(rules);
    }

    private static DetectionRule rule(
            String regex,
            Class<? extends StressFinding> type,
            Priority priority,
            String template,
            String label) {
        return new DetectionRule(Pattern.compile(regex), type, priority, template, label);
    }

    // ── Core scan API ─────────────────────────────────────────────────────────

    /**
     * Scans all {@code .java} files under {@code sourceRoot} and returns a {@link StressPlan}.
     *
     * @param sourceRoot the root directory to scan recursively
     * @return stress plan ready for {@link StressPlan#summary()} and {@link StressPlan#toScript()}
     * @throws IOException if the directory cannot be read
     */
    public static StressPlan scan(Path sourceRoot) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        var findings = new ArrayList<StressFinding>();

        try (var stream = Files.walk(sourceRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(file -> findings.addAll(analyzeFile(file)));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        // Sort: CRITICAL first, then HIGH, then MEDIUM; within priority by file name
        findings.sort(
                (a, b) -> {
                    int pc = a.priority().compareTo(b.priority());
                    if (pc != 0) return pc;
                    return a.file()
                            .getFileName()
                            .toString()
                            .compareTo(b.file().getFileName().toString());
                });

        return new StressPlan(sourceRoot, findings);
    }

    /**
     * Analyzes a single Java source file for breaking-point patterns.
     *
     * @param file path to the {@code .java} file
     * @return list of findings (empty if no patterns detected)
     */
    public static List<StressFinding> analyzeFile(Path file) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }

        var fileName = file.getFileName().toString();
        var className =
                fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        var packageName = extractPackage(source);

        // Group signals by (findingType, priority, template) — multiple rule hits merge
        record Key(Class<? extends StressFinding> type, Priority priority, String template) {}
        var grouped = new java.util.LinkedHashMap<Key, List<String>>();

        for (var rule : RULES) {
            var matcher = rule.regex().matcher(source);
            while (matcher.find()) {
                var key = new Key(rule.findingType(), rule.priority(), rule.recommendedTemplate());
                grouped.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(rule.signalLabel() + ": \"" + matcher.group().strip() + "\"");
            }
        }

        return grouped.entrySet().stream()
                .map(
                        entry ->
                                buildFinding(
                                        entry.getKey().type(),
                                        file,
                                        className,
                                        packageName,
                                        entry.getKey().priority(),
                                        entry.getValue().stream().distinct().toList(),
                                        entry.getKey().template()))
                .toList();
    }

    // ── CLI entry point ───────────────────────────────────────────────────────

    /**
     * CLI entry point: {@code StressTestScanner <sourceRoot> [--plan] [--generate]}.
     *
     * <ul>
     *   <li>Always prints the scan summary to stdout.
     *   <li>{@code --plan}: writes {@code stress-tests.sh}.
     *   <li>{@code --generate}: also executes the generated script via {@code bash}.
     * </ul>
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: StressTestScanner <sourceRoot> [--plan] [--generate]");
            System.exit(1);
        }

        var sourceRoot = Path.of(args[0]);
        var argList = Arrays.asList(args);
        var writePlan = argList.contains("--plan") || argList.contains("--generate");
        var generate = argList.contains("--generate");

        if (!Files.isDirectory(sourceRoot)) {
            System.err.println("ERROR: not a directory: " + sourceRoot);
            System.exit(1);
        }

        var plan = scan(sourceRoot);
        System.out.println(plan.summary());

        if (writePlan) {
            var scriptPath = Path.of("stress-tests.sh");
            Files.writeString(scriptPath, plan.toScript());
            System.out.println("Written: " + scriptPath.toAbsolutePath());
        }

        if (generate && !plan.findings().isEmpty()) {
            System.out.println("\nRunning stress-tests.sh ...");
            var proc = new ProcessBuilder("bash", "stress-tests.sh").inheritIO().start();
            System.exit(proc.waitFor());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static String extractPackage(String source) {
        var matcher = PACKAGE_DECL.matcher(source);
        return matcher.find() ? matcher.group(1) : "org.example";
    }

    private static StressFinding buildFinding(
            Class<? extends StressFinding> type,
            Path file,
            String className,
            String packageName,
            Priority priority,
            List<String> signals,
            String template) {
        if (type == StressFinding.QueuePattern.class)
            return new StressFinding.QueuePattern(
                    file, className, packageName, priority, signals, template);
        if (type == StressFinding.LockContention.class)
            return new StressFinding.LockContention(
                    file, className, packageName, priority, signals, template);
        if (type == StressFinding.SharedStatePattern.class)
            return new StressFinding.SharedStatePattern(
                    file, className, packageName, priority, signals, template);
        if (type == StressFinding.ListenerPattern.class)
            return new StressFinding.ListenerPattern(
                    file, className, packageName, priority, signals, template);
        if (type == StressFinding.TimeoutPattern.class)
            return new StressFinding.TimeoutPattern(
                    file, className, packageName, priority, signals, template);
        if (type == StressFinding.BoundaryPattern.class)
            return new StressFinding.BoundaryPattern(
                    file, className, packageName, priority, signals, template);
        throw new IllegalArgumentException("Unknown finding type: " + type);
    }
}
