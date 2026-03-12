package io.github.seanchatmangpt.jotp.messaging;

import java.io.Serializable;
import java.util.UUID;

/**
 * Base sealed interface for all messaging patterns.
 * All messages in the JOTP messaging framework inherit from this type,
 * enabling pattern matching and type-safe routing.
 */
public sealed interface Message extends Serializable permits
    Message.CommandMsg,
    Message.EventMsg,
    Message.QueryMsg,
    Message.DocumentMsg {

    /**
     * Unique message identifier for correlation and idempotency checks.
     */
    UUID messageId();

    /**
     * System clock timestamp when message was created (milliseconds).
     */
    long createdAt();

    /**
     * Command message: request with expected reply.
     * Used for synchronous-style RPC over async channels (Vernon: Command Message).
     */
    record CommandMsg(
        UUID messageId,
        long createdAt,
        String commandType,
        Object payload,
        Object replyTo  // ProcRef or return address
    ) implements Message {
        public CommandMsg {
            if (commandType == null || commandType.isBlank()) {
                throw new IllegalArgumentException("commandType must not be blank");
            }
        }
    }

    /**
     * Event message: immutable notification, usually 1:N broadcast.
     * Used for publish-subscribe patterns (Vernon: Event Message, Document Message).
     */
    record EventMsg(
        UUID messageId,
        long createdAt,
        String eventType,
        Object payload
    ) implements Message {
        public EventMsg {
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("eventType must not be blank");
            }
        }
    }

    /**
     * Query message: request for information.
     * Used for dynamic routing and process registry lookups.
     */
    record QueryMsg(
        UUID messageId,
        long createdAt,
        String queryType,
        Object criteria
    ) implements Message {
        public QueryMsg {
            if (queryType == null || queryType.isBlank()) {
                throw new IllegalArgumentException("queryType must not be blank");
            }
        }
    }

    /**
     * Document message: entire domain entity wrapped as a message.
     * Used when transferring large or complex data structures (Vernon: Document Message).
     */
    record DocumentMsg(
        UUID messageId,
        long createdAt,
        String documentType,
        byte[] documentBytes  // serialized POJO
    ) implements Message {
        public DocumentMsg {
            if (documentType == null || documentType.isBlank()) {
                throw new IllegalArgumentException("documentType must not be blank");
            }
            if (documentBytes == null || documentBytes.length == 0) {
                throw new IllegalArgumentException("documentBytes must not be empty");
            }
        }
    }

    /**
     * Factory method to create a new CommandMsg with current timestamp and random UUID.
     */
    static CommandMsg command(String commandType, Object payload, Object replyTo) {
        return new CommandMsg(UUID.randomUUID(), System.currentTimeMillis(), commandType, payload, replyTo);
    }

    /**
     * Factory method to create a new EventMsg with current timestamp and random UUID.
     */
    static EventMsg event(String eventType, Object payload) {
        return new EventMsg(UUID.randomUUID(), System.currentTimeMillis(), eventType, payload);
    }

    /**
     * Factory method to create a new QueryMsg with current timestamp and random UUID.
     */
    static QueryMsg query(String queryType, Object criteria) {
        return new QueryMsg(UUID.randomUUID(), System.currentTimeMillis(), queryType, criteria);
    }

    /**
     * Factory method to create a new DocumentMsg with current timestamp and random UUID.
     */
    static DocumentMsg document(String documentType, byte[] documentBytes) {
        return new DocumentMsg(UUID.randomUUID(), System.currentTimeMillis(), documentType, documentBytes);
    }
}
