package io.github.seanchatmangpt.jotp.messaging.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Composed Message Processor pattern (Vernon EIP) — chains multiple routers/filters into a
 * declarative pipeline.
 *
 * <p>Enables composition of message processing steps using functional composition. Each step
 * transforms or filters a message; the pipeline short-circuits on filtering.
 *
 * <p><strong>Design:</strong>
 *
 * <ul>
 *   <li>Each processor is a {@code Function<Message, Message>} or filter predicate
 *   <li>Composition chains them sequentially
 *   <li>Filters return {@code null} to signal rejection
 *   <li>Routers transform the message into the next form
 *   <li>Fluent API: {@code compose(...).thenFilter(...).thenRoute(...)}
 * </ul>
 *
 * <p><strong>Declarative DSL example:</strong>
 *
 * <pre>{@code
 * var pipeline = ComposedMessageProcessor.<String>compose(
 *     msg -> msg.trim(),                          // Router: strip whitespace
 *     msg -> msg.toUpperCase()                    // Router: uppercase
 *   )
 *   .thenFilter(msg -> !msg.isEmpty())            // Filter: reject empty
 *   .thenFilter(msg -> !msg.startsWith("SKIP"))   // Filter: reject SKIP prefix
 *   .thenRoute(msg -> "[" + msg + "]");           // Router: wrap in brackets
 *
 * var result = pipeline.apply("  hello world  ");
 * // Result: "[HELLO WORLD]"
 * }</pre>
 *
 * <p><strong>Joe Armstrong influence:</strong> Promotes composition over inheritance,
 * mirroring pipe-and-filter architecture. Functions are pure and side-effect-free.
 */
public final class ComposedMessageProcessor<M> implements Function<M, M> {

  private final List<Function<M, M>> pipeline;
  private final List<Predicate<M>> filters;

  private ComposedMessageProcessor(List<Function<M, M>> pipeline, List<Predicate<M>> filters) {
    this.pipeline = new ArrayList<>(pipeline);
    this.filters = new ArrayList<>(filters);
  }

  /**
   * Compose multiple message processors into a single pipeline.
   *
   * @param processors variable number of router functions
   * @return a new ComposedMessageProcessor
   */
  @SafeVarargs
  public static <M> ComposedMessageProcessor<M> compose(Function<M, M>... processors) {
    List<Function<M, M>> pipeline = new ArrayList<>();
    for (Function<M, M> p : processors) {
      if (p != null) {
        pipeline.add(p);
      }
    }
    return new ComposedMessageProcessor<>(pipeline, new ArrayList<>());
  }

  /**
   * Add a filter predicate to the pipeline. Messages failing the predicate are rejected.
   *
   * @param predicate filter condition
   * @return this processor (fluent API)
   */
  public ComposedMessageProcessor<M> thenFilter(Predicate<M> predicate) {
    this.filters.add(predicate);
    return this;
  }

  /**
   * Add a router function to transform the message further.
   *
   * @param router transformation function
   * @return this processor (fluent API)
   */
  public ComposedMessageProcessor<M> thenRoute(Function<M, M> router) {
    if (router != null) {
      this.pipeline.add(router);
    }
    return this;
  }

  /**
   * Apply the composed pipeline to a message. Routers are applied first, then filters are
   * checked. If any filter rejects the message, {@code null} is returned.
   *
   * @param message the input message
   * @return the transformed message, or {@code null} if rejected by a filter
   */
  @Override
  public M apply(M message) {
    if (message == null) {
      return null;
    }

    // Apply all routers sequentially
    M current = message;
    for (Function<M, M> router : pipeline) {
      current = router.apply(current);
      if (current == null) {
        return null;
      }
    }

    // Apply all filters
    for (Predicate<M> filter : filters) {
      if (!filter.test(current)) {
        return null;
      }
    }

    return current;
  }

  /**
   * Convenience: apply this processor and unwrap null (returns Optional-like behavior).
   * Useful for nullable returns in stream operations.
   *
   * @param message the input message
   * @return the transformed message wrapped in Optional
   */
  public java.util.Optional<M> applyOptional(M message) {
    return java.util.Optional.ofNullable(apply(message));
  }

  /**
   * Chain this processor with another, creating a new composite.
   *
   * @param next the next processor in the chain
   * @return a new processor that applies this, then next
   */
  public ComposedMessageProcessor<M> andThen(ComposedMessageProcessor<M> next) {
    ComposedMessageProcessor<M> combined = new ComposedMessageProcessor<>(pipeline, filters);
    combined.pipeline.addAll(next.pipeline);
    combined.filters.addAll(next.filters);
    return combined;
  }

  /**
   * Apply a side-effect function (e.g., logging) without modifying the message.
   *
   * @param observer side-effect function
   * @return this processor
   */
  public ComposedMessageProcessor<M> peek(java.util.function.Consumer<M> observer) {
    this.pipeline.add(msg -> {
      observer.accept(msg);
      return msg;
    });
    return this;
  }
}
