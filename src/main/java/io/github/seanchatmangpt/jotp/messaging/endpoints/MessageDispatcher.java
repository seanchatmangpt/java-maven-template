package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.concurrent.TimeoutException;

/**
 * Message Dispatcher (Vernon: "Message Dispatcher")
 *
 * <p>Converts synchronous (blocking) request-response into asynchronous
 * message-based communication. Bridges sync systems with async messaging.
 *
 * <p>JOTP Implementation: Uses virtual threads + ask(msg, timeout) for
 * managed blocking with timeouts.
 *
 * <p>Example:
 * <pre>
 * var dispatcher = MessageDispatcher.create(receiverProc);
 * var response = dispatcher.syncCall(request, 5000); // blocking
 * </pre>
 */
public final class MessageDispatcher {

    /**
     * Dispatcher state: receiver reference and metrics.
     */
    static class DispatcherState {
        ProcRef<?, Message> receiver;
        long callCount = 0;
        long totalLatency = 0;

        DispatcherState(ProcRef<?, Message> receiver) {
            this.receiver = receiver;
        }
    }

    private MessageDispatcher() {
    }

    /**
     * Creates a dispatcher that bridges sync callers to async receiver.
     *
     * @param receiver ProcRef to dispatch messages to
     * @return Dispatcher instance
     */
    public static MessageDispatcher create(ProcRef<?, Message> receiver) {
        return new MessageDispatcher() {
            DispatcherState state = new DispatcherState(receiver);

            /**
             * Synchronous call: blocks waiting for async response.
             */
            public Message syncCall(Message request, long timeoutMs) throws TimeoutException {
                long start = System.currentTimeMillis();
                try {
                    var response = Proc.ask(state.receiver, request, timeoutMs);
                    state.callCount++;
                    state.totalLatency += (System.currentTimeMillis() - start);
                    return response;
                } catch (TimeoutException e) {
                    state.callCount++;
                    state.totalLatency += (System.currentTimeMillis() - start);
                    throw e;
                }
            }

            /**
             * Gets average latency of dispatched calls.
             */
            public double getAverageLatency() {
                if (state.callCount == 0) return 0;
                return (double) state.totalLatency / state.callCount;
            }

            /**
             * Gets number of dispatched calls.
             */
            public long getCallCount() {
                return state.callCount;
            }
        };
    }

    /**
     * Dispatches message and waits for reply (using virtual threads).
     * This can be called safely from any context without blocking platform threads.
     *
     * @param receiver Target receiver
     * @param request Request message
     * @param timeoutMs Timeout in milliseconds
     * @return Reply message
     * @throws TimeoutException If no reply received in time
     */
    public static Message dispatch(
        ProcRef<?, Message> receiver,
        Message request,
        long timeoutMs) throws TimeoutException {

        return Proc.ask(receiver, request, timeoutMs);
    }
}
