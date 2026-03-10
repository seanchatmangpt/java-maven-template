package org.acme;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Result type — sealed interface for success/failure outcomes (railway-oriented programming).
 *
 * <p>Joe Armstrong: "In Erlang, we don't throw exceptions across process boundaries.
 * We return {@code {ok, Value}} or {@code {error, Reason}}. This forces the caller to
 * handle both cases explicitly."
 *
 * <p>This is the Java 26 equivalent of Erlang/OTP's tagged tuples. Instead of throwing
 * exceptions that propagate invisibly up the call stack, operations return a {@code Result}
 * that the caller must pattern-match on — making error handling explicit and composable.
 *
 * <p><strong>Mapping to Erlang:</strong>
 *
 * <pre>{@code
 * Erlang                        Java 26
 * ──────────────────────────    ──────────────────────────────────────
 * {ok, Value}               →   Result.ok(value) / Result.success(value)
 * {error, Reason}           →   Result.err(reason) / Result.failure(reason)
 * case {ok, V} -> ...       →   case Result.Ok<S,F>(var v) -> ...
 * case {error, E} -> ...    →   case Result.Err<S,F>(var e) -> ...
 * }</pre>
 *
 * <p><strong>Railway-oriented programming:</strong>
 *
 * <p>Think of success and failure as two parallel tracks. Operations on the success track
 * ({@link #map}, {@link #flatMap}) are skipped if the result is already on the failure track.
 * This lets you chain operations without nested if-statements:
 *
 * <pre>{@code
 * Result<Order, OrderError> result = Result.of(() -> validate(request))
 *     .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
 *     .flatMap(order -> Result.of(() -> reserveInventory(order)))
 *     .peek(order -> auditLog.add(order))
 *     .recover(error -> {
 *         metrics.recordFailure(error);
 *         return Result.failure(error);
 *     });
 * }</pre>
 *
 * <p><strong>Two naming conventions:</strong>
 *
 * <ul>
 *   <li>{@link Ok}/{@link Err} — Erlang-style short names
 *   <li>{@link Success}/{@link Failure} — more explicit names for readability
 * </ul>
 *
 * <p>Both conventions are equivalent; use whichever reads better in context.
 *
 * @param <S> success type — the value carried on success
 * @param <F> failure type — the error carried on failure (often {@code Exception} or a sealed error hierarchy)
 * @see CrashRecovery
 * @see Parallel
 */
public sealed interface Result<S, F> permits Result.Ok, Result.Err, Result.Success, Result.Failure {
    /** Successful result carrying a value — Erlang's {@code {ok, Value}}. */
    record Ok<S, F>(S value) implements Result<S, F> {}

    /** Failed result carrying an error — Erlang's {@code {error, Reason}}. */
    record Err<S, F>(F error) implements Result<S, F> {}

    // Aliases for pattern matching
    /** Alias for {@link Ok} — more explicit naming. */
    record Success<S, F>(S value) implements Result<S, F> {}

    /** Alias for {@link Err} — more explicit naming. */
    record Failure<S, F>(F error) implements Result<S, F> {}

    /**
     * Create a successful result.
     *
     * @param value the success value
     * @return {@code Ok(value)}
     */
    static <S, F> Result<S, F> ok(S value) { return new Ok<>(value); }

    /**
     * Create a failed result.
     *
     * @param error the error value
     * @return {@code Err(error)}
     */
    static <S, F> Result<S, F> err(F error) { return new Err<>(error); }

    // Aliases for convenience
    /**
     * Alias for {@link #ok(Object)} — more explicit naming.
     *
     * @param value the success value
     * @return {@code Success(value)}
     */
    static <S, F> Result<S, F> success(S value) { return new Success<>(value); }

    /**
     * Alias for {@link #err(Object)} — more explicit naming.
     *
     * @param error the error value
     * @return {@code Failure(error)}
     */
    static <S, F> Result<S, F> failure(F error) { return new Failure<>(error); }

    /**
     * Wrap a supplier that may throw — converts exceptions to {@code Err}.
     *
     * <p>This is the bridge between Java's exception-based APIs and railway-oriented
     * programming. Any exception thrown by {@code supplier} becomes an {@code Err}.
     *
     * @param supplier the operation to execute
     * @return {@code Ok(value)} if successful, {@code Err(exception)} if an exception was thrown
     */
    @SuppressWarnings("unchecked")
    static <S, F> Result<S, F> of(Supplier<S> supplier) {
        try {
            return new Ok<>(supplier.get());
        } catch (Exception e) {
            return (Result<S, F>) new Err<>(e);
        }
    }

    /** Returns {@code true} if this is a successful result. */
    default boolean isSuccess() { return this instanceof Ok<S, F> || this instanceof Success<S, F>; }

    /** Returns {@code true} if this is a failed result. */
    default boolean isError() { return this instanceof Err<S, F> || this instanceof Failure<S, F>; }

    /** Alias for {@link #isError()}. */
    default boolean isFailure() { return isError(); }

    /**
     * Transform the success value — railway "map" operation.
     *
     * <p>If this is a success, applies {@code mapper} to the value and returns the new result.
     * If this is a failure, returns the same failure unchanged (short-circuits).
     *
     * @param mapper transformation function
     * @return new result with transformed value, or the same failure
     */
    default <T> Result<T, F> map(Function<? super S, ? extends T> mapper) {
        return switch (this) {
            case Ok<S, F>(var v) -> new Ok<>(mapper.apply(v));
            case Success<S, F>(var v) -> new Success<>(mapper.apply(v));
            case Err<S, F>(var e) -> new Err<>(e);
            case Failure<S, F>(var e) -> new Failure<>(e);
        };
    }

    /**
     * Chain operations that return Results — railway "flatMap" operation.
     *
     * <p>If this is a success, applies {@code mapper} to get the next result.
     * If this is a failure, returns the same failure unchanged (short-circuits).
     *
     * @param mapper function returning the next result
     * @return result from mapper, or the same failure
     */
    @SuppressWarnings("unchecked")
    default <T> Result<T, F> flatMap(Function<? super S, ? extends Result<T, F>> mapper) {
        return switch (this) {
            case Ok<S, F>(var v) -> mapper.apply(v);
            case Success<S, F>(var v) -> mapper.apply(v);
            case Err<S, F> ignored -> (Result<T, F>) this;
            case Failure<S, F> ignored -> (Result<T, F>) this;
        };
    }

    /**
     * Get the success value, or a default if this is a failure.
     *
     * @param defaultValue value to return on failure
     * @return the success value or default
     */
    default S orElse(S defaultValue) {
        return switch (this) {
            case Ok<S, F>(var v) -> v;
            case Success<S, F>(var v) -> v;
            case Err<S, F> ignored -> defaultValue;
            case Failure<S, F> ignored -> defaultValue;
        };
    }

    /**
     * Get the success value, or throw if this is a failure.
     *
     * <p>If the failure value is a {@link Throwable}, it's wrapped in a
     * {@link RuntimeException} and thrown. Otherwise, the failure value
     * is converted to a string and thrown.
     *
     * @return the success value
     * @throws RuntimeException if this is a failure
     */
    @SuppressWarnings("unchecked")
    default S orElseThrow() {
        return switch (this) {
            case Ok<S, F>(var v) -> v;
            case Success<S, F>(var v) -> v;
            case Err<S, F>(var e) -> {
                if (e instanceof Throwable t) throw new RuntimeException(t);
                throw new RuntimeException(String.valueOf(e));
            }
            case Failure<S, F>(var e) -> {
                if (e instanceof Throwable t) throw new RuntimeException(t);
                throw new RuntimeException(String.valueOf(e));
            }
        };
    }

    /**
     * Fold both outcomes into a single value — "eliminator" for the Result type.
     *
     * <p>Handles both success and failure cases, returning a unified type.
     * This is the pattern-matching equivalent of {@code match} in Rust or
     * {@code either} in Haskell.
     *
     * @param onSuccess function to apply on success
     * @param onError function to apply on failure
     * @return result of applying the appropriate function
     */
    @SuppressWarnings("unchecked")
    default <T> T fold(Function<? super S, ? extends T> onSuccess, Function<? super F, ? extends T> onError) {
        return switch (this) {
            case Ok<S, F>(var v) -> onSuccess.apply(v);
            case Success<S, F>(var v) -> onSuccess.apply(v);
            case Err<S, F>(var e) -> onError.apply(e);
            case Failure<S, F>(var e) -> onError.apply(e);
        };
    }
}
