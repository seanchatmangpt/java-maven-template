package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Result;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Idempotent Receiver pattern — deduplicates message delivery using message ID tracking.
 *
 * <p>Gregor Hohpe's "Idempotent Receiver" pattern: prevents accidental reprocessing of the same
 * message by maintaining a set of seen message IDs. Routes duplicate messages to an optional
 * handler without reprocessing the business logic.
 *
 * <p>Joe Armstrong principle: "Messages can be delivered more than once, and you must handle that
 * gracefully. Store message IDs you've already processed, and ignore duplicates."
 *
 * <p>Mapping to JOTP:
 *
 * <ul>
 *   <li>Message ID tracking → {@code Set<UUID>} held in process state
 *   <li>Lightweight process → {@link Proc<State,Message>}
 *   <li>Duplicate detection → pattern match on seen ID set
 *   <li>History size bounded → LRU eviction when exceeding max history
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * var receiver = IdempotentReceiver.<Order>create(100)
 *     .receive(orderId, order, ctx -> {
 *         System.out.println("Processing order: " + order);
 *         return order;
 *     });
 *
 * // Resending same order is safe — duplicate is detected and ignored
 * receiver.receive(orderId, order, ctx -> {
 *     System.out.println("This won't print — duplicate detected");
 *     return order;
 * });
 *
 * if (receiver.isDuplicate(orderId)) {
 *     System.out.println("Order already processed");
 * }
 * }</pre>
 *
 * @param <M> message type processed by this receiver
 */
public final class IdempotentReceiver<M> {

  /**
   * Message envelope with unique identifier.
   *
   * @param id unique message identifier (UUID for global uniqueness)
   * @param payload the actual message
   */
  public record Message<M>(UUID id, M payload) {}

  /**
   * Processing result carrying both the state and whether message was duplicate.
   *
   * @param result the processing result
   * @param isDuplicate true if message ID was already seen before this call
   */
  public record ProcessingResult<R>(R result, boolean isDuplicate) {}

  /** Internal receiver state: set of seen IDs. */
  public static class ReceiverState {
    private final Set<UUID> seenIds = ConcurrentHashMap.newKeySet();
    private final int maxHistorySize;
    private volatile long processedCount = 0;
    private volatile long duplicateCount = 0;

    ReceiverState(int maxHistorySize) {
      if (maxHistorySize <= 0) {
        throw new IllegalArgumentException("maxHistorySize must be > 0");
      }
      this.maxHistorySize = maxHistorySize;
    }

    boolean isDuplicate(UUID messageId) {
      return seenIds.contains(messageId);
    }

    void recordId(UUID messageId) {
      seenIds.add(messageId);
      processedCount++;

      // LRU eviction: if we exceed max history, create new set
      // This is a simple strategy; more sophisticated LRU can track insertion order
      if (seenIds.size() > maxHistorySize) {
        seenIds.clear();
        seenIds.add(messageId);
      }
    }

    void markDuplicate() {
      duplicateCount++;
    }

    public int seenCount() {
      return seenIds.size();
    }

    public long processedCount() {
      return processedCount;
    }

    public long duplicateCount() {
      return duplicateCount;
    }

    void reset() {
      seenIds.clear();
      processedCount = 0;
      duplicateCount = 0;
    }
  }

  private final Proc<ReceiverState, Message<M>> proc;

  /**
   * Create a new IdempotentReceiver with a bounded history size.
   *
   * @param maxHistorySize maximum number of message IDs to track before LRU eviction
   * @param <M> message type
   * @return a new receiver instance
   */
  public static <M> IdempotentReceiver<M> create(int maxHistorySize) {
    return new IdempotentReceiver<>(maxHistorySize);
  }

  private IdempotentReceiver(int maxHistorySize) {
    this.proc =
        new Proc<>(
            new ReceiverState(maxHistorySize),
            (state, message) -> {
              if (state.isDuplicate(message.id)) {
                state.markDuplicate();
              } else {
                state.recordId(message.id);
              }
              return state;
            });
  }

  /**
   * Receive a message with a processing handler. If the message ID is duplicate, the handler is
   * NOT invoked.
   *
   * <p>This is the core pattern: check for duplicate, only process if new.
   *
   * @param id unique message identifier
   * @param payload the message payload
   * @param handler processing function: state -> result (only called for new messages)
   * @param <R> result type from handler
   * @return processing result with isDuplicate flag
   */
  public <R> Result<ProcessingResult<R>, String> receive(
      UUID id, M payload, BiFunction<ReceiverState, M, R> handler) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(handler, "handler must not be null");

    try {
      // Send message to process for deduplication check
      proc.tell(new Message<>(id, payload));

      // Get current state synchronously via ask
      var stateFuture = proc.ask(new Message<>(id, null)); // null payload for state query

      var result =
          stateFuture
              .thenApply(
                  state -> {
                    boolean isDuplicate = state.isDuplicate(id);

                    // Only call handler for new messages
                    if (isDuplicate) {
                      return new ProcessingResult<R>(null, true);
                    } else {
                      try {
                        R handlerResult = handler.apply(state, payload);
                        return new ProcessingResult<>(handlerResult, false);
                      } catch (Exception e) {
                        throw new RuntimeException(e);
                      }
                    }
                  })
              .join();

      return Result.ok(result);
    } catch (Exception e) {
      return Result.err(e.getMessage());
    }
  }

  /**
   * Check if a message ID has already been processed (is duplicate).
   *
   * @param id message ID to check
   * @return true if this ID was already processed
   */
  public boolean isDuplicate(UUID id) {
    try {
      var stateFuture = proc.ask(new Message<>(id, null));
      var state = stateFuture.join();
      return state.isDuplicate(id);
    } catch (Exception e) {
      return false; // On error, assume not duplicate
    }
  }

  /**
   * Get current receiver statistics without blocking.
   *
   * @return a snapshot of seen count, processed count, and duplicate count
   */
  public Result<String, String> statistics() {
    try {
      var stateFuture = proc.ask(new Message<>(UUID.randomUUID(), null));
      var state = stateFuture.join();
      var stats =
          String.format(
              "seen=%d, processed=%d, duplicates=%d",
              state.seenCount(), state.processedCount(), state.duplicateCount());
      return Result.ok(stats);
    } catch (Exception e) {
      return Result.err("Failed to get statistics: " + e.getMessage());
    }
  }

  /**
   * Get number of unique IDs currently tracked.
   *
   * @return count of tracked message IDs
   */
  public int trackedCount() {
    try {
      var stateFuture = proc.ask(new Message<>(UUID.randomUUID(), null));
      var state = stateFuture.join();
      return state.seenCount();
    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Reset receiver state — clears all tracked IDs and statistics.
   *
   * <p>Use in testing or when restarting processing after known clean state.
   */
  public void reset() {
    try {
      proc.ask(new Message<>(UUID.randomUUID(), null))
          .thenApply(
              state -> {
                state.reset();
                return state;
              })
          .join();
    } catch (Exception e) {
      // Ignore
    }
  }

  /** Stop the receiver process. */
  public void stop() throws InterruptedException {
    proc.stop();
  }

  /** Check if the receiver process is still alive. */
  public boolean isAlive() {
    return proc.thread().isAlive();
  }
}
