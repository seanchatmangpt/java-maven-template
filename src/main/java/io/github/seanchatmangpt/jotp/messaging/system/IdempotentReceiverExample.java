package io.github.seanchatmangpt.jotp.messaging.system;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable example of the Idempotent Receiver pattern.
 *
 * <p>Demonstrates safe message deduplication in a payment system:
 * - Client sends payment confirmation
 * - Network duplicates the same confirmation message
 * - Receiver detects duplicate and safely ignores it
 * - No double-charging occurs
 *
 * <p>Also demonstrates: - Fresh message processing
 * - Duplicate detection
 * - Statistics tracking
 * - LRU history management
 *
 * <p>Run with: java io.github.seanchatmangpt.jotp.messaging.system.IdempotentReceiverExample
 */
public class IdempotentReceiverExample {

  /** Payment message type. */
  record Payment(String id, String account, double amount) {
    @Override
    public String toString() {
      return String.format("Payment{id=%s, account=%s, amount=%.2f}", id, account, amount);
    }
  }

  /** Global counter to track payment processing. */
  static class PaymentProcessor {
    private final ConcurrentHashMap<String, Double> processedPayments = new ConcurrentHashMap<>();
    private final AtomicInteger totalProcessed = new AtomicInteger(0);

    void process(Payment p) {
      processedPayments.put(p.id, p.amount);
      totalProcessed.incrementAndGet();
      System.out.println("  [Processor] ✓ Payment processed: " + p);
    }

    int totalProcessed() {
      return totalProcessed.get();
    }

    boolean wasProcessed(String id) {
      return processedPayments.containsKey(id);
    }

    double getAmount(String id) {
      return processedPayments.getOrDefault(id, 0.0);
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("=== Idempotent Receiver Pattern Example ===\n");

    // Create receiver with history size of 100
    var receiver = IdempotentReceiver.<Payment>create(100);
    var processor = new PaymentProcessor();

    // Scenario 1: Fresh payment arrives
    System.out.println("--- Scenario 1: Fresh Payment ---");
    var paymentId1 = UUID.randomUUID();
    var payment1 = new Payment("PAY-001", "account-123", 50.00);

    System.out.println("Receiving payment: " + payment1);
    var result1 =
        receiver.receive(
            paymentId1,
            payment1,
            (state, p) -> {
              processor.process(p);
              return p;
            });

    result1.fold(
        processingResult -> {
          System.out.println(
              "Result: isDuplicate="
                  + processingResult.isDuplicate()
                  + ", result="
                  + processingResult.result());
          return null;
        },
        error -> {
          System.out.println("Error: " + error);
          return null;
        });

    // Scenario 2: Same payment ID arrives again (network duplicate)
    System.out.println("\n--- Scenario 2: Duplicate Payment (Network Retry) ---");
    System.out.println("Receiving same payment again: " + payment1);
    var result2 =
        receiver.receive(
            paymentId1, // Same ID
            payment1,
            (state, p) -> {
              System.out.println("  [This should NOT print - duplicate detected]");
              processor.process(p);
              return p;
            });

    result2.fold(
        processingResult -> {
          System.out.println(
              "Result: isDuplicate="
                  + processingResult.isDuplicate()
                  + ", result="
                  + processingResult.result());
          if (processingResult.isDuplicate()) {
            System.out.println("  ✓ Duplicate correctly detected, handler not invoked");
          }
          return null;
        },
        error -> {
          System.out.println("Error: " + error);
          return null;
        });

    // Scenario 3: Third duplicate attempt
    System.out.println("\n--- Scenario 3: Third Duplicate Attempt ---");
    System.out.println("Receiving payment again: " + payment1);
    var result3 =
        receiver.receive(
            paymentId1, // Same ID again
            payment1,
            (state, p) -> {
              System.out.println("  [This should NOT print]");
              processor.process(p);
              return p;
            });

    result3.fold(
        processingResult -> {
          System.out.println(
              "Result: isDuplicate="
                  + processingResult.isDuplicate()
                  + ", result="
                  + processingResult.result());
          return null;
        },
        error -> {
          System.out.println("Error: " + error);
          return null;
        });

    // Scenario 4: Different payment arrives (fresh)
    System.out.println("\n--- Scenario 4: Different Fresh Payment ---");
    var paymentId2 = UUID.randomUUID();
    var payment2 = new Payment("PAY-002", "account-456", 75.00);

    System.out.println("Receiving payment: " + payment2);
    var result4 =
        receiver.receive(
            paymentId2, // Different ID
            payment2,
            (state, p) -> {
              processor.process(p);
              return p;
            });

    result4.fold(
        processingResult -> {
          System.out.println(
              "Result: isDuplicate="
                  + processingResult.isDuplicate()
                  + ", result="
                  + processingResult.result());
          return null;
        },
        error -> {
          System.out.println("Error: " + error);
          return null;
        });

    // Scenario 5: Check if IDs are duplicates
    System.out.println("\n--- Scenario 5: Duplicate Status Check ---");
    System.out.println("isDuplicate(paymentId1): " + receiver.isDuplicate(paymentId1));
    System.out.println("isDuplicate(paymentId2): " + receiver.isDuplicate(paymentId2));

    var unknownId = UUID.randomUUID();
    System.out.println("isDuplicate(unknownId): " + receiver.isDuplicate(unknownId));

    // Scenario 6: Statistics
    System.out.println("\n--- Scenario 6: Statistics ---");
    var statsResult = receiver.statistics();
    statsResult.fold(
        stats -> {
          System.out.println("Receiver statistics: " + stats);
          return null;
        },
        error -> {
          System.out.println("Error: " + error);
          return null;
        });

    System.out.println("Tracked message IDs: " + receiver.trackedCount());

    // Verification
    System.out.println("\n--- Verification ---");
    System.out.println("Total payments processed: " + processor.totalProcessed());
    System.out.println("Expected: 2 (only fresh payments)");
    System.out.println("Payment 1 processed: " + processor.wasProcessed("PAY-001"));
    System.out.println("Payment 2 processed: " + processor.wasProcessed("PAY-002"));

    if (processor.totalProcessed() == 2) {
      System.out.println("\n✓ Idempotent receiver prevented duplicate processing!");
    } else {
      System.out.println(
          "\n✗ FAILED: Expected 2 payments processed, got " + processor.totalProcessed());
    }

    // Cleanup
    receiver.stop();

    System.out.println("\n=== Example Complete ===");
  }
}
