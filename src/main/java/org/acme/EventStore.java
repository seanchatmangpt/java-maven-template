package org.acme;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.function.Consumer;

/**
 * Event Store — append-only log for event sourcing with projections.
 *
 * <p>Event Sourcing Pattern: "Persist the state of a business entity as a sequence
 * of state-changing events. Reconstruct current state by replaying events."
 *
 * <p>Joe Armstrong: "In Erlang, you don't persist state — you persist the messages
 * that led to the state. Recovery is replay. This is the essence of event sourcing."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Append-only</b> — Events are never modified, only appended</li>
 *   <li><b>Stream per aggregate</b> — Each entity has its own event stream</li>
 *   <li><b>Projections</b> — Build read models from event streams</li>
 *   <li><b>Snapshots</b> — Optimize replay by storing periodic state</li>
 *   <li><b>Subscriptions</b> — Real-time notification of new events</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * EventStore store = EventStore.create();
 *
 * // Append events to a stream
 * store.append("order-123", List.of(
 *     new OrderCreated(...),
 *     new ItemAdded(...),
 *     new OrderSubmitted(...)
 * ));
 *
 * // Load events for replay
 * Stream<Event> events = store.load("order-123");
 *
 * // Subscribe to new events
 * store.subscribe("order-123", event -> handleEvent(event));
 *
 * // Build a projection
 * store.addProjection("order-summary", new OrderSummaryProjection());
 * }</pre>
 */
public final class EventStore implements Application.Infrastructure {

    /** Stored event with metadata. */
    public record StoredEvent(
            String streamId,
            long version,
            Object event,
            Instant timestamp,
            UUID eventId,
            Map<String, String> metadata) {

        public static StoredEvent of(String streamId, long version, Object event) {
            return new StoredEvent(streamId, version, event, Instant.now(), UUID.randomUUID(), Map.of());
        }

        public static StoredEvent of(String streamId, long version, Object event, Map<String, String> metadata) {
            return new StoredEvent(streamId, version, event, Instant.now(), UUID.randomUUID(), metadata);
        }
    }

    /** Subscription handle. */
    public interface Subscription {
        String id();
        void cancel();
        boolean isActive();
    }

    /** Projection interface for building read models. */
    public interface Projection {
        String name();
        void apply(StoredEvent event);
        void reset();
    }

    /** Event store statistics. */
    public record Stats(
            long totalEvents,
            int streams,
            int projections,
            int subscriptions) {}

    // ── Internal state ──────────────────────────────────────────────────────────

    private final String name;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<StoredEvent>> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Projection> projections = new ConcurrentHashMap<>();
    private final List<StreamSubscription> allSubscriptions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicLong totalEvents = new AtomicLong(0);

    private static final class StreamSubscription {
            final String id;
            final String streamId;
            final Consumer<StoredEvent> handler;
            volatile boolean active;
            StreamSubscription(String id, String streamId, Consumer<StoredEvent> handler, boolean active) {
                this.id = id;
                this.streamId = streamId;
                this.handler = handler;
                this.active = active;
            }
        }

    // ─-- Constructor ─────────────────────────────────────────────────────────────

    private EventStore(String name) {
        this.name = name;
    }

    // ── Factory methods ──────────────────────────────────────────────────────────

    public static EventStore create() {
        return new EventStore("event-store");
    }

    public static EventStore create(String name) {
        return new EventStore(name);
    }

    // ── Append operations ────────────────────────────────────────────────────────

    /**
     * Append events to a stream.
     *
     * @param streamId the stream identifier (e.g., aggregate ID)
     * @param events the events to append
     * @return the new stream version
     */
    public long append(String streamId, List<?> events) {
        AtomicLong version = versions.computeIfAbsent(streamId, k -> new AtomicLong(0));
        ConcurrentLinkedQueue<StoredEvent> stream = streams.computeIfAbsent(streamId, k -> new ConcurrentLinkedQueue<>());

        long newVersion = 0;
        for (Object event : events) {
            newVersion = version.incrementAndGet();
            StoredEvent stored = StoredEvent.of(streamId, newVersion, event);
            stream.add(stored);
            totalEvents.incrementAndGet();

            // Notify subscribers
            notifySubscribers(streamId, stored);

            // Apply to projections
            applyToProjections(stored);
        }

        return newVersion;
    }

    /**
     * Append events with expected version (optimistic concurrency).
     *
     * @throws IllegalStateException if version mismatch
     */
    public long append(String streamId, List<?> events, long expectedVersion) {
        AtomicLong version = versions.computeIfAbsent(streamId, k -> new AtomicLong(0));

        if (version.get() != expectedVersion) {
            throw new IllegalStateException(
                    "Version mismatch: expected " + expectedVersion + " but was " + version.get());
        }

        return append(streamId, events);
    }

    /**
     * Append a single event.
     */
    public long append(String streamId, Object event) {
        return append(streamId, List.of(event));
    }

    // ── Load operations ──────────────────────────────────────────────────────────

    /**
     * Load all events for a stream.
     */
    public Stream<StoredEvent> load(String streamId) {
        Queue<StoredEvent> stream = streams.get(streamId);
        if (stream == null) return Stream.empty();
        return stream.stream();
    }

    /**
     * Load events from a specific version.
     */
    public Stream<StoredEvent> loadFrom(String streamId, long fromVersion) {
        return load(streamId).filter(e -> e.version() > fromVersion);
    }

    /**
     * Load events up to a specific version.
     */
    public Stream<StoredEvent> loadUpTo(String streamId, long toVersion) {
        return load(streamId).filter(e -> e.version() <= toVersion);
    }

    /**
     * Get the current version of a stream.
     */
    public long currentVersion(String streamId) {
        AtomicLong version = versions.get(streamId);
        return version != null ? version.get() : 0;
    }

    /**
     * Check if a stream exists.
     */
    public boolean exists(String streamId) {
        return streams.containsKey(streamId);
    }

    // ── Subscriptions ────────────────────────────────────────────────────────────

    /**
     * Subscribe to all events on a stream.
     */
    public Subscription subscribe(String streamId, Consumer<StoredEvent> handler) {
        String id = UUID.randomUUID().toString();
        StreamSubscription sub = new StreamSubscription(id, streamId, handler, true);
        allSubscriptions.add(sub);

        return new Subscription() {
            @Override public String id() { return id; }
            @Override public void cancel() { sub.active = false; allSubscriptions.remove(sub); }
            @Override public boolean isActive() { return sub.active; }
        };
    }

    /**
     * Subscribe to all events in the store.
     */
    public Subscription subscribeAll(Consumer<StoredEvent> handler) {
        return subscribe("*", handler);
    }

    private void notifySubscribers(String streamId, StoredEvent event) {
        for (StreamSubscription sub : allSubscriptions) {
            if (sub.active && ("*".equals(sub.streamId) || sub.streamId.equals(streamId))) {
                try {
                    sub.handler.accept(event);
                } catch (Exception e) {
                    // Log but don't fail
                    System.err.println("[EventStore] Subscriber error: " + e.getMessage());
                }
            }
        }
    }

    // ── Projections ──────────────────────────────────────────────────────────────

    /**
     * Add a projection.
     */
    public void addProjection(Projection projection) {
        projections.put(projection.name(), projection);
    }

    /**
     * Rebuild a projection from all events.
     */
    public void rebuildProjection(String name) {
        Projection projection = projections.get(name);
        if (projection == null) return;

        projection.reset();
        streams.values().stream()
                .flatMap(Queue::stream)
                .sorted(Comparator.comparing(StoredEvent::timestamp))
                .forEach(projection::apply);
    }

    /**
     * Get a projection by name.
     */
    @SuppressWarnings("unchecked")
    public <T extends Projection> Optional<T> getProjection(String name) {
        return Optional.ofNullable((T) projections.get(name));
    }

    private void applyToProjections(StoredEvent event) {
        for (Projection projection : projections.values()) {
            try {
                projection.apply(event);
            } catch (Exception e) {
                System.err.println("[EventStore] Projection error in " + projection.name() + ": " + e.getMessage());
            }
        }
    }

    // ── Statistics ───────────────────────────────────────────────────────────────

    public Stats stats() {
        return new Stats(
                totalEvents.get(),
                streams.size(),
                projections.size(),
                allSubscriptions.size());
    }

    // ── Infrastructure lifecycle ─────────────────────────────────────────────────

    @Override
    public String name() { return name; }

    @Override
    public void onStop(Application app) {
        allSubscriptions.clear();
    }

    // ── Snapshot support ─────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, Snapshot<?>> snapshots = new ConcurrentHashMap<>();

    /** Store a snapshot for a stream. */
    public <T> void saveSnapshot(String streamId, T state, long version) {
        snapshots.put(streamId, new Snapshot<>(state, version, Instant.now()));
    }

    /** Load the latest snapshot for a stream. */
    @SuppressWarnings("unchecked")
    public <T> Optional<Snapshot<T>> loadSnapshot(String streamId) {
        return Optional.ofNullable((Snapshot<T>) snapshots.get(streamId));
    }

    /** Snapshot record. */
    public record Snapshot<T>(T state, long version, Instant timestamp) {}
}
