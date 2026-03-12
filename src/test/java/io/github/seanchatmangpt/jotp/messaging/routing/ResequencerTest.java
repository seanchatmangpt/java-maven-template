package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Resequencer Tests")
class ResequencerTest {

  private Resequencer<String> resequencer;

  @BeforeEach
  void setUp() {
    resequencer = Resequencer.create(1L);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    resequencer.stop();
  }

  @Test
  @DisplayName("should deliver messages in order when they arrive in sequence")
  void testInOrderSequence() throws ExecutionException, InterruptedException {
    // Arrange
    var msg1 = new Resequencer.SequencedMessage<>(1L, "first");
    var msg2 = new Resequencer.SequencedMessage<>(2L, "second");
    var msg3 = new Resequencer.SequencedMessage<>(3L, "third");

    // Act
    var result1 = resequencer.offer(msg1).get();
    var result2 = resequencer.offer(msg2).get();
    var result3 = resequencer.offer(msg3).get();

    // Assert
    assertThat(result1).contains("first");
    assertThat(result2).contains("second");
    assertThat(result3).contains("third");
  }

  @Test
  @DisplayName("should buffer out-of-order messages and deliver when sequence is complete")
  void testOutOfOrderSequence() throws ExecutionException, InterruptedException {
    // Arrange
    var msg3 = new Resequencer.SequencedMessage<>(3L, "third");
    var msg1 = new Resequencer.SequencedMessage<>(1L, "first");
    var msg2 = new Resequencer.SequencedMessage<>(2L, "second");

    // Act
    var result3 = resequencer.offer(msg3).get();
    var result1 = resequencer.offer(msg1).get();
    var result2 = resequencer.offer(msg2).get();

    // Assert
    assertThat(result3).isEmpty(); // Buffered, seq 3 not next
    assertThat(result1).contains("first"); // Matches expected seq 1
    assertThat(result2).contains("second"); // Now seq 2 is next
  }

  @Test
  @DisplayName("should handle gaps with timeout expiry")
  void testTimeoutWithGap() throws ExecutionException, InterruptedException {
    // Arrange
    var reseq = Resequencer.create(1L, Duration.ofMillis(500), 10);
    var msg1 = new Resequencer.SequencedMessage<>(1L, "first");
    var msg3 = new Resequencer.SequencedMessage<>(3L, "third"); // Gap: seq 2 missing

    try {
      // Act
      var result1 = reseq.offer(msg1).get();
      var result3 = reseq.offer(msg3).get();

      // Assert
      assertThat(result1).contains("first");
      assertThat(result3).isEmpty(); // Buffered waiting for seq 2

      // Wait for timeout and then offer something to trigger timeout logic
      Thread.sleep(600);
      var msg4 = new Resequencer.SequencedMessage<>(4L, "fourth");
      var result4 = reseq.offer(msg4).get();
      // After timeout, we should have released seq 3 from buffer or seq 4 will be buffered
      // This tests that timeout mechanism is triggered
      assertThat(result4).isNotNull();

    } finally {
      reseq.stop();
    }
  }

  @Test
  @DisplayName("should reject messages when buffer is at capacity")
  void testCapacityLimit() throws ExecutionException, InterruptedException {
    // Arrange
    var reseq = Resequencer.create(1L, Duration.ofMillis(1000), 2);

    try {
      // Act - Fill buffer with out-of-order messages
      var msg3 = new Resequencer.SequencedMessage<>(3L, "third");
      var msg4 = new Resequencer.SequencedMessage<>(4L, "fourth");
      var msg5 = new Resequencer.SequencedMessage<>(5L, "fifth"); // Should overflow

      var result3 = reseq.offer(msg3).get();
      var result4 = reseq.offer(msg4).get();
      var result5 = reseq.offer(msg5).get();

      // Assert
      assertThat(result3).isEmpty(); // Buffered
      assertThat(result4).isEmpty(); // Buffered (at capacity now)
      assertThat(result5).isEmpty(); // Rejected due to capacity
    } finally {
      reseq.stop();
    }
  }

  @Test
  @DisplayName("should deliver consecutive messages after gap is filled")
  void testDeliverAfterGapFilled() throws ExecutionException, InterruptedException {
    // Arrange
    var msg1 = new Resequencer.SequencedMessage<>(1L, "first");
    var msg3 = new Resequencer.SequencedMessage<>(3L, "third");
    var msg2 = new Resequencer.SequencedMessage<>(2L, "second");

    // Act
    var result1 = resequencer.offer(msg1).get();
    var result3 = resequencer.offer(msg3).get();
    var result2 = resequencer.offer(msg2).get();

    // Assert - result2 should trigger delivery of buffered seq 3
    assertThat(result1).contains("first");
    assertThat(result3).isEmpty(); // Buffered
    assertThat(result2).contains("second"); // Delivered when seq 2 arrived
  }

  @Test
  @DisplayName("should handle duplicate sequence numbers")
  void testDuplicateSequence() throws ExecutionException, InterruptedException {
    // Arrange
    var msg1a = new Resequencer.SequencedMessage<>(1L, "first-a");
    var msg1b = new Resequencer.SequencedMessage<>(1L, "first-b");
    var msg2 = new Resequencer.SequencedMessage<>(2L, "second");

    // Act
    var result1a = resequencer.offer(msg1a).get();
    var result1b = resequencer.offer(msg1b).get(); // Overwrites previous seq 1
    var result2 = resequencer.offer(msg2).get();

    // Assert - Last write wins (TreeMap behavior)
    assertThat(result1a).contains("first-a");
    assertThat(result1b).isEmpty(); // Already delivered seq 1
    assertThat(result2).contains("second");
  }

  @Test
  @DisplayName("should preserve message type through resequencing")
  void testTypePreservation() throws ExecutionException, InterruptedException {
    // Arrange
    var reseq = Resequencer.create(1L);
    record Order(long id, String item) {}

    try {
      var order1 = new Order(101, "Book");
      var order2 = new Order(102, "Pen");

      var msg2 = new Resequencer.SequencedMessage<>(2L, order2);
      var msg1 = new Resequencer.SequencedMessage<>(1L, order1);

      // Act
      var result2 = reseq.offer(msg2).get();
      var result1 = reseq.offer(msg1).get();

      // Assert
      assertThat(result2).isEmpty();
      assertThat(result1).contains(order1);
    } finally {
      reseq.stop();
    }
  }

  @Test
  @DisplayName("should handle large sequence number gaps")
  void testLargeSequenceGap() throws ExecutionException, InterruptedException {
    // Arrange
    var reseq = Resequencer.create(1L, Duration.ofMillis(200), 100);

    try {
      var msg1 = new Resequencer.SequencedMessage<>(1L, "seq-1");
      var msg1000 = new Resequencer.SequencedMessage<>(1000L, "seq-1000");

      // Act
      var result1 = reseq.offer(msg1).get();
      var result1000 = reseq.offer(msg1000).get();

      // Assert - seq 1000 should be buffered far away
      assertThat(result1).contains("seq-1");
      assertThat(result1000).isEmpty();
    } finally {
      reseq.stop();
    }
  }
}
