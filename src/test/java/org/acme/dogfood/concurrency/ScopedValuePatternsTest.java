package org.acme.dogfood.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class ScopedValuePatternsTest implements WithAssertions {

    // ── Pattern 1: Basic scoped value ────────────────────────────────────────

    @Test
    void handleAsUser_bindsUserForScope() {
        AtomicReference<String> captured = new AtomicReference<>();
        ScopedValuePatterns.handleAsUser("alice", () -> captured.set(ScopedValuePatterns.currentUser()));
        assertThat(captured.get()).isEqualTo("alice");
    }

    @Test
    void currentUser_throwsWhenNotBound() {
        assertThatThrownBy(ScopedValuePatterns::currentUser)
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── Pattern 2: Scoped value with return value ────────────────────────────

    @Test
    void withTrace_bindsAndReturns() throws Exception {
        Callable<String> task = () -> ScopedValuePatterns.TRACE_ID.get();
        var result = ScopedValuePatterns.withTrace("trace-123", task);
        assertThat(result).isEqualTo("trace-123");
    }

    @Test
    void withTrace_unbindsAfterScope() throws Exception {
        ScopedValuePatterns.withTrace("trace-456", () -> {
            assertThat(ScopedValuePatterns.TRACE_ID.get()).isEqualTo("trace-456");
            return null;
        });
        assertThat(ScopedValuePatterns.TRACE_ID.isBound()).isFalse();
    }

    // ── Pattern 3: Multiple scoped values bound together ──────────────────────

    @Test
    void withRequestContext_bindsAllValues() {
        var ctx = new ScopedValuePatterns.RequestContext("user-1", "trace-1", "tenant-1");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedTenant = new AtomicReference<>();

        ScopedValuePatterns.withRequestContext(ctx, () -> {
            capturedUser.set(ScopedValuePatterns.CURRENT_USER.get());
            capturedTrace.set(ScopedValuePatterns.TRACE_ID.get());
            capturedTenant.set(ScopedValuePatterns.TENANT_ID.get());
        });

        assertThat(capturedUser.get()).isEqualTo("user-1");
        assertThat(capturedTrace.get()).isEqualTo("trace-1");
        assertThat(capturedTenant.get()).isEqualTo("tenant-1");
    }

    @Test
    void withRequestContext_withReturn_bindsAllValues() throws Exception {
        var ctx = new ScopedValuePatterns.RequestContext("user-2", "trace-2", "tenant-2");
        var result = ScopedValuePatterns.withRequestContext(ctx, () -> {
            return ScopedValuePatterns.CURRENT_USER.get() + "|"
                    + ScopedValuePatterns.TRACE_ID.get() + "|"
                    + ScopedValuePatterns.TENANT_ID.get();
        });
        assertThat(result).isEqualTo("user-2|trace-2|tenant-2");
    }

    // ── Pattern 4: Nested scopes ─────────────────────────────────────────────

    @Test
    void runAsSystem_shadowsExistingBinding() {
        AtomicReference<String> outerUser = new AtomicReference<>();
        AtomicReference<String> innerUser = new AtomicReference<>();

        ScopedValuePatterns.handleAsUser("alice", () -> {
            outerUser.set(ScopedValuePatterns.currentUser());
            ScopedValuePatterns.runAsSystem(() -> innerUser.set(ScopedValuePatterns.currentUser()));
        });

        assertThat(outerUser.get()).isEqualTo("alice");
        assertThat(innerUser.get()).isEqualTo("SYSTEM");
    }

    @Test
    void nestedScopes_innerShadowsOuter() {
        var log = new java.util.ArrayList<String>();
        ScopedValuePatterns.nestedScopes(
                "outer-user",
                "inner-user",
                () -> log.add("outer: " + ScopedValuePatterns.currentUser()),
                () -> log.add("inner: " + ScopedValuePatterns.currentUser()));

        assertThat(log).containsExactly("outer: outer-user", "inner: inner-user", "outer: outer-user");
    }

    // ── Pattern 5: ScopedValue with StructuredTaskScope ───────────────────────

    @Test
    void concurrentWithContext_inheritsScopedValues() throws Exception {
        Callable<String> taskA = () -> ScopedValuePatterns.CURRENT_USER.get() + "|A";
        Callable<String> taskB = () -> ScopedValuePatterns.TRACE_ID.get() + "|B";

        var results = ScopedValuePatterns.concurrentWithContext("alice", "trace-xyz", taskA, taskB);

        assertThat(results).containsExactlyInAnyOrder("alice|A", "trace-xyz|B");
    }

    // ── Pattern 6: Conditional binding ────────────────────────────────────────

    @Test
    void currentUserOrDefault_returnsBoundValue() {
        ScopedValuePatterns.handleAsUser("bob", () -> {
            assertThat(ScopedValuePatterns.currentUserOrDefault("default")).isEqualTo("bob");
        });
    }

    @Test
    void currentUserOrDefault_returnsDefaultWhenNotBound() {
        assertThat(ScopedValuePatterns.currentUserOrDefault("default")).isEqualTo("default");
    }

    @Test
    void currentUserOptional_returnsEmptyWhenNotBound() {
        assertThat(ScopedValuePatterns.currentUserOptional()).isEmpty();
    }

    @Test
    void currentUserOptional_returnsValueWhenBound() {
        ScopedValuePatterns.handleAsUser("charlie", () -> {
            assertThat(ScopedValuePatterns.currentUserOptional()).contains("charlie");
        });
    }

    // ── Pattern 7: Real-world server pattern ──────────────────────────────────

    @Test
    void serveRequest_bindsFullContext() {
        var captured = new java.util.ArrayList<String>();
        ScopedValuePatterns.serveRequest("user-req", "tenant-req", "trace-req", () -> {
            captured.add(ScopedValuePatterns.CURRENT_USER.get());
            captured.add(ScopedValuePatterns.TENANT_ID.get());
            captured.add(ScopedValuePatterns.TRACE_ID.get());
        });
        assertThat(captured).containsExactly("user-req", "tenant-req", "trace-req");
    }
}
