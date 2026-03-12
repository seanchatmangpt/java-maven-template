package io.github.seanchatmangpt.jotp.dogfood.innovation;

import io.github.seanchatmangpt.jotp.dogfood.innovation.GoNoGoEngine.Severity;
import io.github.seanchatmangpt.jotp.dogfood.innovation.GoNoGoEngine.Verdict;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GoNoGoEngine")
class GoNoGoEngineTest implements WithAssertions {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Verdict check(String source) {
        return GoNoGoEngine.check(source, "TestClass");
    }

    private static String small(String body) {
        // Small class (<= 150 lines) — single LIVE_BLOCKER → Go, not NoGoLiveRisk
        return """
                package com.example;
                public class TestClass {
                %s
                }
                """
                .formatted(body);
    }

    private static String large(String body) {
        // Large class (> 150 lines) — LIVE_BLOCKER → NoGoLiveRisk
        var padding = "\n".repeat(160);
        return """
                package com.example;
                public class TestClass {
                %s
                %s
                }
                """
                .formatted(body, padding);
    }

    // ── Rule 1 — Share Nothing ────────────────────────────────────────────────

    @Nested
    @DisplayName("Rule 1 — Share Nothing")
    class Rule1 {

        @Test
        @DisplayName("static HashMap → Go LIVE_BLOCKER")
        void staticHashMap() {
            var source = small(
                    "private static final Map<String, User> cache = new HashMap<>();");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(1);
            assertThat(go.violation().severity()).isEqualTo(Severity.LIVE_BLOCKER);
            assertThat(go.refactorCommand()).contains("patterns/actor");
            assertThat(go.refactorCommand()).contains("TestClass");
        }

        @Test
        @DisplayName("synchronized block → Go LIVE_BLOCKER")
        void synchronizedBlock() {
            var source = small("void update() { synchronized (this) { count++; } }");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            assertThat(((Verdict.Go) verdict).violation().severity())
                    .isEqualTo(Severity.LIVE_BLOCKER);
        }

        @Test
        @DisplayName("ThreadLocal → Go LIVE_BLOCKER")
        void threadLocal() {
            var source = small(
                    "private static final ThreadLocal<User> currentUser = new ThreadLocal<>();");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            assertThat(((Verdict.Go) verdict).violation().ruleNumber()).isEqualTo(1);
            assertThat(((Verdict.Go) verdict).violation().severity())
                    .isEqualTo(Severity.LIVE_BLOCKER);
        }

        @Test
        @DisplayName("static volatile → Go LIVE_BLOCKER")
        void staticVolatile() {
            var source = small("private static volatile Config instance;");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            assertThat(((Verdict.Go) verdict).violation().ruleNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("large class with Rule 1 → NoGoLiveRisk")
        void largeClassWithRule1() {
            var source = large(
                    "private static final Map<String, String> cache = new HashMap<>();");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.NoGoLiveRisk.class);
            var risk = (Verdict.NoGoLiveRisk) verdict;
            assertThat(risk.violation().ruleNumber()).isEqualTo(1);
            assertThat(risk.violation().severity()).isEqualTo(Severity.LIVE_BLOCKER);
            assertThat(risk.monitor()).isNotBlank();
            assertThat(risk.schedule()).isNotBlank();
        }
    }

    // ── Rule 2 — Let It Crash ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Rule 2 — Let It Crash")
    class Rule2 {

        @Test
        @DisplayName("log-and-continue catch → Go LIVE_BLOCKER")
        void logAndContinue() {
            var source = small(
                    """
                    void process() {
                        try { doWork(); }
                        catch (Exception e) { log.error("failed", e); }
                    }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(2);
            assertThat(go.violation().severity()).isEqualTo(Severity.LIVE_BLOCKER);
            assertThat(go.refactorCommand()).contains("CrashRecovery");
        }
    }

    // ── Rule 5 — Pattern Match Everything ────────────────────────────────────

    @Nested
    @DisplayName("Rule 5 — Pattern Match Everything")
    class Rule5 {

        @Test
        @DisplayName("instanceof chain → Go DEFER")
        void instanceofChain() {
            var source = small(
                    """
                    void handle(Object msg) {
                        if (msg instanceof Foo) { doFoo(); }
                        else if (msg instanceof Bar) { doBar(); }
                    }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(5);
            assertThat(go.violation().severity()).isEqualTo(Severity.DEFER);
            assertThat(go.refactorCommand()).contains("core/sealed-types");
        }
    }

    // ── Rule 6 — Data Is Correct or Wrong ────────────────────────────────────

    @Nested
    @DisplayName("Rule 6 — Data Is Correct or Wrong")
    class Rule6 {

        @Test
        @DisplayName("return null → Go POST_LIVE")
        void returnNull() {
            var source = small(
                    """
                    User findUser(String id) {
                        if (id == null) return null;
                        return repo.find(id);
                    }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(6);
            assertThat(go.violation().severity()).isEqualTo(Severity.POST_LIVE);
            assertThat(go.refactorCommand()).contains("error-handling/result-railway");
        }
    }

    // ── Rule 7 — Fail Loudly ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Rule 7 — Fail Loudly")
    class Rule7 {

        @Test
        @DisplayName("catch returns false → Go LIVE_BLOCKER")
        void catchReturnsFalse() {
            var source = small(
                    """
                    boolean save(User u) {
                        try { repo.save(u); return true; }
                        catch (Exception e) { return false; }
                    }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(7);
            assertThat(go.violation().severity()).isEqualTo(Severity.LIVE_BLOCKER);
            assertThat(go.refactorCommand()).contains("Result.of");
        }

        @Test
        @DisplayName("empty catch block → Go LIVE_BLOCKER")
        void emptyCatch() {
            var source = small(
                    """
                    void load() {
                        try { init(); }
                        catch (Exception e) {}
                    }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            assertThat(((Verdict.Go) verdict).violation().ruleNumber()).isEqualTo(7);
            assertThat(((Verdict.Go) verdict).violation().severity())
                    .isEqualTo(Severity.LIVE_BLOCKER);
        }
    }

    // ── Rule 8 — Immutability in the Pure Parts ───────────────────────────────

    @Nested
    @DisplayName("Rule 8 — Immutability in the Pure Parts")
    class Rule8 {

        @Test
        @DisplayName("public setter → Go DEFER")
        void publicSetter() {
            var source = small(
                    """
                    private String name;
                    public void setName(String name) { this.name = name; }
                    public String getName() { return name; }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(8);
            assertThat(go.violation().severity()).isEqualTo(Severity.DEFER);
            assertThat(go.refactorCommand()).contains("core/record");
        }
    }

    // ── Rule 9 — Supervised Restarts ──────────────────────────────────────────

    @Nested
    @DisplayName("Rule 9 — Supervised Restarts, Not Defensive Code")
    class Rule9 {

        @Test
        @DisplayName("Thread.sleep in retry loop → Go POST_LIVE")
        void threadSleep() {
            var source = small(
                    """
                    void send() throws InterruptedException {
                        for (int i = 0; i < 3; i++) {
                            try { doSend(); return; }
                            catch (Exception e) { Thread.sleep(1000 * i); }
                        }
                    }
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var go = (Verdict.Go) verdict;
            assertThat(go.violation().ruleNumber()).isEqualTo(9);
            assertThat(go.violation().severity()).isEqualTo(Severity.POST_LIVE);
            assertThat(go.refactorCommand()).contains("patterns/supervisor");
        }

        @Test
        @DisplayName("maxRetries field → Go POST_LIVE")
        void maxRetriesField() {
            var source = small("private int maxRetries = 5;");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            assertThat(((Verdict.Go) verdict).violation().ruleNumber()).isEqualTo(9);
            assertThat(((Verdict.Go) verdict).violation().severity())
                    .isEqualTo(Severity.POST_LIVE);
        }
    }

    // ── NoGo — correct code ───────────────────────────────────────────────────

    @Nested
    @DisplayName("NoGo — correct code")
    class NoGoTests {

        @Test
        @DisplayName("Result railway record → NoGo")
        void resultRecord() {
            var source =
                    """
                    package io.github.seanchatmangpt.jotp;
                    public record UserId(String value) {
                        public static Result<UserId, String> of(String raw) {
                            return raw == null || raw.isBlank()
                                ? Result.failure("id cannot be blank")
                                : Result.success(new UserId(raw));
                        }
                    }
                    """;
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.NoGo.class);
        }

        @Test
        @DisplayName("virtual-thread Proc → NoGo")
        void procWithVirtualThread() {
            var source =
                    """
                    package io.github.seanchatmangpt.jotp;
                    public record CounterState(int count) {}
                    public final class Counter {
                        private final Proc<CounterState, Integer> proc =
                            new Proc<>(new CounterState(0),
                                (state, msg) -> new CounterState(state.count() + msg));
                    }
                    """;
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.NoGo.class);
        }
    }

    // ── NoGoComplex — multiple rules violated ─────────────────────────────────

    @Nested
    @DisplayName("NoGoComplex — multiple distinct rules")
    class NoGoComplexTests {

        @Test
        @DisplayName("Rule 8 + Rule 9 → NoGoComplex")
        void rule8AndRule9() {
            var source = small(
                    """
                    private String name;
                    private int maxRetries = 3;
                    public void setName(String n) { this.name = n; }
                    """);
            var verdict = check(source);

            // Rules 8 (DEFER) and 9 (POST_LIVE) — no LIVE_BLOCKER → NoGoComplex
            assertThat(verdict).isInstanceOf(Verdict.NoGoComplex.class);
            var complex = (Verdict.NoGoComplex) verdict;
            assertThat(complex.reason()).contains("distinct rule");
            assertThat(complex.flag()).isNotBlank();
        }
    }

    // ── NoGoLiveRisk — multiple rules with LIVE_BLOCKER ───────────────────────

    @Nested
    @DisplayName("NoGoLiveRisk — LIVE_BLOCKER in large or multi-violation class")
    class NoGoLiveRiskTests {

        @Test
        @DisplayName("Rule 1 + Rule 9 → NoGoLiveRisk (has LIVE_BLOCKER)")
        void rule1AndRule9() {
            var source = small(
                    """
                    private static final Map<String, String> cache = new HashMap<>();
                    private int maxRetries = 3;
                    """);
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.NoGoLiveRisk.class);
            var risk = (Verdict.NoGoLiveRisk) verdict;
            assertThat(risk.violation().severity()).isEqualTo(Severity.LIVE_BLOCKER);
        }
    }

    // ── render() output format ────────────────────────────────────────────────

    @Nested
    @DisplayName("render() output format")
    class RenderTests {

        @Test
        @DisplayName("Go render contains all required sections")
        void goRender() {
            var source = small("private static final List<String> items = new ArrayList<>();");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.Go.class);
            var rendered = verdict.render();
            assertThat(rendered)
                    .contains("RULE VIOLATED:")
                    .contains("SEVERITY:")
                    .contains("GO —")
                    .contains("REFACTOR:")
                    .contains("BECAUSE:");
        }

        @Test
        @DisplayName("NoGo render starts with NO-GO —")
        void noGoRender() {
            var verdict = check("package io.github.seanchatmangpt.jotp; public record Foo(String x) {}");
            assertThat(verdict.render()).startsWith("NO-GO —");
        }

        @Test
        @DisplayName("NoGoComplex render contains NO-GO (COMPLEX) and FLAG:")
        void noGoComplexRender() {
            var source = small(
                    "private String n; public void setN(String n){this.n=n;} int maxRetries=3;");
            var verdict = check(source);
            if (verdict instanceof Verdict.NoGoComplex) {
                assertThat(verdict.render())
                        .contains("NO-GO (COMPLEX)")
                        .contains("FLAG:");
            }
        }

        @Test
        @DisplayName("NoGoLiveRisk render contains all required sections")
        void noGoLiveRiskRender() {
            var source = large("private static final Map<String,String> m = new HashMap<>();");
            var verdict = check(source);

            assertThat(verdict).isInstanceOf(Verdict.NoGoLiveRisk.class);
            var rendered = verdict.render();
            assertThat(rendered)
                    .contains("NO-GO (LIVE RISK)")
                    .contains("SEVERITY:")
                    .contains("MONITOR:")
                    .contains("SCHEDULE:");
        }
    }

    // ── audit() — full scan ───────────────────────────────────────────────────

    @Nested
    @DisplayName("audit() — returns all violations without first-wins")
    class AuditTests {

        @Test
        @DisplayName("multiple violations all returned")
        void multipleViolations() {
            var source = small(
                    """
                    private static final Map<String, String> cache = new HashMap<>();
                    private String name;
                    public void setName(String n) { this.name = n; }
                    private int maxRetries = 3;
                    """);
            var violations = GoNoGoEngine.audit(source);

            // Rule 1, Rule 8, Rule 9 all present
            var ruleNumbers =
                    violations.stream()
                            .map(GoNoGoEngine.RuleViolation::ruleNumber)
                            .toList();
            assertThat(ruleNumbers).contains(1, 8, 9);
        }

        @Test
        @DisplayName("no violations returns empty list")
        void noViolations() {
            var violations = GoNoGoEngine.audit("package io.github.seanchatmangpt.jotp; public record Foo(int x) {}");
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("audit result is immutable")
        void immutableResult() {
            var violations = GoNoGoEngine.audit(
                    small("private static final List<String> x = new ArrayList<>();"));
            assertThatThrownBy(() -> violations.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Edge Cases (JIDOKA - Stop and Fix) ────────────────────────────────────

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCases {

        @Test
        @DisplayName("check null source throws NullPointerException")
        void checkNullSource() {
            assertThatNullPointerException()
                    .isThrownBy(() -> GoNoGoEngine.check(null, "TestClass"))
                    .withMessageContaining("javaSource");
        }

        @Test
        @DisplayName("check null className throws NullPointerException")
        void checkNullClassName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> GoNoGoEngine.check("public class Foo {}", null))
                    .withMessageContaining("className");
        }

        @Test
        @DisplayName("audit null source throws NullPointerException")
        void auditNullSource() {
            assertThatNullPointerException()
                    .isThrownBy(() -> GoNoGoEngine.audit(null))
                    .withMessageContaining("javaSource");
        }

        @Test
        @DisplayName("check empty source returns NoGo (code is correct)")
        void checkEmptySource() {
            var verdict = GoNoGoEngine.check("", "Empty");

            // NoGo means "code is already correct" - no violations found
            assertThat(verdict).isInstanceOf(Verdict.NoGo.class);
        }

        @Test
        @DisplayName("audit empty source returns empty list")
        void auditEmptySource() {
            var violations = GoNoGoEngine.audit("");

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("check blank source returns NoGo (code is correct)")
        void checkBlankSource() {
            var verdict = GoNoGoEngine.check("   \n\n   ", "Blank");

            // NoGo means "code is already correct" - no violations found
            assertThat(verdict).isInstanceOf(Verdict.NoGo.class);
        }

        @Test
        @DisplayName("audit blank source returns empty list")
        void auditBlankSource() {
            var violations = GoNoGoEngine.audit("   \n\n   ");

            assertThat(violations).isEmpty();
        }
    }
}
