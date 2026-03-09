package org.acme;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Result} — the railway-oriented programming type.
 *
 * <p>Covers: Success/Failure construction, map, flatMap, fold, recover,
 * orElseThrow, orElse, peek, peekError, of, isSuccess, isFailure.
 */
class ResultTest implements WithAssertions {

    // ── Factory methods ───────────────────────────────────────────────────────

    @Test
    void success_createsSuccessVariant() {
        Result<Integer, String> r = Result.success(42);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
    }

    @Test
    void failure_createsFailureVariant() {
        Result<Integer, String> r = Result.failure("oops");
        assertThat(r.isFailure()).isTrue();
        assertThat(r.isSuccess()).isFalse();
    }

    // ── Result.of ────────────────────────────────────────────────────────────

    @Test
    void of_withNonThrowingSupplier_returnsSuccess() {
        var r = Result.of(() -> 99);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.orElse(-1)).isEqualTo(99);
    }

    @Test
    void of_withThrowingSupplier_returnsFailure() {
        var r = Result.<Integer, Exception>of(() -> {
            throw new RuntimeException("boom");
        });
        assertThat(r.isFailure()).isTrue();
    }

    // ── map ──────────────────────────────────────────────────────────────────

    @Test
    void map_onSuccess_transformsValue() {
        var r = Result.<Integer, String>success(5).map(v -> v * 2);
        assertThat(r.orElse(-1)).isEqualTo(10);
    }

    @Test
    void map_onFailure_propagatesError() {
        var r = Result.<Integer, String>failure("err").map(v -> v * 2);
        assertThat(r.isFailure()).isTrue();
    }

    // ── flatMap ───────────────────────────────────────────────────────────────

    @Test
    void flatMap_onSuccess_chainsOperation() {
        var r = Result.<Integer, String>success(3)
                .flatMap(v -> v > 0 ? Result.success(v + 10) : Result.failure("negative"));
        assertThat(r.orElse(-1)).isEqualTo(13);
    }

    @Test
    void flatMap_onSuccess_canReturnFailure() {
        var r = Result.<Integer, String>success(-1)
                .flatMap(v -> v > 0 ? Result.success(v) : Result.failure("must be positive"));
        assertThat(r.isFailure()).isTrue();
    }

    @Test
    void flatMap_onFailure_doesNotExecute() {
        var r = Result.<Integer, String>failure("initial")
                .flatMap(v -> Result.success(v + 1));
        assertThat(r.isFailure()).isTrue();
    }

    // ── fold ──────────────────────────────────────────────────────────────────

    @Test
    void fold_onSuccess_appliesSuccessHandler() {
        var result = Result.<Integer, String>success(7)
                .fold(v -> "got " + v, e -> "error: " + e);
        assertThat(result).isEqualTo("got 7");
    }

    @Test
    void fold_onFailure_appliesFailureHandler() {
        var result = Result.<Integer, String>failure("bad")
                .fold(v -> "got " + v, e -> "error: " + e);
        assertThat(result).isEqualTo("error: bad");
    }

    // ── recover ──────────────────────────────────────────────────────────────

    @Test
    void recover_onSuccess_returnsValue() {
        int val = Result.<Integer, String>success(10).recover(e -> -1);
        assertThat(val).isEqualTo(10);
    }

    @Test
    void recover_onFailure_returnsFallback() {
        int val = Result.<Integer, String>failure("error").recover(e -> 99);
        assertThat(val).isEqualTo(99);
    }

    // ── recoverWith ───────────────────────────────────────────────────────────

    @Test
    void recoverWith_onFailure_returnsAlternativeResult() {
        var r = Result.<Integer, String>failure("bad")
                .recoverWith(e -> Result.success(0));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.orElse(-1)).isZero();
    }

    // ── orElseThrow ───────────────────────────────────────────────────────────

    @Test
    void orElseThrow_onSuccess_returnsValue() {
        int val = Result.<Integer, Exception>success(5).orElseThrow();
        assertThat(val).isEqualTo(5);
    }

    @Test
    void orElseThrow_onFailure_withException_wrapsAndThrows() {
        var r = Result.<Integer, Exception>failure(new RuntimeException("inner"));
        assertThatThrownBy(r::orElseThrow)
                .isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessage("inner");
    }

    @Test
    void orElseThrow_onFailure_withNonException_throwsRuntimeException() {
        var r = Result.<Integer, String>failure("not an exception");
        assertThatThrownBy(r::orElseThrow)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not an exception");
    }

    // ── orElse / orElseGet ────────────────────────────────────────────────────

    @Test
    void orElse_onSuccess_returnsValue() {
        assertThat(Result.<Integer, String>success(7).orElse(-1)).isEqualTo(7);
    }

    @Test
    void orElse_onFailure_returnsDefault() {
        assertThat(Result.<Integer, String>failure("e").orElse(-1)).isEqualTo(-1);
    }

    @Test
    void orElseGet_onFailure_computesDefault() {
        assertThat(Result.<Integer, String>failure("e").orElseGet(() -> 42)).isEqualTo(42);
    }

    // ── peek / peekError ─────────────────────────────────────────────────────

    @Test
    void peek_onSuccess_executesSideEffect() {
        var seen = new int[]{0};
        Result.<Integer, String>success(100).peek(v -> seen[0] = v);
        assertThat(seen[0]).isEqualTo(100);
    }

    @Test
    void peek_onFailure_doesNotExecute() {
        var seen = new int[]{0};
        Result.<Integer, String>failure("err").peek(v -> seen[0] = v);
        assertThat(seen[0]).isZero();
    }

    @Test
    void peekError_onFailure_executesSideEffect() {
        var seen = new String[]{null};
        Result.<Integer, String>failure("oops").peekError(e -> seen[0] = e);
        assertThat(seen[0]).isEqualTo("oops");
    }

    @Test
    void peekError_onSuccess_doesNotExecute() {
        var seen = new String[]{null};
        Result.<Integer, String>success(1).peekError(e -> seen[0] = e);
        assertThat(seen[0]).isNull();
    }

    // ── mapError ─────────────────────────────────────────────────────────────

    @Test
    void mapError_onFailure_transformsError() {
        var r = Result.<Integer, String>failure("raw").mapError(String::toUpperCase);
        assertThat(r.isFailure()).isTrue();
        // fold to extract the mapped error
        var err = r.fold(v -> "", e -> e);
        assertThat(err).isEqualTo("RAW");
    }

    @Test
    void mapError_onSuccess_doesNotChange() {
        var r = Result.<Integer, String>success(1).mapError(String::toUpperCase);
        assertThat(r.isSuccess()).isTrue();
    }

    // ── Chaining ─────────────────────────────────────────────────────────────

    @Test
    void chain_mapThenFlatMap_onSuccess_composesCorrectly() {
        var r = Result.<Integer, String>success(1)
                .map(v -> v + 1)
                .flatMap(v -> Result.success(v * 10))
                .map(v -> v - 5);
        assertThat(r.orElse(-1)).isEqualTo(15);
    }
}
