package com.meshdrop.message;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Binary codec for encoding and decoding {@link Message} instances and {@code MESSAGE_ACK} payloads.
 *
 * Wire Format (MESSAGE payload):
 *   Offset  Size   Field
 *   0       16     messageId (UUID: 8 bytes mostSigBits, 8 bytes leastSigBits)
 *   16      16     senderId (UUID: 8 bytes mostSigBits, 8 bytes leastSigBits)
 *   32      16     recipientId (UUID: 8 bytes mostSigBits, 8 bytes leastSigBits)
 *   48      8      timestamp (epoch milliseconds)
 *   56      4      contentLength (UTF-8 byte length, 32-bit big-endian int)
 *   60      N      content (UTF-8 encoded text)
 *   Total: 60 + N bytes.
 *
 * Wire Format (MESSAGE_ACK payload):
 *   Offset  Size   Field
 *   0       16     messageId (UUID: 8 bytes mostSigBits, 8 bytes leastSigBits)
 *   16      8      ackTimestamp (epoch milliseconds)
 *   Total: 24 bytes.
 */
public final class MessageCodec {

    public static final int MESSAGE_HEADER_BYTES = 60;
    public static final int ACK_PAYLOAD_BYTES = 24;

    private MessageCodec() {}

    /**
     * Serializes a Message into its deterministic binary representation.
     */
    public static byte[] encode(Message message) {
        Objects.requireNonNull(message, "message must not be null");

        byte[] contentBytes = message.content().getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > ProtocolConstants.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Message content exceeds maximum allowed size of " + ProtocolConstants.MAX_MESSAGE_BYTES + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_HEADER_BYTES + contentBytes.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 1. messageId (16B)
        buffer.putLong(message.messageId().getMostSignificantBits());
        buffer.putLong(message.messageId().getLeastSignificantBits());

        // 2. senderId (16B)
        buffer.putLong(message.senderId().getMostSignificantBits());
        buffer.putLong(message.senderId().getLeastSignificantBits());

        // 3. recipientId (16B)
        buffer.putLong(message.recipientId().getMostSignificantBits());
        buffer.putLong(message.recipientId().getLeastSignificantBits());

        // 4. timestamp (8B)
        buffer.putLong(message.timestamp());

        // 5. contentLength (4B)
        buffer.putInt(contentBytes.length);

        // 6. content (NB)
        buffer.put(contentBytes);

        return buffer.array();
    }

    /**
     * Deserializes a Message from a binary payload.
     *
     * @param payload binary message payload
     * @return decoded Message
     * @throws ProtocolException if the payload is truncated, invalid, or malformed
     */
    public static Message decode(byte[] payload) throws ProtocolException {
        if (payload == null) {
            throw new ProtocolException("Message payload must not be null");
        }

        if (payload.length < MESSAGE_HEADER_BYTES) {
            throw new ProtocolException("Message payload truncated: expected at least " +
                    MESSAGE_HEADER_BYTES + " bytes, got " + payload.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 1. messageId
        long msgMost = buffer.getLong();
        long msgLeast = buffer.getLong();
        UUID messageId = new UUID(msgMost, msgLeast);

        // 2. senderId
        long sndMost = buffer.getLong();
        long sndLeast = buffer.getLong();
        UUID senderId = new UUID(sndMost, sndLeast);

        // 3. recipientId
        long rcvMost = buffer.getLong();
        long rcvLeast = buffer.getLong();
        UUID recipientId = new UUID(rcvMost, rcvLeast);

        // 4. timestamp
        long timestamp = buffer.getLong();
        if (timestamp <= 0) {
            throw new ProtocolException("Invalid message timestamp: " + timestamp);
        }

        // 5. contentLength
        int contentLength = buffer.getInt();
        if (contentLength < 0) {
            throw new ProtocolException("Negative message content length: " + contentLength);
        }
        if (contentLength > ProtocolConstants.MAX_MESSAGE_BYTES) {
            throw new ProtocolException("Message content exceeds maximum allowed size: " +
                    contentLength + " > " + ProtocolConstants.MAX_MESSAGE_BYTES);
        }

        // 6. Exact bounds check (reject trailing bytes or truncated content)
        if (payload.length != MESSAGE_HEADER_BYTES + contentLength) {
            throw new ProtocolException("Message payload size mismatch: expected " +
                    (MESSAGE_HEADER_BYTES + contentLength) + " bytes, got " + payload.length);
        }

        // 7. Decode content
        byte[] contentBytes = new byte[contentLength];
        buffer.get(contentBytes);
        String content = new String(contentBytes, StandardCharsets.UTF_8);

        if (content.isEmpty()) {
            throw new ProtocolException("Decoded message content is empty");
        }

        return Message.of(messageId, senderId, recipientId, timestamp, content);
    }

    /**
     * Serializes a MESSAGE_ACK payload.
     */
    public static byte[] encodeAck(UUID messageId, long ackTimestamp) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        ByteBuffer buffer = ByteBuffer.allocate(ACK_PAYLOAD_BYTES);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(messageId.getMostSignificantBits());
        buffer.putLong(messageId.getLeastSignificantBits());
        buffer.putLong(ackTimestamp);
        return buffer.array();
    }

    /**
     * Deserializes a MESSAGE_ACK payload.
     */
    public static AckPayload decodeAck(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length != ACK_PAYLOAD_BYTES) {
            throw new ProtocolException("Invalid MESSAGE_ACK payload length: expected " +
                    ACK_PAYLOAD_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        long most = buffer.getLong();
        long least = buffer.getLong();
        UUID messageId = new UUID(most, least);
        long ackTimestamp = buffer.getLong();

        return new AckPayload(messageId, ackTimestamp);
    }

    public record AckPayload(UUID messageId, long ackTimestamp) {}
}
