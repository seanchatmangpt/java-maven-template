package io.github.seanchatmangpt.jotp.messaging.construction;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.acme.Result;

/**
 * ClaimCheck pattern (Vernon: "Claim Check").
 *
 * <p>Splits large payload: store externally, send claim token.
 * Receiver uses claim ID to retrieve full payload asynchronously.
 *
 * <p>Joe Armstrong: "In Erlang, we pass the Pid, not the entire mailbox. The process
 * retrieves what it needs on-demand. This is the ClaimCheck — send the handle, not the
 * data."
 *
 * <p><strong>Pattern:</strong>
 * <ol>
 *   <li>Large object stored in external storage (map, database, etc.)
 *   <li>UUID claim token generated and returned
 *   <li>Lightweight CheckedMessage sent across process boundaries (contains only token)
 *   <li>Receiver calls retrieve(claimId) to get full payload on-demand
 *   <li>Result<T, String> makes async retrieval type-safe and composable
 * </ol>
 *
 * <p><strong>Use cases:</strong>
 * <ul>
 *   <li>Large document transmission over constrained networks
 *   <li>Deferred loading in distributed systems
 *   <li>Memory-efficient process communication
 *   <li>Idempotent claim lookups (same token always returns same payload)
 * </ul>
 */
public final class ClaimCheck {

  private static final Map<UUID, Object> CLAIM_STORE = new ConcurrentHashMap<>();

  /**
   * Lightweight message carrying only the claim ID (token).
   *
   * @param claimId unique token to retrieve the full payload
   * @param timestamp creation time (milliseconds)
   * @param metadata optional metadata about the claimed payload
   */
  public record CheckedMessage(UUID claimId, long timestamp, Map<String, String> metadata)
      implements Serializable {
    public CheckedMessage {
      if (claimId == null) {
        throw new IllegalArgumentException("claimId must not be null");
      }
      if (metadata == null) {
        throw new IllegalArgumentException("metadata must not be null");
      }
    }

    /**
     * Retrieve the full payload associated with this claim.
     *
     * @return Result.Ok with the payload, or Result.Err with error message
     */
    public <T> Result<T, String> retrieve() {
      return ClaimCheck.retrieve(claimId);
    }
  }

  /**
   * Store a large object and return a lightweight claim token.
   *
   * @param payload the object to store
   * @param metadata optional metadata describing the payload
   * @return CheckedMessage containing the claim ID and metadata
   */
  public static CheckedMessage claimCheck(Object payload, Map<String, String> metadata) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    UUID claimId = UUID.randomUUID();
    CLAIM_STORE.put(claimId, payload);
    return new CheckedMessage(claimId, System.currentTimeMillis(), metadata);
  }

  /**
   * Convenience method: claimCheck with empty metadata.
   *
   * @param payload the object to store
   * @return CheckedMessage with the claim ID
   */
  public static CheckedMessage claimCheck(Object payload) {
    return claimCheck(payload, Map.of());
  }

  /**
   * Retrieve the full payload using a claim ID.
   *
   * <p>This method returns a Result<T, String> for composable, type-safe async retrieval.
   * If the claim exists, it returns Ok with the payload. If not, it returns Err with a
   * descriptive message.
   *
   * @param claimId the token issued by claimCheck
   * @return Result.Ok(payload) if found, Result.Err(errorMsg) if not
   */
  @SuppressWarnings("unchecked")
  public static <T> Result<T, String> retrieve(UUID claimId) {
    if (claimId == null) {
      return Result.failure("claimId must not be null");
    }
    Object payload = CLAIM_STORE.get(claimId);
    return payload != null
        ? Result.ok((T) payload)
        : Result.failure("No payload found for claim ID: " + claimId);
  }

  /**
   * Release a claim (remove from storage). Useful for cleanup and preventing memory leaks.
   *
   * @param claimId the token to release
   * @return true if the claim existed and was removed; false otherwise
   */
  public static boolean release(UUID claimId) {
    return CLAIM_STORE.remove(claimId) != null;
  }

  /**
   * Retrieve and immediately release a claim. Single-use pattern.
   *
   * @param claimId the token to consume
   * @return Result.Ok(payload) if found and released, Result.Err(errorMsg) if not
   */
  @SuppressWarnings("unchecked")
  public static <T> Result<T, String> consumeClaim(UUID claimId) {
    if (claimId == null) {
      return Result.failure("claimId must not be null");
    }
    Object payload = CLAIM_STORE.remove(claimId);
    return payload != null
        ? Result.ok((T) payload)
        : Result.failure("No payload found for claim ID: " + claimId);
  }

  /**
   * Check if a claim exists without retrieving the payload.
   *
   * @param claimId the token to check
   * @return true if the claim exists in storage; false otherwise
   */
  public static boolean exists(UUID claimId) {
    return claimId != null && CLAIM_STORE.containsKey(claimId);
  }

  /**
   * Get the current number of claims in storage (for monitoring/debugging).
   *
   * @return claim count
   */
  public static int claimCount() {
    return CLAIM_STORE.size();
  }

  /**
   * Clear all claims from storage (for testing or shutdown).
   */
  public static void clearAll() {
    CLAIM_STORE.clear();
  }

  private ClaimCheck() {
    // utility class
  }
}
