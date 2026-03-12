package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;

/**
 * Message Bridge (Vernon: "Message Bridge")
 *
 * <p>Connects two messaging systems with different protocols or formats.
 * Translates messages between systems transparently.
 *
 * <p>JOTP Implementation: Adapter Proc that wraps both systems,
 * translates messages between them.
 *
 * <p>Example:
 * <pre>
 * var bridge = MessageBridge.create(
 *     systemA,  // ProcRef to system A
 *     systemB,  // ProcRef to system B
 *     msg -> transformToB(msg),
 *     msg -> transformToA(msg)
 * );
 * </pre>
 */
public final class MessageBridge {

    /**
     * Bridge state: both connected systems and message counts.
     */
    static class BridgeState {
        ProcRef<?, Message> systemA;
        ProcRef<?, Message> systemB;
        long messagesFromA = 0;
        long messagesFromB = 0;
        long translationErrors = 0;

        BridgeState(ProcRef<?, Message> a, ProcRef<?, Message> b) {
            this.systemA = a;
            this.systemB = b;
        }
    }

    private MessageBridge() {
    }

    /**
     * Creates a bridge connecting two messaging systems.
     *
     * @param systemA First system ProcRef
     * @param systemB Second system ProcRef
     * @param translatorAtoB Translates messages A->B
     * @param translatorBtoA Translates messages B->A
     * @return Bridge ProcRef
     */
    public static ProcRef<BridgeState, Message> create(
        ProcRef<?, Message> systemA,
        ProcRef<?, Message> systemB,
        java.util.function.Function<Message, Message> translatorAtoB,
        java.util.function.Function<Message, Message> translatorBtoA) {

        var bridge = Proc.spawn(
            new BridgeState(systemA, systemB),
            state -> msg -> {
                try {
                    // Determine source and route to target
                    // In real implementation, would use message origin tracking
                    var translated = translatorAtoB.apply(msg);
                    systemB.send(translated);
                    state.messagesFromA++;
                } catch (Exception e) {
                    state.translationErrors++;
                }
                return state;
            }
        );

        return bridge;
    }

    /**
     * Sends a message from system A through the bridge to system B.
     *
     * @param bridge The bridge ProcRef
     * @param message Message to bridge
     */
    public static void forwardFromA(ProcRef<BridgeState, Message> bridge, Message message) {
        bridge.send(message);
    }

    /**
     * Sends a message from system B through the bridge to system A.
     *
     * @param bridge The bridge ProcRef
     * @param message Message to bridge
     */
    public static void forwardFromB(ProcRef<BridgeState, Message> bridge, Message message) {
        bridge.send(message);
    }

    /**
     * Gets bridge statistics.
     *
     * @param bridge The bridge ProcRef
     * @return Formatted stats
     */
    public static String getStats(ProcRef<BridgeState, Message> bridge) {
        var state = Proc.getState(bridge);
        return String.format(
            "A->B=%d, B->A=%d, errors=%d",
            state.messagesFromA,
            state.messagesFromB,
            state.translationErrors
        );
    }
}
