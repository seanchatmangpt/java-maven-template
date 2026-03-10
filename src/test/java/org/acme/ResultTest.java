package org.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Result} — verifies Javadoc documentation matches actual behavior.
 *
 * <p>Tests the sealed Result interface for railway-oriented programming,
 * matching the Erlang/OTP pattern: {ok, Value} | {error, Reason}.
 */
@DisplayName("Result<T,E> railway-oriented programming")
class ResultTest implements WithAssertions {

    // ─────────────────────────────────────────────────────────────────────────────
    // Factory Methods — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Result.ok(value) — Erlang's {ok, Value}")
    class OkFactory {

        @Test
        @DisplayName("creates successful result carrying a value")
        void createsSuccessWithValue() {
            Result<String, Exception> result = Result.ok("hello");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isError()).isFalse();
            assertThat(result.isFailure()).isFalse();
            assertThat(result).isInstanceOf(Result.Ok.class);
        }

        @Test
        @DisplayName("pattern matches with Ok record")
        void patternMatchesOkRecord() {
            Result<Integer, String> result = Result.ok(42);

            switch (result) {
                case Result.Ok<Integer, String>(var v) -> assertThat(v).isEqualTo(42);
                default -> fail("Expected Ok variant");
            }
        }

        @Test
        @DisplayName("returns value via orElseThrow")
        void returnsValueViaOrElseThrow() {
            Result<String, Exception> result = Result.ok("value");
            assertThat(result.orElseThrow()).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("Result.err(error) — Erlang's {error, Reason}")
    class ErrFactory {

        @Test
        @DisplayName("creates failed result carrying an error")
        void createsFailureWithError() {
            Result<String, String> result = Result.err("something went wrong");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isError()).isTrue();
            assertThat(result.isFailure()).isTrue();
            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("pattern matches with Err record")
        void patternMatchesErrRecord() {
            Result<String, Integer> result = Result.err(404);

            switch (result) {
                case Result.Err<String, Integer>(var e) -> assertThat(e).isEqualTo(404);
                default -> fail("Expected Err variant");
            }
        }

        @Test
        @DisplayName("throws on orElseThrow with string error")
        void throwsOnOrElseThrowWithStringError() {
            Result<String, String> result = Result.err("error message");

            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("error message");
        }

        @Test
        @DisplayName("throws on orElseThrow with Throwable error")
        void throwsOnOrElseThrowWithThrowableError() {
            Exception original = new IllegalStateException("boom");
            Result<String, Exception> result = Result.err(original);

            assertThatThrownBy(result::orElseThrow)
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Result.success(value) — alias for ok()")
    class SuccessFactory {

        @Test
        @DisplayName("is equivalent to ok()")
        void equivalentToOk() {
            Result<String, Exception> r1 = Result.success("value");
            Result<String, Exception> r2 = Result.ok("value");

            assertThat(r1.isSuccess()).isTrue();
            assertThat(r2.isSuccess()).isTrue();
            assertThat(r1).isInstanceOf(Result.Success.class);
            assertThat(r2).isInstanceOf(Result.Ok.class);
        }

        @Test
        @DisplayName("pattern matches with Success record")
        void patternMatchesSuccessRecord() {
            Result<Integer, String> result = Result.success(99);

            switch (result) {
                case Result.Success<Integer, String>(var v) -> assertThat(v).isEqualTo(99);
                default -> fail("Expected Success variant");
            }
        }
    }

    @Nested
    @DisplayName("Result.failure(error) — alias for err()")
    class FailureFactory {

        @Test
        @DisplayName("is equivalent to err()")
        void equivalentToErr() {
            Result<String, String> r1 = Result.failure("error");
            Result<String, String> r2 = Result.err("error");

            assertThat(r1.isFailure()).isTrue();
            assertThat(r2.isFailure()).isTrue();
            assertThat(r1).isInstanceOf(Result.Failure.class);
            assertThat(r2).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("pattern matches with Failure record")
        void patternMatchesFailureRecord() {
            Result<String, String> result = Result.failure("failed");

            switch (result) {
                case Result.Failure<String, String>(var e) -> assertThat(e).isEqualTo("failed");
                default -> fail("Expected Failure variant");
            }
        }
    }

    @Nested
    @DisplayName("Result.of(Supplier) — wraps throwing operations")
    class OfFactory {

        @Test
        @DisplayName("returns Ok when supplier succeeds")
        void returnsOkOnSuccess() {
            Result<Integer, Exception> result = Result.of(() -> 42);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(42);
        }

        @Test
        @DisplayName("returns Err when supplier throws")
        void returnsErrOnException() {
            Result<Integer, Exception> result = Result.of(() -> {
                throw new IllegalStateException("boom");
            });

            assertThat(result.isError()).isTrue();
            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("captures exception as error value")
        void capturesExceptionAsError() {
            Result<String, Exception> result = Result.of(() -> {
                throw new IllegalArgumentException("invalid");
            });

            switch (result) {
                case Result.Err<String, Exception>(var e) -> {
                    assertThat(e).isInstanceOf(IllegalArgumentException.class);
                    assertThat(e.getMessage()).isEqualTo("invalid");
                }
                default -> fail("Expected Err variant");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Railway Operations — documented in Javadoc
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("map(Function) — transforms success track")
    class MapOperation {

        @Test
        @DisplayName("transforms success value")
        void transformsSuccessValue() {
            Result<Integer, String> result = Result.<Integer, String>ok(5)
                    .map(x -> x * 2);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(10);
        }

        @Test
        @DisplayName("short-circuits on failure — returns same error")
        void shortCircuitsOnFailure() {
            Result<Integer, String> original = Result.err("error");
            Result<Integer, String> mapped = original.map(x -> x * 2);

            assertThat(mapped.isError()).isTrue();
            assertThat(mapped).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("preserves Err type on short-circuit")
        void preservesErrType() {
            Result<String, Integer> result = Result.<String, Integer>err(404)
                    .map(String::toUpperCase);

            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("preserves Failure type on short-circuit")
        void preservesFailureType() {
            Result<String, Integer> result = Result.<String, Integer>failure(500)
                    .map(String::toUpperCase);

            assertThat(result).isInstanceOf(Result.Failure.class);
        }

        @Test
        @DisplayName("chains multiple map operations")
        void chainsMultipleMaps() {
            Result<Integer, String> result = Result.<Integer, String>ok(1)
                    .map(x -> x + 1)
                    .map(x -> x * 2)
                    .map(x -> x + 3);

            assertThat(result.orElseThrow()).isEqualTo(7); // ((1+1)*2)+3 = 7
        }
    }

    @Nested
    @DisplayName("flatMap(Function) — chains Result-returning operations")
    class FlatMapOperation {

        @Test
        @DisplayName("chains success to next result")
        void chainsSuccess() {
            Result<Integer, String> result = Result.<String, String>ok("42")
                    .flatMap(s -> {
                        try {
                            return Result.ok(Integer.parseInt(s));
                        } catch (NumberFormatException e) {
                            return Result.err("not a number");
                        }
                    });

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isEqualTo(42);
        }

        @Test
        @DisplayName("short-circuits on failure")
        void shortCircuitsOnFailure() {
            Result<Integer, String> result = Result.<String, String>err("initial error")
                    .flatMap(s -> Result.ok(Integer.parseInt(s)));

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("propagates inner failure")
        void propagatesInnerFailure() {
            Result<Integer, String> result = Result.<String, String>ok("not-a-number")
                    .flatMap(s -> Result.err("parse error"));

            assertThat(result.isError()).isTrue();
            switch (result) {
                case Result.Err<Integer, String>(var e) -> assertThat(e).isEqualTo("parse error");
                default -> fail("Expected Err");
            }
        }
    }

    @Nested
    @DisplayName("orElse(default) — provides fallback")
    class OrElseOperation {

        @Test
        @DisplayName("returns success value")
        void returnsSuccessValue() {
            Result<String, Exception> result = Result.ok("actual");

            assertThat(result.orElse("fallback")).isEqualTo("actual");
        }

        @Test
        @DisplayName("returns default on failure")
        void returnsDefaultOnFailure() {
            Result<String, Exception> result = Result.err(new RuntimeException());

            assertThat(result.orElse("fallback")).isEqualTo("fallback");
        }
    }

    @Nested
    @DisplayName("fold(onSuccess, onError) — eliminates Result type")
    class FoldOperation {

        @Test
        @DisplayName("applies onSuccess for success")
        void appliesSuccessBranch() {
            Result<String, Integer> result = Result.ok("hello");

            int length = result.fold(String::length, error -> -1);

            assertThat(length).isEqualTo(5);
        }

        @Test
        @DisplayName("applies onError for failure")
        void appliesErrorBranch() {
            Result<String, Integer> result = Result.err(404);

            String message = result.fold(
                    value -> "success: " + value,
                    error -> "error code: " + error
            );

            assertThat(message).isEqualTo("error code: 404");
        }

        @Test
        @DisplayName("unifies both branches to same type")
        void unifiesTypes() {
            Result<String, String> success = Result.ok("hello");
            Result<String, String> failure = Result.err("error");

            String s1 = success.fold(String::toUpperCase, e -> "ERROR: " + e);
            String s2 = failure.fold(String::toUpperCase, e -> "ERROR: " + e);

            assertThat(s1).isEqualTo("HELLO");
            assertThat(s2).isEqualTo("ERROR: error");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Railway Chaining — Javadoc example verification
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Railway Chaining — Javadoc examples")
    class RailwayChaining {

        @Test
        @DisplayName("validates and transforms number string")
        void validateAndTransformNumber() {
            // From Javadoc: chained operations without nested if-statements
            Result<String, String> result = Result.of(() -> "  42  ")
                    .map(String::strip)
                    .flatMap(s -> s.matches("\\d+")
                            ? Result.ok(Integer.parseInt(s))
                            : Result.err("not a number"))
                    .map(n -> n * 2)
                    .fold(
                            n -> "result=" + n,
                            e -> "error=" + e
                    );

            assertThat(result).isEqualTo("result=84");
        }

        @Test
        @DisplayName("short-circuits on validation failure")
        void shortCircuitsOnValidationFailure() {
            Result<String, String> result = Result.of(() -> "  abc  ")
                    .map(String::strip)
                    .flatMap(s -> s.matches("\\d+")
                            ? Result.ok(Integer.parseInt(s))
                            : Result.err("not a number"))
                    .map(n -> n * 2) // skipped
                    .fold(
                            n -> "result=" + n,
                            e -> "error=" + e
                    );

            assertThat(result).isEqualTo("error=not a number");
        }

        @Test
        @DisplayName("wraps throwing operation in Result.of")
        void wrapsThrowingOperation() {
            Result<Integer, Exception> result = Result.of(() -> {
                if (true) throw new RuntimeException("intentional");
                return 0;
            });

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("multi-step pipeline with error recovery")
        void multiStepPipelineWithErrorRecovery() {
            record Order(String id, int quantity, double price) {}

            Result<Order, String> pipeline = Result.of(() -> "order-123")
                    .map(id -> new Order(id, 10, 99.99))
                    .flatMap(order -> order.quantity() > 0
                            ? Result.ok(order)
                            : Result.err("invalid quantity"))
                    .map(order -> new Order(order.id(), order.quantity() * 2, order.price()));

            assertThat(pipeline.isSuccess()).isTrue();
            assertThat(pipeline.orElseThrow().quantity()).isEqualTo(20);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Edge Cases
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("null success value is allowed")
        void nullSuccessValue() {
            Result<String, Exception> result = Result.ok(null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElseThrow()).isNull();
        }

        @Test
        @DisplayName("null error value is allowed")
        void nullErrorValue() {
            Result<String, String> result = Result.err(null);

            assertThat(result.isError()).isTrue();
            switch (result) {
                case Result.Err<String, String>(var e) -> assertThat(e).isNull();
                default -> fail("Expected Err");
            }
        }

        @Test
        @DisplayName("Success and Ok variants are distinct but both succeed")
        void successAndOkAreDistinct() {
            Result<String, String> ok = Result.ok("value");
            Result<String, String> success = Result.success("value");

            assertThat(ok).isInstanceOf(Result.Ok.class);
            assertThat(success).isInstanceOf(Result.Success.class);
            assertThat(ok.isSuccess()).isTrue();
            assertThat(success.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Err and Failure variants are distinct but both fail")
        void errAndFailureAreDistinct() {
            Result<String, String> err = Result.err("error");
            Result<String, String> failure = Result.failure("error");

            assertThat(err).isInstanceOf(Result.Err.class);
            assertThat(failure).isInstanceOf(Result.Failure.class);
            assertThat(err.isError()).isTrue();
            assertThat(failure.isError()).isTrue();
        }
    }
}
