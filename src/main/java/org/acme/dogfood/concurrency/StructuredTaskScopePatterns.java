package org.acme.dogfood.concurrency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Structured concurrency patterns using {@link StructuredTaskScope} (Java 21 preview).
 *
 * <p>Generated from {@code templates/java/concurrency/structured-task-scope.tera}.
 *
 * <p>Structured concurrency ensures that concurrent subtasks are treated as a single unit of work:
 * they are all started within a scope, and the scope does not complete until every subtask has
 * finished (successfully, with failure, or via cancellation). This eliminates thread leaks and
 * dangling tasks.
 *
 * <h2>Two built-in policies (Java 21 preview):</h2>
 *
 * <ul>
 *   <li>{@code ShutdownOnFailure} — all subtasks must succeed; the first failure cancels the rest
 *   <li>{@code ShutdownOnSuccess<T>} — first successful result wins; remaining subtasks are
 *       cancelled
 * </ul>
 */
@SuppressWarnings("preview")
public final class StructuredTaskScopePatterns {

    private StructuredTaskScopePatterns() {}

    // =========================================================================
    // PATTERN 1: All-must-succeed — two tasks
    // =========================================================================

    public record Pair<A, B>(A first, B second) {}

    /**
     * Runs two independent tasks concurrently; both must succeed. If either fails, the other is
     * cancelled immediately.
     */
    public static <A, B> Pair<A, B> runBoth(Callable<A> taskA, Callable<B> taskB)
            throws ExecutionException, InterruptedException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Subtask<A> a = scope.fork(taskA);
            Subtask<B> b = scope.fork(taskB);
            scope.join().throwIfFailed();
            return new Pair<>(a.get(), b.get());
        }
    }

    // =========================================================================
    // PATTERN 2: All-must-succeed — three tasks
    // =========================================================================

    public record Triple<A, B, C>(A first, B second, C third) {}

    /** Runs three independent tasks concurrently; all must succeed. */
    public static <A, B, C> Triple<A, B, C> runAll(
            Callable<A> taskA, Callable<B> taskB, Callable<C> taskC)
            throws ExecutionException, InterruptedException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Subtask<A> a = scope.fork(taskA);
            Subtask<B> b = scope.fork(taskB);
            Subtask<C> c = scope.fork(taskC);
            scope.join().throwIfFailed();
            return new Triple<>(a.get(), b.get(), c.get());
        }
    }

    // =========================================================================
    // PATTERN 3: Race — first result wins
    // =========================================================================

    /**
     * Races multiple competing tasks; returns the first successful result. All other subtasks are
     * cancelled once a winner is determined.
     */
    public static <T> T raceForFirst(List<Callable<T>> tasks)
            throws ExecutionException, InterruptedException {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
            for (var task : tasks) scope.fork(task);
            scope.join();
            return scope.result();
        }
    }

    // =========================================================================
    // PATTERN 4: Timeout with joinUntil
    // =========================================================================

    /**
     * Runs a task with a deadline. If the task does not complete in time, it is cancelled and the
     * scope throws.
     */
    public static <T> T runWithTimeout(Callable<T> task, Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Subtask<T> subtask = scope.fork(task);
            scope.joinUntil(Instant.now().plus(timeout)).throwIfFailed();
            return subtask.get();
        }
    }

    // =========================================================================
    // PATTERN 5: Fan-out / scatter-gather
    // =========================================================================

    /**
     * Fans out work across virtual threads, one per item. All must succeed; failure in any cancels
     * the rest. Results are returned in the same order as the input items.
     */
    public static <T, R> List<R> fanOut(List<T> items, Function<T, R> processor)
            throws ExecutionException, InterruptedException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<Subtask<R>> subtasks =
                    items.stream().map(item -> scope.fork(() -> processor.apply(item))).toList();
            scope.join().throwIfFailed();
            return subtasks.stream().map(Subtask::get).toList();
        }
    }

    // =========================================================================
    // PATTERN 6: Fan-out with timeout
    // =========================================================================

    /**
     * Fans out work with a deadline. If the work is not complete in time, all remaining subtasks
     * are cancelled.
     */
    public static <T, R> List<R> fanOutWithTimeout(
            List<T> items, Function<T, R> processor, Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<Subtask<R>> subtasks =
                    items.stream().map(item -> scope.fork(() -> processor.apply(item))).toList();
            scope.joinUntil(Instant.now().plus(timeout)).throwIfFailed();
            return subtasks.stream().map(Subtask::get).toList();
        }
    }

    // =========================================================================
    // PATTERN 7: Nested scopes — composing structured concurrency
    // =========================================================================

    /**
     * Demonstrates nested structured task scopes. The outer scope coordinates two independent
     * groups of work, each of which uses its own inner scope for fan-out.
     */
    public static <T, R> Pair<List<R>, List<R>> nestedFanOut(
            List<T> groupA, List<T> groupB, Function<T, R> processor)
            throws ExecutionException, InterruptedException {
        try (var outer = new StructuredTaskScope.ShutdownOnFailure()) {
            Subtask<List<R>> resultsA = outer.fork(() -> fanOut(groupA, processor));
            Subtask<List<R>> resultsB = outer.fork(() -> fanOut(groupB, processor));
            outer.join().throwIfFailed();
            return new Pair<>(resultsA.get(), resultsB.get());
        }
    }
}
