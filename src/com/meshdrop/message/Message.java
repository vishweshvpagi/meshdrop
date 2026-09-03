package com.meshdrop.message;

import com.meshdrop.protocol.ProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain model representing an application-level peer-to-peer text message.
 *
 * Each Message has:
 *   - A globally unique messageId (UUID)
 *   - Sender node identity UUID
 *   - Recipient node identity UUID
 *   - Epoch millisecond creation timestamp
 *   - Non-empty UTF-8 text content (capped at 64 KiB)
 */
public record Message(
        UUID messageId,
        UUID senderId,
        UUID recipientId,
        long timestamp,
        String content
) {

    public Message {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(senderId, "senderId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(content, "content must not be null");

        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive, got: " + timestamp);
        }

        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }

        int byteLen = content.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen > ProtocolConstants.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Message content exceeds maximum allowed size of " + ProtocolConstants.MAX_MESSAGE_BYTES +
                    " bytes (actual: " + byteLen + " bytes)");
        }
    }

    /**
     * Creates a new outgoing Message with a randomly generated UUID and current timestamp.
     *
     * @param senderId local node UUID
     * @param recipientId target peer node UUID
     * @param content text content
     * @return constructed Message
     */
    public static Message create(UUID senderId, UUID recipientId, String content) {
        return new Message(UUID.randomUUID(), senderId, recipientId, System.currentTimeMillis(), content);
    }

    /**
     * Constructs a Message with all explicit fields (e.g. during packet decoding).
     */
    public static Message of(UUID messageId, UUID senderId, UUID recipientId, long timestamp, String content) {
        return new Message(messageId, senderId, recipientId, timestamp, content);
    }

    /**
     * Returns the byte length of the content when encoded as UTF-8.
     */
    public int getContentByteLength() {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
