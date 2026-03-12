package io.github.seanchatmangpt.jotp.reactive;

import java.util.function.Consumer;

/**
 * Wire Tap — EIP system-management pattern that inserts a point-to-point channel into a
 * message flow to inspect or copy messages without affecting the main channel.
 *
 * <p>The tap consumer receives every message asynchronously on a dedicated virtual thread
 * so it cannot slow down or block the primary message flow. The primary channel always
 * receives the original message, regardless of tap failures.
 *
 * <p>Use cases: audit logging, debugging, monitoring, message tracing, protocol bridging.
 *
 * <p>Erlang/OTP analogy: tracing via {@code sys:trace/2} — attaches a handler that
 * receives copies of messages without interfering with the traced process.
 *
 * <p>Composition: a WireTap wraps any {@link MessageChannel}, so multiple taps can be
 * stacked: {@code new WireTap<>(new WireTap<>(primary, tap1), tap2)}.
 *
 * @param <T> message type
 */
public final class WireTap<T> implements MessageChannel<T> {

    private final MessageChannel<T> primary;
    private final Consumer<T> tap;
    private volatile boolean active = true;

    /**
     * Creates a WireTap that forwards all messages to {@code primary} and asynchronously
     * delivers copies to {@code tap}.
     *
     * @param primary the main message channel
     * @param tap     consumer that observes each message (runs on a virtual thread)
     */
    public WireTap(MessageChannel<T> primary, Consumer<T> tap) {
        this.primary = primary;
        this.tap = tap;
    }

    /**
     * Delivers {@code message} to the primary channel, then—on a dedicated virtual thread—
     * delivers a copy to the tap consumer.
     *
     * <p>The primary channel always receives the message first. If the tap consumer throws,
     * the exception is swallowed so the primary flow is unaffected.
     */
    @Override
    public void send(T message) {
        primary.send(message);
        if (active) {
            Thread.ofVirtual()
                    .name("wire-tap-")
                    .start(() -> {
                        try {
                            tap.accept(message);
                        } catch (Exception ignored) {
                            // Wire tap must never affect primary flow
                        }
                    });
        }
    }

    /**
     * Deactivates the tap — subsequent messages are forwarded to primary only.
     * Thread-safe; takes effect immediately on the next {@link #send}.
     */
    public void deactivate() {
        active = false;
    }

    /**
     * Reactivates the tap after a previous {@link #deactivate()} call.
     * Thread-safe; takes effect immediately on the next {@link #send}.
     */
    public void activate() {
        active = true;
    }

    /** Returns {@code true} if the tap is currently active. */
    public boolean isActive() {
        return active;
    }

    /** No managed threads — no-op stop. Callers stop the primary channel independently. */
    @Override
    public void stop() throws InterruptedException {
        // WireTap is stateless; the primary channel manages its own lifecycle
    }
}
