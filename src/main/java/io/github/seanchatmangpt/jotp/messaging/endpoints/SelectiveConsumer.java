package io.github.seanchatmangpt.jotp.messaging.endpoints;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.function.Predicate;

/**
 * Selective Consumer (Vernon: "Selective Consumer")
 *
 * <p>Consumer that only accepts messages matching a selection criterion.
 * Others are left in the queue for another consumer.
 *
 * <p>JOTP Implementation: Uses Proc<S,M> with pattern matching predicate
 * and ProcessRegistry for named consumers.
 *
 * <p>Example:
 * <pre>
 * var consumer = SelectiveConsumer.create(
 *     msg -> msg instanceof Message.CommandMsg,
 *     msg -> System.out.println("Processing command: " + msg)
 * );
 * </pre>
 */
public final class SelectiveConsumer {

    /**
     * Consumer state: accepted/rejected message counts.
     */
    static class ConsumerState {
        String name;
        int accepted = 0;
        int rejected = 0;

        ConsumerState(String name) {
            this.name = name;
        }
    }

    private SelectiveConsumer() {
    }

    /**
     * Creates a selective consumer that filters by predicate.
     *
     * @param selector Predicate: returns true if message should be consumed
     * @param handler Handler function for accepted messages
     * @return ProcRef for the consumer
     */
    public static ProcRef<ConsumerState, Message> create(
        Predicate<Message> selector,
        java.util.function.Consumer<Message> handler) {

        return Proc.spawn(
            new ConsumerState("SELECTIVE_CONSUMER"),
            state -> msg -> {
                if (selector.test(msg)) {
                    handler.accept(msg);
                    state.accepted++;
                } else {
                    state.rejected++;
                    // In real system, would re-queue for other consumers
                }
                return state;
            }
        );
    }

    /**
     * Creates a selective consumer registered by name in ProcessRegistry.
     * Other processes can look it up with whereis(name).
     *
     * @param consumerName Registry name
     * @param selector Predicate for message selection
     * @param handler Handler function
     * @return Consumer ProcRef (already registered in registry)
     */
    public static ProcRef<ConsumerState, Message> createNamed(
        String consumerName,
        Predicate<Message> selector,
        java.util.function.Consumer<Message> handler) {

        var consumer = create(selector, handler);
        ProcessRegistry.register(consumerName, consumer);
        return consumer;
    }

    /**
     * Gets acceptance statistics.
     *
     * @param consumer The consumer ProcRef
     * @return Formatted stats
     */
    public static String getStats(ProcRef<ConsumerState, Message> consumer) {
        var state = Proc.getState(consumer);
        return String.format(
            "accepted=%d, rejected=%d, total=%d",
            state.accepted,
            state.rejected,
            state.accepted + state.rejected
        );
    }

    /**
     * Creates a type-filtered consumer (only accepts specific message type).
     *
     * @param messageType Type class to filter for
     * @param handler Handler for matching messages
     * @return ProcRef for type-selective consumer
     */
    public static ProcRef<ConsumerState, Message> createTyped(
        Class<? extends Message> messageType,
        java.util.function.Consumer<Message> handler) {

        return create(
            msg -> messageType.isInstance(msg),
            handler
        );
    }

    /**
     * Creates a route-filtered consumer (matches by event/command type).
     *
     * @param routeKey Route name to match (from Message.eventType or commandType)
     * @param handler Handler for matching messages
     * @return ProcRef for route-selective consumer
     */
    public static ProcRef<ConsumerState, Message> createByRoute(
        String routeKey,
        java.util.function.Consumer<Message> handler) {

        return create(
            msg -> {
                if (msg instanceof Message.EventMsg evt) {
                    return routeKey.equals(evt.eventType());
                } else if (msg instanceof Message.CommandMsg cmd) {
                    return routeKey.equals(cmd.commandType());
                }
                return false;
            },
            handler
        );
    }
}
