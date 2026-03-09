package org.acme.dogfood.innovation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GoNoGoEngine — programmatic implementation of the Joe Armstrong 9-rule Go/No-Go verdict.
 *
 * <p>Checks Java source code against the 9 Armstrong rules in order. First rule with a violation
 * produces the verdict. Returns a typed {@link Verdict} with severity tier, Armstrong-voice
 * rationale, and exact refactor command.
 *
 * <pre>{@code
 * var verdict = GoNoGoEngine.check(javaSource, "UserCache");
 * System.out.println(verdict.render());
 * }</pre>
 *
 * <p>Severity tiers:
 *
 * <ul>
 *   <li>{@link Severity#LIVE_BLOCKER} — fix before deployment (Rules 1, 2, 7)
 *   <li>{@link Severity#POST_LIVE} — fix in next sprint (Rules 3, 4, 6, 9)
 *   <li>{@link Severity#DEFER} — tech debt (Rules 5, 8)
 * </ul>
 *
 * <p>Verdict types:
 *
 * <ul>
 *   <li>{@link Verdict.Go} — single instant refactor exists
 *   <li>{@link Verdict.NoGo} — code is already correct
 *   <li>{@link Verdict.NoGoComplex} — multiple violations, no single fix
 *   <li>{@link Verdict.NoGoLiveRisk} — LIVE_BLOCKER in large/complex class: monitor, don't touch
 * </ul>
 */
public final class GoNoGoEngine {

    private GoNoGoEngine() {}

    // ── Severity ──────────────────────────────────────────────────────────────

    /** Deployment urgency of a rule violation. */
    public enum Severity {
        /** Fix before deployment — will corrupt data or fail silently in production. */
        LIVE_BLOCKER,
        /** Fix in the next sprint — wrong but not immediately lethal. */
        POST_LIVE,
        /** Schedule as tech debt — no immediate production risk. */
        DEFER
    }

    // ── RuleViolation ─────────────────────────────────────────────────────────

    /** Evidence that a specific Armstrong rule is violated at a specific source location. */
    public record RuleViolation(
            int ruleNumber,
            String ruleName,
            Severity severity,
            String matchedText,
            int lineNumber) {

        public RuleViolation {
            Objects.requireNonNull(ruleName, "ruleName must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(matchedText, "matchedText must not be null");
        }
    }

    // ── Verdict ───────────────────────────────────────────────────────────────

    /**
     * Sealed verdict hierarchy. Use {@link #render()} for Armstrong-voice formatted output ready to
     * paste into a PR comment or CLI output.
     */
    public sealed interface Verdict {

        /** A single instant refactor exists and is safe to apply. */
        record Go(
                RuleViolation violation,
                String armstrongVoice,
                String refactorCommand,
                String rationale)
                implements Verdict {}

        /** No violations found — the code is correct by these rules. */
        record NoGo(String confirmation) implements Verdict {}

        /**
         * Multiple distinct rules violated — no single instant refactor covers all. Architectural
         * work required first.
         */
        record NoGoComplex(String reason, String flag) implements Verdict {}

        /**
         * LIVE_BLOCKER violation in a large or complex class. The fix is architecturally correct
         * but touching it now is riskier than leaving it. Monitor and schedule post-live.
         */
        record NoGoLiveRisk(RuleViolation violation, String monitor, String schedule)
                implements Verdict {}

        /** Renders the verdict as Armstrong-voice output matching the gonogo-refactor-prompt.md format. */
        default String render() {
            return switch (this) {
                case Go g ->
                        """
                        RULE VIOLATED: Rule %d — %s
                        SEVERITY: %s

                        GO — %s

                        REFACTOR:
                          %s

                        BECAUSE: %s
                        """
                                .formatted(
                                        g.violation().ruleNumber(),
                                        g.violation().ruleName(),
                                        g.violation().severity(),
                                        g.armstrongVoice(),
                                        g.refactorCommand(),
                                        g.rationale());
                case NoGo n -> "NO-GO — " + n.confirmation() + "\n";
                case NoGoComplex c ->
                        """
                        NO-GO (COMPLEX) — %s
                        FLAG: %s
                        """
                                .formatted(c.reason(), c.flag());
                case NoGoLiveRisk r ->
                        """
                        NO-GO (LIVE RISK) — Do not touch this now.
                        SEVERITY: %s
                        MONITOR: %s
                        SCHEDULE: %s
                        """
                                .formatted(r.violation().severity(), r.monitor(), r.schedule());
            };
        }
    }

    // ── Internal rule specification ───────────────────────────────────────────

    private record RuleSpec(
            int number,
            String name,
            Severity severity,
            List<Pattern> triggers,
            String voiceTemplate,
            String refactorTemplate,
            String rationale,
            String monitorHint,
            String scheduleHint) {}

    // ── Rule definitions ──────────────────────────────────────────────────────

    private static final List<RuleSpec> RULE_SPECS = buildSpecs();

    @SuppressWarnings("java:S5843") // regex complexity is intentional pattern matching
    private static List<RuleSpec> buildSpecs() {
        var specs = new ArrayList<RuleSpec>();

        // Rule 1 — Share Nothing (LIVE_BLOCKER)
        specs.add(
                new RuleSpec(
                        1,
                        "Share Nothing",
                        Severity.LIVE_BLOCKER,
                        List.of(
                                Pattern.compile(
                                        "static\\s+(?:final\\s+)?(?:Map|HashMap|LinkedHashMap"
                                                + "|ConcurrentHashMap|List|ArrayList|LinkedList"
                                                + "|Set|HashSet|TreeMap|TreeSet)\\s*[<\\s]"),
                                Pattern.compile("static\\s+volatile\\b"),
                                Pattern.compile("\\bThreadLocal\\s*<"),
                                Pattern.compile("\\bsynchronized\\s*\\(")),
                        "This %s is shared state and will corrupt under concurrency;"
                                + " in Erlang this is a process.",
                        "jgen generate -t patterns/actor -n %s",
                        "A process owns its state; no other process reads or writes it directly"
                                + " — that is how you get nine nines.",
                        "Watch for ConcurrentModificationException and stale reads in production"
                                + " logs.",
                        "Wrap state in Proc<State, Msg> with tests; do not touch without coverage."));

        // Rule 2 — Let It Crash (LIVE_BLOCKER)
        specs.add(
                new RuleSpec(
                        2,
                        "Let It Crash",
                        Severity.LIVE_BLOCKER,
                        List.of(
                                Pattern.compile(
                                        "catch\\s*\\([^)]+\\)\\s*\\{[^}]*"
                                                + "(?:log|logger|LOG|LOGGER)\\.[a-z]+\\(",
                                        Pattern.DOTALL),
                                Pattern.compile(
                                        "while\\s*\\([^)]*retri(?:es|ed)?\\s*(?:--|>)\\s*0"
                                                + "\\s*\\)")),
                        "This %s swallows a failure; there is a Supervisor for that.",
                        "CrashRecovery.retry(3, () -> { /* replace: %s */ })",
                        "Let it crash — a process that dies is honest;"
                                + " a process that swallows is lying.",
                        "Check logs for suppressed stack traces;"
                                + " add debug logging before catch block as a temporary measure.",
                        "Replace catch block with CrashRecovery.retry();"
                                + " add tests before touching."));

        // Rule 3 — One Thing Per Process (POST_LIVE)
        specs.add(
                new RuleSpec(
                        3,
                        "One Thing Per Process",
                        Severity.POST_LIVE,
                        List.of(
                                Pattern.compile(
                                        "(?s)(?:JavaMailSender|JmsTemplate|KafkaTemplate)"
                                                + "[\\s\\S]{0,400}"
                                                + "(?:JpaRepository|CrudRepository|EntityManager)"),
                                Pattern.compile(
                                        "(?s)(?:JpaRepository|CrudRepository|EntityManager)"
                                                + "[\\s\\S]{0,400}"
                                                + "(?:JavaMailSender|JmsTemplate|KafkaTemplate)")),
                        "This class mixes persistence and messaging — %s means two supervisors"
                                + " and two processes.",
                        "jgen generate -t patterns/actor -n %sProcessor",
                        "In Erlang a gen_server has one job;"
                                + " split into two Proc instances with one responsibility each.",
                        "Log method entry/exit to identify which responsibility is on the hot"
                                + " path before splitting.",
                        "Extract messaging to a dedicated Proc;"
                                + " keep persistence in its own process."));

        // Rule 4 — Message Passing Only (POST_LIVE)
        specs.add(
                new RuleSpec(
                        4,
                        "Message Passing Only",
                        Severity.POST_LIVE,
                        List.of(
                                Pattern.compile(
                                        "public\\s+(?:List|ArrayList|Map|HashMap|Set|HashSet)"
                                                + "\\s*(?:<[^>]+>)?\\s+get\\w+\\s*\\(\\s*\\)"),
                                Pattern.compile("\\.getState\\s*\\(\\s*\\)")),
                        "This %s exposes mutable internal state;"
                                + " callers can modify it without sending a message.",
                        "return List.copyOf(%s)",
                        "In Erlang you send a message to a process;"
                                + " you never reach in and grab its state.",
                        "Monitor for unexpected collection mutations;"
                                + " add assertion checks on returned references.",
                        "Return a defensive copy (List.copyOf) or convert the holder to a Proc."));

        // Rule 5 — Pattern Match Everything (DEFER)
        specs.add(
                new RuleSpec(
                        5,
                        "Pattern Match Everything",
                        Severity.DEFER,
                        List.of(
                                Pattern.compile(
                                        "instanceof\\s+\\w+\\s*\\)\\s*\\{[^}]*\\}\\s*else\\s+if"
                                                + "\\s*\\([^)]*instanceof",
                                        Pattern.DOTALL),
                                Pattern.compile("getClass\\s*\\(\\s*\\)\\.equals\\s*\\(")),
                        "This %s is a missing sealed hierarchy;"
                                + " the compiler cannot prove exhaustiveness.",
                        "jgen generate -t core/sealed-types -n %s",
                        "In Erlang function clauses pattern match exhaustively;"
                                + " a missed clause crashes — and you find the bug immediately.",
                        "No immediate production risk; defer to next sprint.",
                        "Replace instanceof chain with sealed interface + switch expression."));

        // Rule 6 — Data Is Correct or Wrong (POST_LIVE)
        specs.add(
                new RuleSpec(
                        6,
                        "Data Is Correct or Wrong",
                        Severity.POST_LIVE,
                        List.of(
                                Pattern.compile("\\breturn\\s+null\\s*;"),
                                Pattern.compile(
                                        "Optional\\.ofNullable\\s*\\([^)]*Optional")),
                        "This %s is a lie — a method that returns null is hiding an error from"
                                + " its callers.",
                        "jgen generate -t error-handling/result-railway -n %s",
                        "In Erlang a function returns {ok, Value} or {error, Reason};"
                                + " there is no null.",
                        "Add null-checks at all call sites to prevent NullPointerException"
                                + " before fixing root cause.",
                        "Replace return null with Result.failure(reason)"
                                + " and propagate via railway."));

        // Rule 7 — Fail Loudly (LIVE_BLOCKER)
        specs.add(
                new RuleSpec(
                        7,
                        "Fail Loudly",
                        Severity.LIVE_BLOCKER,
                        List.of(
                                Pattern.compile(
                                        "catch\\s*\\([^)]+\\)\\s*\\{[^{}]*"
                                                + "return\\s+(?:false|null|"
                                                + "Optional\\.empty\\(\\)|"
                                                + "Collections\\.empty(?:List|Map|Set)\\(\\))"
                                                + "\\s*;",
                                        Pattern.DOTALL),
                                Pattern.compile(
                                        "catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}",
                                        Pattern.DOTALL)),
                        "This %s converts a failure into a lie;"
                                + " the caller believes nothing went wrong.",
                        "Result.of(() -> { /* replace: %s */ })",
                        "A process that returns false on failure is not reliable;"
                                + " it is a ticking bomb in production.",
                        "Search logs for the root exception that is now invisible;"
                                + " add temporary debug logging before the catch block.",
                        "Wrap with Result.of() so failures propagate;"
                                + " remove the sentinel return."));

        // Rule 8 — Immutability in the Pure Parts (DEFER)
        specs.add(
                new RuleSpec(
                        8,
                        "Immutability in the Pure Parts",
                        Severity.DEFER,
                        List.of(
                                Pattern.compile("public\\s+void\\s+set[A-Z]\\w*\\s*\\("),
                                Pattern.compile(
                                        "public\\s+void\\s+add[A-Z]\\w*\\s*\\([^)]+\\)\\s*\\{")),
                        "This %s is a mutable value object;"
                                + " it is a bag of surprises waiting to be mutated by any caller.",
                        "jgen generate -t core/record -n %s",
                        "A record in Java is what a tuple is in Erlang — immutable, structural,"
                                + " honest.",
                        "No immediate production risk; defer to next sprint.",
                        "Replace with record; remove all setters."));

        // Rule 9 — Supervised Restarts, Not Defensive Code (POST_LIVE)
        specs.add(
                new RuleSpec(
                        9,
                        "Supervised Restarts, Not Defensive Code",
                        Severity.POST_LIVE,
                        List.of(
                                Pattern.compile("Thread\\.sleep\\s*\\("),
                                Pattern.compile("\\bmaxRetries\\b|\\bretryCount\\b|\\bretryLimit\\b"),
                                Pattern.compile("Math\\.pow\\s*\\([^,]+,\\s*attempt")),
                        "This %s is a supervisor written inside business logic;"
                                + " it belongs in a Supervisor, not here.",
                        "jgen generate -t patterns/supervisor -n %s",
                        "In Erlang the supervisor tree handles restarts;"
                                + " business logic never sleeps, counts, or backs off.",
                        "Log retry attempts and backoff durations to quantify the failure rate"
                                + " before refactoring.",
                        "Replace with Supervisor using ONE_FOR_ONE + restart window;"
                                + " remove Thread.sleep and maxRetries fields."));

        return List.copyOf(specs);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Checks {@code javaSource} against all 9 Armstrong rules and returns a verdict.
     *
     * <p>Rules are checked in order 1–9. The first rule with at least one violation determines the
     * verdict type. If violations span multiple distinct rules, {@link Verdict.NoGoComplex} or
     * {@link Verdict.NoGoLiveRisk} is returned — no single instant refactor exists.
     *
     * @param javaSource full Java source code as a string
     * @param className  the simple class name used in refactor command templates
     * @return one of {@link Verdict.Go}, {@link Verdict.NoGo}, {@link Verdict.NoGoComplex}, or
     *         {@link Verdict.NoGoLiveRisk}
     */
    public static Verdict check(String javaSource, String className) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        Objects.requireNonNull(className, "className must not be null");

        var lines = javaSource.split("\n", -1);
        var violations = new ArrayList<RuleViolation>();

        for (var spec : RULE_SPECS) {
            for (var trigger : spec.triggers()) {
                var hit = firstMatch(trigger, lines, javaSource);
                if (hit != null) {
                    violations.add(
                            new RuleViolation(
                                    spec.number(),
                                    spec.name(),
                                    spec.severity(),
                                    hit.matchedText(),
                                    hit.lineNumber()));
                    break; // one match per rule is enough
                }
            }
        }

        return buildVerdict(violations, javaSource, className);
    }

    /**
     * Returns ALL rule violations found in {@code javaSource}, ignoring the "first violation wins"
     * rule. Useful for full audits and generating backlog reports.
     *
     * @param javaSource full Java source code as a string
     * @return all violations found, in rule order (Rule 1 first)
     */
    public static List<RuleViolation> audit(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");

        var lines = javaSource.split("\n", -1);
        var violations = new ArrayList<RuleViolation>();

        for (var spec : RULE_SPECS) {
            for (var trigger : spec.triggers()) {
                var hit = firstMatch(trigger, lines, javaSource);
                if (hit != null) {
                    violations.add(
                            new RuleViolation(
                                    spec.number(),
                                    spec.name(),
                                    spec.severity(),
                                    hit.matchedText(),
                                    hit.lineNumber()));
                    break;
                }
            }
        }

        return List.copyOf(violations);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private record LineMatch(String matchedText, int lineNumber) {}

    /**
     * Tries single-line matching first (for precise line numbers), then falls back to full-source
     * matching to handle multiline patterns (DOTALL catch blocks, Rule 3 import proximity).
     */
    private static LineMatch firstMatch(Pattern pattern, String[] lines, String fullSource) {
        // Single-line pass — fast and gives accurate line numbers
        for (int i = 0; i < lines.length; i++) {
            Matcher m = pattern.matcher(lines[i]);
            if (m.find()) {
                return new LineMatch(m.group().strip(), i + 1);
            }
        }
        // Full-source pass — handles DOTALL / multiline patterns
        Matcher m = pattern.matcher(fullSource);
        if (m.find()) {
            var text = m.group().strip().replaceAll("\\R\\s*", " ");
            if (text.length() > 70) {
                text = text.substring(0, 67) + "...";
            }
            return new LineMatch(text, 1);
        }
        return null;
    }

    private static Verdict buildVerdict(
            List<RuleViolation> violations, String javaSource, String className) {

        if (violations.isEmpty()) {
            return new Verdict.NoGo(
                    "No Armstrong rule violations detected — this code is correct.");
        }

        long distinctRuleCount =
                violations.stream().mapToInt(RuleViolation::ruleNumber).distinct().count();

        if (distinctRuleCount == 1) {
            var v = violations.get(0);
            var spec = RULE_SPECS.get(v.ruleNumber() - 1);
            int lineCount = javaSource.split("\n").length;

            // Large class (>150 lines) + LIVE_BLOCKER = risky to touch without test coverage
            if (v.severity() == Severity.LIVE_BLOCKER && lineCount > 150) {
                return new Verdict.NoGoLiveRisk(v, spec.monitorHint(), spec.scheduleHint());
            }

            return new Verdict.Go(
                    v,
                    spec.voiceTemplate().formatted(v.matchedText()),
                    spec.refactorTemplate().formatted(className),
                    spec.rationale());
        }

        // Multiple distinct rules — no single instant refactor covers all
        var firstViolation = violations.get(0);
        var spec = RULE_SPECS.get(firstViolation.ruleNumber() - 1);
        boolean hasLiveBlocker =
                violations.stream().anyMatch(v -> v.severity() == Severity.LIVE_BLOCKER);

        if (hasLiveBlocker) {
            return new Verdict.NoGoLiveRisk(
                    firstViolation, spec.monitorHint(), spec.scheduleHint());
        }

        return new Verdict.NoGoComplex(
                distinctRuleCount
                        + " distinct rule violations — no single instant refactor covers all.",
                "Address Rule "
                        + firstViolation.ruleNumber()
                        + " ("
                        + firstViolation.ruleName()
                        + ") first; it is the highest priority violation found.");
    }
}
