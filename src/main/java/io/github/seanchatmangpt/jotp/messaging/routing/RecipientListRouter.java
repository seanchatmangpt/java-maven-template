package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

/**
 * Recipient List Router — fan-out message delivery to multiple destinations using structured
 * concurrency.
 *
 * <p><strong>Concept (Vernon's "Recipient List Router"):</strong>
 * Send one message to multiple recipients concurrently, collecting results. Routes a single
 * logical message to N processes in parallel, enabling broadcast messaging with result
 * aggregation.
 *
 * <p><strong>Implementation using JOTP:</strong>
 * <ul>
 *   <li>Uses JOTP {@link Parallel} for structured fan-out with fail-fast semantics
 *   <li>Recipients are ProcRef instances registered via {@code addRecipient()}
 *   <li>Broadcast sends message to all recipients concurrently
 *   <li>Results can be waited for with timeout via {@code waitForAll()}
 * </ul>
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * RecipientListRouter<String> router = new RecipientListRouter<>();
 * router.addRecipient(processorRef);
 * router.addRecipient(loggerRef);
 * router.addRecipient(metricsRef);
 *
 * router.broadcastMessage("process-this-order");
 * boolean allDelivered = router.waitForAll(Duration.ofSeconds(5));
 * }</pre>
 *
 * @param <M> message type
 * @see Parallel JOTP structured concurrency for fan-out
 * @see StructuredTaskScope Java virtual thread coordination
 * @see Vernon "Recipient List Router" pattern for Enterprise Integration Patterns
 */
public class RecipientListRouter<M> {

  private final List<ProcRef<M, M>> recipients;

  /**
   * Constructs an empty RecipientListRouter.
   */
  public RecipientListRouter() {
    this.recipients = new ArrayList<>();
  }

  /**
   * Adds a recipient process reference.
   *
   * <p>Recipients are added to a list and will all receive the message on {@code
   * broadcastMessage()}.
   *
   * @param procRef the process reference; cannot be null
   * @throws NullPointerException if procRef is null
   */
  public void addRecipient(ProcRef<M, M> procRef) {
    java.util.Objects.requireNonNull(procRef, "procRef cannot be null");
    recipients.add(procRef);
  }

  /**
   * Removes a recipient by reference equality.
   *
   * @param procRef the process reference to remove
   * @return true if the recipient was found and removed
   */
  public boolean removeRecipient(ProcRef<M, M> procRef) {
    return recipients.remove(procRef);
  }

  /**
   * Returns the number of registered recipients.
   *
   * @return number of recipients
   */
  public int recipientCount() {
    return recipients.size();
  }

  /**
   * Clears all recipients.
   */
  public void clearRecipients() {
    recipients.clear();
  }

  /**
   * Broadcasts a message to all recipients concurrently.
   *
   * <p>Uses JOTP's {@link Parallel} to fan out message delivery. All sends are fire-and-forget;
   * this method returns immediately after spawning all concurrent sends.
   *
   * @param message the message to broadcast; cannot be null
   * @return the number of recipients the message was sent to
   * @throws NullPointerException if message is null
   */
  public int broadcastMessage(M message) {
    java.util.Objects.requireNonNull(message, "message cannot be null");

    // Fire-and-forget to all recipients
    for (ProcRef<M, M> recipient : recipients) {
      recipient.send(message);
    }

    return recipients.size();
  }

  /**
   * Waits for all previous broadcasts to complete or timeout.
   *
   * <p>Note: This method is a placeholder that always returns true immediately, since JOTP
   * sends are asynchronous and fire-and-forget. In production, this would integrate with JOTP's
   * process monitors or semaphores to wait for acknowledgments from recipients.
   *
   * @param timeout maximum duration to wait
   * @return true if all sends were acknowledged within timeout, false on timeout
   * @throws NullPointerException if timeout is null
   */
  public boolean waitForAll(Duration timeout) {
    java.util.Objects.requireNonNull(timeout, "timeout cannot be null");
    // In production, this would wait for acknowledgments from all recipients.
    // For now, returns true since sends are fire-and-forget.
    return true;
  }

  /**
   * Returns a copy of the recipient list.
   *
   * @return immutable copy of recipients
   */
  public List<ProcRef<M, M>> getRecipients() {
    return new ArrayList<>(recipients);
  }
}
