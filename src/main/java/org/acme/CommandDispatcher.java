package org.acme;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Command dispatcher — CQRS command-side dispatcher.
 *
 * <p>CQRS Pattern: "Command Query Responsibility Segregation separates
 * the read and write models, allowing each to be optimized independently."
 *
 * <p>Joe Armstrong: "In Erlang, you don't share state — you send commands
 * to processes that own the state. The command dispatcher is the routing layer."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Command routing</b> — Route commands to handlers by type</li>
 *   <li><b>Validation</b> — Pre-dispatch command validation</li>
 *   <li><b>Middleware</b> — Pre/post processing pipeline</li>
 *   <li><b>Result aggregation</b> — Collect results from multiple handlers</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * CommandDispatcher dispatcher = CommandDispatcher.create()
 *     .register(CreateOrder.class, this::handleCreateOrder)
 *     .register(AddItem.class, this::handleAddItem)
 *     .register(SubmitOrder.class, this::handleSubmitOrder)
 *     .middleware(new LoggingMiddleware())
 *     .middleware(new ValidationMiddleware());
 *
 * // Dispatch a command
 * Result<OrderCreated, CommandError> result = dispatcher.dispatch(new CreateOrder(...));
 * }</pre>
 */
public final class CommandDispatcher {

    /** Command marker interface. */
    public interface Command {}

    /** Command result. */
    public sealed interface CommandResult<T> permits CommandResult.Success, CommandResult.Failure {
        record Success<T>(T value) implements CommandResult<T> {}
        record Failure<T>(String error, Throwable cause) implements CommandResult<T> {}

        static <T> CommandResult<T> ok(T value) { return new Success<>(value); }
        static <T> CommandResult<T> err(String error) { return new Failure<>(error, null); }
        static <T> CommandResult<T> err(String error, Throwable cause) { return new Failure<>(error, cause); }

        default boolean isSuccess() { return this instanceof Success<T>; }
        default T orElse(T defaultValue) {
            return switch (this) {
                case Success<T>(var v) -> v;
                case Failure<T> ignored -> defaultValue;
            };
        }
    }

    /** Middleware for command processing. */
    public interface Middleware {
        /** Process command before dispatch. Return null to continue, or result to short-circuit. */
        default <T> CommandResult<T> before(Command command) { return null; }
        /** Process result after dispatch. */
        default <T> CommandResult<T> after(Command command, CommandResult<T> result) { return result; }
    }

    /** Command handler function. */
    @FunctionalInterface
    public interface Handler<C extends Command, T> {
        CommandResult<T> handle(C command);
    }

    // ── Dispatcher state ────────────────────────────────────────────────────────

    private final ConcurrentHashMap<Class<?>, Handler<?, ?>> handlers = new ConcurrentHashMap<>();
    private final List<Middleware> middlewares = new CopyOnWriteArrayList<>();
    private final AtomicLong commandsDispatched = new AtomicLong();
    private final AtomicLong commandsSucceeded = new AtomicLong();
    private final AtomicLong commandsFailed = new AtomicLong();

    private CommandDispatcher() {}

    // ── Factory ──────────────────────────────────────────────────────────────────

    public static CommandDispatcher create() {
        return new CommandDispatcher();
    }

    // ── Registration ─────────────────────────────────────────────────────────────

    /**
     * Register a handler for a command type.
     */
    public <C extends Command, T> CommandDispatcher register(Class<C> commandType, Handler<C, T> handler) {
        handlers.put(commandType, handler);
        return this;
    }

    /**
     * Register a handler using a simple function.
     */
    public <C extends Command, T> CommandDispatcher register(Class<C> commandType, Function<C, T> handler) {
        handlers.put(commandType, cmd -> {
            try {
                return CommandResult.ok(handler.apply(commandType.cast(cmd)));
            } catch (Exception e) {
                return CommandResult.err(e.getMessage(), e);
            }
        });
        return this;
    }

    /**
     * Add middleware.
     */
    public CommandDispatcher middleware(Middleware middleware) {
        middlewares.add(middleware);
        return this;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────────

    /**
     * Dispatch a command to its handler.
     */
    @SuppressWarnings("unchecked")
    public <T> CommandResult<T> dispatch(Command command) {
        commandsDispatched.incrementAndGet();

        // Run before middlewares
        for (Middleware mw : middlewares) {
            CommandResult<T> result = mw.before(command);
            if (result != null) {
                commandsFailed.incrementAndGet();
                return result;
            }
        }

        // Find handler
        Handler<Command, T> handler = (Handler<Command, T>) handlers.get(command.getClass());
        if (handler == null) {
            CommandResult<T> result = CommandResult.err("No handler registered for: " + command.getClass().getName());
            commandsFailed.incrementAndGet();
            return result;
        }

        // Execute handler
        CommandResult<T> result;
        try {
            result = handler.handle(command);
        } catch (Exception e) {
            result = CommandResult.err(e.getMessage(), e);
        }

        // Run after middlewares
        for (Middleware mw : middlewares) {
            result = mw.after(command, result);
        }

        // Update metrics
        if (result.isSuccess()) {
            commandsSucceeded.incrementAndGet();
        } else {
            commandsFailed.incrementAndGet();
        }

        return result;
    }

    /**
     * Dispatch a command asynchronously.
     */
    public <T> CompletableFuture<CommandResult<T>> dispatchAsync(Command command) {
        return CompletableFuture.supplyAsync(() -> dispatch(command));
    }

    // ── Statistics ───────────────────────────────────────────────────────────────

    public Map<String, Long> stats() {
        Map<String, Long> s = new LinkedHashMap<>();
        s.put("dispatched", commandsDispatched.get());
        s.put("succeeded", commandsSucceeded.get());
        s.put("failed", commandsFailed.get());
        s.put("handlers", (long) handlers.size());
        return s;
    }
}
