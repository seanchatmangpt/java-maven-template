package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.function.Predicate;

/**
 * Message Filter — filters/drops messages matching criteria, forwards others to a destination.
 *
 * <p>Vernon: "A Message Filter passes on only messages that match specific criteria and
 * discards all others."
 *
 * <p>Joe Armstrong influence: "Processes share nothing, communicate only by message passing."
 * Filtering is pure predicate-based logic applied to each message before forwarding.
 *
 * <p><strong>Filtering Pattern:</strong>
 * <ol>
 *   <li>Caller sends message to filter
 *   <li>Filter applies the predicate to the message
 *   <li>If predicate returns true, message is forwarded to the next destination
 *   <li>If predicate returns false, message is dropped silently
 * </ol>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * // Create a filter that passes only high-priority messages
 * var highPriorityFilter = MessageFilter.create(
 *     msg -> msg.priority() >= 8,
 *     destination  // ProcRef or Destination<Message>
 * );
 *
 * // Send a message (will be forwarded if predicate matches)
 * highPriorityFilter.filter(incomingMessage);
 *
 * // Get filter statistics
 * MessageFilter.Stats stats = highPriorityFilter.stats();
 * System.out.println("Received: " + stats.received());
 * System.out.println("Forwarded: " + stats.forwarded());
 * System.out.println("Dropped: " + stats.dropped());
 * }</pre>
 *
 * @param <M> message type
 * @see ContentBasedRouter
 */
public final class MessageFilter<M> {

    /** Destination abstraction for forwarding filtered messages. */
    public interface Destination<M> {
        void send(M message);
    }

    /** Statistics for filter operations. */
    public record Stats(long received, long forwarded, long dropped) {
        public long total() {
            return received;
        }

        public double forwardedPercentage() {
            return received == 0 ? 0 : (100.0 * forwarded) / received;
        }
    }

    /** Filter state: predicate, destination, and counters. */
    private record FilterState<M>(
            Predicate<M> predicate,
            Destination<M> next,
            long received,
            long forwarded,
            long dropped) {}

    private final Proc<FilterState<M>, FilterCommand<M>> filterProc;

    /** Sealed message hierarchy for the filter. */
    sealed interface FilterCommand<M>
            permits FilterCommand.FilterMsg, FilterCommand.GetStats {

        record FilterMsg<M>(M message) implements FilterCommand<M> {}

        record GetStats<M>() implements FilterCommand<M> {}
    }

    /**
     * Create a message filter with a predicate and destination.
     *
     * @param predicate condition to determine which messages are forwarded
     * @param next destination to forward matching messages to
     * @return a new message filter
     */
    public static <M> MessageFilter<M> create(Predicate<M> predicate, Destination<M> next) {
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        if (next == null) throw new IllegalArgumentException("next must not be null");
        return new MessageFilter<>(predicate, next);
    }

    /**
     * Create a message filter that forwards messages to a Proc (adapter pattern).
     *
     * @param predicate condition to determine which messages are forwarded
     * @param next destination process
     * @return a new message filter
     */
    public static <M> MessageFilter<M> create(Predicate<M> predicate, Proc<?, M> next) {
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        if (next == null) throw new IllegalArgumentException("next must not be null");
        return new MessageFilter<>(predicate, next::tell);
    }

    /** Private constructor — use factory methods. */
    private MessageFilter(Predicate<M> predicate, Destination<M> next) {
        FilterState<M> initialState = new FilterState<>(predicate, next, 0, 0, 0);
        this.filterProc =
                new Proc<>(
                        initialState,
                        (state, cmd) -> {
                            return switch (cmd) {
                                case FilterCommand.FilterMsg<M> fm -> processFilter(state, fm.message());
                                case FilterCommand.GetStats<M> _gs -> state;
                            };
                        });
    }

    /**
     * Filter a message: forward if predicate matches, drop otherwise.
     *
     * <p>This is a fire-and-forget operation — messages are forwarded without waiting
     * for processing confirmation.
     *
     * @param message the message to filter
     */
    public void filter(M message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        filterProc.tell(new FilterCommand.FilterMsg<>(message));
    }

    /**
     * Get the filter's process reference (for monitoring or linking).
     */
    public Proc<FilterState<M>, FilterCommand<M>> process() {
        return filterProc;
    }

    /**
     * Process a filter command: apply predicate and forward if true.
     */
    private FilterState<M> processFilter(FilterState<M> state, M message) {
        long newReceived = state.received() + 1;
        if (state.predicate().test(message)) {
            state.next().send(message);
            return new FilterState<>(
                    state.predicate(),
                    state.next(),
                    newReceived,
                    state.forwarded() + 1,
                    state.dropped());
        } else {
            return new FilterState<>(
                    state.predicate(),
                    state.next(),
                    newReceived,
                    state.forwarded(),
                    state.dropped() + 1);
        }
    }
}
