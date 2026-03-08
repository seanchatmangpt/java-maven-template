package org.acme.dogfood.concurrency;

import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Supplier;

/**
 * {@link ScopedValue} patterns for immutable, inheritable context (Java 26).
 *
 * <p>Generated from {@code templates/java/concurrency/scoped-value.tera}.
 *
 * <p>Scoped values provide a mechanism to share immutable data within a thread and its child
 * threads (virtual or platform) for a bounded period. They are the modern replacement for {@link
 * ThreadLocal} and are designed to work naturally with virtual threads and structured concurrency.
 *
 * <h2>Why ScopedValue over ThreadLocal?</h2>
 *
 * <ul>
 *   <li>Immutable — cannot be mutated once bound; no stale-value bugs
 *   <li>Bounded lifetime — automatically unbound when scope exits
 *   <li>Inherited by child virtual threads — works with {@link StructuredTaskScope}
 *   <li>No memory leaks — no need for {@code remove()} calls
 *   <li>Better performance — optimized for virtual threads
 * </ul>
 */
public final class ScopedValuePatterns {

    private ScopedValuePatterns() {}

    // =========================================================================
    // PATTERN 1: Basic scoped value — request context
    // =========================================================================

    /** The currently authenticated user for this request scope. */
    public static final ScopedValue<String> CURRENT_USER = ScopedValue.newInstance();

    /**
     * Runs a request handler with a bound user identity. The binding is automatically removed when
     * the scope exits.
     */
    public static void handleAsUser(String userId, Runnable handler) {
        ScopedValue.where(CURRENT_USER, userId).run(handler);
    }

    /** Returns the current user within a scoped context. */
    public static String currentUser() {
        return CURRENT_USER.get();
    }

    // =========================================================================
    // PATTERN 2: Scoped value with a return value — call()
    // =========================================================================

    /** A trace ID for distributed tracing. */
    public static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

    /** Executes a callable within a tracing scope and returns the result. */
    public static <T> T withTrace(String traceId, Callable<T> task) throws Exception {
        return ScopedValue.where(TRACE_ID, traceId).call(task::call);
    }

    // =========================================================================
    // PATTERN 3: Multiple scoped values bound together (Carrier)
    // =========================================================================

    /** The tenant ID for multi-tenant applications. */
    public static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();

    /** Represents the full context for an incoming request. */
    public record RequestContext(String userId, String traceId, String tenantId) {}

    /**
     * Binds a full request context (user, trace, tenant) and runs the handler. All three values
     * are bound atomically and inherited by child threads.
     */
    public static void withRequestContext(RequestContext ctx, Runnable handler) {
        ScopedValue.where(CURRENT_USER, ctx.userId())
                .where(TRACE_ID, ctx.traceId())
                .where(TENANT_ID, ctx.tenantId())
                .run(handler);
    }

    /** Binds a full request context and returns a result. */
    public static <T> T withRequestContext(RequestContext ctx, Callable<T> task) throws Exception {
        return ScopedValue.where(CURRENT_USER, ctx.userId())
                .where(TRACE_ID, ctx.traceId())
                .where(TENANT_ID, ctx.tenantId())
                .call(task::call);
    }

    // =========================================================================
    // PATTERN 4: Nested scopes — inner binding shadows outer
    // =========================================================================

    /**
     * Runs a privileged operation as a system user, temporarily shadowing any existing user
     * binding. The outer binding is automatically restored when the inner scope exits.
     */
    public static void runAsSystem(Runnable systemAction) {
        ScopedValue.where(CURRENT_USER, "SYSTEM").run(systemAction);
    }

    /**
     * Demonstrates nested scoping: the inner scope shadows the outer binding, and the outer value
     * is automatically restored on exit.
     */
    public static void nestedScopes(
            String outerUser, String innerUser, Runnable outerAction, Runnable innerAction) {
        ScopedValue.where(CURRENT_USER, outerUser)
                .run(
                        () -> {
                            outerAction.run();
                            ScopedValue.where(CURRENT_USER, innerUser).run(innerAction);
                            outerAction.run();
                        });
    }

    // =========================================================================
    // PATTERN 5: ScopedValue with StructuredTaskScope — inherited by child tasks
    // =========================================================================

    /**
     * Demonstrates that scoped values are automatically inherited by subtasks forked within a
     * {@link StructuredTaskScope}. No manual context passing is needed.
     */
    public static <T> java.util.List<T> concurrentWithContext(
            String userId, String traceId, Callable<T> taskA, Callable<T> taskB) throws Exception {
        return ScopedValue.where(CURRENT_USER, userId)
                .where(TRACE_ID, traceId)
                .call(
                        () -> {
                            try (var scope =
                                    StructuredTaskScope.open(
                                            StructuredTaskScope.Joiner
                                                    .<Object>awaitAllSuccessfulOrThrow())) {
                                var a = scope.fork(taskA);
                                var b = scope.fork(taskB);
                                scope.join();
                                return java.util.List.of(a.get(), b.get());
                            }
                        });
    }

    // =========================================================================
    // PATTERN 6: Conditional binding — check isBound() before reading
    // =========================================================================

    /** Safely reads the current user, returning a default if no binding exists. */
    public static String currentUserOrDefault(String defaultUser) {
        return CURRENT_USER.isBound() ? CURRENT_USER.get() : defaultUser;
    }

    /** Returns the current user wrapped in an Optional. */
    public static java.util.Optional<String> currentUserOptional() {
        return CURRENT_USER.isBound()
                ? java.util.Optional.of(CURRENT_USER.get())
                : java.util.Optional.empty();
    }

    // =========================================================================
    // PATTERN 7: Real-world server pattern — binding context at the edge
    // =========================================================================

    /**
     * Simulates a server request handler that binds scoped values at the entry point and uses
     * structured concurrency for parallel work. All downstream code reads context via {@code
     * ScopedValue.get()} without explicit parameter passing.
     */
    public static void serveRequest(
            String userId, String tenantId, String traceId, Runnable handler) {
        ScopedValue.where(CURRENT_USER, userId)
                .where(TENANT_ID, tenantId)
                .where(TRACE_ID, traceId)
                .run(handler);
    }
}
