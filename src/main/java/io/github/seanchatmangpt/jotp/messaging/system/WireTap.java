package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wire Tap (Vernon: "Wire Tap")
 *
 * <p>Non-invasively spy on message traffic without affecting the main flow.
 * Useful for monitoring, logging, and diagnostics.
 *
 * <p>JOTP Implementation: Uses ProcessMonitor to observe messages
 * sent to a target process without intercepting them.
 *
 * <p>Example:
 * <pre>
 * var listener = WireTap.tap(targetProcessRef,
 *     msg -> System.out.println("Intercepted: " + msg));
 * </pre>
 */
public final class WireTap {

    /**
     * Tap state: captured messages for analysis.
     */
    static class TapState {
        List<Message> capturedMessages = new CopyOnWriteArrayList<>();
        int messageCount = 0;
        long startTime = System.currentTimeMillis();

        void capture(Message msg) {
            capturedMessages.add(msg);
            messageCount++;
        }
    }

    private WireTap() {
    }

    /**
     * Creates a wire tap that observes messages sent to a process.
     * Messages pass through normally; tap is non-intrusive.
     *
     * @param targetProcess Process to monitor
     * @param listener Listener function called on each message
     * @param <S> Target process state type
     * @param <M> Message type
     * @return ProcRef for the tap (can be monitored or discarded)
     */
    public static <S, M> ProcRef<TapState, M> tap(
        ProcRef<S, M> targetProcess,
        java.util.function.Consumer<M> listener) {

        // Create a monitor that watches the target process
        var tapProcess = Proc.spawn(new TapState(), state -> msg -> {
            listener.accept(msg);
            if (msg instanceof Message) {
                state.capture((Message) msg);
            }
            return state;
        });

        // Monitor the target (receives DOWN when target crashes)
        ProcessMonitor.monitor(targetProcess, tapProcess);

        return tapProcess;
    }

    /**
     * Creates a tap that forwards all intercepted messages to an observer process.
     * Observer receives each message as they flow.
     *
     * @param targetProcess Process to monitor
     * @param observerProcess Process that receives copies of messages
     * @param <S> State type
     * @param <M> Message type
     */
    public static <S, M> void tapToProcess(
        ProcRef<S, M> targetProcess,
        ProcRef<?, M> observerProcess) {

        tap(targetProcess, msg -> observerProcess.send(msg));
    }

    /**
     * Gets captured messages from the tap.
     *
     * @param tap The tap ProcRef
     * @return List of captured messages
     */
    public static List<Message> getCaptured(ProcRef<TapState, ?> tap) {
        var state = Proc.getState(tap);
        return new CopyOnWriteArrayList<>(state.capturedMessages);
    }

    /**
     * Gets tap statistics.
     *
     * @param tap The tap ProcRef
     * @return Formatted stats
     */
    public static String getStats(ProcRef<TapState, ?> tap) {
        var state = Proc.getState(tap);
        long uptime = System.currentTimeMillis() - state.startTime;
        double rate = state.messageCount / (uptime / 1000.0);
        return String.format(
            "messages=%d, uptime=%dms, rate=%.2f msg/s",
            state.messageCount,
            uptime,
            rate
        );
    }
}
