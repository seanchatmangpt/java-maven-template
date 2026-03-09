package org.acme.dogfood.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class ResultRailwayTest implements WithAssertions {

    // ── Factory methods ───────────────────────────────────────────────────────

    @Test
    void success_isSuccess() {
        var r = ResultRailway.success("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
        assertThat(r).isInstanceOf(ResultRailway.Success.class);
    }

    @Test
    void failure_isFailure() {
        var r = ResultRailway.failure("error");
        assertThat(r.isFailure()).isTrue();
        assertThat(r.isSuccess()).isFalse();
        assertThat(r).isInstanceOf(ResultRailway.Failure.class);
    }

    @Test
    void of_wrapsSuccessfulSupplier() {
        var r = ResultRailway.of(() -> 42);
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<Integer, ?>) r).value()).isEqualTo(42);
    }

    @Test
    void of_capturesException() {
        var r = ResultRailway.<Integer, RuntimeException>of(() -> {
            throw new IllegalStateException("boom");
        });
        assertThat(r.isFailure()).isTrue();
        assertThat((Throwable) ((ResultRailway.Failure<Integer, ?>) r).error())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    // ── map ───────────────────────────────────────────────────────────────────

    @Test
    void map_transformsSuccessValue() {
        var r = ResultRailway.<String, String>success("hello").map(String::toUpperCase);
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("HELLO");
    }

    @Test
    void map_passesFailureThrough() {
        var r = ResultRailway.<String, String>failure("err").map(String::toUpperCase);
        assertThat(r.isFailure()).isTrue();
        assertThat(((ResultRailway.Failure<?, String>) r).error()).isEqualTo("err");
    }

    // ── mapError ─────────────────────────────────────────────────────────────

    @Test
    void mapError_transformsFailureError() {
        var r = ResultRailway.<String, String>failure("err").mapError(String::length);
        assertThat(r.isFailure()).isTrue();
        assertThat(((ResultRailway.Failure<?, Integer>) r).error()).isEqualTo(3);
    }

    @Test
    void mapError_passesSuccessThrough() {
        var r = ResultRailway.<String, String>success("ok").mapError(String::length);
        assertThat(r.isSuccess()).isTrue();
    }

    // ── flatMap ───────────────────────────────────────────────────────────────

    @Test
    void flatMap_chainsSuccess() {
        var r = ResultRailway.<String, String>success("42")
                .flatMap(s -> ResultRailway.success(Integer.parseInt(s)));
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<Integer, ?>) r).value()).isEqualTo(42);
    }

    @Test
    void flatMap_shortCircuitsOnFailure() {
        var r = ResultRailway.<String, String>failure("no")
                .flatMap(s -> ResultRailway.success(Integer.parseInt(s)));
        assertThat(r.isFailure()).isTrue();
    }

    @Test
    void flatMap_propagatesInnerFailure() {
        var r = ResultRailway.<String, String>success("abc")
                .flatMap(s -> ResultRailway.failure("inner error"));
        assertThat(r.isFailure()).isTrue();
        assertThat(((ResultRailway.Failure<?, String>) r).error()).isEqualTo("inner error");
    }

    // ── fold ─────────────────────────────────────────────────────────────────

    @Test
    void fold_appliesSuccessBranch() {
        var result = ResultRailway.<String, String>success("hello")
                .fold(String::length, e -> -1);
        assertThat(result).isEqualTo(5);
    }

    @Test
    void fold_appliesFailureBranch() {
        var result = ResultRailway.<String, String>failure("err")
                .fold(String::length, e -> -1);
        assertThat(result).isEqualTo(-1);
    }

    // ── recover ───────────────────────────────────────────────────────────────

    @Test
    void recover_returnsValueOnSuccess() {
        var r = ResultRailway.<String, String>success("ok").recover(e -> "fallback");
        assertThat(r).isEqualTo("ok");
    }

    @Test
    void recover_appliesFallbackOnFailure() {
        var r = ResultRailway.<String, String>failure("err").recover(e -> "fallback");
        assertThat(r).isEqualTo("fallback");
    }

    // ── recoverWith ───────────────────────────────────────────────────────────

    @Test
    void recoverWith_returnsSuccessAsIs() {
        var r = ResultRailway.<String, String>success("ok")
                .recoverWith(e -> ResultRailway.success("recovered"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("ok");
    }

    @Test
    void recoverWith_replacesFailureWithNewResult() {
        var r = ResultRailway.<String, String>failure("err")
                .recoverWith(e -> ResultRailway.success("recovered"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(((ResultRailway.Success<String, ?>) r).value()).isEqualTo("recovered");
    }

    // ── peek / peekError ──────────────────────────────────────────────────────

    @Test
    void peek_calledOnSuccess() {
        var called = new AtomicBoolean(false);
        ResultRailway.<String, String>success("ok").peek(v -> called.set(true));
        assertThat(called).isTrue();
    }

    @Test
    void peek_notCalledOnFailure() {
        var called = new AtomicBoolean(false);
        ResultRailway.<String, String>failure("err").peek(v -> called.set(true));
        assertThat(called).isFalse();
    }

    @Test
    void peekError_calledOnFailure() {
        var called = new AtomicBoolean(false);
        ResultRailway.<String, String>failure("err").peekError(e -> called.set(true));
        assertThat(called).isTrue();
    }

    @Test
    void peekError_notCalledOnSuccess() {
        var called = new AtomicBoolean(false);
        ResultRailway.<String, String>success("ok").peekError(e -> called.set(true));
        assertThat(called).isFalse();
    }

    @Test
    void peek_returnsOriginalResult() {
        var r = ResultRailway.<String, String>success("ok");
        assertThat(r.peek(v -> {})).isSameAs(r);
    }

    // ── orElse / orElseGet / orElseThrow ─────────────────────────────────────

    @Test
    void orElse_returnsValueOnSuccess() {
        assertThat(ResultRailway.<String, String>success("ok").orElse("fallback"))
                .isEqualTo("ok");
    }

    @Test
    void orElse_returnsFallbackOnFailure() {
        assertThat(ResultRailway.<String, String>failure("err").orElse("fallback"))
                .isEqualTo("fallback");
    }

    @Test
    void orElseGet_computesFallbackLazily() {
        var r = ResultRailway.<String, String>failure("err").orElseGet(() -> "computed");
        assertThat(r).isEqualTo("computed");
    }

    @Test
    void orElseThrow_returnsValueOnSuccess() {
        assertThat(ResultRailway.<String, String>success("ok").orElseThrow()).isEqualTo("ok");
    }

    @Test
    void orElseThrow_throwsRuntimeExceptionOnExceptionFailure() {
        var r = ResultRailway.<String, Exception>failure(new IllegalStateException("boom"));
        assertThatThrownBy(r::orElseThrow)
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void orElseThrow_throwsForNonExceptionFailure() {
        var r = ResultRailway.<String, String>failure("error message");
        assertThatThrownBy(r::orElseThrow)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("error message");
    }

    // ── Railway chaining ──────────────────────────────────────────────────────

    @Test
    void railwayChain_shortCircuitsOnFirstFailure() {
        var result = ResultRailway.<String, String>success("  42  ")
                .map(String::strip)
                .flatMap(s -> s.matches("\\d+")
                        ? ResultRailway.success(Integer.parseInt(s))
                        : ResultRailway.failure("not a number"))
                .map(n -> n * 2)
                .fold(n -> "result=" + n, e -> "error=" + e);

        assertThat(result).isEqualTo("result=84");
    }

    @Test
    void railwayChain_propagatesFailureToEnd() {
        var result = ResultRailway.<String, String>success("abc")
                .flatMap(s -> ResultRailway.failure("not a number"))
                .map(n -> n + "!")  // should not run
                .fold(v -> "ok", e -> "failed: " + e);

        assertThat(result).isEqualTo("failed: not a number");
    }
}
