package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Routing Slip messaging pattern: message carries an ordered list of next hops (destinations).
 *
 * <p>Vernon: "A message carries a routing slip — a list of process identifiers. Each step in the
 * workflow processes the message and pops the next destination, forwarding the message along. This
 * enables declarative, composable process workflows without centralized routing logic."
 *
 * <p>The routing slip is embedded in the message envelope. Each recipient processes the message
 * according to its role, then pops the next hop and forwards the message. When the slip is empty,
 * the message has completed its journey.
 *
 * <p>Example: an invoice message travels through validation → approval → payment → archive,
 * with each step managing its own logic and routing decisions.
 *
 * <p><strong>Immutable design:</strong> Messages and slips are immutable value objects. Each
 * processing step creates a new message with an updated slip.
 *
 * @param <M> message payload type
 * @param <S> process state type
 */
public final class RoutingSlip<M, S> {

    /**
     * A message envelope with an embedded routing slip.
     *
     * <p>The slip is a list of destinations (ProcRef). As the message flows through the system,
     * each recipient pops the next destination and forwards the message along.
     *
     * @param <M> message payload type
     * @param <S> process state type
     */
    public static record MessageWithSlip<M, S>(M payload, List<ProcRef<S, Object>> slip) {

        /**
         * Create a new MessageWithSlip with the current payload and slip.
         *
         * @param payload the message payload
         * @param slip the ordered list of destinations
         */
        public MessageWithSlip {
            // Defensive copy: ensure slip is immutable
            slip = slip != null ? List.copyOf(slip) : List.of();
        }

        /**
         * Return the next hop (destination) without modifying the slip.
         *
         * @return the next ProcRef, or empty if slip is exhausted
         */
        public java.util.Optional<ProcRef<S, Object>> peekNext() {
            return slip.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(slip.get(0));
        }

        /**
         * Create a new MessageWithSlip with the first destination removed.
         *
         * <p>Returns a new instance with the slip advanced by one hop. The original instance is
         * unchanged.
         *
         * @return new MessageWithSlip with slip advanced, or throws if slip is already empty
         * @throws IllegalStateException if slip is exhausted
         */
        public MessageWithSlip<M, S> popNext() {
            if (slip.isEmpty()) {
                throw new IllegalStateException("Routing slip exhausted: no more hops");
            }
            return new MessageWithSlip<>(payload, slip.subList(1, slip.size()));
        }

        /**
         * Check if this message's journey is complete (slip is empty).
         *
         * @return true if no more hops remain
         */
        public boolean isComplete() {
            return slip.isEmpty();
        }

        /**
         * Return the number of remaining hops in the slip.
         *
         * @return hop count
         */
        public int remainingHops() {
            return slip.size();
        }
    }

    private RoutingSlip() {}

    /**
     * Create a message with a routing slip.
     *
     * <p>Wraps a payload with an ordered list of destinations. The message is then forwarded to
     * the first recipient in the slip.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param payload the message content
     * @param hops ordered list of destinations
     * @return a MessageWithSlip ready to be sent to the first hop
     */
    public static <M, S> MessageWithSlip<M, S> withSlip(M payload, List<ProcRef<S, Object>> hops) {
        if (hops == null || hops.isEmpty()) {
            throw new IllegalArgumentException("Routing slip must contain at least one hop");
        }
        return new MessageWithSlip<>(payload, hops);
    }

    /**
     * Create a message with a routing slip from varargs destinations.
     *
     * <p>Convenience method for inline slip construction.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param payload the message content
     * @param hops destinations (varargs)
     * @return a MessageWithSlip ready to be sent to the first hop
     */
    @SafeVarargs
    public static <M, S> MessageWithSlip<M, S> withSlip(M payload, ProcRef<S, Object>... hops) {
        if (hops == null || hops.length == 0) {
            throw new IllegalArgumentException("Routing slip must contain at least one hop");
        }
        return new MessageWithSlip<>(payload, List.of(hops));
    }

    /**
     * Execute the routing slip: send the message to the first hop and iterate through all hops.
     *
     * <p>This method orchestrates the message journey. It sends the message to each recipient in
     * order, updating the slip as it progresses. The message is sent via
     * <em>fire-and-forget</em> ({@link ProcRef#tell tell}) — the sender does not wait for a
     * reply.
     *
     * <p>If any hop fails (throws an exception), the journey terminates immediately.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @return {@code Result.success} if the message completes its journey, or {@code
     *     Result.failure(exception)} if any hop fails
     */
    @SuppressWarnings("unchecked")
    public static <M, S> Result<Void, Exception> executeSlip(
            MessageWithSlip<M, S> messageWithSlip) {
        try {
            MessageWithSlip<M, S> current = messageWithSlip;

            // Iterate through all hops
            while (!current.isComplete()) {
                var nextHop = current.peekNext().orElseThrow();
                // Send message to next hop (fire-and-forget)
                nextHop.tell((M) current);
                // Advance the slip
                current = current.popNext();
            }

            return Result.ok(null);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Execute the routing slip with request-reply semantics.
     *
     * <p>Sends the message to each hop via {@link ProcRef#ask ask}, waiting for each hop to
     * process the message before proceeding to the next hop. This is a sequential, synchronous
     * workflow.
     *
     * <p>Useful when each hop must return state or acknowledgment before the next step proceeds.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @param timeout timeout for each request-reply hop
     * @return {@code Result.success} if all hops complete within timeout, or {@code
     *     Result.failure(exception)} if any hop times out or fails
     */
    @SuppressWarnings("unchecked")
    public static <M, S> Result<List<S>, Exception> executeSlipSync(
            MessageWithSlip<M, S> messageWithSlip, Duration timeout) {
        try {
            MessageWithSlip<M, S> current = messageWithSlip;
            var replies = new ArrayList<S>();

            // Iterate through all hops synchronously
            while (!current.isComplete()) {
                var nextHop = current.peekNext().orElseThrow();
                // Send message and wait for reply (request-reply)
                var reply = nextHop.ask((M) current, timeout).get();
                replies.add(reply);
                // Advance the slip
                current = current.popNext();
            }

            return Result.ok(replies);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Execute the routing slip asynchronously, returning immediately.
     *
     * <p>Forks the execution of the slip into a virtual thread and returns a CompletableFuture
     * that completes when the message journey finishes or fails.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @return a CompletableFuture that completes when the slip execution finishes
     */
    public static <M, S> CompletableFuture<Result<Void, Exception>> executeSlipAsync(
            MessageWithSlip<M, S> messageWithSlip) {
        return CompletableFuture.supplyAsync(() -> executeSlip(messageWithSlip), task -> {
            Thread.ofVirtual().start(task);
        });
    }

    /**
     * Check the next hop in the slip without advancing it.
     *
     * <p>Used for introspection or logging to see where the message is headed next.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @return optional containing the next ProcRef, or empty if slip is exhausted
     */
    public static <M, S> java.util.Optional<ProcRef<S, Object>> peekNext(
            MessageWithSlip<M, S> messageWithSlip) {
        return messageWithSlip.peekNext();
    }

    /**
     * Advance the slip by one hop.
     *
     * <p>Creates a new MessageWithSlip with the first destination removed. The original instance
     * is unchanged (immutable).
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @return new MessageWithSlip with slip advanced by one hop
     * @throws IllegalStateException if slip is already exhausted
     */
    public static <M, S> MessageWithSlip<M, S> popNext(MessageWithSlip<M, S> messageWithSlip) {
        return messageWithSlip.popNext();
    }

    /**
     * Get the journey progress: how many hops remain.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @return number of remaining hops
     */
    public static <M, S> int remainingHops(MessageWithSlip<M, S> messageWithSlip) {
        return messageWithSlip.remainingHops();
    }

    /**
     * Check if the message's journey is complete.
     *
     * @param <M> message payload type
     * @param <S> process state type
     * @param messageWithSlip the message envelope with slip
     * @return true if no more hops remain
     */
    public static <M, S> boolean isComplete(MessageWithSlip<M, S> messageWithSlip) {
        return messageWithSlip.isComplete();
    }
}
