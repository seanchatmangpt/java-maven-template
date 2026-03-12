package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcessRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipientListRouter} — verifies fan-out message delivery to multiple
 * recipients.
 *
 * <p>Tests the recipient list router pattern for broadcasting one message to N concurrent
 * recipients.
 */
@DisplayName("RecipientListRouter — fan-out messaging")
class RecipientListRouterTest implements WithAssertions {

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("creates empty router")
    void createsEmptyRouter() {
      RecipientListRouter<String> router = new RecipientListRouter<>();
      assertThat(router.recipientCount()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("addRecipient(procRef)")
  class AddRecipient {

    @Test
    @DisplayName("requires non-null procRef")
    void requiresNonNullProcRef() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      assertThatThrownBy(() -> router.addRecipient(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("procRef cannot be null");
    }

    @Test
    @DisplayName("adds recipient to list")
    void addsRecipient() {
      RecipientListRouter<String> router = new RecipientListRouter<>();
      assertThat(router.recipientCount()).isEqualTo(0);

      ProcRef<String, String> dummy = new DummyProcRef<>();
      router.addRecipient(dummy);

      assertThat(router.recipientCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("adds multiple recipients")
    void addsMultipleRecipients() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      for (int i = 0; i < 5; i++) {
        router.addRecipient(new DummyProcRef<>());
      }

      assertThat(router.recipientCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("allows duplicate recipients")
    void allowsDuplicateRecipients() {
      RecipientListRouter<String> router = new RecipientListRouter<>();
      ProcRef<String, String> ref = new DummyProcRef<>();

      router.addRecipient(ref);
      router.addRecipient(ref);

      assertThat(router.recipientCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("removeRecipient(procRef)")
  class RemoveRecipient {

    @Test
    @DisplayName("removes recipient by reference")
    void removesRecipient() {
      RecipientListRouter<String> router = new RecipientListRouter<>();
      ProcRef<String, String> ref1 = new DummyProcRef<>();
      ProcRef<String, String> ref2 = new DummyProcRef<>();

      router.addRecipient(ref1);
      router.addRecipient(ref2);

      boolean removed = router.removeRecipient(ref1);

      assertThat(removed).isTrue();
      assertThat(router.recipientCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns false when recipient not found")
    void returnsFalseWhenNotFound() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      boolean removed = router.removeRecipient(new DummyProcRef<>());

      assertThat(removed).isFalse();
    }
  }

  @Nested
  @DisplayName("broadcastMessage(message)")
  class BroadcastMessage {

    @Test
    @DisplayName("requires non-null message")
    void requiresNonNullMessage() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      assertThatThrownBy(() -> router.broadcastMessage(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("message cannot be null");
    }

    @Test
    @DisplayName("returns zero recipients when empty")
    void returnsZeroRecipientsWhenEmpty() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      int sent = router.broadcastMessage("test");

      assertThat(sent).isEqualTo(0);
    }

    @Test
    @DisplayName("broadcasts message to all recipients")
    void broadcastsToAllRecipients() {
      List<String> messages1 = new ArrayList<>();
      List<String> messages2 = new ArrayList<>();
      List<String> messages3 = new ArrayList<>();

      ProcRef<String, String> ref1 =
          Proc.spawn(
              "recipient-1",
              () -> messages1,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> ref2 =
          Proc.spawn(
              "recipient-2",
              () -> messages2,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> ref3 =
          Proc.spawn(
              "recipient-3",
              () -> messages3,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(ref1);
      router.addRecipient(ref2);
      router.addRecipient(ref3);

      int sent = router.broadcastMessage("broadcast-message");

      assertThat(sent).isEqualTo(3);

      Thread.yield();

      assertThat(messages1).contains("broadcast-message");
      assertThat(messages2).contains("broadcast-message");
      assertThat(messages3).contains("broadcast-message");

      ProcessRegistry.unregister("recipient-1");
      ProcessRegistry.unregister("recipient-2");
      ProcessRegistry.unregister("recipient-3");
    }

    @Test
    @DisplayName("returns correct recipient count")
    void returnsCorrectRecipientCount() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      int sent1 = router.broadcastMessage("msg1");
      assertThat(sent1).isEqualTo(0);

      router.addRecipient(new DummyProcRef<>());
      router.addRecipient(new DummyProcRef<>());

      int sent2 = router.broadcastMessage("msg2");
      assertThat(sent2).isEqualTo(2);

      router.addRecipient(new DummyProcRef<>());

      int sent3 = router.broadcastMessage("msg3");
      assertThat(sent3).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("waitForAll(timeout)")
  class WaitForAll {

    @Test
    @DisplayName("requires non-null timeout")
    void requiresNonNullTimeout() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      assertThatThrownBy(() -> router.waitForAll(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("timeout cannot be null");
    }

    @Test
    @DisplayName("returns true")
    void returnsTrue() {
      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(new DummyProcRef<>());

      boolean result = router.waitForAll(Duration.ofSeconds(5));

      assertThat(result).isTrue();
    }
  }

  @Nested
  @DisplayName("clearRecipients()")
  class ClearRecipients {

    @Test
    @DisplayName("removes all recipients")
    void removesAllRecipients() {
      RecipientListRouter<String> router = new RecipientListRouter<>();

      for (int i = 0; i < 3; i++) {
        router.addRecipient(new DummyProcRef<>());
      }

      assertThat(router.recipientCount()).isEqualTo(3);

      router.clearRecipients();

      assertThat(router.recipientCount()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("getRecipients()")
  class GetRecipients {

    @Test
    @DisplayName("returns copy of recipient list")
    void returnsCopyOfRecipients() {
      RecipientListRouter<String> router = new RecipientListRouter<>();
      ProcRef<String, String> ref1 = new DummyProcRef<>();
      ProcRef<String, String> ref2 = new DummyProcRef<>();

      router.addRecipient(ref1);
      router.addRecipient(ref2);

      List<ProcRef<String, String>> copy = router.getRecipients();

      assertThat(copy).hasSize(2).contains(ref1, ref2);

      // Verify it's a copy (modifications don't affect router)
      copy.clear();
      assertThat(router.recipientCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Fan-out scenario")
  class FanOutScenario {

    @AfterEach
    void cleanup() {
      for (String name : ProcessRegistry.registered()) {
        ProcessRegistry.unregister(name);
      }
    }

    @Test
    @DisplayName("sends one message to multiple recipients concurrently")
    void fanOutToMultipleRecipients() {
      List<String> orderServiceMessages = new ArrayList<>();
      List<String> notificationServiceMessages = new ArrayList<>();
      List<String> auditServiceMessages = new ArrayList<>();

      ProcRef<String, String> orderRef =
          Proc.spawn(
              "order-processor",
              () -> orderServiceMessages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> notificationRef =
          Proc.spawn(
              "notification-service",
              () -> notificationServiceMessages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> auditRef =
          Proc.spawn(
              "audit-service",
              () -> auditServiceMessages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(orderRef);
      router.addRecipient(notificationRef);
      router.addRecipient(auditRef);

      String orderEvent = "OrderCreated(id=12345, amount=99.99)";
      int recipientCount = router.broadcastMessage(orderEvent);

      assertThat(recipientCount).isEqualTo(3);

      Thread.yield();

      assertThat(orderServiceMessages).contains(orderEvent);
      assertThat(notificationServiceMessages).contains(orderEvent);
      assertThat(auditServiceMessages).contains(orderEvent);
    }

    @Test
    @DisplayName("broadcasts multiple messages to all recipients")
    void broadcastsMultipleMessages() {
      List<String> recipient1Messages = new ArrayList<>();
      List<String> recipient2Messages = new ArrayList<>();

      ProcRef<String, String> ref1 =
          Proc.spawn(
              "recipient-1",
              () -> recipient1Messages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      ProcRef<String, String> ref2 =
          Proc.spawn(
              "recipient-2",
              () -> recipient2Messages,
              (state, msg) -> {
                state.add(msg);
                return state;
              });

      RecipientListRouter<String> router = new RecipientListRouter<>();
      router.addRecipient(ref1);
      router.addRecipient(ref2);

      router.broadcastMessage("message-1");
      router.broadcastMessage("message-2");
      router.broadcastMessage("message-3");

      Thread.yield();

      assertThat(recipient1Messages).hasSize(3).contains("message-1", "message-2", "message-3");
      assertThat(recipient2Messages).hasSize(3).contains("message-1", "message-2", "message-3");
    }
  }

  /**
   * Dummy ProcRef for testing that doesn't route to any actual process.
   */
  private static class DummyProcRef<M> implements ProcRef<M, M> {
    @Override
    public void send(M message) {
      // No-op
    }

    @Override
    public String name() {
      return "dummy";
    }
  }
}
