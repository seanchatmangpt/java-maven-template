package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.LinkedList;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Resequencer pattern (Vernon EIP) — orders out-of-order messages by sequence number.
 *
 * <p>Buffers incoming messages keyed by sequence number and delivers them in order,
 * handling gaps and timeouts. Uses a Proc<State, Message> internally with a TreeMap
 * accumulator for ordered delivery.
 *
 * <p><strong>Pattern semantics:</strong>
 *
 * <ul>
 *   <li>Messages are accumulated in a TreeMap by sequence number
 *   <li>When a message arrives, check if we can deliver the next-expected sequence
 *   <li>Deliver all consecutive messages starting from the expected sequence
 *   <li>If a gap is detected and timeout expires, deliver up to the gap or drop
 *   <li>Handles capacity limits (reject when full)
 * </ul>
 *
 * <p><strong>Joe Armstrong influence:</strong> Uses Proc for state isolation and
 * mailbox-based ordering. The resequencer process holds mutable accumulator state
 * without sharing it across threads.
 */
public final class Resequencer<T> {

  private static final long DEFAULT_TIMEOUT_MILLIS = 5000;
  private static final int DEFAULT_CAPACITY = 1000;

  /** Message wrapper carrying sequence number and payload. */
  public record SequencedMessage<T>(long sequence, T payload) {}

  /** Internal state: next expected sequence, TreeMap buffer, timestamp of oldest buffered. */
  private record State<T>(
      long nextExpected,
      SortedMap<Long, T> buffer,
      long oldestBufferTime,
      int capacity,
      long timeoutMillis) {}

  private record OfferMsg<T>(SequencedMessage<T> msg, CompletableFuture<Optional<T>> reply) {}

  private record Message<T>(OfferMsg<T> offer) {}

  private final Proc<State<T>, Message<T>> proc;

  /**
   * Create a resequencer with default timeout and capacity.
   *
   * @param startSeq the first sequence number to expect
   * @return a new Resequencer
   */
  public static <T> Resequencer<T> create(long startSeq) {
    return create(startSeq, Duration.ofMillis(DEFAULT_TIMEOUT_MILLIS), DEFAULT_CAPACITY);
  }

  /**
   * Create a resequencer with custom timeout and capacity.
   *
   * @param startSeq the first sequence number to expect
   * @param timeout maximum time to wait for a missing sequence before delivering buffered messages
   * @param capacity maximum number of buffered messages
   * @return a new Resequencer
   */
  public static <T> Resequencer<T> create(long startSeq, Duration timeout, int capacity) {
    State<T> initialState =
        new State<>(startSeq, new TreeMap<>(), System.currentTimeMillis(), capacity,
            timeout.toMillis());
    Resequencer<T> reseq = new Resequencer<>();
    reseq.proc = new Proc<>(initialState, reseq::handleMessage);
    return reseq;
  }

  private Resequencer() {}

  /**
   * Offer a message to the resequencer. Returns immediately with an Optional that is non-empty
   * if the message (or any buffered messages) can be delivered in sequence.
   *
   * @param msg the sequenced message
   * @return an Optional containing the next in-order message if available, empty otherwise
   */
  public CompletableFuture<Optional<T>> offer(SequencedMessage<T> msg) {
    CompletableFuture<Optional<T>> reply = new CompletableFuture<>();
    OfferMsg<T> offer = new OfferMsg<>(msg, reply);
    proc.tell(new Message<>(offer));
    return reply;
  }

  private State<T> handleMessage(State<T> state, Message<T> message) {
    OfferMsg<T> offer = message.offer();
    SequencedMessage<T> msg = offer.msg;
    CompletableFuture<Optional<T>> reply = offer.reply;

    long seq = msg.sequence;
    T payload = msg.payload;

    // Check capacity
    if (state.buffer.size() >= state.capacity) {
      reply.complete(Optional.empty());
      return state;
    }

    // Add to buffer
    SortedMap<Long, T> newBuffer = new TreeMap<>(state.buffer);
    newBuffer.put(seq, payload);

    long newOldestTime = System.currentTimeMillis();

    // Try to deliver consecutive messages from nextExpected
    long delivered = state.nextExpected;
    while (newBuffer.containsKey(delivered)) {
      delivered++;
    }

    if (delivered > state.nextExpected) {
      // We have at least one message to deliver
      T toDeliver = newBuffer.get(state.nextExpected);
      newBuffer.remove(state.nextExpected);
      reply.complete(Optional.of(toDeliver));

      // Update state with delivered sequence
      State<T> nextState =
          new State<>(state.nextExpected + 1, newBuffer, newOldestTime, state.capacity,
              state.timeoutMillis);
      return nextState;
    }

    // Check timeout: if oldest message exceeds timeout, flush buffer up to next gap
    if (!newBuffer.isEmpty()) {
      long elapsed = System.currentTimeMillis() - state.oldestBufferTime;
      if (elapsed > state.timeoutMillis) {
        // Timeout: deliver up to the first gap
        for (long i = state.nextExpected; i <= newBuffer.lastKey(); i++) {
          if (!newBuffer.containsKey(i)) {
            // Gap found; deliver nothing and reset timer
            reply.complete(Optional.empty());
            return new State<>(state.nextExpected, newBuffer, System.currentTimeMillis(),
                state.capacity, state.timeoutMillis);
          }
          // Deliver this one
          T item = newBuffer.get(i);
          if (i == state.nextExpected) {
            newBuffer.remove(i);
            reply.complete(Optional.of(item));
            return new State<>(state.nextExpected + 1, newBuffer, System.currentTimeMillis(),
                state.capacity, state.timeoutMillis);
          }
        }
      }
    }

    reply.complete(Optional.empty());
    return new State<>(state.nextExpected, newBuffer, state.oldestBufferTime, state.capacity,
        state.timeoutMillis);
  }

  /**
   * Wait for the internal process to finish (graceful shutdown).
   *
   * @throws InterruptedException if interrupted
   */
  public void stop() throws InterruptedException {
    proc.stop();
  }
}
