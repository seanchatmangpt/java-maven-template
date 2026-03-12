package io.github.seanchatmangpt.jotp.reactive;

/**
 * Fundamental reactive messaging abstraction — the typed conduit between producers and consumers.
 *
 * <p>Maps to Erlang's {@code !} (send) operator and process mailbox as an explicit, named channel.
 * Enterprise Integration Pattern: <em>Message Channel</em> (Hohpe &amp; Woolf, EIP §6).
 *
 * <p>In OTP, every process has an implicit mailbox. {@code MessageChannel} makes the channel
 * <em>explicit</em> and <em>named</em> — enabling topology-level reasoning about message flow
 * independent of the processes at each end. This is the foundational abstraction from which all
 * other reactive messaging patterns (router, filter, transformer, aggregator, splitter, pipeline)
 * are composed.
 *
 * <p>The type parameter {@code T} enforces compile-time message type safety. Use a {@code Record}
 * or sealed interface of Records for immutability by construction.
 *
 * @param <T> message type — use a {@code Record} or sealed interface of Records
 * @see PointToPointChannel
 * @see PublishSubscribeChannel
 * @see DeadLetterChannel
 */
public interface MessageChannel<T> {

    /**
     * Send a message into this channel.
     *
     * <p>Corresponds to Erlang's {@code Pid ! Message} (fire-and-forget send). The caller returns
     * immediately; downstream processing happens asynchronously in the channel's virtual thread.
     *
     * @param message the message to enqueue — must not be {@code null}
     */
    void send(T message);

    /**
     * Shut down this channel, waiting for in-flight messages to drain.
     *
     * <p>Corresponds to OTP {@code gen_server:stop/1} — graceful termination after the current
     * workload completes.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    void stop() throws InterruptedException;
}
