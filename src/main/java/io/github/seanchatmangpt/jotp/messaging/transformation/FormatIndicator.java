package io.github.seanchatmangpt.jotp.messaging.transformation;

import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Format Indicator (Vernon: "Format Indicator")
 *
 * <p>Adds format metadata to messages, allowing receivers to know
 * how to deserialize the message body.
 *
 * <p>Pattern: Message carries a "format" header indicating its encoding
 * (JSON, XML, PROTOBUF, etc.).
 *
 * <p>Example:
 * <pre>
 * var msg = Message.event("ORDER", order);
 * var formatted = FormatIndicator.withFormat(msg, "JSON");
 * var format = FormatIndicator.getFormat(formatted); // "JSON"
 * </pre>
 */
public final class FormatIndicator {

    /**
     * Message wrapper with format metadata.
     */
    public record FormattedMessage(
        Message message,
        String format,
        Map<String, String> metadata
    ) {
        public FormattedMessage {
            if (format == null || format.isBlank()) {
                throw new IllegalArgumentException("format must not be blank");
            }
        }
    }

    // Format registry
    private static final Map<String, String> FORMAT_ALIASES = new ConcurrentHashMap<>();

    static {
        FORMAT_ALIASES.put("json", "application/json");
        FORMAT_ALIASES.put("xml", "application/xml");
        FORMAT_ALIASES.put("protobuf", "application/protobuf");
        FORMAT_ALIASES.put("avro", "application/avro");
        FORMAT_ALIASES.put("msgpack", "application/msgpack");
        FORMAT_ALIASES.put("csv", "text/csv");
        FORMAT_ALIASES.put("plain", "text/plain");
    }

    private FormatIndicator() {
    }

    /**
     * Wraps a message with format indicator.
     *
     * @param message The message
     * @param format  Format code (JSON, XML, PROTOBUF, etc.)
     * @return FormattedMessage with metadata
     */
    public static FormattedMessage withFormat(Message message, String format) {
        var mimeType = FORMAT_ALIASES.getOrDefault(format.toLowerCase(), format);
        var metadata = Map.of(
            "format", format,
            "mime-type", mimeType,
            "timestamp", String.valueOf(System.currentTimeMillis())
        );
        return new FormattedMessage(message, format, metadata);
    }

    /**
     * Extracts format from a formatted message.
     *
     * @param formatted The FormattedMessage
     * @return Format code
     */
    public static String getFormat(FormattedMessage formatted) {
        return formatted.format();
    }

    /**
     * Extracts MIME type for the format.
     *
     * @param formatted The FormattedMessage
     * @return MIME type
     */
    public static String getMimeType(FormattedMessage formatted) {
        return formatted.metadata().get("mime-type");
    }

    /**
     * Registers a new format alias.
     *
     * @param shortName Short name (e.g., "json")
     * @param mimeType  MIME type (e.g., "application/json")
     */
    public static void registerFormat(String shortName, String mimeType) {
        FORMAT_ALIASES.put(shortName.toLowerCase(), mimeType);
    }

    /**
     * Checks if two messages are compatible (same format).
     *
     * @param msg1 First formatted message
     * @param msg2 Second formatted message
     * @return True if formats match
     */
    public static boolean isCompatible(FormattedMessage msg1, FormattedMessage msg2) {
        return msg1.format().equalsIgnoreCase(msg2.format());
    }

    /**
     * Converts format indicator (transcode).
     *
     * @param formatted  The formatted message
     * @param targetFormat Target format code
     * @return New FormattedMessage with target format
     */
    public static FormattedMessage convert(FormattedMessage formatted, String targetFormat) {
        // In reality, this would deserialize/reserialize with target format
        return withFormat(formatted.message(), targetFormat);
    }

    /**
     * Validates format is supported.
     *
     * @param format Format to validate
     * @return True if format is registered
     */
    public static boolean isSupported(String format) {
        return FORMAT_ALIASES.containsKey(format.toLowerCase()) ||
               FORMAT_ALIASES.containsValue(format);
    }
}
