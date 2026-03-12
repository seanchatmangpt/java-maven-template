package io.github.seanchatmangpt.jotp.messaging.system;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit Jupiter tests for IdempotentReceiver pattern.
 *
 * <p>Tests cover:
 * - Fresh message processing
 * - Duplicate detection and suppression
 * - Handler invocation semantics
 * - Statistics tracking
 * - LRU history management
 * - State safety across concurrent operations
 */
class IdempotentReceiverTest {

  record Message(String id, String payload) {}

  private IdempotentReceiver<Message> receiver;

  @BeforeEach
  void setup() {
    receiver = IdempotentReceiver.create(100);
  }

  @AfterEach
  void cleanup() throws InterruptedException {
    if (receiver.isAlive()) {
      receiver.stop();
    }
  }

  @Test
  @DisplayName("Should process fresh message and invoke handler")
  void testFreshMessageProcessing() {
    var messageId = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");
    var handlerInvoked = new AtomicInteger(0);

    var result = receiver.receive(messageId, message, (state, msg) -> {
      handlerInvoked.incrementAndGet();
      return msg;
    });

    assertThat(result.isSuccess()).isTrue();
    result.fold(
        processingResult -> {
          assertThat(processingResult.isDuplicate()).isFalse();
          assertThat(processingResult.result()).isEqualTo(message);
          assertThat(handlerInvoked.get()).isEqualTo(1);
          return null;
        },
        error -> {
          fail("Unexpected error: " + error);
          return null;
        });
  }

  @Test
  @DisplayName("Should detect duplicate and NOT invoke handler")
  void testDuplicateDetectionAndSuppression() {
    var messageId = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");
    var handlerInvokeCount = new AtomicInteger(0);

    // First message (fresh)
    var result1 = receiver.receive(messageId, message, (state, msg) -> {
      handlerInvokeCount.incrementAndGet();
      return msg;
    });

    assertThat(result1.isSuccess()).isTrue();
    result1.fold(
        r1 -> assertThat(r1.isDuplicate()).isFalse(),
        error -> fail("First receive should succeed"));

    assertThat(handlerInvokeCount.get()).isEqualTo(1);

    // Same message ID (duplicate)
    var result2 = receiver.receive(messageId, message, (state, msg) -> {
      handlerInvokeCount.incrementAndGet();
      return msg;
    });

    assertThat(result2.isSuccess()).isTrue();
    result2.fold(
        r2 -> {
          assertThat(r2.isDuplicate()).isTrue();
          assertThat(r2.result()).isNull(); // Null result for duplicates
        },
        error -> fail("Second receive should succeed"));

    // Handler should NOT be called again
    assertThat(handlerInvokeCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should handle multiple duplicates correctly")
  void testMultipleDuplicates() {
    var messageId = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");
    var invocationCount = new AtomicInteger(0);

    // Send message 5 times
    for (int i = 0; i < 5; i++) {
      var result = receiver.receive(messageId, message, (state, msg) -> {
        invocationCount.incrementAndGet();
        return msg;
      });

      assertThat(result.isSuccess()).isTrue();
      if (i == 0) {
        result.fold(
            r -> assertThat(r.isDuplicate()).isFalse(),
            error -> fail("Should succeed"));
      } else {
        result.fold(
            r -> assertThat(r.isDuplicate()).isTrue(),
            error -> fail("Should succeed"));
      }
    }

    // Handler invoked only once
    assertThat(invocationCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should track different message IDs independently")
  void testMultipleMessageTracking() {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var id3 = UUID.randomUUID();

    var msg1 = new Message("MSG-001", "payload1");
    var msg2 = new Message("MSG-002", "payload2");
    var msg3 = new Message("MSG-003", "payload3");

    // Process three different messages
    var result1 = receiver.receive(id1, msg1, (state, msg) -> msg);
    var result2 = receiver.receive(id2, msg2, (state, msg) -> msg);
    var result3 = receiver.receive(id3, msg3, (state, msg) -> msg);

    assertThat(result1.isSuccess()).isTrue();
    assertThat(result2.isSuccess()).isTrue();
    assertThat(result3.isSuccess()).isTrue();

    result1.fold(r -> assertThat(r.isDuplicate()).isFalse(), error -> fail(""));
    result2.fold(r -> assertThat(r.isDuplicate()).isFalse(), error -> fail(""));
    result3.fold(r -> assertThat(r.isDuplicate()).isFalse(), error -> fail(""));

    // Verify tracked count
    assertThat(receiver.trackedCount()).isGreaterThan(0);

    // Now send duplicates
    var dup1 = receiver.receive(id1, msg1, (state, msg) -> msg);
    var dup2 = receiver.receive(id2, msg2, (state, msg) -> msg);
    var dup3 = receiver.receive(id3, msg3, (state, msg) -> msg);

    dup1.fold(r -> assertThat(r.isDuplicate()).isTrue(), error -> fail(""));
    dup2.fold(r -> assertThat(r.isDuplicate()).isTrue(), error -> fail(""));
    dup3.fold(r -> assertThat(r.isDuplicate()).isTrue(), error -> fail(""));
  }

  @Test
  @DisplayName("Should report isDuplicate correctly for known and unknown IDs")
  void testIsDuplicateQuery() {
    var knownId = UUID.randomUUID();
    var unknownId = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");

    // Process one message
    receiver.receive(knownId, message, (state, msg) -> msg);

    // Check status
    assertThat(receiver.isDuplicate(knownId)).isTrue();
    assertThat(receiver.isDuplicate(unknownId)).isFalse();
  }

  @Test
  @DisplayName("Should track statistics correctly")
  void testStatisticsTracking() {
    var msg = new Message("MSG-001", "payload");

    // Process 3 fresh messages
    for (int i = 0; i < 3; i++) {
      var id = UUID.randomUUID();
      receiver.receive(id, msg, (state, m) -> m);
    }

    var trackedCount1 = receiver.trackedCount();
    assertThat(trackedCount1).isGreaterThanOrEqualTo(3);

    // Send duplicates
    var id = UUID.randomUUID();
    receiver.receive(id, msg, (state, m) -> m);

    for (int i = 0; i < 2; i++) {
      receiver.receive(id, msg, (state, m) -> m);
    }

    // Statistics should show duplicates
    var statsResult = receiver.statistics();
    assertThat(statsResult.isSuccess()).isTrue();
    statsResult.fold(
        stats -> {
          assertThat(stats).contains("seen=");
          assertThat(stats).contains("processed=");
          assertThat(stats).contains("duplicates=");
          return null;
        },
        error -> {
          fail("Statistics failed: " + error);
          return null;
        });
  }

  @Test
  @DisplayName("Should reset state and clear history")
  void testReset() {
    var id = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");

    // Process message
    receiver.receive(id, message, (state, msg) -> msg);
    assertThat(receiver.isDuplicate(id)).isTrue();

    // Reset
    receiver.reset();

    // ID should no longer be considered duplicate
    assertThat(receiver.isDuplicate(id)).isFalse();

    // Should be able to process again
    var result = receiver.receive(id, message, (state, msg) -> msg);
    result.fold(
        r -> assertThat(r.isDuplicate()).isFalse(),
        error -> fail(""));
  }

  @Test
  @DisplayName("Should handle bounded history with LRU eviction")
  void testBoundedHistoryEviction() throws Exception {
    // Create receiver with small history size
    var smallReceiver = IdempotentReceiver.<Message>create(3);

    var msg = new Message("MSG", "payload");

    // Add 5 unique IDs (exceeds history size of 3)
    for (int i = 0; i < 5; i++) {
      smallReceiver.receive(UUID.randomUUID(), msg, (state, m) -> m);
    }

    // History should be bounded
    assertThat(smallReceiver.trackedCount()).isLessThanOrEqualTo(3);

    smallReceiver.stop();
  }

  @Test
  @DisplayName("Should handle handler exceptions gracefully")
  void testHandlerExceptionHandling() {
    var id = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");

    var result = receiver.receive(id, message, (state, msg) -> {
      throw new RuntimeException("Handler error");
    });

    assertThat(result.isFailure()).isTrue();
    result.fold(
        success -> {
          fail("Should not succeed");
          return null;
        },
        error -> {
          assertThat(error).contains("Handler error");
          return null;
        });
  }

  @Test
  @DisplayName("Should maintain idempotency guarantee under repeated sends")
  void testIdempotencyUnderRepeatedSends() {
    var id = UUID.randomUUID();
    var message = new Message("CRITICAL", "transaction");
    var executionCount = new AtomicInteger(0);

    for (int i = 0; i < 10; i++) {
      receiver.receive(id, message, (state, msg) -> {
        executionCount.incrementAndGet();
        return msg;
      });
    }

    // Handler should execute only once despite 10 sends
    assertThat(executionCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should be safe to call concurrent receives")
  void testConcurrentReceives() throws Exception {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var message = new Message("MSG", "payload");
    var invokeCount = new AtomicInteger(0);

    // Send concurrently
    Thread t1 = new Thread(() -> {
      for (int i = 0; i < 5; i++) {
        receiver.receive(id1, message, (state, msg) -> {
          invokeCount.incrementAndGet();
          return msg;
        });
      }
    });

    Thread t2 = new Thread(() -> {
      for (int i = 0; i < 5; i++) {
        receiver.receive(id2, message, (state, msg) -> {
          invokeCount.incrementAndGet();
          return msg;
        });
      }
    });

    t1.start();
    t2.start();
    t1.join();
    t2.join();

    // Each unique ID should be processed exactly once
    assertThat(invokeCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should handle null message gracefully")
  void testNullMessageHandling() {
    var id = UUID.randomUUID();

    assertThatThrownBy(() -> receiver.receive(id, null, (state, msg) -> msg))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should handle null message ID gracefully")
  void testNullMessageIdHandling() {
    var message = new Message("MSG-001", "payload");

    assertThatThrownBy(() -> receiver.receive(null, message, (state, msg) -> msg))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Should handle null handler gracefully")
  void testNullHandlerHandling() {
    var id = UUID.randomUUID();
    var message = new Message("MSG-001", "payload");

    assertThatThrownBy(() -> receiver.receive(id, message, null))
        .isInstanceOf(NullPointerException.class);
  }
}
