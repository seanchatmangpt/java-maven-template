package org.acme;

import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Supplier;

/**
 * Parallel fan-out with fail-fast semantics, inspired by Erlang's supervisor philosophy.
 *
 * <p>Joe Armstrong: "If any process fails, fail fast. Don't mask errors — let a supervisor handle
 * them."
 *
 * <p>Uses Java 25 {@link StructuredTaskScope} with {@code Joiner.allSuccessfulOrThrow()}: all tasks
 * run concurrently on virtual threads, and on the <em>first</em> failure the scope cancels all
 * remaining tasks immediately (Armstrong: crash one, crash all). Returns {@code Result<List<T>,
 * Exception>} integrating with the existing railway-oriented {@link Result} type.
 *
 * <p>Requires {@code --enable-preview} (Java 25).
 */
public final class Parallel {

    private Parallel() {}

    /**
     * Run all {@code tasks} concurrently on virtual threads. Returns {@code Success(results)} if
     * every task succeeds (in fork order), or {@code Failure(firstException)} if any task fails —
     * cancelling the remaining tasks immediately.
     */
    public static <T> Result<List<T>, Exception> all(List<Supplier<T>> tasks) {
        try (var scope =
                StructuredTaskScope.open(StructuredTaskScope.Joiner.<T>allSuccessfulOrThrow())) {
            var subtasks =
                    tasks.stream()
                            .<StructuredTaskScope.Subtask<T>>map(t -> scope.fork(t::get))
                            .toList();
            var completed = scope.join();
            return Result.success(completed.map(StructuredTaskScope.Subtask::get).toList());
        } catch (Exception e) {
            return Result.failure(e);
        }
    }
}
