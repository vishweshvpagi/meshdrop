package com.meshdrop.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Comprehensive unit tests for PacketEncoder.
 */
public class PacketEncoderTest {

    public void runAll() throws Exception {
        testBasicPacket();
        testEmptyPayloadHeaderSize();
        testNonEmptyPayload();
        testUuidPreservation();
        testVersionPreservation();
        testTypePreservation();
        testFlagsPreservation();
        testPayloadPreservation();
        testMaxPayload();
        testOversizedPayloadRejection();
    }

    /**
     * 1. Basic packet encoding test.
     */
    private void testBasicPacket() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        UUID reqId = UUID.randomUUID();
        byte[] payload = "Hello binary protocol".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.of(PacketType.MESSAGE, reqId, payload);

        byte[] encoded = encoder.encodeToBytes(packet);
        assert encoded != null : "Encoded bytes must not be null";
        assert encoded.length == ProtocolConstants.HEADER_LENGTH + payload.length :
                "Encoded length should be 28 + payload.length (" + (28 + payload.length) + "), got: " + encoded.length;
    }

    /**
     * 2. Empty payload: exact 28 bytes header size verification.
     */
    private void testEmptyPayloadHeaderSize() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        UUID reqId = UUID.randomUUID();
        Packet packet = Packet.of(PacketType.PING, reqId, new byte[0]);

        byte[] encoded = encoder.encodeToBytes(packet);
        assert encoded.length == 28 : "Encoded empty payload packet must be exactly 28 bytes, got: " + encoded.length;
        assert encoded.length == ProtocolConstants.HEADER_LENGTH : "Must match ProtocolConstants.HEADER_LENGTH";
    }

    /**
     * 3. Non-empty payload length correctness.
     */
    private void testNonEmptyPayload() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 0xAA);
        Packet packet = Packet.of(PacketType.FILE_CHUNK, payload);

        byte[] encoded = encoder.encodeToBytes(packet);
        assert encoded.length == 28 + 1024 : "Expected 1052 bytes, got: " + encoded.length;
    }

    /**
     * 4. UUID preservation: verify most/least significant bits on wire.
     */
    private void testUuidPreservation() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        UUID reqId = UUID.fromString("12345678-1234-5678-1234-567812345678");
        Packet packet = Packet.of(PacketType.HELLO, reqId, new byte[]{0x01});

        byte[] encoded = encoder.encodeToBytes(packet);
        ByteBuffer buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        buf.position(12); // Skip magic(4) + ver(1) + type(1) + flags(2) + len(4) = 12
        long mostSig = buf.getLong();
        long leastSig = buf.getLong();
        UUID wireUuid = new UUID(mostSig, leastSig);

        assert reqId.equals(wireUuid) : "UUID mismatch: expected " + reqId + ", got " + wireUuid;
    }

    /**
     * 5. Version preservation on wire.
     */
    private void testVersionPreservation() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        Packet packet = Packet.createPing();
        byte[] encoded = encoder.encodeToBytes(packet);

        ByteBuffer buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt();
        byte version = buf.get();

        assert magic == ProtocolConstants.MAGIC : "Magic mismatch";
        assert version == ProtocolConstants.CURRENT_VERSION : "Version mismatch: " + version;
    }

    /**
     * 6. Type preservation for various PacketTypes.
     */
    private void testTypePreservation() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        for (PacketType type : PacketType.values()) {
            Packet packet = Packet.of(type, new byte[0]);
            byte[] encoded = encoder.encodeToBytes(packet);
            byte wireTypeCode = encoded[5]; // Byte index 5 is Type
            assert wireTypeCode == type.getCode() : "Type code mismatch for " + type + ": expected " + type.getCode() + ", got " + wireTypeCode;
        }
    }

    /**
     * 7. Flags preservation on wire.
     */
    private void testFlagsPreservation() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        Packet packet = new Packet(
                ProtocolConstants.MAGIC,
                ProtocolConstants.CURRENT_VERSION,
                PacketType.MESSAGE,
                PacketFlags.COMPRESSED,
                0,
                UUID.randomUUID(),
                new byte[0]
        );

        byte[] encoded = encoder.encodeToBytes(packet);
        ByteBuffer buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        buf.position(6); // Skip magic(4) + ver(1) + type(1) = 6
        short flags = buf.getShort();

        assert flags == PacketFlags.COMPRESSED : "Flags mismatch: expected " + PacketFlags.COMPRESSED + ", got " + flags;
    }

    /**
     * 8. Payload preservation on wire.
     */
    private void testPayloadPreservation() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        byte[] original = "Preserve this payload exactly!".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.of(PacketType.MESSAGE, original);

        byte[] encoded = encoder.encodeToBytes(packet);
        byte[] payloadExtracted = Arrays.copyOfRange(encoded, 28, encoded.length);

        assert Arrays.equals(original, payloadExtracted) : "Payload on wire does not match original";
    }

    /**
     * 9. Maximum allowed payload encoding (e.g. 1 MiB in test, up to 16 MiB limit).
     */
    private void testMaxPayload() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        // 1 MiB payload
        byte[] large = new byte[1024 * 1024];
        Arrays.fill(large, (byte) 0x7F);
        Packet packet = Packet.of(PacketType.FILE_CHUNK, large);

        byte[] encoded = encoder.encodeToBytes(packet);
        assert encoded.length == 28 + (1024 * 1024) : "Encoded size mismatch for 1 MiB payload";
    }

    /**
     * 10. Oversized payload rejection.
     */
    private void testOversizedPayloadRejection() {
        // Attempting to construct or encode an oversized packet (> 16 MiB) must be rejected
        boolean rejected = false;
        try {
            byte[] oversized = new byte[0];
            new Packet(
                    ProtocolConstants.MAGIC,
                    ProtocolConstants.CURRENT_VERSION,
                    PacketType.FILE_CHUNK,
                    PacketFlags.NONE,
                    ProtocolConstants.MAX_PAYLOAD_SIZE + 1,
                    UUID.randomUUID(),
                    oversized
            );
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected : "Oversized payload length must be rejected";
    }
}
