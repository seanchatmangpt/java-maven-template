package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable example: Invoice workflow using Routing Slip.
 *
 * <p>Scenario: An invoice message flows through a series of processing steps:
 * validation → approval → payment → archive. Each step is a process with its own
 * state and logic. The invoice carries a routing slip (list of next steps) that guides its
 * journey.
 *
 * <p>Features demonstrated:
 *
 * <ul>
 *   <li>Declarative routing via embedded slip
 *   <li>Immutable message envelopes
 *   <li>Sequential workflow coordination
 *   <li>Message enrichment at each step
 *   <li>Fire-and-forget and sync execution modes
 * </ul>
 */
public class RoutingSlipExample {

    /** Invoice payload: order details. */
    record Invoice(
            String invoiceId,
            double amount,
            String vendor,
            Instant createdAt,
            java.util.List<String> auditLog) {

        /**
         * Create a new Invoice with an appended audit log entry.
         *
         * @param entry the audit log entry
         * @return new Invoice with entry added
         */
        public Invoice withAuditLog(String entry) {
            var log = new ArrayList<>(auditLog);
            log.add("[" + Instant.now() + "] " + entry);
            return new Invoice(invoiceId, amount, vendor, createdAt, log);
        }
    }

    /** Process state for each workflow step. */
    record ProcessState(String stepName, long processedCount) {}

    /** Messages for each workflow step. */
    sealed interface WorkflowMessage {
        record ProcessInvoice(Invoice invoice) implements WorkflowMessage {}

        record Stats() implements WorkflowMessage {}
    }

    /**
     * Validator process: checks invoice format and amount.
     *
     * @param state process state
     * @param msg workflow message
     * @return updated state with processedCount incremented
     */
    private static ProcessState validatorHandler(ProcessState state, WorkflowMessage msg) {
        return switch (msg) {
            case WorkflowMessage.ProcessInvoice req -> {
                var invoice = req.invoice();
                System.out.println("[Validator] Processing invoice " + invoice.invoiceId());

                // Simulate validation logic
                if (invoice.amount() <= 0) {
                    System.out.println("[Validator] ERROR: Invalid amount " + invoice.amount());
                } else if (invoice.vendor() == null || invoice.vendor().isBlank()) {
                    System.out.println("[Validator] ERROR: Missing vendor");
                } else {
                    System.out.println("[Validator] OK: Invoice passed validation");
                }

                try {
                    Thread.sleep(200); // Simulate processing
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                yield new ProcessState(state.stepName(), state.processedCount() + 1);
            }
            case WorkflowMessage.Stats stats -> {
                System.out.println("[Validator] Stats: processed " + state.processedCount());
                yield state;
            }
        };
    }

    /**
     * Approver process: verifies budgets and approvals.
     *
     * @param state process state
     * @param msg workflow message
     * @return updated state with processedCount incremented
     */
    private static ProcessState approverHandler(ProcessState state, WorkflowMessage msg) {
        return switch (msg) {
            case WorkflowMessage.ProcessInvoice req -> {
                var invoice = req.invoice();
                System.out.println("[Approver] Processing invoice " + invoice.invoiceId());

                // Simulate approval logic
                if (invoice.amount() > 10000) {
                    System.out.println("[Approver] HIGH VALUE: Requires manual approval ($"
                            + invoice.amount() + ")");
                } else {
                    System.out.println("[Approver] AUTO-APPROVED: Within spending limit ($"
                            + invoice.amount() + ")");
                }

                try {
                    Thread.sleep(300); // Simulate processing
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                yield new ProcessState(state.stepName(), state.processedCount() + 1);
            }
            case WorkflowMessage.Stats stats -> {
                System.out.println("[Approver] Stats: processed " + state.processedCount());
                yield state;
            }
        };
    }

    /**
     * Payment processor: initiates payment.
     *
     * @param state process state
     * @param msg workflow message
     * @return updated state with processedCount incremented
     */
    private static ProcessState paymentHandler(ProcessState state, WorkflowMessage msg) {
        return switch (msg) {
            case WorkflowMessage.ProcessInvoice req -> {
                var invoice = req.invoice();
                System.out.println("[Payment] Processing invoice " + invoice.invoiceId());

                // Simulate payment logic
                String confirmationId = "PAY-" + System.currentTimeMillis();
                System.out.println("[Payment] PAYMENT INITIATED: " + confirmationId + " for $"
                        + invoice.amount());

                try {
                    Thread.sleep(400); // Simulate payment processing
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                yield new ProcessState(state.stepName(), state.processedCount() + 1);
            }
            case WorkflowMessage.Stats stats -> {
                System.out.println("[Payment] Stats: processed " + state.processedCount());
                yield state;
            }
        };
    }

    /**
     * Archive process: logs and stores invoice.
     *
     * @param state process state
     * @param msg workflow message
     * @return updated state with processedCount incremented
     */
    private static ProcessState archiveHandler(ProcessState state, WorkflowMessage msg) {
        return switch (msg) {
            case WorkflowMessage.ProcessInvoice req -> {
                var invoice = req.invoice();
                System.out.println("[Archive] Processing invoice " + invoice.invoiceId());

                // Simulate archival
                System.out.println("[Archive] STORED: Invoice archived with " + invoice
                        .auditLog().size() + " audit entries");
                invoice.auditLog().forEach(entry -> System.out.println("  " + entry));

                try {
                    Thread.sleep(200); // Simulate processing
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                yield new ProcessState(state.stepName(), state.processedCount() + 1);
            }
            case WorkflowMessage.Stats stats -> {
                System.out.println("[Archive] Stats: processed " + state.processedCount());
                yield state;
            }
        };
    }

    /**
     * Main: run invoice workflow example.
     *
     * @param args ignored
     * @throws InterruptedException if interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Routing Slip Example: Invoice Workflow ===\n");

        // Create workflow processes
        var validator =
                new Proc<>(
                        new ProcessState("Validator", 0), RoutingSlipExample::validatorHandler);
        var approver =
                new Proc<>(new ProcessState("Approver", 0), RoutingSlipExample::approverHandler);
        var payment =
                new Proc<>(new ProcessState("Payment", 0), RoutingSlipExample::paymentHandler);
        var archive =
                new Proc<>(new ProcessState("Archive", 0), RoutingSlipExample::archiveHandler);

        // Create process references
        @SuppressWarnings("unchecked")
        var validatorRef = (ProcRef<ProcessState, Object>) (ProcRef) new ProcRef<>(validator);
        @SuppressWarnings("unchecked")
        var approverRef = (ProcRef<ProcessState, Object>) (ProcRef) new ProcRef<>(approver);
        @SuppressWarnings("unchecked")
        var paymentRef = (ProcRef<ProcessState, Object>) (ProcRef) new ProcRef<>(payment);
        @SuppressWarnings("unchecked")
        var archiveRef = (ProcRef<ProcessState, Object>) (ProcRef) new ProcRef<>(archive);

        System.out.println("Created workflow processes:");
        System.out.println("  1. Validator");
        System.out.println("  2. Approver");
        System.out.println("  3. Payment");
        System.out.println("  4. Archive\n");

        // Create invoices
        var invoice1 =
                new Invoice(
                        "INV-001",
                        5000.0,
                        "Acme Corp",
                        Instant.now(),
                        new ArrayList<>(List.of("Created")));
        var invoice2 =
                new Invoice(
                        "INV-002",
                        15000.0,
                        "TechVendor Inc",
                        Instant.now(),
                        new ArrayList<>(List.of("Created")));

        System.out.println("Processing invoices with routing slip:\n");

        // Create routing slip: validator -> approver -> payment -> archive
        var slip =
                List.of(validatorRef, approverRef, paymentRef, archiveRef);

        // Invoice 1: standard path
        System.out.println("--- Invoice 1 (5000.0) ---");
        var msgWithSlip1 =
                RoutingSlip.withSlip(
                        new WorkflowMessage.ProcessInvoice(invoice1), slip);

        System.out.println("Routing slip has " + msgWithSlip1.remainingHops() + " hops:");
        msgWithSlip1.slip().forEach(ref -> System.out.println("  -> " + ref));
        System.out.println();

        // Execute slip (fire-and-forget)
        var result1 = RoutingSlip.executeSlip(msgWithSlip1);
        System.out.println("Slip execution result: "
                + (result1 instanceof Result.Ok ? "SUCCESS" : "FAILED"));

        Thread.sleep(2000); // Wait for async processing

        System.out.println("\n--- Invoice 2 (15000.0) ---");
        var msgWithSlip2 =
                RoutingSlip.withSlip(
                        new WorkflowMessage.ProcessInvoice(invoice2), slip);
        var result2 = RoutingSlip.executeSlip(msgWithSlip2);
        System.out.println("Slip execution result: "
                + (result2 instanceof Result.Ok ? "SUCCESS" : "FAILED"));

        Thread.sleep(2000); // Wait for async processing

        System.out.println("\n--- Statistics ---");
        validator.tell(new WorkflowMessage.Stats());
        approver.tell(new WorkflowMessage.Stats());
        payment.tell(new WorkflowMessage.Stats());
        archive.tell(new WorkflowMessage.Stats());

        Thread.sleep(500);

        System.out.println("\nShutting down workflow...");
        validator.stop();
        approver.stop();
        payment.stop();
        archive.stop();

        System.out.println("Done.");
    }
}
