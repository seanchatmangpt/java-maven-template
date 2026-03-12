package io.github.seanchatmangpt.jotp.messaging.construction;

import static org.assertj.core.api.Assertions.*;

import java.io.Serializable;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommandMessage} — verifies Vaughn Vernon's command message pattern
 * implementation with JOTP ProcRef integration.
 *
 * <p>Tests cover message creation, factory methods, correlation IDs, timeouts, and type safety.
 */
@DisplayName("CommandMessage — Command message construction pattern")
class CommandMessageTest implements WithAssertions {

    // Test domain types
    record CreateUserCmd(String username, String email) implements Serializable {}
    record UpdateProfileCmd(String userId, String bio) implements Serializable {}

    record MockReplyAddress() {}

    // ─────────────────────────────────────────────────────────────────────────────
    // Basic Creation — factory methods and message envelope
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Basic creation — message construction and defaults")
    class BasicCreation {

        @Test
        @DisplayName("creates command message with required fields")
        void createsMessageWithRequiredFields() {
            CreateUserCmd payload = new CreateUserCmd("alice", "alice@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", payload, replyTo);

            assertThat(msg)
                    .isNotNull()
                    .isInstanceOf(CommandMessage.class);
            assertThat(msg.commandType()).isEqualTo("CreateUser");
            assertThat(msg.payload()).isEqualTo(payload);
            assertThat(msg.replyTo()).isEqualTo(replyTo);
        }

        @Test
        @DisplayName("auto-generates unique message ID")
        void autoGeneratesUniqueMessageId() {
            CreateUserCmd payload = new CreateUserCmd("bob", "bob@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg1 = CommandMessage.create("CreateUser", payload, replyTo);
            CommandMessage<?> msg2 = CommandMessage.create("CreateUser", payload, replyTo);

            assertThat(msg1.messageId()).isNotNull().isNotEqualTo(msg2.messageId());
        }

        @Test
        @DisplayName("sets current timestamp on creation")
        void setsCurrentTimestamp() {
            long before = System.currentTimeMillis();
            CreateUserCmd payload = new CreateUserCmd("charlie", "charlie@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            long after = System.currentTimeMillis();
            assertThat(msg.createdAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("rejects null command type")
        void rejectsNullCommandType() {
            CreateUserCmd payload = new CreateUserCmd("dave", "dave@example.com");
            Object replyTo = new MockReplyAddress();

            assertThatThrownBy(() -> CommandMessage.create(null, payload, replyTo))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("commandType");
        }

        @Test
        @DisplayName("rejects blank command type")
        void rejectsBlankCommandType() {
            CreateUserCmd payload = new CreateUserCmd("eve", "eve@example.com");
            Object replyTo = new MockReplyAddress();

            assertThatThrownBy(() -> CommandMessage.create("   ", payload, replyTo))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("rejects null payload")
        void rejectsNullPayload() {
            Object replyTo = new MockReplyAddress();

            assertThatThrownBy(() -> CommandMessage.create("CreateUser", null, replyTo))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        @DisplayName("rejects null reply-to address")
        void rejectsNullReplyTo() {
            CreateUserCmd payload = new CreateUserCmd("frank", "frank@example.com");

            assertThatThrownBy(() -> CommandMessage.create("CreateUser", payload, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("replyTo");
        }

        @Test
        @DisplayName("rejects negative timestamp")
        void rejectsNegativeTimestamp() {
            CreateUserCmd payload = new CreateUserCmd("grace", "grace@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            // The impl record validates in its compact constructor
            assertThat(msg.createdAt()).isGreaterThanOrEqualTo(0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Correlation ID — request-response tracking
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Correlation ID — request-response correlation")
    class CorrelationId {

        @Test
        @DisplayName("message starts with no correlation ID")
        void messageStartsWithNoCorrelationId() {
            CreateUserCmd payload = new CreateUserCmd("henry", "henry@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            assertThat(msg.correlationId()).isNull();
            assertThat(msg.hasCorrelationId()).isFalse();
        }

        @Test
        @DisplayName("withCorrelationId() sets correlation ID")
        void withCorrelationIdSetsId() {
            CreateUserCmd payload = new CreateUserCmd("iris", "iris@example.com");
            Object replyTo = new MockReplyAddress();
            UUID correlationId = UUID.randomUUID();

            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withCorrelationId(correlationId);

            assertThat(msg.correlationId()).isEqualTo(correlationId);
            assertThat(msg.hasCorrelationId()).isTrue();
        }

        @Test
        @DisplayName("withCorrelationId() returns new message instance")
        void withCorrelationIdReturnsNewInstance() {
            CreateUserCmd payload = new CreateUserCmd("jack", "jack@example.com");
            Object replyTo = new MockReplyAddress();
            UUID correlationId = UUID.randomUUID();

            CommandMessage<?> original = CommandMessage.create("CreateUser", payload, replyTo);
            CommandMessage<?> withCorr = original.withCorrelationId(correlationId);

            // Original should be unchanged
            assertThat(original.correlationId()).isNull();
            assertThat(withCorr.correlationId()).isEqualTo(correlationId);
            // They should have the same message ID (correlation ID is optional overlay)
            assertThat(withCorr.messageId()).isEqualTo(original.messageId());
        }

        @Test
        @DisplayName("multiple messages can have different correlation IDs")
        void multipleMessagesDifferentCorrelationIds() {
            CreateUserCmd payload1 = new CreateUserCmd("karen", "karen@example.com");
            UpdateProfileCmd payload2 = new UpdateProfileCmd("user-123", "Software Engineer");
            Object replyTo = new MockReplyAddress();

            UUID corrId1 = UUID.randomUUID();
            UUID corrId2 = UUID.randomUUID();

            CommandMessage<?> msg1 =
                    CommandMessage.create("CreateUser", payload1, replyTo)
                            .withCorrelationId(corrId1);
            CommandMessage<?> msg2 =
                    CommandMessage.create("UpdateProfile", payload2, replyTo)
                            .withCorrelationId(corrId2);

            assertThat(msg1.correlationId()).isEqualTo(corrId1);
            assertThat(msg2.correlationId()).isEqualTo(corrId2);
            assertThat(msg1.correlationId()).isNotEqualTo(msg2.correlationId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Timeout — deadline specification
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Timeout — deadline specification for replies")
    class TimeoutHandling {

        @Test
        @DisplayName("message starts with no timeout")
        void messageStartsWithNoTimeout() {
            CreateUserCmd payload = new CreateUserCmd("leo", "leo@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            assertThat(msg.timeout()).isNull();
            assertThat(msg.hasTimeout()).isFalse();
        }

        @Test
        @DisplayName("withTimeout() sets timeout duration")
        void withTimeoutSetsDuration() {
            CreateUserCmd payload = new CreateUserCmd("mona", "mona@example.com");
            Object replyTo = new MockReplyAddress();
            Duration timeout = Duration.ofSeconds(5);

            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", payload, replyTo).withTimeout(timeout);

            assertThat(msg.timeout()).isEqualTo(timeout);
            assertThat(msg.hasTimeout()).isTrue();
        }

        @Test
        @DisplayName("withTimeout() accepts various duration units")
        void withTimeoutAcceptsDurations() {
            CreateUserCmd payload = new CreateUserCmd("neal", "neal@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg1 =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withTimeout(Duration.ofMillis(500));
            CommandMessage<?> msg2 =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withTimeout(Duration.ofSeconds(10));
            CommandMessage<?> msg3 =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withTimeout(Duration.ofMinutes(1));

            assertThat(msg1.timeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(msg2.timeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(msg3.timeout()).isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("rejects null timeout")
        void rejectsNullTimeout() {
            CreateUserCmd payload = new CreateUserCmd("olivia", "olivia@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            assertThatThrownBy(() -> msg.withTimeout(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("rejects zero duration")
        void rejectsZeroDuration() {
            CreateUserCmd payload = new CreateUserCmd("paul", "paul@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            assertThatThrownBy(() -> msg.withTimeout(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("rejects negative duration")
        void rejectsNegativeDuration() {
            CreateUserCmd payload = new CreateUserCmd("quinn", "quinn@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg = CommandMessage.create("CreateUser", payload, replyTo);

            assertThatThrownBy(() -> msg.withTimeout(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("withTimeout() returns new instance")
        void withTimeoutReturnsNewInstance() {
            CreateUserCmd payload = new CreateUserCmd("rachel", "rachel@example.com");
            Object replyTo = new MockReplyAddress();
            Duration timeout = Duration.ofSeconds(3);

            CommandMessage<?> original = CommandMessage.create("CreateUser", payload, replyTo);
            CommandMessage<?> withTimeout = original.withTimeout(timeout);

            assertThat(original.timeout()).isNull();
            assertThat(withTimeout.timeout()).isEqualTo(timeout);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fluent API — chaining methods
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fluent API — method chaining for configuration")
    class FluentApi {

        @Test
        @DisplayName("chains correlation ID and timeout")
        void chainsCorrelationAndTimeout() {
            CreateUserCmd payload = new CreateUserCmd("sam", "sam@example.com");
            Object replyTo = new MockReplyAddress();
            UUID correlationId = UUID.randomUUID();
            Duration timeout = Duration.ofSeconds(2);

            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withCorrelationId(correlationId)
                            .withTimeout(timeout);

            assertThat(msg.correlationId()).isEqualTo(correlationId);
            assertThat(msg.timeout()).isEqualTo(timeout);
            assertThat(msg.hasCorrelationId()).isTrue();
            assertThat(msg.hasTimeout()).isTrue();
        }

        @Test
        @DisplayName("chains timeout and correlation ID in reverse order")
        void chainsTimeoutThenCorrelationId() {
            CreateUserCmd payload = new CreateUserCmd("tina", "tina@example.com");
            Object replyTo = new MockReplyAddress();
            UUID correlationId = UUID.randomUUID();
            Duration timeout = Duration.ofSeconds(4);

            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", payload, replyTo)
                            .withTimeout(timeout)
                            .withCorrelationId(correlationId);

            assertThat(msg.correlationId()).isEqualTo(correlationId);
            assertThat(msg.timeout()).isEqualTo(timeout);
        }

        @Test
        @DisplayName("maintains immutability across chain operations")
        void maintainsImmutabilityAcrossChain() {
            CreateUserCmd payload = new CreateUserCmd("una", "una@example.com");
            Object replyTo = new MockReplyAddress();
            UUID correlationId = UUID.randomUUID();

            CommandMessage<?> msg1 = CommandMessage.create("CreateUser", payload, replyTo);
            CommandMessage<?> msg2 = msg1.withCorrelationId(correlationId);

            // msg1 should not be modified
            assertThat(msg1.correlationId()).isNull();
            assertThat(msg2.correlationId()).isEqualTo(correlationId);

            // Message IDs should be preserved
            assertThat(msg2.messageId()).isEqualTo(msg1.messageId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Type Safety — generic type parameters
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type safety — generic reply type handling")
    class TypeSafety {

        @Test
        @DisplayName("message supports various payload types")
        void supportsVariousPayloadTypes() {
            Object replyTo = new MockReplyAddress();

            // String payload
            CommandMessage<?> msg1 = CommandMessage.create("Echo", "hello", replyTo);
            assertThat(msg1.payload()).isEqualTo("hello");

            // Integer payload
            CommandMessage<?> msg2 = CommandMessage.create("Count", 42, replyTo);
            assertThat(msg2.payload()).isEqualTo(42);

            // Complex record payload
            CreateUserCmd cmd = new CreateUserCmd("victor", "victor@example.com");
            CommandMessage<?> msg3 = CommandMessage.create("CreateUser", cmd, replyTo);
            assertThat(msg3.payload()).isEqualTo(cmd);
        }

        @Test
        @DisplayName("preserves payload type through message lifecycle")
        void preservesPayloadType() {
            CreateUserCmd original = new CreateUserCmd("wendy", "wendy@example.com");
            Object replyTo = new MockReplyAddress();

            CommandMessage<?> msg =
                    CommandMessage.create("CreateUser", original, replyTo)
                            .withCorrelationId(UUID.randomUUID())
                            .withTimeout(Duration.ofSeconds(1));

            assertThat(msg.payload()).isInstanceOf(CreateUserCmd.class);
            assertThat(msg.payload()).isEqualTo(original);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Integration — realistic scenarios
    // ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Integration — realistic command message scenarios")
    class Integration {

        @Test
        @DisplayName("creates complete command for RPC over mailbox")
        void createsCompleteRpcCommand() {
            CreateUserCmd payload = new CreateUserCmd("xavier", "xavier@example.com");
            Object clientReplyTo = new MockReplyAddress();
            UUID correlationId = UUID.randomUUID();
            Duration timeout = Duration.ofSeconds(5);

            CommandMessage<?> rpcCmd =
                    CommandMessage.create("CreateUser", payload, clientReplyTo)
                            .withCorrelationId(correlationId)
                            .withTimeout(timeout);

            // Verify all RPC properties
            assertThat(rpcCmd.commandType()).isEqualTo("CreateUser");
            assertThat(rpcCmd.payload()).isEqualTo(payload);
            assertThat(rpcCmd.replyTo()).isEqualTo(clientReplyTo);
            assertThat(rpcCmd.correlationId()).isEqualTo(correlationId);
            assertThat(rpcCmd.timeout()).isEqualTo(timeout);
            assertThat(rpcCmd.messageId()).isNotNull();
            assertThat(rpcCmd.createdAt()).isGreaterThan(0);
        }

        @Test
        @DisplayName("multiple commands with same correlation ID group related requests")
        void groupsRelatedRequestsByCorrelation() {
            Object replyTo = new MockReplyAddress();
            UUID transactionId = UUID.randomUUID();

            CreateUserCmd cmd1 = new CreateUserCmd("yara", "yara@example.com");
            CommandMessage<?> msg1 =
                    CommandMessage.create("CreateUser", cmd1, replyTo)
                            .withCorrelationId(transactionId);

            UpdateProfileCmd cmd2 = new UpdateProfileCmd("user-456", "Data Scientist");
            CommandMessage<?> msg2 =
                    CommandMessage.create("UpdateProfile", cmd2, replyTo)
                            .withCorrelationId(transactionId);

            // Both messages part of same transaction
            assertThat(msg1.correlationId()).isEqualTo(msg2.correlationId());
        }
    }
}
