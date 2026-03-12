package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import io.github.seanchatmangpt.jotp.EventManager;

/**
 * ATLAS Advanced Streams broker — OTP {@code gen_event} fan-out to Kafka, InfluxDB, and
 * other engineering data consumers.
 *
 * <p>In ATLAS, <em>Advanced Streams</em> is the open-data fan-out layer that sits above the SQL
 * Race database. Live engineering values from the RTA gRPC stream are broadcast via a Kafka broker
 * to multiple downstream sinks:
 *
 * <ul>
 *   <li>Grafana live dashboards (via InfluxDB connector)
 *   <li>Custom post-processing pipelines
 *   <li>ATLAS 10 live display plugins (Display API)
 *   <li>Remote team factories (over WAN replication)
 * </ul>
 *
 * <p>This Java 26 / OTP refactor uses an {@link EventManager}{@code <StreamEvent>} to replicate
 * the fan-out semantics. A crashing consumer handler is removed without affecting the stream —
 * exactly OTP's {@code gen_event} fault-isolation guarantee:
 *
 * <pre>{@code
 * var streams = AdvancedStreams.start();
 * streams.addSink(new KafkaSinkHandler("telemetry"));
 * streams.addSink(new InfluxDbSinkHandler("http://influx:8086"));
 *
 * // Broadcast a batch of vCar samples to all sinks:
 * streams.publish(new StreamEvent.ParameterData(
 *     "vCar:Chassis",
 *     ParameterValues.single(timestampNs, 237.5)));
 * }</pre>
 *
 * <h2>Stream events</h2>
 *
 * <p>{@link StreamEvent} is the sealed event hierarchy broadcast to all registered sinks.
 * In the real ATLAS Advanced Streams library this is the "open data format" schema sent over Kafka.
 */
public final class AdvancedStreams {

    // ---------------------------------------------------------------------------
    // Stream events (sealed — mirrors the ATLAS Advanced Streams data format)
    // ---------------------------------------------------------------------------

    /**
     * Typed stream event hierarchy — the open data format for ATLAS Advanced Streams.
     *
     * <p>In the real system, these map to Kafka topic messages and gRPC proto payloads
     * ({@code MA.Streaming.Proto.Core}).
     */
    public sealed interface StreamEvent
            permits StreamEvent.SessionStart,
                    StreamEvent.ParameterData,
                    StreamEvent.LapCompleted,
                    StreamEvent.SessionEnd {

        /**
         * A new session has been opened by the ATLAS data server recorder.
         *
         * @param summary metadata about the newly opened session
         */
        record SessionStart(SqlRaceSessionSummary summary) implements StreamEvent {}

        /**
         * A batch of telemetry samples for a single parameter.
         *
         * <p>In the real Kafka stream, each message contains up to 256 samples to amortise
         * per-message overhead at 1 kHz rates.
         *
         * @param paramIdentifier SQL Race identifier (e.g. {@code "vCar:Chassis"})
         * @param values          timestamped sample batch
         */
        record ParameterData(String paramIdentifier, ParameterValues values)
                implements StreamEvent {}

        /**
         * The lap detector has committed a new lap.
         *
         * @param lap the completed lap record
         */
        record LapCompleted(SqlRaceLap lap) implements StreamEvent {}

        /**
         * The session has been closed and archived to the SQL Race database.
         *
         * @param key the session identity for downstream reconciliation
         */
        record SessionEnd(SqlRaceSessionKey key) implements StreamEvent {}
    }

    // ---------------------------------------------------------------------------
    // Built-in sink handlers (stubs — demonstrate the gen_event Handler pattern)
    // ---------------------------------------------------------------------------

    /**
     * Kafka sink handler — publishes events to a Kafka broker topic.
     *
     * <p>In the real implementation this wraps the {@code MA.Streaming.Core} gRPC client
     * ({@code MA.Streaming.Proto.ServerComponent} NuGet package).
     *
     * <p>Demonstrates gen_event handler fault isolation: if the Kafka broker is unreachable,
     * this handler throws and is removed from the event manager without affecting other sinks.
     */
    public static final class KafkaSinkHandler implements EventManager.Handler<StreamEvent> {
        private final String topic;
        private int publishedCount;

        public KafkaSinkHandler(String topic) {
            this.topic = topic;
        }

        @Override
        public void handleEvent(StreamEvent event) {
            // Stub: in production, serialise to protobuf and publish to Kafka broker
            publishedCount++;
        }

        @Override
        public void terminate(Throwable reason) {
            // Connection cleanup
        }

        /** Number of events published (for testing). */
        public int publishedCount() {
            return publishedCount;
        }

        /** Kafka topic this handler publishes to. */
        public String topic() {
            return topic;
        }
    }

    /**
     * InfluxDB sink handler — writes telemetry to InfluxDB for Grafana dashboards.
     *
     * <p>In the real ATLAS system, the InfluxDB connector is one of the standard Advanced Streams
     * sinks, enabling live Grafana lap-time overlays during FP sessions.
     */
    public static final class InfluxDbSinkHandler implements EventManager.Handler<StreamEvent> {
        private final String endpoint;
        private int writtenSamples;

        public InfluxDbSinkHandler(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void handleEvent(StreamEvent event) {
            if (event instanceof StreamEvent.ParameterData(var id, var values)) {
                writtenSamples += values.count();
            }
        }

        /** Total samples written to InfluxDB (for testing). */
        public int writtenSamples() {
            return writtenSamples;
        }
    }

    // ---------------------------------------------------------------------------
    // Manager
    // ---------------------------------------------------------------------------

    private final EventManager<StreamEvent> manager;

    private AdvancedStreams(EventManager<StreamEvent> manager) {
        this.manager = manager;
    }

    /**
     * Start the Advanced Streams broker.
     *
     * @return running broker
     */
    public static AdvancedStreams start() {
        return new AdvancedStreams(EventManager.start());
    }

    /**
     * Register a downstream sink handler — mirrors {@code gen_event:add_handler/3}.
     *
     * @param sink a handler that consumes {@link StreamEvent}s
     */
    public void addSink(EventManager.Handler<StreamEvent> sink) {
        manager.addHandler(sink);
    }

    /**
     * Remove a sink — mirrors {@code gen_event:delete_handler/3}.
     *
     * @param sink the sink to remove
     * @return {@code true} if removed
     */
    public boolean removeSink(EventManager.Handler<StreamEvent> sink) {
        return manager.deleteHandler(sink);
    }

    /**
     * Publish a stream event to all registered sinks asynchronously —
     * mirrors {@code gen_event:notify/2}.
     *
     * @param event the event to broadcast
     */
    public void publish(StreamEvent event) {
        manager.notify(event);
    }

    /**
     * Publish synchronously and wait for all sinks to complete —
     * mirrors {@code gen_event:sync_notify/2}.
     *
     * @param event the event to broadcast
     * @throws InterruptedException if interrupted
     */
    public void publishSync(StreamEvent event) throws InterruptedException {
        manager.syncNotify(event);
    }

    /**
     * Stop the broker and all registered sinks.
     */
    public void stop() {
        manager.stop();
    }
}
