package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcessRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Dynamic Router — routes messages to runtime-determined destinations using ProcessRegistry.
 *
 * <p><strong>Concept (Vernon's "Dynamic Router"):</strong>
 * Route destination is determined at runtime via a resolver function, enabling late-binding
 * message routing. Unlike static routing where destinations are hardcoded, dynamic routing
 * consults a resolver (e.g., database lookup, configuration service) to determine the target
 * process.
 *
 * <p><strong>Implementation using JOTP:</strong>
 * <ul>
 *   <li>Uses ProcessRegistry for process lookup by name: {@code whereis(name) -> ProcRef}
 *   <li>Destination resolver function: {@code Message -> String (process name)}
 *   <li>Route handler receives message and resolves destination at runtime
 *   <li>Supports handler registration for intercepting routed messages
 * </ul>
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * DynamicRouter<String> router = new DynamicRouter<>(msg -> {
 *   if (msg.startsWith("order:")) return "order-service";
 *   if (msg.startsWith("payment:")) return "payment-service";
 *   return "default-service";
 * });
 *
 * router.registerHandler("order-service", msg -> System.out.println("Order: " + msg));
 * router.route("order:12345"); // resolves to "order-service"
 * }</pre>
 *
 * @param <M> message type
 * @see ProcessRegistry ProcessRegistry for process lookup
 * @see Proc JOTP process abstraction
 * @see Vernon "Dynamic Router" pattern for Enterprise Integration Patterns
 */
public class DynamicRouter<M> {

  private final Function<M, String> destinationResolver;
  private final Map<String, RoutingHandler<M>> handlers;

  /**
   * Constructs a DynamicRouter with a destination resolver function.
   *
   * @param destinationResolver function that maps message to destination process name;
   *        cannot be null
   * @throws NullPointerException if destinationResolver is null
   */
  public DynamicRouter(Function<M, String> destinationResolver) {
    this.destinationResolver =
        java.util.Objects.requireNonNull(
            destinationResolver, "destinationResolver cannot be null");
    this.handlers = new HashMap<>();
  }

  /**
   * Routes a message to a destination determined at runtime.
   *
   * <p>The destination name is obtained by calling {@code destinationResolver.apply(message)}.
   * The process is then looked up in ProcessRegistry. If found, the message is sent; if not
   * found, the message is discarded with a warning.
   *
   * @param message the message to route; cannot be null
   * @return true if routing succeeded (destination found), false otherwise
   * @throws NullPointerException if message is null
   */
  @SuppressWarnings("unchecked")
  public boolean route(M message) {
    java.util.Objects.requireNonNull(message, "message cannot be null");

    // Resolve destination at runtime
    String destinationName = destinationResolver.apply(message);

    // Invoke pre-routing handler if registered
    if (handlers.containsKey(destinationName)) {
      handlers.get(destinationName).beforeRoute(message);
    }

    // Look up process in registry
    Optional<ProcRef<M, M>> procRef = (Optional<ProcRef<M, M>>) (Optional<?>) ProcessRegistry.whereis(destinationName);

    if (procRef.isPresent()) {
      // Send message asynchronously (fire-and-forget)
      procRef.get().send(message);
      return true;
    }

    // Destination not found
    return false;
  }

  /**
   * Registers a handler that executes before routing to a specific destination.
   *
   * <p>Handlers are useful for logging, metrics collection, or message transformation.
   * If a handler already exists for the destination, it is replaced.
   *
   * @param destinationName the process name; cannot be null
   * @param handler the handler to register; cannot be null
   * @throws NullPointerException if destinationName or handler is null
   */
  public void registerHandler(String destinationName, RoutingHandler<M> handler) {
    java.util.Objects.requireNonNull(
        destinationName, "destinationName cannot be null");
    java.util.Objects.requireNonNull(handler, "handler cannot be null");
    handlers.put(destinationName, handler);
  }

  /**
   * Unregisters a handler for a destination.
   *
   * @param destinationName the process name
   */
  public void unregisterHandler(String destinationName) {
    handlers.remove(destinationName);
  }

  /**
   * Returns the number of registered handlers.
   *
   * @return number of handlers
   */
  public int handlerCount() {
    return handlers.size();
  }

  /**
   * Functional interface for routing handlers.
   *
   * @param <M> message type
   */
  @FunctionalInterface
  public interface RoutingHandler<M> {
    /**
     * Called before message is routed to destination.
     *
     * @param message the message being routed
     */
    void beforeRoute(M message);
  }
}
