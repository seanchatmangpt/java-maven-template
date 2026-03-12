package io.github.seanchatmangpt.jotp.messaging.routing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Resequencer pattern example — simulates out-of-order event delivery from a distributed system.
 *
 * <p><strong>Scenario:</strong> A financial transaction system receives events (order placed,
 * payment processed, inventory updated) out of order due to network delays. The resequencer
 * buffers them and delivers in sequence number order, ensuring causal consistency.
 *
 * <p><strong>Pattern demonstration:</strong>
 *
 * <ul>
 *   <li>Events arrive with sequence numbers in random order
 *   <li>Resequencer accumulates them in a TreeMap
 *   <li>When the next sequence is available, it's delivered
 *   <li>Gaps trigger timeout logic; consecutive delivery ensures ordering guarantees
 * </ul>
 */
public final class ResequencerExample {

  record TransactionEvent(long id, String type, long amount, String description) {
    @Override
    public String toString() {
      return "[SEQ " + id + "] " + type + ": $" + amount + " - " + description;
    }
  }

  public static void main(String[] args) throws Exception {
    System.out.println("===== Resequencer Pattern Example =====\n");
    System.out.println("Scenario: Processing out-of-order financial transactions\n");

    // Create a resequencer with 5-second timeout
    Resequencer<TransactionEvent> resequencer =
        Resequencer.create(1L, Duration.ofMillis(5000), 100);

    // Simulate events arriving out of order
    List<TransactionEvent> events = new ArrayList<>();
    events.add(new TransactionEvent(1, "ORDER_PLACED", 100, "Customer ordered item"));
    events.add(new TransactionEvent(2, "PAYMENT_PROCESSED", 100, "Credit card charged"));
    events.add(new TransactionEvent(3, "INVENTORY_UPDATED", -1, "Item deducted from stock"));
    events.add(new TransactionEvent(4, "SHIPMENT_INITIATED", 0, "Package prepared"));
    events.add(new TransactionEvent(5, "NOTIFICATION_SENT", 0, "Customer notified"));

    System.out.println("Original event sequence (in order):");
    for (TransactionEvent e : events) {
      System.out.println("  " + e);
    }

    System.out.println("\n--- Shuffling events to simulate network delays ---\n");

    // Shuffle events
    List<TransactionEvent> shuffled = new ArrayList<>(events);
    shuffleShuffle(shuffled, new Random(42));

    System.out.println("Events arriving (out of order):");
    for (TransactionEvent e : shuffled) {
      System.out.println("  ARRIVAL: " + e);
    }

    System.out.println("\n--- Processing through Resequencer ---\n");

    // Process events through resequencer
    List<TransactionEvent> delivered = new ArrayList<>();
    List<CompletableFuture<java.util.Optional<TransactionEvent>>> futures = new ArrayList<>();

    for (TransactionEvent event : shuffled) {
      Resequencer.SequencedMessage<TransactionEvent> msg =
          new Resequencer.SequencedMessage<>(event.id(), event);

      CompletableFuture<java.util.Optional<TransactionEvent>> future = resequencer.offer(msg);
      futures.add(future);

      // Small delay to simulate processing time
      Thread.sleep(50);
    }

    // Collect results
    for (CompletableFuture<java.util.Optional<TransactionEvent>> future : futures) {
      var optional = future.join();
      if (optional.isPresent()) {
        TransactionEvent event = optional.get();
        delivered.add(event);
        System.out.println("  DELIVERED: " + event);
      } else {
        System.out.println("  (buffered, waiting for sequence)");
      }
    }

    System.out.println("\n--- Results ---\n");
    System.out.println("Events delivered in order: " + delivered.size() + "/" + events.size());
    System.out.println("Delivery guarantees maintained: " + (delivered.size() == events.size()));

    // Verify order
    boolean inOrder = true;
    for (int i = 0; i < delivered.size(); i++) {
      if (delivered.get(i).id() != i + 1) {
        inOrder = false;
        break;
      }
    }
    System.out.println("Sequence integrity: " + inOrder);

    resequencer.stop();
  }

  private static <T> void shuffleShuffle(List<T> list, Random random) {
    for (int i = list.size() - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      T temp = list.get(i);
      list.set(i, list.get(j));
      list.set(j, temp);
    }
  }
}
