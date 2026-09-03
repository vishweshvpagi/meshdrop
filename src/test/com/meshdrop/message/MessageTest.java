package com.meshdrop.message;

import com.meshdrop.protocol.ProtocolConstants;

import java.util.UUID;

/**
 * Unit tests for Message domain model validation, immutability, and size limits.
 */
public class MessageTest {

    public void runAll() {
        testValidMessageCreation();
        testNullFieldsRejected();
        testEmptyContentRejected();
        testInvalidTimestampRejected();
        testOversizedContentRejected();
        testUnicodeContentSupported();
        testUniqueIdsGenerated();
    }

    private void testValidMessageCreation() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        String text = "Hello from sender to recipient";

        Message msg = Message.create(sender, recipient, text);
        assert msg.messageId() != null : "messageId must not be null";
        assert msg.senderId().equals(sender) : "senderId mismatch";
        assert msg.recipientId().equals(recipient) : "recipientId mismatch";
        assert msg.timestamp() > 0 : "timestamp must be positive";
        assert text.equals(msg.content()) : "content mismatch";
        assert msg.getContentByteLength() == text.getBytes().length : "getContentByteLength mismatch";
    }

    private void testNullFieldsRejected() {
        UUID id = UUID.randomUUID();

        assertThrows(() -> new Message(null, id, id, 1000, "text"), NullPointerException.class);
        assertThrows(() -> new Message(id, null, id, 1000, "text"), NullPointerException.class);
        assertThrows(() -> new Message(id, id, null, 1000, "text"), NullPointerException.class);
        assertThrows(() -> new Message(id, id, id, 1000, null), NullPointerException.class);
    }

    private void testEmptyContentRejected() {
        UUID id = UUID.randomUUID();
        assertThrows(() -> new Message(id, id, id, 1000, ""), IllegalArgumentException.class);
    }

    private void testInvalidTimestampRejected() {
        UUID id = UUID.randomUUID();
        assertThrows(() -> new Message(id, id, id, 0, "text"), IllegalArgumentException.class);
        assertThrows(() -> new Message(id, id, id, -500, "text"), IllegalArgumentException.class);
    }

    private void testOversizedContentRejected() {
        UUID id = UUID.randomUUID();
        // 64 KiB + 1 byte
        String oversized = "A".repeat(ProtocolConstants.MAX_MESSAGE_BYTES + 1);
        assertThrows(() -> new Message(id, id, id, 1000, oversized), IllegalArgumentException.class);

        // Exactly 64 KiB is allowed
        String exactLimit = "B".repeat(ProtocolConstants.MAX_MESSAGE_BYTES);
        Message msg = new Message(id, id, id, 1000, exactLimit);
        assert msg.getContentByteLength() == ProtocolConstants.MAX_MESSAGE_BYTES;
    }

    private void testUnicodeContentSupported() {
        UUID id = UUID.randomUUID();
        String unicode = "ನಮಸ್ಕಾರ MeshDrop 🌐 こんにちは éàç";
        Message msg = Message.create(id, id, unicode);
        assert unicode.equals(msg.content());
        assert msg.getContentByteLength() > unicode.length() : "UTF-8 byte length should exceed char count";
    }

    private void testUniqueIdsGenerated() {
        UUID s = UUID.randomUUID();
        UUID r = UUID.randomUUID();
        Message m1 = Message.create(s, r, "Msg 1");
        Message m2 = Message.create(s, r, "Msg 2");
        assert !m1.messageId().equals(m2.messageId()) : "Message IDs must be unique";
    }

    private void assertThrows(Runnable runnable, Class<? extends Throwable> expected) {
        try {
            runnable.run();
            throw new AssertionError("Expected " + expected.getSimpleName() + " was not thrown");
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + t.getClass().getSimpleName(), t);
            }
        }
    }
}
