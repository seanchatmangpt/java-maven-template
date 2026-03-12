package io.github.seanchatmangpt.jotp.messaging.construction;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EnvelopeWrapper pattern (Vernon: "Envelope Wrapper").
 *
 * <p>Wraps payload with metadata envelope: headers, timestamp, sequence number, etc.
 * Enables stateless routing, tracing, and deferred processing.
 *
 * <p>Joe Armstrong: "Message headers tell the router where to go, what to do, and how to
 * recover if things fail. The envelope is the contract; the payload is the data."
 *
 * <p><strong>Pattern:</strong>
 * <ol>
 *   <li>Record-based Envelope<T> carries payload + metadata
 *   <li>UUID correlation ID for distributed tracing
 *   <li>long timestamp for causality and replay
 *   <li>Map<String, String> headers for extensible metadata
 *   <li>Generic payload preserves type safety across process boundaries
 * </ol>
 *
 * <p><strong>Use cases:</strong>
 * <ul>
 *   <li>Distributed request tracing (correlation ID follows request chain)
 *   <li>Message routing: header router directs based on header values
 *   <li>Versioning: "format-version" header enables schema migration
 *   <li>Idempotency: "idempotency-key" header deduplicates retries
 *   <li>Priority/SLA: "priority", "deadline" headers influence scheduling
 * </ul>
 */
public final class EnvelopeWrapper {

  /**
   * Immutable envelope wrapping a payload with metadata.
   *
   * @param id unique envelope ID (UUID v4)
   * @param timestamp milliseconds since epoch when envelope created
   * @param headers immutable map of metadata strings
   * @param payload the wrapped message/data
   * @param <T> the payload type (generic for type safety)
   */
  public record Envelope<T>(UUID id, long timestamp, Map<String, String> headers, T payload)
      implements Serializable {

    /**
     * Compact constructor with validation.
     */
    public Envelope {
      if (id == null) {
        throw new IllegalArgumentException("id must not be null");
      }
      if (timestamp < 0) {
        throw new IllegalArgumentException("timestamp must be non-negative");
      }
      if (headers == null) {
        throw new IllegalArgumentException("headers must not be null");
      }
      if (payload == null) {
        throw new IllegalArgumentException("payload must not be null");
      }
      // Make headers immutable
      if (!(headers instanceof java.util.Collections.UnmodifiableMap)) {
        throw new IllegalArgumentException("headers must be immutable");
      }
    }

    /**
     * Get a header value by key, or null if not present.
     *
     * @param key the header name
     * @return the header value, or null
     */
    public String getHeader(String key) {
      return headers.get(key);
    }

    /**
     * Get a header value by key with a default fallback.
     *
     * @param key the header name
     * @param defaultValue the default if key not found
     * @return the header value or default
     */
    public String getHeaderOrDefault(String key, String defaultValue) {
      return headers.getOrDefault(key, defaultValue);
    }

    /**
     * Check if a header exists.
     *
     * @param key the header name
     * @return true if the header is present
     */
    public boolean hasHeader(String key) {
      return headers.containsKey(key);
    }

    /**
     * Create a new envelope with additional headers. Original envelope is unchanged.
     *
     * @param additionalHeaders headers to merge in
     * @return new Envelope with merged headers
     */
    public Envelope<T> withHeaders(Map<String, String> additionalHeaders) {
      if (additionalHeaders == null || additionalHeaders.isEmpty()) {
        return this;
      }
      Map<String, String> merged = new HashMap<>(this.headers);
      merged.putAll(additionalHeaders);
      return new Envelope<>(this.id, this.timestamp, Collections.unmodifiableMap(merged),
          this.payload);
    }

    /**
     * Create a new envelope with a single additional header.
     *
     * @param key header name
     * @param value header value
     * @return new Envelope with the added header
     */
    public Envelope<T> withHeader(String key, String value) {
      return withHeaders(Map.of(key, value));
    }

    /**
     * Get the payload, unwrapped.
     *
     * @return the wrapped payload
     */
    public T unwrap() {
      return payload;
    }

    /**
     * Get correlation ID for tracing, or generate a new one if not present.
     *
     * @return correlation ID string
     */
    public String getCorrelationId() {
      String cid = getHeader("correlation-id");
      return cid != null ? cid : this.id.toString();
    }

    /**
     * Get request ID (for idempotency), or null if not present.
     *
     * @return request ID string, or null
     */
    public String getRequestId() {
      return getHeader("request-id");
    }

    /**
     * Get deadline timestamp (SLA), or null if not present.
     *
     * @return deadline timestamp as string, or null
     */
    public String getDeadline() {
      return getHeader("deadline");
    }

    /**
     * Get priority level (for queue scheduling), or null if not present.
     *
     * @return priority string ("low", "normal", "high"), or null
     */
    public String getPriority() {
      return getHeader("priority");
    }
  }

  /**
   * Wrap a payload with an empty envelope (minimal metadata).
   *
   * @param payload the object to wrap
   * @return Envelope<T> with auto-generated id and timestamp
   */
  public static <T> Envelope<T> wrap(T payload) {
    return wrap(payload, Map.of());
  }

  /**
   * Wrap a payload with headers in the envelope.
   *
   * @param payload the object to wrap
   * @param headers immutable headers map
   * @return Envelope<T>
   */
  public static <T> Envelope<T> wrap(T payload, Map<String, String> headers) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    if (headers == null) {
      throw new IllegalArgumentException("headers must not be null");
    }
    // Ensure headers are immutable
    Map<String, String> immutable = headers instanceof java.util.Collections.UnmodifiableMap
        ? headers
        : Collections.unmodifiableMap(new HashMap<>(headers));

    return new Envelope<>(
        UUID.randomUUID(),
        System.currentTimeMillis(),
        immutable,
        payload);
  }

  /**
   * Wrap a payload with builder-style header construction.
   *
   * @param payload the object to wrap
   * @param headerBuilder function to populate headers map
   * @return Envelope<T>
   */
  public static <T> Envelope<T> wrap(T payload,
      java.util.function.Consumer<Map<String, String>> headerBuilder) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    Map<String, String> headers = new HashMap<>();
    headerBuilder.accept(headers);
    return wrap(payload, Collections.unmodifiableMap(headers));
  }

  /**
   * Unwrap payload from an envelope.
   *
   * @param envelope the envelope to unwrap
   * @return the payload
   */
  public static <T> T unwrap(Envelope<T> envelope) {
    if (envelope == null) {
      throw new IllegalArgumentException("envelope must not be null");
    }
    return envelope.unwrap();
  }

  /**
   * Create a new envelope with the same payload but different headers.
   *
   * @param envelope source envelope
   * @param newHeaders replacement headers
   * @return new Envelope with same id/timestamp/payload, different headers
   */
  public static <T> Envelope<T> replaceHeaders(Envelope<T> envelope,
      Map<String, String> newHeaders) {
    if (envelope == null) {
      throw new IllegalArgumentException("envelope must not be null");
    }
    Map<String, String> immutable = newHeaders instanceof java.util.Collections.UnmodifiableMap
        ? newHeaders
        : Collections.unmodifiableMap(new HashMap<>(newHeaders));
    return new Envelope<>(envelope.id, envelope.timestamp, immutable, envelope.payload);
  }

  private EnvelopeWrapper() {
    // utility class
  }
}
