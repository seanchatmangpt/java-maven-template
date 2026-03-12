package io.github.seanchatmangpt.jotp.messaging.transformation;

import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.Map;

/**
 * Normalizer (Vernon: "Normalizer")
 *
 * <p>Converts messages to a canonical format, enabling integration
 * of systems with different message schemas.
 *
 * <p>JOTP Implementation: Uses sealed Message types as the canonical format.
 * Normalizers detect incoming format and convert to standard Message types.
 *
 * <p>Example:
 * <pre>
 * var canonical = Normalizer.toCanonical(xmlMessageString);
 * // Returns normalized Message.EventMsg with parsed payload
 * </pre>
 */
public sealed class Normalizer {

    /**
     * Canonical message format wrapper.
     */
    public record CanonicalMessage(
        String type,
        String sourceFormat,
        Object payload
    ) {}

    private Normalizer() {
    }

    /**
     * Detects input format and converts to canonical Message format.
     *
     * @param input Raw input (could be String, byte[], Map, etc.)
     * @return Normalized Message
     */
    public static Message toCanonical(Object input) {
        return switch (input) {
            case String str -> normalizeFromString(str);
            case byte[] bytes -> normalizeFromBytes(bytes);
            case Map<?, ?> map -> normalizeFromMap(map);
            case Message msg -> msg; // Already canonical
            default -> Message.event("UNKNOWN_FORMAT", input);
        };
    }

    /**
     * Converts normalized Message back to target format.
     *
     * @param message The canonical message
     * @param targetFormat Target format (JSON, XML, PROTO, etc.)
     * @return Formatted output
     */
    public static String fromCanonical(Message message, String targetFormat) {
        return switch (targetFormat.toUpperCase()) {
            case "JSON" -> toJson(message);
            case "XML" -> toXml(message);
            case "CSV" -> toCsv(message);
            default -> message.toString();
        };
    }

    // Internal format detectors

    private static Message normalizeFromString(String str) {
        if (str.startsWith("{") || str.startsWith("[")) {
            // Likely JSON
            return Message.event("JSON_INPUT", str);
        } else if (str.startsWith("<")) {
            // Likely XML
            return Message.event("XML_INPUT", str);
        }
        return Message.event("TEXT_INPUT", str);
    }

    private static Message normalizeFromBytes(byte[] bytes) {
        return Message.document("BINARY_INPUT", bytes);
    }

    private static Message normalizeFromMap(Map<?, ?> map) {
        var type = map.getOrDefault("type", "OBJECT");
        var payload = map.getOrDefault("payload", map);
        return Message.event(String.valueOf(type), payload);
    }

    // Internal format converters

    private static String toJson(Message msg) {
        return switch (msg) {
            case Message.EventMsg evt ->
                String.format("{\"type\":\"EVENT\",\"eventType\":\"%s\"}", evt.eventType());
            case Message.CommandMsg cmd ->
                String.format("{\"type\":\"COMMAND\",\"command\":\"%s\"}", cmd.commandType());
            default -> "{}";
        };
    }

    private static String toXml(Message msg) {
        return switch (msg) {
            case Message.EventMsg evt ->
                String.format("<message type=\"EVENT\" eventType=\"%s\"/>", evt.eventType());
            case Message.CommandMsg cmd ->
                String.format("<message type=\"COMMAND\" command=\"%s\"/>", cmd.commandType());
            default -> "<message/>";
        };
    }

    private static String toCsv(Message msg) {
        return switch (msg) {
            case Message.EventMsg evt -> "EVENT," + evt.eventType();
            case Message.CommandMsg cmd -> "COMMAND," + cmd.commandType();
            default -> "UNKNOWN,";
        };
    }

    /**
     * Validates message conforms to canonical format.
     */
    public static boolean isCanonical(Message msg) {
        return msg instanceof (Message.EventMsg | Message.CommandMsg |
                              Message.QueryMsg | Message.DocumentMsg);
    }
}
