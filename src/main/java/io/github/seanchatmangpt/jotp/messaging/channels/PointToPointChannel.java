package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.concurrent.TimeoutException;

/**
 * Point-to-Point Channel (Vernon: "Point-to-Point Channel")
 *
 * <p>A messaging channel that ensures each message is received by exactly one recipient.
 * Perfect for one-to-one communication between processes where ordering and delivery
 * guarantees matter.
 *
 * <p>JOTP Implementation: Leverages {@code ProcRef<State, Message>} as a stable,
 * persistent message mailbox. Each channel consumer is a lightweight Proc that handles
 * incoming messages with pure state transformation.
 *
 * <p>Example:
 * <pre>
 * // Create a receiver process
 * var receiver = PointToPointChannel.createReceiver(
 *     state -> message -> {
 *         System.out.println("Received: " + message);
 *         return state; // update state or return unchanged
 *     }
 * );
 *
 * // Send a message to the channel
 * receiver.send(Message.event("OrderPlaced", new Order(123)));
 *
 * // The message is delivered exactly once to the receiver's mailbox
 * </pre>
 */
public final class PointToPointChannel {

    private PointToPointChannel() {
    }

    /**
     * Creates a Point-to-Point receiver process with a message handler.
     *
     * @param handler Pure function: (state, message) -> newState
     * @param <State> Application state type
     * @return ProcRef that can receive messages via send()
     */
    public static <State> ProcRef<State, Message> createReceiver(
        java.util.function.Function<State, java.util.function.Function<Message, State>> handler,
        State initialState) {

        return Proc.spawn(initialState, handler);
    }

    /**
     * Sends a message to a Point-to-Point channel (receiver).
     * Non-blocking; message is queued in the receiver's mailbox.
     *
     * @param receiver The ProcRef to send to
     * @param message  The message to send
     * @param <State>  Receiver's state type
     */
    public static <State> void send(ProcRef<State, Message> receiver, Message message) {
        receiver.send(message);
    }

    /**
     * Sends a message and waits for a reply (blocking with timeout).
     * Assumes handler sends a reply to the correlating replyTo address.
     *
     * @param receiver The ProcRef to send to
     * @param message  The message to send
     * @param timeoutMs Maximum time to wait (milliseconds)
     * @param <State>  Receiver's state type
     * @param <Reply>  Reply message type
     * @return The reply message
     * @throws TimeoutException if no reply received within timeoutMs
     */
    public static <State, Reply> Reply sendAndWait(
        ProcRef<State, Message> receiver,
        Message message,
        long timeoutMs) throws TimeoutException {

        // Use built-in ask() mechanism for request-reply
        return Proc.ask(receiver, message, timeoutMs);
    }

    /**
     * Creates a pipeline of Point-to-Point channels, chaining one receiver to the next.
     * Useful for sequential processing where each stage is a separate process.
     *
     * <p>Example: Stage1 -> Stage2 -> Stage3
     *
     * @param handlers Array of message handlers
     * @param initialState Initial state for first stage
     * @param <State> State type
     * @return Array of ProcRefs forming a pipeline
     */
    @SafeVarargs
    public static <State> ProcRef<State, Message>[] createPipeline(
        State initialState,
        java.util.function.Function<State, java.util.function.Function<Message, State>>... handlers) {

        @SuppressWarnings("unchecked")
        ProcRef<State, Message>[] pipeline = new ProcRef[handlers.length];

        for (int i = 0; i < handlers.length; i++) {
            pipeline[i] = Proc.spawn(initialState, handlers[i]);
        }

        return pipeline;
    }

    /**
     * Routes a message through a pipeline of Point-to-Point channels.
     *
     * @param pipeline Array of receivers from createPipeline()
     * @param message  Message to route
     * @param <State>  State type
     */
    public static <State> void routeThrough(
        ProcRef<State, Message>[] pipeline,
        Message message) {

        for (ProcRef<State, Message> stage : pipeline) {
            send(stage, message);
        }
    }
}
