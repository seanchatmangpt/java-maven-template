package org.acme;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * OTP {@code gen_event} — event manager that decouples event producers from consumers.
 *
 * <p>Joe Armstrong: "gen_event is how OTP decouples event sources from event sinks. The error
 * logger, alarm handler, and SASL all use gen_event. Without it, you hardcode your event routing."
 *
 * <p>OTP's {@code gen_event} manages a list of <em>event handlers</em>. Any process can:
 *
 * <ul>
 *   <li>{@code gen_event:add_handler/3} → {@link #addHandler(Handler)} — register a handler
 *   <li>{@code gen_event:notify/2} → {@link #notify(Object)} — broadcast to all handlers
 *   <li>{@code gen_event:sync_notify/2} → {@link #syncNotify(Object)} — broadcast and wait
 *   <li>{@code gen_event:delete_handler/3} → {@link #deleteHandler(Handler)} — remove a handler
 *   <li>{@code gen_event:call/4} → {@link #call(Handler, Object)} — sync call to one handler
 *   <li>{@code gen_event:stop/1} → {@link #stop()} — shut down the manager
 * </ul>
 *
 * <p><strong>Key OTP property:</strong> a crashing handler is removed from the list but does
 * <em>not</em> kill the event manager — the manager isolates handler failures. This mirrors OTP's
 * behaviour where {@code gen_event} swallows handler exceptions and removes the offending handler.
 *
 * <p><strong>Implementation:</strong> the event manager is itself a {@link Proc}, making it a
 * first-class process with a mailbox — exactly as in OTP where a gen_event manager is a process.
 *
 * @param <E> event type — use a {@code Record} or sealed interface of Records
 */
public final class EventManager<E> {

    /**
     * OTP {@code gen_event} handler behaviour — implement this interface to receive events.
     *
     * @param <E> event type
     */
    public interface Handler<E> {
        /**
         * Called for each event broadcast via {@link EventManager#notify} or {@link
         * EventManager#syncNotify}.
         *
         * <p>Exceptions thrown here are caught by the manager; the handler is removed and the
         * manager continues serving other handlers — mirroring OTP's fault-isolation guarantee.
         *
         * @param event the event to handle
         */
        void handleEvent(E event);

        /**
         * Called when this handler is removed — either by {@link EventManager#deleteHandler} or
         * because it crashed.
         *
         * @param reason {@code null} for a normal removal, non-null if removed due to a crash
         */
        default void terminate(Throwable reason) {}
    }

    /** Internal message hierarchy for the event manager process. */
    private sealed interface Msg<E>
            permits Msg.Notify, Msg.SyncNotify, Msg.Add, Msg.Delete, Msg.Call, Msg.Stop {
        record Notify<E>(E event) implements Msg<E> {}

        record SyncNotify<E>(E event, CompletableFuture<Void> done) implements Msg<E> {}

        record Add<E>(Handler<E> handler) implements Msg<E> {}

        record Delete<E>(Handler<E> handler, CompletableFuture<Boolean> result)
                implements Msg<E> {}

        record Call<E>(Handler<E> handler, E event, CompletableFuture<Void> done)
                implements Msg<E> {}

        record Stop<E>() implements Msg<E> {}
    }

    private final Proc<List<Handler<E>>, Msg<E>> proc;

    private EventManager() {
        proc = new Proc<>(new CopyOnWriteArrayList<>(), EventManager::handle);
    }

    @SuppressWarnings("unchecked")
    private static <E> List<Handler<E>> handle(List<Handler<E>> handlers, Msg<E> msg) {
        return switch (msg) {
            case Msg.Add<E>(var h) -> {
                handlers.add(h);
                yield handlers;
            }
            case Msg.Delete<E>(var h, var result) -> {
                boolean removed = handlers.remove(h);
                if (removed) h.terminate(null);
                result.complete(removed);
                yield handlers;
            }
            case Msg.Notify<E>(var event) -> {
                broadcast(handlers, event);
                yield handlers;
            }
            case Msg.SyncNotify<E>(var event, var done) -> {
                broadcast(handlers, event);
                done.complete(null);
                yield handlers;
            }
            case Msg.Call<E>(var h, var event, var done) -> {
                if (handlers.contains(h)) {
                    try {
                        h.handleEvent(event);
                    } catch (RuntimeException e) {
                        handlers.remove(h);
                        h.terminate(e);
                    }
                }
                done.complete(null);
                yield handlers;
            }
            case Msg.Stop<E>() -> {
                for (Handler<E> h : new ArrayList<>(handlers)) {
                    h.terminate(null);
                }
                handlers.clear();
                yield handlers;
            }
        };
    }

    /**
     * Broadcast {@code event} to all handlers without waiting — mirrors OTP {@code
     * gen_event:notify/2}.
     *
     * <p>Handlers that throw are removed silently; the manager continues.
     */
    private static <E> void broadcast(List<Handler<E>> handlers, E event) {
        List<Handler<E>> toRemove = new ArrayList<>();
        for (Handler<E> h : handlers) {
            try {
                h.handleEvent(event);
            } catch (RuntimeException e) {
                toRemove.add(h);
                h.terminate(e);
            }
        }
        handlers.removeAll(toRemove);
    }

    /**
     * Start a new event manager process.
     *
     * @param <E> event type
     * @return the running event manager
     */
    public static <E> EventManager<E> start() {
        return new EventManager<>();
    }

    /**
     * Register an event handler — mirrors OTP {@code gen_event:add_handler/3}.
     *
     * <p>The handler will receive all subsequent events until it is removed or crashes.
     *
     * @param handler the handler to register
     */
    public void addHandler(Handler<E> handler) {
        proc.tell(new Msg.Add<>(handler));
    }

    /**
     * Broadcast {@code event} to all handlers asynchronously — mirrors OTP {@code
     * gen_event:notify/2}.
     *
     * <p>Returns immediately; the event is processed in the manager's own virtual thread.
     *
     * @param event the event to broadcast
     */
    public void notify(E event) {
        proc.tell(new Msg.Notify<>(event));
    }

    /**
     * Broadcast {@code event} and wait until all handlers have processed it — mirrors OTP {@code
     * gen_event:sync_notify/2}.
     *
     * @param event the event to broadcast
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void syncNotify(E event) throws InterruptedException {
        var done = new CompletableFuture<Void>();
        proc.tell(new Msg.SyncNotify<>(event, done));
        try {
            done.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("syncNotify failed", e);
        }
    }

    /**
     * Remove a handler — mirrors OTP {@code gen_event:delete_handler/3}.
     *
     * <p>Calls {@link Handler#terminate(Throwable)} with {@code null} reason on the removed
     * handler.
     *
     * @param handler the handler to remove
     * @return {@code true} if the handler was registered and removed
     */
    public boolean deleteHandler(Handler<E> handler) {
        var result = new CompletableFuture<Boolean>();
        proc.tell(new Msg.Delete<>(handler, result));
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("deleteHandler failed", e);
        }
    }

    /**
     * Synchronously call a specific handler with {@code event} — mirrors OTP {@code
     * gen_event:call/4}.
     *
     * <p>Only the specified handler receives the event via this path; other handlers are not
     * notified. If the handler is not registered, this is a no-op.
     *
     * @param handler the specific handler to call
     * @param event the event to deliver
     */
    public void call(Handler<E> handler, E event) {
        var done = new CompletableFuture<Void>();
        proc.tell(new Msg.Call<>(handler, event, done));
        try {
            done.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("call failed", e);
        }
    }

    /**
     * Shut down the event manager — mirrors OTP {@code gen_event:stop/1}.
     *
     * <p>Calls {@link Handler#terminate(Throwable)} on all registered handlers, then stops the
     * manager process.
     */
    public void stop() {
        proc.tell(new Msg.Stop<>());
        try {
            proc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
