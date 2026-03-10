package org.acme.dogfood.innovation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.acme.StateMachine;

/**
 * Armstrong AGI Engine — a higher-order reasoning layer that chains all six innovation engines into
 * a multi-pass pipeline modelled on Joe Armstrong's diagnostic philosophy:
 *
 * <blockquote>
 * "First understand what's wrong; then understand why; then understand what to do about it."
 * </blockquote>
 *
 * <p>The engine runs four analysis passes concurrently (Stage 1), then builds root-cause {@link
 * ExplanationChain}s linking each Armstrong rule violation to the OTP primitive that fixes it (Stage
 * 2), assembles a prioritised {@link ActionPlan} (Stage 3), and finally self-verifies its plan by
 * re-running {@link GoNoGoEngine} on a stub-patched version of the source to confirm the verdict
 * improves (Stage 4).
 *
 * <p>The {@link #assessAsync} entry point exposes a live {@link StateMachine} progressing through
 * {@code Idle → Assessing → Explaining → Planning → Done}, so callers can observe the pipeline in
 * flight — an Erlang {@code sys:get_state/1} pattern applied to the AGI itself.
 *
 * <pre>{@code
 * // Synchronous
 * var result = ArmstrongAgiEngine.assess(javaSource, "UserCache");
 * System.out.println(result.plan().summary());
 *
 * // Async — inspect live state
 * var sm = ArmstrongAgiEngine.assessAsync(javaSource, "UserCache");
 * await().until(() -> sm.state() instanceof AgiState.Done);
 * var plan = sm.data().plan();
 * }</pre>
 */
public final class ArmstrongAgiEngine {

    private ArmstrongAgiEngine() {}

    // ── Package extraction ─────────────────────────────────────────────────────

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    // ── AgiState — reasoning pipeline states ──────────────────────────────────

    /** States of the AGI reasoning pipeline. Maps to OTP {@code gen_statem} state names. */
    public sealed interface AgiState
            permits AgiState.Idle,
                    AgiState.Assessing,
                    AgiState.Explaining,
                    AgiState.Planning,
                    AgiState.Done {

        /** Waiting for the {@link AgiEvent.Start} trigger. */
        record Idle() implements AgiState {}

        /** Parallel engine assessment in progress. */
        record Assessing() implements AgiState {}

        /** Building root-cause {@link ExplanationChain}s from evidence. */
        record Explaining() implements AgiState {}

        /** Assembling the {@link ActionPlan} from chains and migrations. */
        record Planning() implements AgiState {}

        /** Assessment complete — {@link AgiData#plan()} is populated. */
        record Done() implements AgiState {}
    }

    // ── AgiEvent — pipeline driver events ─────────────────────────────────────

    /** Events that drive the AGI reasoning state machine. */
    public sealed interface AgiEvent
            permits AgiEvent.Start,
                    AgiEvent.AssessmentComplete,
                    AgiEvent.ExplanationsReady,
                    AgiEvent.PlanReady {

        /** Kick off the pipeline with the source to analyze. */
        record Start(String source, String className) implements AgiEvent {}

        /** Stage 1 complete — carries the aggregated {@link AgiEvidence}. */
        record AssessmentComplete(AgiEvidence evidence) implements AgiEvent {}

        /** Stage 2 complete — carries the built explanation chains. */
        record ExplanationsReady(List<ExplanationChain> chains) implements AgiEvent {}

        /** Stage 3 complete — carries the finished {@link ActionPlan}. */
        record PlanReady(ActionPlan plan) implements AgiEvent {}
    }

    // ── AgiData — state machine data carried across all states ────────────────

    /**
     * Data carried through the state machine. Fields are {@code null} until their stage completes.
     */
    public record AgiData(
            String source,
            String className,
            AgiEvidence evidence,
            List<ExplanationChain> chains,
            ActionPlan plan) {

        /** Creates the initial (pre-assessment) data. */
        static AgiData initial(String source, String className) {
            return new AgiData(source, className, null, null, null);
        }
    }

    // ── AgiEvidence — aggregated evidence from all engines ────────────────────

    /**
     * Aggregated output from the four engines run in Stage 1.
     *
     * @param verdict     first-violation verdict from {@link GoNoGoEngine#check}
     * @param violations  all violations from {@link GoNoGoEngine#audit}
     * @param score       modernization score from {@link ModernizationScorer}
     * @param migrations  ontology-driven migration analysis from {@link OntologyMigrationEngine}
     * @param docElements parsed documentation structure from {@link LivingDocGenerator}
     */
    public record AgiEvidence(
            GoNoGoEngine.Verdict verdict,
            List<GoNoGoEngine.RuleViolation> violations,
            ModernizationScorer.CodebaseScore score,
            OntologyMigrationEngine.AnalysisResult migrations,
            List<LivingDocGenerator.DocElement> docElements) {

        public AgiEvidence {
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(violations, "violations must not be null");
            Objects.requireNonNull(score, "score must not be null");
            Objects.requireNonNull(migrations, "migrations must not be null");
            Objects.requireNonNull(docElements, "docElements must not be null");
            violations = List.copyOf(violations);
            docElements = List.copyOf(docElements);
        }
    }

    // ── ExplanationChain — root-cause narrative for one violation ─────────────

    /**
     * Links a single Armstrong rule violation to its fault-tolerance rationale and OTP fix.
     *
     * @param violation         the detected rule violation
     * @param armstrongPrinciple human-readable rule name (e.g. "Let It Crash")
     * @param rootCause          one sentence explaining why the pattern breaks fault tolerance
     * @param otpFix             the OTP primitive that addresses it (e.g. "CrashRecovery")
     * @param codeHint           minimal Java 26 snippet demonstrating the fix
     */
    public record ExplanationChain(
            GoNoGoEngine.RuleViolation violation,
            String armstrongPrinciple,
            String rootCause,
            String otpFix,
            String codeHint) {

        public ExplanationChain {
            Objects.requireNonNull(violation, "violation must not be null");
            Objects.requireNonNull(armstrongPrinciple, "armstrongPrinciple must not be null");
            Objects.requireNonNull(rootCause, "rootCause must not be null");
            Objects.requireNonNull(otpFix, "otpFix must not be null");
            Objects.requireNonNull(codeHint, "codeHint must not be null");
        }
    }

    // ── ActionPlan — ordered fix plan with confidence scoring ─────────────────

    /**
     * Ordered, confidence-weighted fix plan produced by Stage 3.
     *
     * @param explanations   root-cause chains for each violation
     * @param safeActions    non-breaking {@code jgen} commands, safe to apply automatically
     * @param breakingActions breaking commands requiring manual review
     * @param confidenceScore 0.0–1.0; approaches 1.0 when fewer LIVE_BLOCKERs remain after plan
     * @param summary         human-readable one-paragraph verdict
     */
    public record ActionPlan(
            List<ExplanationChain> explanations,
            List<RefactorEngine.JgenCommand> safeActions,
            List<RefactorEngine.JgenCommand> breakingActions,
            double confidenceScore,
            String summary) {

        public ActionPlan {
            Objects.requireNonNull(explanations, "explanations must not be null");
            Objects.requireNonNull(safeActions, "safeActions must not be null");
            Objects.requireNonNull(breakingActions, "breakingActions must not be null");
            Objects.requireNonNull(summary, "summary must not be null");
            if (confidenceScore < 0.0 || confidenceScore > 1.0) {
                throw new IllegalArgumentException(
                        "confidenceScore must be 0.0-1.0, got " + confidenceScore);
            }
            explanations = List.copyOf(explanations);
            safeActions = List.copyOf(safeActions);
            breakingActions = List.copyOf(breakingActions);
        }
    }

    // ── AgiAssessment — top-level result ──────────────────────────────────────

    /**
     * The complete result returned by {@link #assess}.
     *
     * @param className    simple class name that was analyzed
     * @param evidence     aggregated engine evidence from Stage 1
     * @param plan         ordered action plan from Stage 3
     * @param selfVerified {@code true} if re-running GoNoGo on stub-patched source improved the
     *                     verdict, confirming the plan's top action is effective
     */
    public record AgiAssessment(
            String className,
            AgiEvidence evidence,
            ActionPlan plan,
            boolean selfVerified) {

        public AgiAssessment {
            Objects.requireNonNull(className, "className must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
        }
    }

    // ── Armstrong rule knowledge base ──────────────────────────────────────────

    private record RuleKnowledge(
            String armstrongPrinciple, String rootCause, String otpFix, String codeHint) {}

    private static final Map<String, RuleKnowledge> RULE_KNOWLEDGE =
            Map.of(
                    "Share Nothing",
                    new RuleKnowledge(
                            "Share Nothing",
                            "Shared mutable state corrupts silently under concurrency — multiple"
                                    + " threads racing on the same data produce unpredictable results"
                                    + " with no crash to alert you.",
                            "Proc<S,M>",
                            "new Proc<>(initialState, (state, msg) -> state.with(msg)); // no sharing"),
                    "Let It Crash",
                    new RuleKnowledge(
                            "Let It Crash",
                            "Swallowing exceptions hides hardware faults and bugs — the program"
                                    + " continues in a corrupted state, compounding the damage silently.",
                            "CrashRecovery",
                            "Result<T, Exception> r = CrashRecovery.attempt(supplier);"),
                    "One Thing Per Process",
                    new RuleKnowledge(
                            "One Thing Per Process",
                            "A class handling multiple responsibilities cannot be restarted"
                                    + " independently — one fault takes down unrelated functionality.",
                            "Supervisor ONE_FOR_ONE",
                            "supervisor.supervise(\"reader\", s, h); supervisor.supervise(\"writer\","
                                    + " s, h);"),
                    "Message Passing Only",
                    new RuleKnowledge(
                            "Message Passing Only",
                            "Exposing mutable state through public fields couples callers to internal"
                                    + " representation, creating invisible data races.",
                            "Proc.tell(msg)",
                            "proc.tell(new UpdateMsg(value)); // no shared references"),
                    "Pattern Match Everything",
                    new RuleKnowledge(
                            "Pattern Match Everything",
                            "instanceof chains are incomplete — adding a new subtype silently falls"
                                    + " through to the else branch instead of forcing compiler coverage.",
                            "sealed interface + switch expression",
                            "switch (event) { case Foo f -> …; case Bar b -> …; } // exhaustive"),
                    "Data Is Correct or Wrong",
                    new RuleKnowledge(
                            "Data Is Correct or Wrong",
                            "Returning null forces every caller to add a null-check or risk"
                                    + " NullPointerException — the error is reported far from the root cause.",
                            "Result<T,E>",
                            "return Result.of(() -> compute()); // Success or Failure, never null"),
                    "Fail Loudly",
                    new RuleKnowledge(
                            "Fail Loudly",
                            "Returning false or a sentinel value on failure delays detection — the"
                                    + " caller ignores the signal and the system enters undefined territory.",
                            "CrashRecovery",
                            "throw new SensorFaultException(\"out of range\"); // let supervisor"
                                    + " restart"),
                    "Immutability in the Pure Parts",
                    new RuleKnowledge(
                            "Immutability in the Pure Parts",
                            "Public setters allow any caller to mutate state at any time, making"
                                    + " reasoning about the value at a given point impossible.",
                            "record",
                            "record Telemetry(double vCar, long ts) {} // no setters possible"),
                    "Supervised Restarts, Not Defensive Code",
                    new RuleKnowledge(
                            "Supervised Restarts, Not Defensive Code",
                            "Thread.sleep loops and manual retry logic in business code mask"
                                    + " transient faults rather than surfacing them to a supervisor.",
                            "Supervisor + CrashRecovery",
                            "supervisor.supervise(\"fetch\", s, h); // crash → restart, not retry"));

    // ── Quick-fix stubs for self-verification ─────────────────────────────────

    /**
     * Simple text substitutions that neutralise the most common detection pattern for each rule.
     * Applied during self-verification (Stage 4) to probe whether the plan's fix improves the
     * GoNoGo verdict.
     */
    private static final Map<String, String[]> QUICK_FIXES =
            Map.of(
                    // Rule 7 — Fail Loudly: empty catch block → propagate
                    "Fail Loudly",
                    new String[] {"catch (Exception e) {}", "catch (Exception e) { throw e; }"},
                    // Rule 1 — Share Nothing: static volatile field → plain volatile
                    "Share Nothing",
                    new String[] {"static volatile ", "volatile "},
                    // Rule 8 — Immutability in the Pure Parts: public setter → private
                    "Immutability in the Pure Parts",
                    new String[] {"public void set", "private void set"},
                    // Rule 9 — Supervised Restarts: retry loop → placeholder comment
                    "Supervised Restarts, Not Defensive Code",
                    new String[] {
                        "while (retries-- > 0)", "// OTP: let supervisor restart instead"
                    });

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Synchronous entry point — drives the full four-stage AGI pipeline and returns the finished
     * assessment.
     *
     * @param javaSource the Java source text to analyze
     * @param className  simple class name (used by {@link GoNoGoEngine} for refactor templates)
     * @return complete {@link AgiAssessment} with evidence, explanation chains, action plan, and
     *         self-verification result
     */
    public static AgiAssessment assess(String javaSource, String className) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        Objects.requireNonNull(className, "className must not be null");

        var evidence = gatherEvidence(javaSource, className);                   // Stage 1
        var chains = buildExplanationChains(evidence);                          // Stage 2
        var plan = buildActionPlan(chains, evidence, className, javaSource);   // Stage 3
        var selfVerified = selfVerify(javaSource, className, evidence, plan);  // Stage 4

        return new AgiAssessment(className, evidence, plan, selfVerified);
    }

    /**
     * Async entry point — starts the pipeline on a virtual thread and returns the live
     * {@link StateMachine} handle for state inspection.
     *
     * <p>Progress through {@code Idle → Assessing → Explaining → Planning → Done} can be observed
     * via {@code sm.state()} — the Erlang {@code sys:get_state/1} pattern applied to the engine
     * itself. The completed data is available via {@code sm.data()} once {@link AgiState.Done} is
     * reached.
     *
     * @param javaSource the Java source text to analyze
     * @param className  simple class name
     * @return a running {@link StateMachine} — poll {@code sm.state()} until {@code Done}
     */
    public static StateMachine<AgiState, AgiEvent, AgiData> assessAsync(
            String javaSource, String className) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        Objects.requireNonNull(className, "className must not be null");

        var sm =
                new StateMachine<AgiState, AgiEvent, AgiData>(
                        new AgiState.Idle(),
                        AgiData.initial(javaSource, className),
                        ArmstrongAgiEngine::transition);

        // Drive the pipeline from a virtual thread — fires events as each stage completes.
        // Armstrong: each stage is a pure computation; the state machine records progress.
        Thread.ofVirtual()
                .name("agi-driver[" + className + "]")
                .start(
                        () -> {
                            sm.send(new AgiEvent.Start(javaSource, className));
                            var evidence = gatherEvidence(javaSource, className);
                            sm.send(new AgiEvent.AssessmentComplete(evidence));
                            var chains = buildExplanationChains(evidence);
                            sm.send(new AgiEvent.ExplanationsReady(chains));
                            var plan = buildActionPlan(chains, evidence, className, javaSource);
                            sm.send(new AgiEvent.PlanReady(plan));
                        });

        return sm;
    }

    // ── StateMachine transition function ──────────────────────────────────────

    private static StateMachine.Transition<AgiState, AgiData> transition(
            AgiState state, AgiEvent event, AgiData data) {
        return switch (state) {
            case AgiState.Idle() ->
                    switch (event) {
                        case AgiEvent.Start(var src, var cls) ->
                                StateMachine.Transition.nextState(
                                        new AgiState.Assessing(), AgiData.initial(src, cls));
                        default -> StateMachine.Transition.keepState(data);
                    };
            case AgiState.Assessing() ->
                    switch (event) {
                        case AgiEvent.AssessmentComplete(var evidence) ->
                                StateMachine.Transition.nextState(
                                        new AgiState.Explaining(),
                                        new AgiData(
                                                data.source(),
                                                data.className(),
                                                evidence,
                                                null,
                                                null));
                        default -> StateMachine.Transition.keepState(data);
                    };
            case AgiState.Explaining() ->
                    switch (event) {
                        case AgiEvent.ExplanationsReady(var chains) ->
                                StateMachine.Transition.nextState(
                                        new AgiState.Planning(),
                                        new AgiData(
                                                data.source(),
                                                data.className(),
                                                data.evidence(),
                                                chains,
                                                null));
                        default -> StateMachine.Transition.keepState(data);
                    };
            case AgiState.Planning() ->
                    switch (event) {
                        case AgiEvent.PlanReady(var plan) ->
                                StateMachine.Transition.nextState(
                                        new AgiState.Done(),
                                        new AgiData(
                                                data.source(),
                                                data.className(),
                                                data.evidence(),
                                                data.chains(),
                                                plan));
                        default -> StateMachine.Transition.keepState(data);
                    };
            case AgiState.Done() -> StateMachine.Transition.keepState(data);
        };
    }

    // ── Stage 1: Parallel evidence gathering ──────────────────────────────────

    private static AgiEvidence gatherEvidence(String javaSource, String className) {
        var scorer = new ModernizationScorer();
        var docGen = new LivingDocGenerator();

        // All four engines run concurrently on virtual threads; heterogeneous return types
        // prevent use of Parallel.all, so we use CompletableFuture with a virtual-thread executor.
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var verdictFuture =
                    CompletableFuture.supplyAsync(
                            () -> GoNoGoEngine.check(javaSource, className), exec);
            var violationsFuture =
                    CompletableFuture.supplyAsync(() -> GoNoGoEngine.audit(javaSource), exec);
            var scoreFuture =
                    CompletableFuture.supplyAsync(() -> scorer.analyze(javaSource), exec);
            var migrationsFuture =
                    CompletableFuture.supplyAsync(
                            () -> OntologyMigrationEngine.analyze(className + ".java", javaSource),
                            exec);
            var docFuture =
                    CompletableFuture.supplyAsync(() -> docGen.parseSource(javaSource), exec);

            CompletableFuture.allOf(
                            verdictFuture, violationsFuture, scoreFuture, migrationsFuture,
                            docFuture)
                    .join();

            return new AgiEvidence(
                    verdictFuture.join(),
                    violationsFuture.join(),
                    scoreFuture.join(),
                    migrationsFuture.join(),
                    docFuture.join());
        }
    }

    // ── Stage 2: Explanation generation ───────────────────────────────────────

    private static List<ExplanationChain> buildExplanationChains(AgiEvidence evidence) {
        return evidence.violations().stream()
                .map(
                        violation -> {
                            var knowledge =
                                    RULE_KNOWLEDGE.getOrDefault(
                                            violation.ruleName(),
                                            new RuleKnowledge(
                                                    violation.ruleName(),
                                                    "This pattern violates Armstrong's"
                                                            + " fault-tolerance principles.",
                                                    "Proc<S,M>",
                                                    "// consult OTP primitive documentation"));
                            return new ExplanationChain(
                                    violation,
                                    knowledge.armstrongPrinciple(),
                                    knowledge.rootCause(),
                                    knowledge.otpFix(),
                                    knowledge.codeHint());
                        })
                .toList();
    }

    // ── Stage 3: Action planning ───────────────────────────────────────────────

    private static ActionPlan buildActionPlan(
            List<ExplanationChain> chains,
            AgiEvidence evidence,
            String className,
            String javaSource) {

        var packageName = extractPackage(javaSource);
        var safeActions = new ArrayList<RefactorEngine.JgenCommand>();
        var breakingActions = new ArrayList<RefactorEngine.JgenCommand>();

        for (var plan : evidence.migrations().byPriority()) {
            var rawTemplate = plan.rule().targetTemplate();
            var template =
                    rawTemplate.endsWith(".tera")
                            ? rawTemplate.substring(0, rawTemplate.length() - 5)
                            : rawTemplate;

            var cmd =
                    new RefactorEngine.JgenCommand(
                            template,
                            className,
                            packageName,
                            plan.rule().label(),
                            plan.rule().priority(),
                            plan.rule().breaking());

            if (plan.rule().breaking()) breakingActions.add(cmd);
            else safeActions.add(cmd);
        }

        // Sort safe actions by priority (1 = highest); breaking actions similarly
        safeActions.sort(Comparator.comparingInt(RefactorEngine.JgenCommand::priority));
        breakingActions.sort(Comparator.comparingInt(RefactorEngine.JgenCommand::priority));

        // Confidence: 1.0 when no violations; decreases with LIVE_BLOCKER count
        long liveBlockers =
                evidence.violations().stream()
                        .filter(v -> v.severity() == GoNoGoEngine.Severity.LIVE_BLOCKER)
                        .count();
        long total = evidence.violations().size();
        double confidence =
                total == 0
                        ? 1.0
                        : Math.max(0.1, Math.min(1.0, 1.0 - (double) liveBlockers / (total + 1)));

        var summary = buildSummary(evidence, safeActions, breakingActions, confidence);
        return new ActionPlan(chains, safeActions, breakingActions, confidence, summary);
    }

    private static String buildSummary(
            AgiEvidence evidence,
            List<RefactorEngine.JgenCommand> safeActions,
            List<RefactorEngine.JgenCommand> breakingActions,
            double confidence) {

        if (evidence.violations().isEmpty()) {
            return ("Armstrong verdict: PASS — code satisfies all 9 fault-tolerance rules."
                            + " Modernization score: %d/100.")
                    .formatted(evidence.score().overallScore());
        }

        var worstSeverity =
                evidence.violations().stream()
                        .map(GoNoGoEngine.RuleViolation::severity)
                        .max(Comparator.naturalOrder())
                        .orElse(GoNoGoEngine.Severity.DEFER);

        return ("Armstrong verdict: %s — %d violation(s) detected (worst: %s)."
                        + " Modernization score: %d/100."
                        + " %d safe action(s), %d breaking action(s)."
                        + " Confidence: %.0f%%.")
                .formatted(
                        worstSeverity == GoNoGoEngine.Severity.LIVE_BLOCKER
                                ? "LIVE BLOCKER"
                                : "ACTION NEEDED",
                        evidence.violations().size(),
                        worstSeverity,
                        evidence.score().overallScore(),
                        safeActions.size(),
                        breakingActions.size(),
                        confidence * 100);
    }

    // ── Stage 4: Self-verification ────────────────────────────────────────────

    /**
     * Applies a stub quick-fix to the source for the most severe violation, re-runs
     * {@link GoNoGoEngine#check}, and returns {@code true} if the verdict improves.
     *
     * <p>Lower {@link #verdictScore} = better verdict. Improvement means the patched verdict has a
     * lower score than the original, confirming the plan's top action is effective.
     */
    private static boolean selfVerify(
            String javaSource,
            String className,
            AgiEvidence evidence,
            ActionPlan plan) {

        if (evidence.violations().isEmpty()) return true; // already clean — trivially verified

        // Target the highest-severity violation first (LIVE_BLOCKER > POST_LIVE > DEFER)
        var target =
                evidence.violations().stream()
                        .max(Comparator.comparingInt(v -> v.severity().ordinal()))
                        .orElse(null);
        if (target == null) return false;

        var fix = QUICK_FIXES.get(target.ruleName());
        if (fix == null) return false; // no stub for this rule

        var patched = javaSource.replace(fix[0], fix[1]);
        if (patched.equals(javaSource)) return false; // stub didn't match — pattern not present

        var newVerdict = GoNoGoEngine.check(patched, className);
        return verdictScore(newVerdict) < verdictScore(evidence.verdict());
    }

    /**
     * Verdict quality score — lower is better.
     *
     * <ul>
     *   <li>0 — {@link GoNoGoEngine.Verdict.NoGo} (clean code, no violations)
     *   <li>1 — {@link GoNoGoEngine.Verdict.Go} (one violation, instant refactor exists)
     *   <li>2 — {@link GoNoGoEngine.Verdict.NoGoComplex} (multiple violations)
     *   <li>3 — {@link GoNoGoEngine.Verdict.NoGoLiveRisk} (LIVE_BLOCKER in large class)
     * </ul>
     */
    private static int verdictScore(GoNoGoEngine.Verdict verdict) {
        return switch (verdict) {
            case GoNoGoEngine.Verdict.NoGo ignored -> 0;
            case GoNoGoEngine.Verdict.Go ignored -> 1;
            case GoNoGoEngine.Verdict.NoGoComplex ignored -> 2;
            case GoNoGoEngine.Verdict.NoGoLiveRisk ignored -> 3;
        };
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String extractPackage(String hint) {
        var m = PACKAGE_DECL.matcher(hint);
        return m.find() ? m.group(1) : "org.acme";
    }
}
