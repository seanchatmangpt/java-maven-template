package org.acme.dogfood.errorhandling;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Dogfood: rendered from templates/java/error-handling/result-railway.tera
 *
 * <p>A sealed Result type implementing railway-oriented programming. This is a standalone copy
 * generated from the template — compare with {@code org.acme.Result} which is the hand-written
 * version.
 *
 * @param <T> the success value type
 * @param <E> the error value type
 */
public sealed interface ResultRailway<T, E>
        permits ResultRailway.Success, ResultRailway.Failure {

    record Success<T, E>(T value) implements ResultRailway<T, E> {}

    record Failure<T, E>(E error) implements ResultRailway<T, E> {}

    static <T, E> ResultRailway<T, E> success(T value) {
        return new Success<>(value);
    }

    static <T, E> ResultRailway<T, E> failure(E error) {
        return new Failure<>(error);
    }

    static <T, E extends Exception> ResultRailway<T, E> of(ThrowingSupplier<T, E> supplier) {
        try {
            return success(supplier.get());
        } catch (Exception e) {
            @SuppressWarnings("unchecked")
            E error = (E) e;
            return failure(error);
        }
    }

    default boolean isSuccess() {
        return this instanceof Success<T, E>;
    }

    default boolean isFailure() {
        return this instanceof Failure<T, E>;
    }

    default <U> ResultRailway<U, E> map(Function<? super T, ? extends U> mapper) {
        return switch (this) {
            case Success<T, E>(var value) -> new Success<>(mapper.apply(value));
            case Failure<T, E>(var error) -> new Failure<>(error);
        };
    }

    default <F> ResultRailway<T, F> mapError(Function<? super E, ? extends F> mapper) {
        return switch (this) {
            case Success<T, E>(var value) -> new Success<>(value);
            case Failure<T, E>(var error) -> new Failure<>(mapper.apply(error));
        };
    }

    default <U> ResultRailway<U, E> flatMap(
            Function<? super T, ? extends ResultRailway<U, E>> mapper) {
        return switch (this) {
            case Success<T, E>(var value) -> mapper.apply(value);
            case Failure<T, E>(var error) -> new Failure<>(error);
        };
    }

    default <U> U fold(
            Function<? super T, ? extends U> onSuccess,
            Function<? super E, ? extends U> onFailure) {
        return switch (this) {
            case Success<T, E>(var value) -> onSuccess.apply(value);
            case Failure<T, E>(var error) -> onFailure.apply(error);
        };
    }

    default T recover(Function<? super E, ? extends T> recovery) {
        return switch (this) {
            case Success<T, E>(var value) -> value;
            case Failure<T, E>(var error) -> recovery.apply(error);
        };
    }

    default ResultRailway<T, E> recoverWith(
            Function<? super E, ? extends ResultRailway<T, E>> recovery) {
        return switch (this) {
            case Success<T, E> s -> s;
            case Failure<T, E>(var error) -> recovery.apply(error);
        };
    }

    default ResultRailway<T, E> peek(Consumer<? super T> consumer) {
        if (this instanceof Success<T, E>(var value)) {
            consumer.accept(value);
        }
        return this;
    }

    default ResultRailway<T, E> peekError(Consumer<? super E> consumer) {
        if (this instanceof Failure<T, E>(var error)) {
            consumer.accept(error);
        }
        return this;
    }

    default T orElseThrow() {
        return switch (this) {
            case Success<T, E>(var value) -> value;
            case Failure<T, E>(var error) -> {
                if (error instanceof Exception e) {
                    throw new RuntimeException(e);
                }
                throw new RuntimeException("Result failed with error: " + error);
            }
        };
    }

    default T orElse(T defaultValue) {
        return switch (this) {
            case Success<T, E>(var value) -> value;
            case Failure<T, E> ignored -> defaultValue;
        };
    }

    default T orElseGet(Supplier<? extends T> supplier) {
        return switch (this) {
            case Success<T, E>(var value) -> value;
            case Failure<T, E> ignored -> supplier.get();
        };
    }

    @FunctionalInterface
    interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
