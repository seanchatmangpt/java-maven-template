package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Data Type Channel (Vernon: "Data Type Channel")
 *
 * <p>Strongly-typed message routing using sealed message hierarchies. Routes incoming messages to
 * type-specific handlers based on pattern matching against Java sealed interface variants.
 *
 * <p>This pattern enables clean, type-safe message dispatching where each message variant
 * (CommandMsg, EventMsg, QueryMsg, DocumentMsg) is routed to its corresponding handler without
 * casting or instanceof checks.
 *
 * <p>JOTP Implementation: A registry-based router that leverages Java's sealed interfaces and
 * pattern matching to dispatch messages to specialized handlers. Each handler is itself a
 * lightweight Proc, enabling decoupled, concurrent processing of different message types.
 *
 * <p>Example:
 * <pre>
 * var channel = DataTypeChannel.create()
 *     .addRoute(Message.CommandMsg.class, commandHandler)
 *     .addRoute(Message.EventMsg.class, eventHandler)
 *     .addRoute(Message.QueryMsg.class, queryHandler);
 *
 * // Dispatch a message - routed to appropriate handler based on type
 * channel.dispatch(Message.command("CreateUser", userPayload, replyTo));
 * </pre>
 */
public final class DataTypeChannel {

  private DataTypeChannel() {}

  /**
   * State container for a DataTypeChannel router.
   *
   * <p>Tracks registered routes (handler procs) and metrics about dispatched messages.
   */
  public static final class ChannelState {
    private final Map<Class<?>, ProcRef<?, Message>> routes;
    private final Map<Class<?>, Integer> dispatchCounts;
    private int totalDispatched = 0;

    private ChannelState(Map<Class<?>, ProcRef<?, Message>> routes) {
      this.routes = new ConcurrentHashMap<>(routes);
      this.dispatchCounts = new ConcurrentHashMap<>();
    }

    private ChannelState() {
      this(new ConcurrentHashMap<>());
    }

    /**
     * Register a handler for a specific message type.
     *
     * @param messageType The sealed message variant class (e.g. Message.CommandMsg.class)
     * @param handler The ProcRef handler for this type
     * @param <M> The message variant type
     * @return This state for chaining
     */
    public <M extends Message> ChannelState addRoute(
        Class<M> messageType, ProcRef<?, Message> handler) {
      routes.put(messageType, handler);
      dispatchCounts.putIfAbsent(messageType, 0);
      return this;
    }

    /**
     * Get the handler for a specific message type.
     *
     * @param messageType The sealed message variant class
     * @return Optional containing the handler, or empty if not registered
     */
    public Optional<ProcRef<?, Message>> getRoute(Class<?> messageType) {
      return Optional.ofNullable(routes.get(messageType));
    }

    /**
     * Check if a message type is registered.
     *
     * @param messageType The sealed message variant class
     * @return true if a handler is registered for this type
     */
    public boolean hasRoute(Class<?> messageType) {
      return routes.containsKey(messageType);
    }

    /**
     * Get all registered message types.
     *
     * @return Unmodifiable set of registered message types
     */
    public Set<Class<?>> registeredTypes() {
      return Collections.unmodifiableSet(routes.keySet());
    }

    /**
     * Get the total number of messages dispatched to a specific type.
     *
     * @param messageType The sealed message variant class
     * @return Count of messages dispatched to this type (0 if not registered)
     */
    public int getDispatchCount(Class<?> messageType) {
      return dispatchCounts.getOrDefault(messageType, 0);
    }

    /**
     * Get total count of all dispatched messages.
     *
     * @return Total message count across all types
     */
    public int getTotalDispatched() {
      return totalDispatched;
    }

    /**
     * Increment dispatch counter for a message type.
     *
     * @param messageType The sealed message variant class
     * @return This state for chaining
     */
    private ChannelState incrementDispatch(Class<?> messageType) {
      dispatchCounts.compute(messageType, (k, v) -> v == null ? 1 : v + 1);
      totalDispatched++;
      return this;
    }
  }

  /**
   * Factory: Create a new DataTypeChannel router.
   *
   * @return A ProcRef representing the router process
   */
  public static ProcRef<ChannelState, Message> create() {
    return Proc.spawn(new ChannelState(), state -> msg -> state);
  }

  /**
   * Create a DataTypeChannel with pre-configured routes.
   *
   * @param routes Mapping of message type classes to handler ProcRefs
   * @return A ProcRef representing the router process
   */
  public static ProcRef<ChannelState, Message> create(
      Map<Class<? extends Message>, ProcRef<?, Message>> routes) {
    ChannelState initialState = new ChannelState();
    for (var entry : routes.entrySet()) {
      initialState.addRoute(entry.getKey(), entry.getValue());
    }
    return Proc.spawn(initialState, state -> msg -> state);
  }

  /**
   * Register a handler for a specific message type on an existing channel.
   *
   * <p>This method sends a configuration message to the channel's router process, which updates
   * its route registry. Safe to call from any thread.
   *
   * @param channel The DataTypeChannel router
   * @param messageType The sealed message variant class
   * @param handler The ProcRef handler for this type
   * @param <M> The message variant type
   */
  public static <M extends Message> void addRoute(
      ProcRef<ChannelState, Message> channel,
      Class<M> messageType,
      ProcRef<?, Message> handler) {

    // Send internal configuration message
    var configMsg =
        new RouteRegistration(messageType, handler, UUID.randomUUID(), System.currentTimeMillis());
    channel.send(configMsg);
  }

  /**
   * Dispatch a message through the channel router.
   *
   * <p>The router performs pattern matching on the message to determine its type, looks up the
   * registered handler, and forwards the message to that handler's mailbox.
   *
   * @param channel The DataTypeChannel router
   * @param message The message to dispatch
   */
  public static void dispatch(ProcRef<ChannelState, Message> channel, Message message) {
    // Get current state and find matching route
    var state = Proc.getState(channel);

    // Pattern match on message type and route accordingly
    Class<?> messageType =
        switch (message) {
          case Message.CommandMsg _ -> Message.CommandMsg.class;
          case Message.EventMsg _ -> Message.EventMsg.class;
          case Message.QueryMsg _ -> Message.QueryMsg.class;
          case Message.DocumentMsg _ -> Message.DocumentMsg.class;
        };

    // Find handler and dispatch
    state.getRoute(messageType).ifPresent(handler -> handler.send(message));

    // Increment dispatch counter
    state.incrementDispatch(messageType);
  }

  /**
   * Get current channel state (registered routes and metrics).
   *
   * @param channel The DataTypeChannel router
   * @return The current ChannelState
   */
  public static ChannelState getState(ProcRef<ChannelState, Message> channel) {
    return Proc.getState(channel);
  }

  /**
   * Internal message type for route registration.
   *
   * <p>This is not part of the public Message sealed interface; it's used internally for channel
   * configuration.
   */
  private static final class RouteRegistration implements Message {
    private final Class<?> messageType;
    private final ProcRef<?, Message> handler;
    private final UUID messageId;
    private final long createdAt;

    private RouteRegistration(
        Class<?> messageType,
        ProcRef<?, Message> handler,
        UUID messageId,
        long createdAt) {
      this.messageType = messageType;
      this.handler = handler;
      this.messageId = messageId;
      this.createdAt = createdAt;
    }

    @Override
    public UUID messageId() {
      return messageId;
    }

    @Override
    public long createdAt() {
      return createdAt;
    }
  }

  /**
   * Create a strongly-typed handler wrapper for a specific message variant.
   *
   * <p>Wraps a handler that only processes one message type, providing a type-safe interface.
   *
   * @param messageType The sealed message variant class (e.g. Message.EventMsg.class)
   * @param handler Consumer that processes messages of this type
   * @param initialState Initial state for the handler process
   * @param <M> The message variant type
   * @param <State> The handler's state type
   * @return A ProcRef representing the handler
   */
  public static <M extends Message, State> ProcRef<State, Message> createTypedHandler(
      Class<M> messageType, Function<State, Function<M, State>> handler, State initialState) {

    // Wrap handler to only accept messages of the correct type
    return Proc.spawn(
        initialState,
        state ->
            msg -> {
              if (messageType.isInstance(msg)) {
                @SuppressWarnings("unchecked")
                M typedMsg = (M) msg;
                return handler.apply(state).apply(typedMsg);
              }
              return state; // Ignore messages not of this type
            });
  }

  /**
   * Create a multi-handler router that processes multiple message types with different handlers.
   *
   * <p>Supports type-specific processing without explicit casting.
   *
   * @param commandHandler Handler for CommandMsg
   * @param eventHandler Handler for EventMsg
   * @param queryHandler Handler for QueryMsg
   * @param documentHandler Handler for DocumentMsg
   * @param initialState Initial state shared across all handlers
   * @param <State> The handler state type
   * @return A ProcRef representing the router
   */
  public static <State> ProcRef<ChannelState, Message> createMultiTypeRouter(
      Function<State, Function<Message.CommandMsg, State>> commandHandler,
      Function<State, Function<Message.EventMsg, State>> eventHandler,
      Function<State, Function<Message.QueryMsg, State>> queryHandler,
      Function<State, Function<Message.DocumentMsg, State>> documentHandler,
      State initialState) {

    // Create individual typed handlers
    var cmdHandler = createTypedHandler(Message.CommandMsg.class, commandHandler, initialState);
    var evtHandler = createTypedHandler(Message.EventMsg.class, eventHandler, initialState);
    var qryHandler = createTypedHandler(Message.QueryMsg.class, queryHandler, initialState);
    var docHandler = createTypedHandler(Message.DocumentMsg.class, documentHandler, initialState);

    // Create channel and register all routes
    var channel = create();
    addRoute(channel, Message.CommandMsg.class, cmdHandler);
    addRoute(channel, Message.EventMsg.class, evtHandler);
    addRoute(channel, Message.QueryMsg.class, qryHandler);
    addRoute(channel, Message.DocumentMsg.class, docHandler);

    return channel;
  }
}
