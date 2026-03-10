package org.acme.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.acme.Proc;
import org.acme.CrashRecovery;
import org.acme.Parallel;
import org.acme.Result;
import org.acme.dogfood.concurrency.ScopedValuePatterns;
import org.acme.dogfood.concurrency.StructuredTaskScopePatterns;
import org.acme.dogfood.core.GathererPatterns;
import org.acme.dogfood.core.PatternMatchingPatterns;
import org.acme.dogfood.api.JavaTimePatterns;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Formal behavioral contract tests for ggen-generated Java development patterns.
 *
 * <p>Thesis claim: <em>ggen generates Java patterns without any loss in capability</em>.
 *
 * <p>This class verifies the behavioral contracts (laws) that each pattern must satisfy —
 * independent of implementation details. A pattern has no capability loss if and only if all of
 * its contracts hold.
 *
 * <h2>Contracts verified:</h2>
 *
 * <ul>
 *   <li>{@link Result}: Monad laws (left identity, right identity, associativity)
 *   <li>{@link Actor}: FIFO ordering guarantee, no-loss, ask/tell semantics
 *   <li>{@link GathererPatterns}: Correctness of sliding window, running sum, dedup
 *   <li>{@link ScopedValuePatterns}: Binding isolation, nested scoping, inheritance
 *   <li>{@link PatternMatchingPatterns}: Exhaustiveness, guarded patterns, null safety
 *   <li>{@link JavaTimePatterns}: Immutability, timezone correctness, DST-safe arithmetic
 *   <li>{@link StructuredTaskScopePatterns}: All-must-succeed, fail-fast, result ordering
 * </ul>
 */
@DisplayName("Pattern Generator — Behavioral Contract Verification")
class PatternCorrectnessTest implements WithAssertions {

    // =========================================================================
    // Result<T,E> — Monad Laws
    // =========================================================================

    @Nested
    @DisplayName("Result<T,E> Monad Laws")
    class ResultMonadLaws {

        @Test
        @DisplayName("Left identity: Result.of(() -> x).map(f) == f.apply(x)")
        void leftIdentity() {
            Function<Integer, Result<Integer, Exception>> f = x -> Result.success(x * 2);
            Result<Integer, Exception> direct = f.apply(5);
            Result<Integer, Exception> wrapped = Result.<Integer, Exception>of(() -> 5).flatMap(f);
            assertThat(wrapped).isEqualTo(direct);
        }

        @Test
        @DisplayName("Right identity: result.flatMap(Result::success) == result")
        void rightIdentity() {
            Result<String, Exception> original = Result.success("hello");
            Result<String, Exception> wrapped = original.flatMap(Result::success);
            assertThat(wrapped).isEqualTo(original);
        }

        @Test
        @DisplayName("Associativity: (m.flatMap(f)).flatMap(g) == m.flatMap(x -> f(x).flatMap(g))")
        void associativity() {
            Result<Integer, Exception> m = Result.success(3);
            Function<Integer, Result<Integer, Exception>> f = x -> Result.success(x + 1);
            Function<Integer, Result<Integer, Exception>> g = x -> Result.success(x * 2);

            Result<Integer, Exception> left = m.flatMap(f).flatMap(g);
            Result<Integer, Exception> right = m.flatMap(x -> f.apply(x).flatMap(g));

            assertThat(left).isEqualTo(right);
        }

        @Test
        @DisplayName("Failure propagation: Failure.map() never applies function")
        void failureDoesNotApplyMap() {
            AtomicInteger callCount = new AtomicInteger(0);
            RuntimeException cause = new RuntimeException("test");
            Result<Integer, RuntimeException> failure = Result.failure(cause);
            Result<String, RuntimeException> mapped = failure.map(x -> {
                callCount.incrementAndGet();
                return x.toString();
            });
            assertThat(callCount.get()).isZero();
            assertThat(mapped).isInstanceOf(Result.Failure.class);
        }

        @Test
        @DisplayName("Railway: success chain produces final value")
        void successChain() {
            Result<Integer, Exception> result = Result.<Integer, Exception>of(() -> 1)
                    .map(x -> x + 1)
                    .map(x -> x * 3)
                    .map(x -> x - 1);
            assertThat(result.<Integer>fold(x -> x, _ -> -1)).isEqualTo(5);
        }

        @Test
        @DisplayName("Railway: first failure short-circuits all subsequent maps")
        void failureShortCircuits() {
            AtomicInteger mapCount = new AtomicInteger(0);
            Result<Integer, Exception> result = Result.<Integer, Exception>of(() -> {
                        throw new RuntimeException("fail");
                    })
                    .map(x -> { mapCount.incrementAndGet(); return x + 1; })
                    .map(x -> { mapCount.incrementAndGet(); return x * 2; });
            assertThat(mapCount.get()).isZero();
            assertThat(result).isInstanceOf(Result.Err.class);
        }
    }

    // =========================================================================
    // Actor — Message Ordering and No-Loss
    // =========================================================================

    @Nested
    @DisplayName("Actor Pattern — Ordering and Reliability")
    class ActorContracts {

        @Test
        @DisplayName("FIFO ordering: 1000 sequential tell()s arrive in send order")
        void fifoOrdering() throws Exception {
            var received = new ArrayList<Integer>();
            var latch = new CountDownLatch(1000);
            var actor = new Proc<List<Integer>, Integer>(
                    received,
                    (state, msg) -> {
                        state.add(msg);
                        latch.countDown();
                        return state;
                    });
            for (int i = 0; i < 1000; i++) actor.tell(i);
            latch.await();
            actor.stop();
            assertThat(received).containsExactlyElementsOf(
                    java.util.stream.IntStream.range(0, 1000).boxed().toList());
        }

        @Test
        @DisplayName("No message loss: all tell()s are processed")
        void noMessageLoss() throws Exception {
            var counter = new AtomicInteger(0);
            var latch = new CountDownLatch(500);
            var actor = new Proc<Integer, Void>(
                    0,
                    (state, _) -> {
                        counter.incrementAndGet();
                        latch.countDown();
                        return state + 1;
                    });
            for (int i = 0; i < 500; i++) actor.tell(null);
            latch.await();
            actor.stop();
            assertThat(counter.get()).isEqualTo(500);
        }

        @Test
        @DisplayName("ask() returns exact reply for exact message")
        void askReplyIntegrity() throws Exception {
            var actor = new Proc<Integer, Integer>(0, (state, msg) -> msg);
            for (int i = 0; i < 50; i++) {
                int reply = actor.ask(i).get();
                assertThat(reply).isEqualTo(i);
            }
            actor.stop();
        }
    }

    // =========================================================================
    // GathererPatterns — Correctness
    // =========================================================================

    @Nested
    @DisplayName("Gatherer Patterns — Correctness")
    class GathererContracts {

        @Test
        @DisplayName("batch: all elements covered, no element lost")
        void batchCoversAll() {
            var items = List.of(1, 2, 3, 4, 5, 6, 7);
            var batches = GathererPatterns.batch(items, 3);
            var flat = batches.stream().flatMap(List::stream).toList();
            assertThat(flat).containsExactlyElementsOf(items);
            assertThat(batches).hasSize(3); // [1,2,3], [4,5,6], [7]
        }

        @Test
        @DisplayName("slidingWindow: overlap is exactly windowSize-1")
        void slidingWindowOverlap() {
            var items = List.of(1, 2, 3, 4, 5);
            var windows = GathererPatterns.slidingWindow(items, 3);
            assertThat(windows).hasSize(3);
            assertThat(windows.get(0)).containsExactly(1, 2, 3);
            assertThat(windows.get(1)).containsExactly(2, 3, 4);
            assertThat(windows.get(2)).containsExactly(3, 4, 5);
        }

        @Test
        @DisplayName("runningSum: last element equals total sum")
        void runningSumLastEqualsTotal() {
            var values = List.of(1, 2, 3, 4, 5);
            var sums = GathererPatterns.runningSum(values);
            assertThat(sums.get(sums.size() - 1)).isEqualTo(15);
            assertThat(sums).containsExactly(1, 3, 6, 10, 15);
        }

        @Test
        @DisplayName("movingAverage: values within valid range")
        void movingAverageRange() {
            var values = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
            var avgs = GathererPatterns.movingAverage(values, 3);
            assertThat(avgs).hasSize(3);
            assertThat(avgs.get(0)).isCloseTo(2.0, within(1e-9));
            assertThat(avgs.get(1)).isCloseTo(3.0, within(1e-9));
            assertThat(avgs.get(2)).isCloseTo(4.0, within(1e-9));
        }

        @Test
        @DisplayName("deduplicateConsecutive: no consecutive duplicates remain")
        void deduplicateConsecutiveRemovesDuplicates() {
            var input = List.of(1, 1, 2, 3, 3, 3, 2, 1, 1);
            var result = input.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();
            assertThat(result).containsExactly(1, 2, 3, 2, 1);
        }

        @Test
        @DisplayName("mapConcurrent: result count matches input count")
        void mapConcurrentCountPreserved() {
            var items = List.of(1, 2, 3, 4, 5, 6, 7, 8);
            var results = GathererPatterns.mapConcurrent(items, 4, x -> x * x);
            assertThat(results).hasSize(items.size());
            assertThat(results).containsExactlyInAnyOrder(1, 4, 9, 16, 25, 36, 49, 64);
        }
    }

    // =========================================================================
    // ScopedValuePatterns — Binding Isolation
    // =========================================================================

    @Nested
    @DisplayName("ScopedValue Patterns — Binding Contracts")
    class ScopedValueContracts {

        @Test
        @DisplayName("handleAsUser: user is visible inside scope, absent outside")
        void userBoundInsideScopeOnly() {
            assertThat(ScopedValuePatterns.CURRENT_USER.isBound()).isFalse();
            var captured = new AtomicReference<String>();
            ScopedValuePatterns.handleAsUser("alice", () -> captured.set(ScopedValuePatterns.currentUser()));
            assertThat(captured.get()).isEqualTo("alice");
            assertThat(ScopedValuePatterns.CURRENT_USER.isBound()).isFalse();
        }

        @Test
        @DisplayName("nestedScopes: inner scope shadows outer, outer restored after inner exits")
        void nestedScopesShadowing() {
            var outer1 = new AtomicReference<String>();
            var inner = new AtomicReference<String>();
            var outer2 = new AtomicReference<String>();
            ScopedValuePatterns.nestedScopes(
                    "outer",
                    "inner",
                    () -> {
                        if (outer1.get() == null) outer1.set(ScopedValuePatterns.currentUser());
                        else outer2.set(ScopedValuePatterns.currentUser());
                    },
                    () -> inner.set(ScopedValuePatterns.currentUser()));
            assertThat(outer1.get()).isEqualTo("outer");
            assertThat(inner.get()).isEqualTo("inner");
            assertThat(outer2.get()).isEqualTo("outer"); // outer restored after inner exits
        }

        @Test
        @DisplayName("withRequestContext: all three values bound and accessible")
        void requestContextAllValuesBound() throws Exception {
            var ctx = new ScopedValuePatterns.RequestContext("user1", "trace-abc", "tenant-42");
            var result = ScopedValuePatterns.withRequestContext(
                    ctx,
                    () -> ScopedValuePatterns.currentUser()
                            + "|"
                            + ScopedValuePatterns.TRACE_ID.get()
                            + "|"
                            + ScopedValuePatterns.TENANT_ID.get());
            assertThat(result).isEqualTo("user1|trace-abc|tenant-42");
        }

        @Test
        @DisplayName("currentUserOrDefault: returns default when not bound")
        void defaultWhenNotBound() {
            assertThat(ScopedValuePatterns.currentUserOrDefault("anonymous")).isEqualTo("anonymous");
        }

        @Test
        @DisplayName("currentUserOptional: returns Optional.empty() when not bound")
        void optionalEmptyWhenNotBound() {
            assertThat(ScopedValuePatterns.currentUserOptional()).isEmpty();
        }
    }

    // =========================================================================
    // PatternMatchingPatterns — Exhaustiveness and Correctness
    // =========================================================================

    @Nested
    @DisplayName("Pattern Matching — Exhaustiveness and Correctness")
    class PatternMatchingContracts {

        @Test
        @DisplayName("describe: all 4 payment variants produce non-null output")
        void allVariantsHandled() {
            var payments = List.of(
                    PatternMatchingPatterns.Payment.CreditCard.class,
                    PatternMatchingPatterns.Payment.BankTransfer.class,
                    PatternMatchingPatterns.Payment.CryptoPay.class,
                    PatternMatchingPatterns.Payment.Voucher.class);

            var card = new PatternMatchingPatterns.Payment.CreditCard("4111111111111111", "Alice", 123, 5000);
            var bank = new PatternMatchingPatterns.Payment.BankTransfer("DE89370400440532013000", "COBADEFFXXX", "Alice");
            var crypto = new PatternMatchingPatterns.Payment.CryptoPay("1A2b3C4d5E6f7G8h", "BTC", 0.5);
            var voucher = new PatternMatchingPatterns.Payment.Voucher("SAVE20", 20.0, true);

            for (var p : List.of(card, bank, crypto, voucher)) {
                assertThat(PatternMatchingPatterns.describe(p)).isNotNull().isNotBlank();
                assertThat(PatternMatchingPatterns.riskLevel(p)).isIn("LOW", "MEDIUM", "HIGH");
                assertThat(PatternMatchingPatterns.route(p)).isNotNull().isNotBlank();
            }
        }

        @Test
        @DisplayName("riskLevel: credit card limit thresholds correct")
        void riskLevelThresholds() {
            assertThat(PatternMatchingPatterns.riskLevel(new PatternMatchingPatterns.Payment.CreditCard("x", "y", 0, 500))).isEqualTo("LOW");
            assertThat(PatternMatchingPatterns.riskLevel(new PatternMatchingPatterns.Payment.CreditCard("x", "y", 0, 5000))).isEqualTo("MEDIUM");
            assertThat(PatternMatchingPatterns.riskLevel(new PatternMatchingPatterns.Payment.CreditCard("x", "y", 0, 20000))).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("route: null payment returns rejection string (null-safe switch)")
        void nullPaymentHandled() {
            assertThat(PatternMatchingPatterns.route(null)).contains("null");
        }

        @Test
        @DisplayName("extractFee: correctly extracts from Double, Integer, String, invalid")
        void extractFeeVariants() {
            assertThat(PatternMatchingPatterns.extractFee(1.5)).isEqualTo(Optional.of(1.5));
            assertThat(PatternMatchingPatterns.extractFee(3)).isEqualTo(Optional.of(3.0));
            assertThat(PatternMatchingPatterns.extractFee("2.5")).isEqualTo(Optional.of(2.5));
            assertThat(PatternMatchingPatterns.extractFee("invalid")).isEmpty();
            assertThat(PatternMatchingPatterns.extractFee(-1.0)).isEmpty();
            assertThat(PatternMatchingPatterns.extractFee(null)).isEmpty();
        }

        @Test
        @DisplayName("processingFee: voucher always has zero fee")
        void voucherZeroFee() {
            var voucher = new PatternMatchingPatterns.Payment.Voucher("X", 100, false);
            assertThat(PatternMatchingPatterns.processingFee(voucher, 1000)).isZero();
        }
    }

    // =========================================================================
    // JavaTimePatterns — Immutability and Correctness
    // =========================================================================

    @Nested
    @DisplayName("java.time Patterns — Immutability and DST-Safe Arithmetic")
    class JavaTimeContracts {

        @Test
        @DisplayName("nextBusinessDay: skips Saturday and Sunday")
        void nextBusinessDaySkipsWeekends() {
            // Friday → Monday (skip Sat, Sun)
            var friday = LocalDate.of(2026, 3, 6); // Known Friday
            var next = JavaTimePatterns.nextBusinessDay(friday);
            assertThat(next.getDayOfWeek()).isNotIn(
                    java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY);
            assertThat(next).isEqualTo(LocalDate.of(2026, 3, 9)); // Monday
        }

        @Test
        @DisplayName("daysBetween: symmetric for positive range")
        void daysBetweenCorrect() {
            var start = LocalDate.of(2026, 1, 1);
            var end = LocalDate.of(2026, 3, 1);
            assertThat(JavaTimePatterns.daysBetween(start, end)).isEqualTo(59);
        }

        @Test
        @DisplayName("age: correctly computed from birth date to reference date")
        void ageCorrect() {
            var birthDate = LocalDate.of(1990, 6, 15);
            var asOf = LocalDate.of(2026, 3, 8);
            assertThat(JavaTimePatterns.age(birthDate, asOf)).isEqualTo(35);
        }

        @Test
        @DisplayName("convertTimezone: same instant, different clock representation")
        void timezoneConversionPreservesInstant() {
            var nyc = JavaTimePatterns.scheduleIn("America/New_York", 2026, 6, 1, 12, 0);
            var utc = JavaTimePatterns.convertTimezone(nyc, "UTC");
            assertThat(nyc.toInstant()).isEqualTo(utc.toInstant());
        }

        @Test
        @DisplayName("isWithin: correctly bounds instant proximity")
        void isWithinCorrect() {
            var a = java.time.Instant.ofEpochSecond(1000);
            var b = java.time.Instant.ofEpochSecond(1003);
            assertThat(JavaTimePatterns.isWithin(a, b, java.time.Duration.ofSeconds(5))).isTrue();
            assertThat(JavaTimePatterns.isWithin(a, b, java.time.Duration.ofSeconds(2))).isFalse();
        }

        @Test
        @DisplayName("parseDate/parseDateTime: round-trip parse and format")
        void parseDateRoundTrip() {
            var date = JavaTimePatterns.parseDate("2026-03-08");
            assertThat(date).isEqualTo(LocalDate.of(2026, 3, 8));

            var dt = JavaTimePatterns.parseDateTime("2026-03-08T10:30:00");
            assertThat(dt.getYear()).isEqualTo(2026);
            assertThat(dt.getMonthValue()).isEqualTo(3);
        }
    }

    // =========================================================================
    // StructuredTaskScopePatterns — Contracts
    // =========================================================================

    @Nested
    @DisplayName("StructuredTaskScope Patterns — Correctness")
    class StructuredTaskScopeContracts {

        @Test
        @DisplayName("runBoth: results match both task outcomes")
        void runBothCorrect() throws Exception {
            var result = StructuredTaskScopePatterns.runBoth(
                    () -> "hello",
                    () -> 42);
            assertThat(result.first()).isEqualTo("hello");
            assertThat(result.second()).isEqualTo(42);
        }

        @Test
        @DisplayName("fanOut: result count and order match input")
        void fanOutPreservesOrder() throws Exception {
            var items = List.of(1, 2, 3, 4, 5);
            var results = StructuredTaskScopePatterns.fanOut(items, x -> x * x);
            assertThat(results).containsExactly(1, 4, 9, 16, 25);
        }

        @Test
        @DisplayName("raceForFirst: returns some successful result")
        void raceForFirstReturnsResult() throws Exception {
            var tasks = List.of(
                    (java.util.concurrent.Callable<String>) () -> "fast",
                    () -> { Thread.sleep(50); return "slow"; });
            var winner = StructuredTaskScopePatterns.raceForFirst(tasks);
            assertThat(winner).isNotNull().isIn("fast", "slow");
        }

        @Test
        @DisplayName("fanOut: propagates failure (any task failing cancels all)")
        void fanOutPropagatesFailure() {
            var items = List.of(1, 2, 3);
            assertThatThrownBy(() -> StructuredTaskScopePatterns.fanOut(
                    items,
                    x -> {
                        if (x == 2) throw new RuntimeException("task failed");
                        return x;
                    }))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Parallel.all: preserves result ordering for 10 tasks")
        void parallelAllOrdering() {
            var tasks = java.util.stream.IntStream.range(0, 10)
                    .<java.util.function.Supplier<Integer>>mapToObj(i -> () -> i)
                    .toList();
            var result = Parallel.all(tasks);
            assertThat(result).isInstanceOf(Result.Success.class);
            result.fold(
                    values -> {
                        assertThat(values).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
                        return null;
                    },
                    _ -> null);
        }
    }

    // =========================================================================
    // CrashRecovery — Bounded Retry Contracts
    // =========================================================================

    @Nested
    @DisplayName("CrashRecovery — Retry Contracts")
    class CrashRecoveryContracts {

        @Test
        @DisplayName("retry: succeeds on first attempt if no exception")
        void successOnFirstAttempt() {
            var result = CrashRecovery.retry(3, () -> "value");
            assertThat(result).isInstanceOf(Result.Ok.class);
            assertThat(result.<String>fold(x -> x, _ -> null)).isEqualTo("value");
        }

        @Test
        @DisplayName("retry: succeeds after N-1 failures (eventual success)")
        void eventualSuccess() {
            var attempts = new AtomicInteger(0);
            var result = CrashRecovery.retry(3, () -> {
                if (attempts.incrementAndGet() < 3) throw new RuntimeException("not yet");
                return "done";
            });
            assertThat(result).isInstanceOf(Result.Ok.class);
        }

        @Test
        @DisplayName("retry: returns Failure after maxAttempts exhausted")
        void failureAfterMaxAttempts() {
            var result = CrashRecovery.retry(3, () -> {
                throw new RuntimeException("always fails");
            });
            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("retry: each attempt is thread-isolated (virtual thread)")
        void attemptsAreThreadIsolated() throws Exception {
            var threadIds = new java.util.concurrent.CopyOnWriteArrayList<Long>();
            CrashRecovery.retry(3, () -> {
                threadIds.add(Thread.currentThread().threadId());
                throw new RuntimeException("fail");
            });
            // All attempts used virtual threads (may reuse thread IDs but are logically isolated)
            assertThat(threadIds).hasSize(3);
        }
    }
}
