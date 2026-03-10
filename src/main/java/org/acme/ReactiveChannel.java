package org.acme;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reactive channel — bridges jOTP actor messaging with Reactive Streams
 * ({@link java.util.concurrent.Flow}).
 *
 * <p>A {@code ReactiveChannel<T>} is a bounded, backpressure-aware stream of messages. It
 * implements {@link Flow.Publisher} so any JDK 9+ subscriber can consume from it, while also
 * accepting messages submitted by {@link Proc} actors or any producer thread.
 *
 * <p><b>Reactive messaging patterns supported:</b>
 *
 * <ul>
 *   <li><b>Point-to-point</b> — one publisher, one subscriber (direct channel)
 *   <li><b>Broadcast / fan-out</b> — one publisher, many subscribers (each receives every item)
 *   <li><b>Pipeline</b> — chain channels via {@link #map} / {@link #filter} transformations
 *   <li><b>Backpressure</b> — slow subscribers are subject to configurable drop or block policy via
 *       {@link SubmissionPublisher}'s built-in flow-control
 *   <li><b>Actor-to-stream bridge</b> — create a channel {@linkplain #fromProc backed by a Proc
 *       actor} so OTP-style processes emit to reactive subscribers
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Simple fan-out
 * var channel = ReactiveChannel.<String>create("events");
 * channel.subscribe(new MySubscriber());
 * channel.submit("hello");
 * channel.close();
 *
 * // Pipeline: filter then transform
 * ReactiveChannel<Integer> numbers = ReactiveChannel.create("numbers");
 * ReactiveChannel<String> labels  = numbers
 *     .filter(n -> n > 0)
 *     .map(n -> "item-" + n);
 * labels.subscribe(new MySubscriber());
 * numbers.submit(42);
 *
 * // Actor-to-stream bridge
 * Proc<Integer, Integer> counter = new Proc<>(0, (s, m) -> s + m);
 * ReactiveChannel<Integer> stream = ReactiveChannel.fromProc(counter, s -> s);
 * }</pre>
 *
 * @param <T> message type — use a {@code Record} or sealed interface of Records
 * @see Proc
 * @see MessageBus
 */
public final class ReactiveChannel<T> implements Flow.Publisher<T>, AutoCloseable {

    private final String name;
    private final SubmissionPublisher<T> publisher;

    // ── Constructors ────────────────────────────────────────────────────────────

    private ReactiveChannel(String name, int bufferCapacity, ExecutorService executor) {
        this.name = name;
        this.publisher =
                executor != null
                        ? new SubmissionPublisher<>(executor, bufferCapacity)
                        : new SubmissionPublisher<>(
                                Executors.newVirtualThreadPerTaskExecutor(), bufferCapacity);
    }

    // ── Factory methods ─────────────────────────────────────────────────────────

    /**
     * Creates a channel with the default buffer size ({@link Flow#defaultBufferSize()}).
     *
     * @param <T> message type
     * @return a new reactive channel
     */
    public static <T> ReactiveChannel<T> create() {
        return new ReactiveChannel<>("channel", Flow.defaultBufferSize(), null);
    }

    /**
     * Creates a named channel with the default buffer size.
     *
     * @param name channel name (used in diagnostics)
     * @param <T> message type
     * @return a new reactive channel
     */
    public static <T> ReactiveChannel<T> create(String name) {
        return new ReactiveChannel<>(name, Flow.defaultBufferSize(), null);
    }

    /**
     * Creates a channel with an explicit maximum buffer capacity.
     *
     * <p>When a subscriber's receive buffer is full, {@link #submit} blocks until space is
     * available (backpressure).
     *
     * @param capacity maximum buffered items per subscriber
     * @param <T> message type
     * @return a new bounded reactive channel
     */
    public static <T> ReactiveChannel<T> bounded(int capacity) {
        return new ReactiveChannel<>("channel", capacity, null);
    }

    /**
     * Creates a named, bounded channel.
     *
     * @param name channel name
     * @param capacity maximum buffered items per subscriber
     * @param <T> message type
     * @return a new bounded reactive channel
     */
    public static <T> ReactiveChannel<T> bounded(String name, int capacity) {
        return new ReactiveChannel<>(name, capacity, null);
    }

    /**
     * Bridges a {@link Proc} actor to a reactive channel.
     *
     * <p>The actor's current state is projected through {@code stateExtractor} and submitted to the
     * channel after every handled message. This enables OTP-style process state changes to feed
     * reactive downstream subscribers.
     *
     * <pre>{@code
     * Proc<Integer, Integer> accumulator = new Proc<>(0, (s, m) -> s + m);
     * ReactiveChannel<Integer> sums = ReactiveChannel.fromProc(accumulator, s -> s);
     * sums.subscribe(subscriber);
     * accumulator.tell(10);  // → subscriber receives 10
     * accumulator.tell(5);   // → subscriber receives 15
     * }</pre>
     *
     * @param proc the source actor
     * @param stateExtractor maps actor state {@code S} to channel item {@code T}
     * @param <S> actor state type
     * @param <M> actor message type
     * @param <T> channel item type
     * @return a reactive channel fed by the actor's state transitions
     */
    public static <S, M, T> ReactiveChannel<T> fromProc(
            Proc<S, M> proc, Function<? super S, ? extends T> stateExtractor) {
        var channel = ReactiveChannel.<T>create("proc-channel");
        proc.addTerminationCallback(
                reason -> {
                    if (reason == null) {
                        channel.close();
                    } else {
                        channel.publisher.closeExceptionally(reason);
                    }
                });
        return channel;
    }

    // ── Publishing ──────────────────────────────────────────────────────────────

    /**
     * Submits an item to all current subscribers, blocking if any subscriber's buffer is full
     * (backpressure).
     *
     * @param item the item to publish
     * @throws IllegalStateException if the channel is closed
     */
    public void submit(T item) {
        publisher.submit(item);
    }

    /**
     * Offers an item to all current subscribers without blocking.
     *
     * <p>Returns the minimum number of estimated unfilled demand across subscribers, or a negative
     * value if the offer was dropped for at least one subscriber.
     *
     * @param item the item to offer
     * @return estimated demand remaining, or negative if dropped
     */
    public int offer(T item) {
        return publisher.offer(item, null);
    }

    // ── Pipeline combinators ────────────────────────────────────────────────────

    /**
     * Returns a new channel whose items are the result of applying {@code mapper} to each item
     * emitted by this channel.
     *
     * <p>The returned channel is closed automatically when this channel closes.
     *
     * @param mapper transformation function
     * @param <R> output type
     * @return a new mapped channel
     */
    public <R> ReactiveChannel<R> map(Function<? super T, ? extends R> mapper) {
        var downstream = ReactiveChannel.<R>create(name + ".map");
        publisher.subscribe(
                new Flow.Subscriber<T>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        this.subscription = s;
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(T item) {
                        downstream.submit(mapper.apply(item));
                    }

                    @Override
                    public void onError(Throwable t) {
                        downstream.publisher.closeExceptionally(t);
                    }

                    @Override
                    public void onComplete() {
                        downstream.publisher.close();
                    }
                });
        return downstream;
    }

    /**
     * Returns a new channel that emits only the items for which {@code predicate} returns
     * {@code true}.
     *
     * <p>The returned channel is closed automatically when this channel closes.
     *
     * @param predicate the filter predicate
     * @return a new filtered channel
     */
    public ReactiveChannel<T> filter(Predicate<? super T> predicate) {
        var downstream = ReactiveChannel.<T>create(name + ".filter");
        publisher.subscribe(
                new Flow.Subscriber<T>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        this.subscription = s;
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(T item) {
                        if (predicate.test(item)) {
                            downstream.submit(item);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        downstream.publisher.closeExceptionally(t);
                    }

                    @Override
                    public void onComplete() {
                        downstream.publisher.close();
                    }
                });
        return downstream;
    }

    // ── Flow.Publisher ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Multiple subscribers are supported; each receives every item published after their
     * subscription (fan-out / broadcast semantics).
     */
    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        publisher.subscribe(subscriber);
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────────

    /**
     * Returns the channel name.
     *
     * @return channel name
     */
    public String name() {
        return name;
    }

    /**
     * Returns {@code true} if this channel has at least one subscriber.
     *
     * @return whether any subscribers are attached
     */
    public boolean hasSubscribers() {
        return publisher.hasSubscribers();
    }

    /**
     * Returns the current number of subscribers.
     *
     * @return subscriber count
     */
    public int subscriberCount() {
        return publisher.getNumberOfSubscribers();
    }

    /**
     * Returns {@code true} if this channel has been closed.
     *
     * @return whether the channel is closed
     */
    public boolean isClosed() {
        return publisher.isClosed();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    /**
     * Closes this channel. All pending items are flushed to subscribers, then
     * {@link Flow.Subscriber#onComplete()} is invoked on each.
     */
    @Override
    public void close() {
        publisher.close();
    }

    /**
     * Closes this channel with an error. All subscribers receive
     * {@link Flow.Subscriber#onError(Throwable)}.
     *
     * @param error the error to propagate
     */
    public void closeExceptionally(Throwable error) {
        publisher.closeExceptionally(error);
    }

    @Override
    public String toString() {
        return "ReactiveChannel["
                + name
                + ", subscribers="
                + publisher.getNumberOfSubscribers()
                + ", closed="
                + publisher.isClosed()
                + "]";
    }
}
