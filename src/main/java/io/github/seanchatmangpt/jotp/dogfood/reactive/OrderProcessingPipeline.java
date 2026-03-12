package io.github.seanchatmangpt.jotp.dogfood.reactive;

import java.util.List;
import io.github.seanchatmangpt.jotp.reactive.DeadLetterChannel;
import io.github.seanchatmangpt.jotp.reactive.MessageAggregator;
import io.github.seanchatmangpt.jotp.reactive.MessageDispatcher;
import io.github.seanchatmangpt.jotp.reactive.MessageFilter;
import io.github.seanchatmangpt.jotp.reactive.MessagePipeline;
import io.github.seanchatmangpt.jotp.reactive.MessageRouter;
import io.github.seanchatmangpt.jotp.reactive.MessageSplitter;
import io.github.seanchatmangpt.jotp.reactive.MessageTransformer;
import io.github.seanchatmangpt.jotp.reactive.PointToPointChannel;
import io.github.seanchatmangpt.jotp.reactive.PublishSubscribeChannel;

/**
 * Dogfood: reactive messaging patterns assembled into a real-world e-commerce order processing
 * pipeline.
 *
 * <p>This class demonstrates how all ten EIP primitives compose into a cohesive system topology.
 * The pipeline handles the lifecycle of an e-commerce order from raw HTTP payload through
 * validation, enrichment, routing, fulfilment, and audit — all wired via typed message channels
 * with zero shared mutable state between stages.
 *
 * <h2>Pipeline Topology</h2>
 *
 * <pre>
 *  [HTTP layer] → RawRequest
 *       ↓ (pipeline: parse → validate → enrich)
 *  ValidatedOrder
 *       ↓ (router)
 *  ┌─────────────────────────────────────────────────────────┐
 *  │ ExpressOrder → ExpressDispatcher (3 competing workers)  │
 *  │ StandardOrder → aggregator → FullBatch → fulfilment     │
 *  │ InvalidOrder → DeadLetterChannel                        │
 *  └─────────────────────────────────────────────────────────┘
 *       ↓ (pub-sub audit bus)
 *  [AuditLog] [MetricsCollector] [FraudDetector]
 * </pre>
 *
 * <p>This is the "eating-our-own-dogfood" validation that all ten reactive primitives compile and
 * integrate correctly in Java 26 JPMS.
 */
public final class OrderProcessingPipeline {

    // ── Domain model ──────────────────────────────────────────────────────────

    /** Raw request arriving from the HTTP layer — unvalidated. */
    public record RawRequest(String customerId, String itemId, int quantity, boolean express) {}

    /** A validated, enriched order ready for fulfilment. */
    public sealed interface ValidatedOrder
            permits ValidatedOrder.ExpressOrder, ValidatedOrder.StandardOrder {
        record ExpressOrder(String orderId, String customerId, String itemId, int qty)
                implements ValidatedOrder {}

        record StandardOrder(String orderId, String customerId, String itemId, int qty)
                implements ValidatedOrder {}
    }

    /** A line item inside a standard-order batch. */
    public record BatchLine(String batchId, String customerId, String itemId, int qty) {}

    /** A fully assembled batch of standard orders ready for warehouse pick-and-pack. */
    public record FullBatch(String batchId, List<BatchLine> lines) {}

    /** An audit event published to all downstream audit consumers. */
    public record AuditEvent(String orderId, String outcome, long epochMs) {}

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Assemble and start the full order processing pipeline.
     *
     * <p>Returns the {@link PointToPointChannel} entry point. Callers push {@link RawRequest}
     * messages in; the pipeline handles everything else.
     *
     * @param auditBus the pub-sub bus receiving {@link AuditEvent} for every processed order
     * @param deadLetter the dead-letter channel capturing invalid orders
     * @param batchSize how many standard order lines constitute a full batch
     * @return the pipeline entry-point channel
     */
    public static PointToPointChannel<RawRequest> assemble(
            PublishSubscribeChannel<AuditEvent> auditBus,
            DeadLetterChannel<ValidatedOrder> deadLetter,
            int batchSize) {

        // ── Stage 5: fulfilment channels ──────────────────────────────────────

        // Express orders: 3 competing workers (virtual-thread pool)
        var expressDispatcher =
                MessageDispatcher.<ValidatedOrder>builder()
                        .workers(
                                3,
                                order -> {
                                    var id = switch (order) {
                                        case ValidatedOrder.ExpressOrder e -> e.orderId();
                                        case ValidatedOrder.StandardOrder s -> s.orderId();
                                    };
                                    auditBus.send(new AuditEvent(id, "express-dispatched", System.currentTimeMillis()));
                                })
                        .build();

        // Standard orders: aggregate N lines into a batch, then fulfil
        var batchFulfilment =
                new PointToPointChannel<FullBatch>(
                        batch ->
                                auditBus.send(
                                        new AuditEvent(
                                                batch.batchId(),
                                                "batch-fulfilled",
                                                System.currentTimeMillis())));

        var splitter =
                MessageSplitter.<FullBatch, BatchLine>of(FullBatch::lines, new PointToPointChannel<>(line -> {}));

        var standardAggregator =
                MessageAggregator.<ValidatedOrder, FullBatch>builder()
                        .correlateBy(
                                order ->
                                        switch (order) {
                                            case ValidatedOrder.ExpressOrder e -> e.orderId();
                                            case ValidatedOrder.StandardOrder s -> "batch-" + (s.orderId().hashCode() % 10);
                                        })
                        .completeWhen(lines -> lines.size() >= batchSize)
                        .aggregateWith(
                                lines -> {
                                    var first = lines.getFirst();
                                    var batchId = switch (first) {
                                        case ValidatedOrder.ExpressOrder e -> e.orderId();
                                        case ValidatedOrder.StandardOrder s -> "batch-" + (s.orderId().hashCode() % 10);
                                    };
                                    var batchLines =
                                            lines.stream()
                                                    .map(
                                                            o ->
                                                                    switch (o) {
                                                                        case ValidatedOrder.ExpressOrder e ->
                                                                                new BatchLine(batchId, e.customerId(), e.itemId(), e.qty());
                                                                        case ValidatedOrder.StandardOrder s ->
                                                                                new BatchLine(batchId, s.customerId(), s.itemId(), s.qty());
                                                                    })
                                                    .toList();
                                    return new FullBatch(batchId, batchLines);
                                })
                        .downstream(batchFulfilment)
                        .build();

        // ── Stage 4: content-based router ────────────────────────────────────

        var router =
                MessageRouter.<ValidatedOrder>builder()
                        .route(o -> o instanceof ValidatedOrder.ExpressOrder, expressDispatcher)
                        .route(o -> o instanceof ValidatedOrder.StandardOrder, standardAggregator)
                        .otherwise(deadLetter)
                        .build();

        // ── Stage 3: transformer (RawRequest → ValidatedOrder) ─────────────

        var deadRaw = new DeadLetterChannel<RawRequest>();
        var transformerChannel =
                MessageTransformer.<RawRequest, ValidatedOrder>of(
                        raw -> {
                            if (raw.quantity() <= 0 || raw.customerId() == null || raw.customerId().isBlank()) {
                                throw new IllegalArgumentException("Invalid request: " + raw);
                            }
                            String orderId = raw.customerId() + "-" + raw.itemId() + "-" + System.nanoTime();
                            return raw.express()
                                    ? new ValidatedOrder.ExpressOrder(orderId, raw.customerId(), raw.itemId(), raw.quantity())
                                    : new ValidatedOrder.StandardOrder(orderId, raw.customerId(), raw.itemId(), raw.quantity());
                        },
                        router,
                        deadRaw);

        // ── Stage 2: filter (drop zero-quantity requests early) ────────────

        var filterChannel =
                MessageFilter.<RawRequest>of(
                        raw -> raw.quantity() > 0 && raw.customerId() != null,
                        transformerChannel,
                        deadRaw);

        // ── Stage 1: entry pipeline (parse + validate) ────────────────────

        var entry = new PointToPointChannel<RawRequest>(filterChannel::send);
        return entry;
    }

    /** Demonstrate the assembled pipeline with sample orders. */
    public static void main(String[] args) throws InterruptedException {
        var auditBus = new PublishSubscribeChannel<AuditEvent>();
        var dead = new DeadLetterChannel<ValidatedOrder>();

        // Subscribe an audit logger
        auditBus.subscribe(event -> System.out.printf("[AUDIT] %s → %s%n", event.orderId(), event.outcome()));

        var pipeline = assemble(auditBus, dead, 2);

        // Send a mix of valid express, standard, and invalid orders
        pipeline.send(new RawRequest("cust-A", "item-1", 2, true));
        pipeline.send(new RawRequest("cust-B", "item-2", 1, false));
        pipeline.send(new RawRequest("cust-C", "item-3", 0, false));  // invalid → dead
        pipeline.send(new RawRequest("cust-D", "item-4", 3, false));  // standard batch completes at 2

        Thread.sleep(500);

        System.out.printf("[DEAD-LETTER] %d undeliverable orders%n", dead.size());
        pipeline.stop();
        auditBus.stop();
    }
}
