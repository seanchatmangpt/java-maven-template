package io.github.seanchatmangpt.jotp.doctest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.github.seanchatmangpt.jotp.ReactiveChannel;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Doctest: {@link ReactiveChannel} — Reactive Messaging Patterns.
 *
 * <p>Documents the {@link ReactiveChannel} API through executable examples. A
 * {@code ReactiveChannel<T>} bridges jOTP actor-style messaging with Reactive Streams
 * ({@link java.util.concurrent.Flow}): it is a {@link Flow.Publisher} that also accepts direct
 * submissions, supports pipeline combinators, and handles backpressure automatically.
 *
 * <p>HTML output: {@code target/site/doctester/ReactiveChannelDocIT.html}.
 */
@ExtendWith(DocTestExtension.class)
@Timeout(10)
class ReactiveChannelDocIT implements WithAssertions {

    // ── Simple subscriber ────────────────────────────────────────────────────────

    @DocSection("Point-to-Point Channel")
    @DocNote(
            "ReactiveChannel.create() returns a Flow.Publisher<T>. "
                    + "Subscribe with a Flow.Subscriber — or use a helper like "
                    + "java.util.concurrent.SubmissionPublisher's built-in consumers.")
    @DocCode(
            """
            ReactiveChannel<String> ch = ReactiveChannel.create("greetings");
            List<String> received = new CopyOnWriteArrayList<>();
            ch.subscribe(new CollectingSubscriber<>(received));
            ch.submit("hello");
            ch.submit("world");
            ch.close();
            await().atMost(2, SECONDS).until(() -> received.size() == 2);
            """)
    @Test
    void submit_deliversToSubscriber() throws Exception {
        ReactiveChannel<String> ch = ReactiveChannel.create("greetings");
        List<String> received = new CopyOnWriteArrayList<>();

        ch.subscribe(collecting(received));
        ch.submit("hello");
        ch.submit("world");
        ch.close();

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> received.size() == 2);
        assertThat(received).containsExactly("hello", "world");
    }

    // ── Broadcast / fan-out ──────────────────────────────────────────────────────

    @DocSection("Broadcast — Fan-out")
    @DocNote(
            "Multiple subscribers on the same channel each receive every item published — "
                    + "broadcast / fan-out semantics. Each subscriber gets its own buffered "
                    + "delivery queue.")
    @Test
    void broadcast_allSubscribersReceiveEveryItem() throws Exception {
        ReactiveChannel<Integer> ch = ReactiveChannel.create("numbers");
        List<Integer> a = new CopyOnWriteArrayList<>();
        List<Integer> b = new CopyOnWriteArrayList<>();

        ch.subscribe(collecting(a));
        ch.subscribe(collecting(b));
        ch.submit(1);
        ch.submit(2);
        ch.submit(3);
        ch.close();

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> a.size() == 3 && b.size() == 3);
        assertThat(a).containsExactly(1, 2, 3);
        assertThat(b).containsExactly(1, 2, 3);
    }

    // ── Pipeline: map ────────────────────────────────────────────────────────────

    @DocSection("Pipeline — map / filter")
    @DocNote(
            "ReactiveChannel.map(fn) returns a new channel whose items are the result of "
                    + "applying fn to each upstream item. Downstream channels are closed "
                    + "automatically when the upstream closes.")
    @DocCode(
            """
            ReactiveChannel<Integer> numbers = ReactiveChannel.create("numbers");
            ReactiveChannel<String> labels   = numbers.map(n -> "item-" + n);
            List<String> out = new CopyOnWriteArrayList<>();
            labels.subscribe(collecting(out));
            numbers.submit(1);
            numbers.submit(2);
            numbers.close();
            await().atMost(2, SECONDS).until(() -> out.size() == 2);
            assertThat(out).containsExactly("item-1", "item-2");
            """)
    @Test
    void map_transformsItems() throws Exception {
        ReactiveChannel<Integer> numbers = ReactiveChannel.create("numbers");
        ReactiveChannel<String> labels = numbers.map(n -> "item-" + n);

        List<String> out = new CopyOnWriteArrayList<>();
        labels.subscribe(collecting(out));

        numbers.submit(1);
        numbers.submit(2);
        numbers.close();

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> out.size() == 2);
        assertThat(out).containsExactly("item-1", "item-2");
    }

    @DocNote(
            "ReactiveChannel.filter(predicate) returns a new channel that only forwards "
                    + "items matching the predicate.")
    @DocCode(
            """
            ReactiveChannel<Integer> all  = ReactiveChannel.create("all");
            ReactiveChannel<Integer> evens = all.filter(n -> n % 2 == 0);
            List<Integer> out = new CopyOnWriteArrayList<>();
            evens.subscribe(collecting(out));
            for (int i = 1; i <= 6; i++) all.submit(i);
            all.close();
            await().atMost(2, SECONDS).until(() -> out.size() == 3);
            assertThat(out).containsExactly(2, 4, 6);
            """)
    @Test
    void filter_removesNonMatchingItems() throws Exception {
        ReactiveChannel<Integer> all = ReactiveChannel.create("all");
        ReactiveChannel<Integer> evens = all.filter(n -> n % 2 == 0);

        List<Integer> out = new CopyOnWriteArrayList<>();
        evens.subscribe(collecting(out));

        for (int i = 1; i <= 6; i++) all.submit(i);
        all.close();

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> out.size() == 3);
        assertThat(out).containsExactly(2, 4, 6);
    }

    // ── Chained pipeline ─────────────────────────────────────────────────────────

    @DocNote(
            "map and filter can be chained to build multi-stage pipelines — "
                    + "composable reactive processing without a separate framework.")
    @Test
    void chained_filter_then_map_pipeline() throws Exception {
        ReactiveChannel<Integer> source = ReactiveChannel.create("source");
        ReactiveChannel<String> result =
                source.filter(n -> n > 0).map(n -> "+" + n);

        List<String> out = new CopyOnWriteArrayList<>();
        result.subscribe(collecting(out));

        source.submit(-1);
        source.submit(0);
        source.submit(3);
        source.submit(7);
        source.close();

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> out.size() == 2);
        assertThat(out).containsExactly("+3", "+7");
    }

    // ── Backpressure / offer ─────────────────────────────────────────────────────

    @DocSection("Backpressure — offer vs submit")
    @DocNote(
            "submit(item) blocks if any subscriber's buffer is full (reactive backpressure). "
                    + "offer(item) is non-blocking: it returns a negative value if the item was "
                    + "dropped for at least one subscriber.")
    @DocWarning(
            "For latency-sensitive paths prefer offer(). For reliable delivery prefer "
                    + "submit() with a bounded channel and an appropriate buffer size.")
    @Test
    void offer_isNonBlocking() {
        ReactiveChannel<Integer> ch = ReactiveChannel.bounded("bounded", 4);
        // No subscriber — all offers go into the buffer
        for (int i = 0; i < 4; i++) {
            ch.offer(i); // fills the buffer
        }
        // Channel is still usable
        assertThat(ch.isClosed()).isFalse();
        ch.close();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    @DocSection("Lifecycle — close and closeExceptionally")
    @DocNote(
            "close() completes all subscribers normally (onComplete). "
                    + "closeExceptionally(cause) signals all subscribers with onError.")
    @Test
    void close_notifiesSubscribersWithOnComplete() throws Exception {
        ReactiveChannel<String> ch = ReactiveChannel.create("lifecycle");
        CountDownLatch completed = new CountDownLatch(1);

        ch.subscribe(
                new Flow.Subscriber<>() {
                    private Flow.Subscription sub;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        sub = s;
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String item) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onComplete() {
                        completed.countDown();
                    }
                });

        ch.close();
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ch.isClosed()).isTrue();
    }

    @Test
    void closeExceptionally_notifiesSubscribersWithOnError() throws Exception {
        ReactiveChannel<String> ch = ReactiveChannel.create("error-lifecycle");
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ch.subscribe(
                new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String item) {}

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                        latch.countDown();
                    }

                    @Override
                    public void onComplete() {}
                });

        ch.closeExceptionally(new RuntimeException("upstream failure"));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).hasMessage("upstream failure");
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────────

    @DocSection("Diagnostics")
    @DocNote("subscriberCount(), hasSubscribers(), name(), and isClosed() provide channel state.")
    @Test
    void diagnostics_reflectState() {
        ReactiveChannel<String> ch = ReactiveChannel.create("diag");
        assertThat(ch.name()).isEqualTo("diag");
        assertThat(ch.subscriberCount()).isZero();
        assertThat(ch.hasSubscribers()).isFalse();
        assertThat(ch.isClosed()).isFalse();

        ch.subscribe(collecting(new CopyOnWriteArrayList<>()));
        assertThat(ch.subscriberCount()).isEqualTo(1);
        assertThat(ch.hasSubscribers()).isTrue();

        ch.close();
        assertThat(ch.isClosed()).isTrue();
    }

    // ── Helper ───────────────────────────────────────────────────────────────────

    /** Convenience: create a simple collecting subscriber for test assertions. */
    private static <T> Flow.Subscriber<T> collecting(List<T> target) {
        return new Flow.Subscriber<>() {
            private Flow.Subscription sub;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                sub = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(T item) {
                target.add(item);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        };
    }
}
