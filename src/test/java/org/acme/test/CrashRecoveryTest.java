package org.acme.test;

import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.acme.CrashRecovery;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class CrashRecoveryTest implements WithAssertions {

    @Test
    void successOnFirstAttempt() {
        var result = CrashRecovery.retry(3, () -> 42);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo(42);
    }

    @Test
    void recoversAfterInitialFailures() {
        var attempts = new AtomicInteger(0);
        var result =
                CrashRecovery.retry(
                        5,
                        () -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new RuntimeException("crash #" + attempts.get());
                            }
                            return "recovered";
                        });
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void failureWhenAllAttemptsExhausted() {
        var result =
                CrashRecovery.retry(
                        3,
                        () -> {
                            throw new IllegalStateException("always fails");
                        });
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void singleAttemptAllowed() {
        var result = CrashRecovery.retry(1, () -> "ok");
        assertThat(result.isSuccess()).isTrue();
    }

    @Property
    void retryConvergesOrExhausts(
            @ForAll @IntRange(min = 1, max = 5) int maxAttempts,
            @ForAll @IntRange(min = 0, max = 6) int failUntilAttempt) {
        var counter = new AtomicInteger(0);
        var result =
                CrashRecovery.retry(
                        maxAttempts,
                        () -> {
                            if (counter.incrementAndGet() <= failUntilAttempt) {
                                throw new RuntimeException("injected failure");
                            }
                            return counter.get();
                        });
        if (failUntilAttempt < maxAttempts) {
            assertThat(result.isSuccess()).isTrue();
        } else {
            assertThat(result.isFailure()).isTrue();
        }
    }
}
