package com.meshdrop.message;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Unit tests for MessageCodec binary serialization, deserialization, bounds checking, and ACK encoding.
 */
public class MessageCodecTest {

    public void runAll() throws Exception {
        testEncodeDecodeRoundTrip();
        testUuidPreservation();
        testTimestampPreservation();
        testUnicodePreservation();
        testNullPayloadRejection();
        testTruncatedPayloadRejection();
        testOversizedContentLengthRejection();
        testTrailingBytesRejection();
        testAckEncodeDecodeRoundTrip();
        testTruncatedAckRejection();
    }

    private void testEncodeDecodeRoundTrip() throws Exception {
        UUID msgId = UUID.randomUUID();
        UUID sndId = UUID.randomUUID();
        UUID rcvId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String content = "Standard text message content";

        Message original = Message.of(msgId, sndId, rcvId, now, content);
        byte[] encoded = MessageCodec.encode(original);

        assert encoded != null;
        assert encoded.length == 60 + content.getBytes(StandardCharsets.UTF_8).length;

        Message decoded = MessageCodec.decode(encoded);
        assert original.messageId().equals(decoded.messageId());
        assert original.senderId().equals(decoded.senderId());
        assert original.recipientId().equals(decoded.recipientId());
        assert original.timestamp() == decoded.timestamp();
        assert original.content().equals(decoded.content());
    }

    private void testUuidPreservation() throws Exception {
        UUID msgId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        UUID sndId = UUID.fromString("abcdef01-abcd-abcd-abcd-abcdef012345");
        UUID rcvId = UUID.fromString("fedcba98-fedc-fedc-fedc-fedcba987654");

        Message msg = Message.of(msgId, sndId, rcvId, 1000, "UUID preservation test");
        Message decoded = MessageCodec.decode(MessageCodec.encode(msg));

        assert msgId.equals(decoded.messageId());
        assert sndId.equals(decoded.senderId());
        assert rcvId.equals(decoded.recipientId());
    }

    private void testTimestampPreservation() throws Exception {
        long timestamp = 1756891234567L;
        Message msg = Message.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), timestamp, "Timestamp test");
        Message decoded = MessageCodec.decode(MessageCodec.encode(msg));

        assert timestamp == decoded.timestamp() : "Timestamp must be identical";
    }

    private void testUnicodePreservation() throws Exception {
        String unicode = "ಕನ್ನಡದಲ್ಲಿ ಸಂದೇಶ - Hello World 🚀 🌟 日本語";
        Message msg = Message.create(UUID.randomUUID(), UUID.randomUUID(), unicode);
        Message decoded = MessageCodec.decode(MessageCodec.encode(msg));

        assert unicode.equals(decoded.content());
    }

    private void testNullPayloadRejection() {
        assertThrows(() -> MessageCodec.decode(null), ProtocolException.class);
    }

    private void testTruncatedPayloadRejection() {
        // Less than 60 bytes header
        byte[] tooShort = new byte[59];
        assertThrows(() -> MessageCodec.decode(tooShort), ProtocolException.class);

        byte[] empty = new byte[0];
        assertThrows(() -> MessageCodec.decode(empty), ProtocolException.class);
    }

    private void testOversizedContentLengthRejection() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 6; i++) buf.putLong(1L); // UUIDs + timestamp (56B)
        buf.putInt(ProtocolConstants.MAX_MESSAGE_BYTES + 100); // 4B: content length > 64 KiB
        buf.putInt(0); // 4B padding

        assertThrows(() -> MessageCodec.decode(buf.array()), ProtocolException.class);
    }

    private void testTrailingBytesRejection() {
        Message msg = Message.create(UUID.randomUUID(), UUID.randomUUID(), "Hello");
        byte[] normal = MessageCodec.encode(msg);

        // Add 5 bytes of garbage to the end
        byte[] withTrailing = new byte[normal.length + 5];
        System.arraycopy(normal, 0, withTrailing, 0, normal.length);

        assertThrows(() -> MessageCodec.decode(withTrailing), ProtocolException.class);
    }

    private void testAckEncodeDecodeRoundTrip() throws Exception {
        UUID msgId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        byte[] encoded = MessageCodec.encodeAck(msgId, now);
        assert encoded.length == 24 : "ACK payload must be exactly 24 bytes";

        MessageCodec.AckPayload ack = MessageCodec.decodeAck(encoded);
        assert msgId.equals(ack.messageId());
        assert now == ack.ackTimestamp();
    }

    private void testTruncatedAckRejection() {
        assertThrows(() -> MessageCodec.decodeAck(new byte[23]), ProtocolException.class);
        assertThrows(() -> MessageCodec.decodeAck(new byte[25]), ProtocolException.class);
        assertThrows(() -> MessageCodec.decodeAck(null), ProtocolException.class);
    }

    private void assertThrows(ThrowingRunnable runnable, Class<? extends Throwable> expected) {
        try {
            runnable.run();
            throw new AssertionError("Expected " + expected.getSimpleName() + " was not thrown");
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + t.getClass().getSimpleName(), t);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
