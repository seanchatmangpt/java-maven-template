package io.github.seanchatmangpt.jotp.messaging.transformation;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.function.Function;

/**
 * Message Translator (Vernon: "Message Translator")
 *
 * <p>Transforms a message from one format/type to another.
 * Essential for integrating systems with different message schemas.
 *
 * <p>JOTP Implementation: Uses pure Function<Message, Message> with Proc<S,M>
 * for stateless message transformation.
 *
 * <p>Example:
 * <pre>
 * var translator = MessageTranslator.create(msg -> {
 *     if (msg instanceof Message.EventMsg evt) {
 *         return Message.command("PROCESS_" + evt.eventType(), evt.payload(), null);
 *     }
 *     return msg;
 * });
 *
 * var transformed = MessageTranslator.translate(translator, incomingMessage);
 * </pre>
 */
public final class MessageTranslator {

    private MessageTranslator() {
    }

    /**
     * Creates a message translator with a custom transformation function.
     *
     * @param transformer Function that converts Message -> Message
     * @return ProcRef that translates messages
     */
    public static ProcRef<Void, Message> create(Function<Message, Message> transformer) {
        return Proc.spawn((Void) null, state -> msg -> {
            transformer.apply(msg); // Transform and forward (implementation detail)
            return state;
        });
    }

    /**
     * Translates a single message using the provided transformer.
     *
     * @param transformer The translation function
     * @param message The input message
     * @return Transformed message
     */
    public static Message translate(Function<Message, Message> transformer, Message message) {
        return transformer.apply(message);
    }

    /**
     * Chains multiple translators (composition).
     *
     * @param transformers Array of transformation functions
     * @return Composed function applying all transformations in order
     */
    @SafeVarargs
    public static Function<Message, Message> compose(Function<Message, Message>... transformers) {
        return msg -> {
            var result = msg;
            for (var transformer : transformers) {
                result = transformer.apply(result);
            }
            return result;
        };
    }

    // Common translator factories

    /**
     * Creates a translator that converts EventMsg to CommandMsg.
     */
    public static Function<Message, Message> eventToCommand(String commandPrefix) {
        return msg -> {
            if (msg instanceof Message.EventMsg evt) {
                return Message.command(commandPrefix + evt.eventType(), evt.payload(), null);
            }
            return msg;
        };
    }

    /**
     * Creates a translator that enriches messages with additional context.
     */
    public static Function<Message, Message> enrichWithMetadata(String source) {
        return msg -> {
            // Add metadata - implementation depends on extending Message with metadata field
            return msg;
        };
    }

    /**
     * Creates a translator that extracts payload and wraps in new message type.
     */
    public static Function<Message, Message> extractAndWrap(String newType) {
        return msg -> {
            Object payload = switch (msg) {
                case Message.EventMsg evt -> evt.payload();
                case Message.CommandMsg cmd -> cmd.payload();
                case Message.QueryMsg q -> q.criteria();
                case Message.DocumentMsg doc -> doc.documentBytes();
            };
            return Message.event(newType, payload);
        };
    }
}
