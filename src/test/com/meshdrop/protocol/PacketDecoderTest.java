package com.meshdrop.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Comprehensive unit tests for PacketDecoder covering stream handling,
 * boundary fragmentation, partial reads, and malformed frame rejection.
 */
public class PacketDecoderTest {

    public void runAll() throws Exception {
        testDecodeOnePacket();
        testDecodeTwoConsecutivePackets();
        testDecodeEmptyPayload();
        testDecodeLargePayload();
        testOneByteAtATimeInputStream();
        testInvalidMagic();
        testInvalidVersion();
        testUnknownType();
        testOversizedPayloadDeclared();
        testTruncatedHeader();
        testTruncatedPayload();
        testUuidPreservation();
        testMultiplePacketsStream();
    }

    /**
     * 1. Decode one packet.
     */
    private void testDecodeOnePacket() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        byte[] payload = "Testing single packet decode".getBytes(StandardCharsets.UTF_8);
        UUID reqId = UUID.randomUUID();
        Packet original = Packet.of(PacketType.HELLO, reqId, payload);

        byte[] encoded = encoder.encodeToBytes(original);
        ByteArrayInputStream in = new ByteArrayInputStream(encoded);
        Packet decoded = decoder.decode(in);

        assert decoded != null : "Decoded packet must not be null";
        assert decoded.getMagic() == ProtocolConstants.MAGIC : "Magic mismatch";
        assert decoded.getVersion() == ProtocolConstants.CURRENT_VERSION : "Version mismatch";
        assert decoded.getType() == PacketType.HELLO : "Type mismatch";
        assert decoded.getRequestId().equals(reqId) : "Request ID mismatch";
        assert decoded.getLength() == payload.length : "Payload length mismatch";
        assert Arrays.equals(decoded.getPayload(), payload) : "Payload content mismatch";
    }

    /**
     * 2. Decode two consecutive packets from the same stream.
     */
    private void testDecodeTwoConsecutivePackets() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        Packet p1 = Packet.createPing();
        Packet p2 = Packet.createMessage("Second packet in stream");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        encoder.encode(p1, baos);
        encoder.encode(p2, baos);

        ByteArrayInputStream in = new ByteArrayInputStream(baos.toByteArray());
        Packet d1 = decoder.decode(in);
        Packet d2 = decoder.decode(in);
        Packet d3 = decoder.decode(in); // Stream finished

        assert d1 != null && d1.getType() == PacketType.PING : "First packet should be PING";
        assert d2 != null && d2.getType() == PacketType.MESSAGE : "Second packet should be MESSAGE";
        assert d3 == null : "Stream should return null at clean EOF";
    }

    /**
     * 3. Empty payload packet decode.
     */
    private void testDecodeEmptyPayload() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        UUID reqId = UUID.randomUUID();
        Packet original = Packet.of(PacketType.PONG, reqId, new byte[0]);
        byte[] encoded = encoder.encodeToBytes(original);

        Packet decoded = decoder.decode(new ByteArrayInputStream(encoded));
        assert decoded != null : "Decoded packet must not be null";
        assert decoded.getLength() == 0 : "Length should be 0";
        assert decoded.getPayload().length == 0 : "Payload should be empty array";
        assert decoded.getRequestId().equals(reqId) : "UUID mismatch";
    }

    /**
     * 4. Large payload decode (1 MiB).
     */
    private void testDecodeLargePayload() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        byte[] large = new byte[1024 * 1024]; // 1 MiB
        Arrays.fill(large, (byte) 0x3C);
        Packet original = Packet.of(PacketType.FILE_CHUNK, large);

        byte[] encoded = encoder.encodeToBytes(original);
        Packet decoded = decoder.decode(new ByteArrayInputStream(encoded));

        assert decoded != null : "Decoded packet must not be null";
        assert decoded.getLength() == large.length : "Length mismatch";
        assert Arrays.equals(decoded.getPayload(), large) : "Payload mismatch for large packet";
    }

    /**
     * 5 & 6. MANDATORY: Custom InputStream that returns at most 1 byte per read().
     * Verifies that the decoder correctly reconstructs the entire packet under extreme fragmentation.
     */
    private void testOneByteAtATimeInputStream() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        byte[] payload = "Fragmentation test with single byte reads".getBytes(StandardCharsets.UTF_8);
        UUID reqId = UUID.randomUUID();
        Packet original = Packet.of(PacketType.MESSAGE, reqId, payload);
        byte[] encoded = encoder.encodeToBytes(original);

        // Custom InputStream forcing 1-byte read increments
        InputStream oneByteIn = new InputStream() {
            private int index = 0;

            @Override
            public int read() {
                if (index >= encoded.length) return -1;
                return encoded[index++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (index >= encoded.length) return -1;
                // Return AT MOST 1 byte even if caller requests more
                b[off] = encoded[index++];
                return 1;
            }
        };

        Packet decoded = decoder.decode(oneByteIn);

        assert decoded != null : "Decoded packet must not be null";
        assert decoded.getType() == PacketType.MESSAGE : "Type mismatch";
        assert decoded.getRequestId().equals(reqId) : "UUID mismatch";
        assert decoded.getLength() == payload.length : "Length mismatch";
        assert Arrays.equals(decoded.getPayload(), payload) : "Payload mismatch under 1-byte stream";
    }

    /**
     * 7. Invalid magic rejection.
     */
    private void testInvalidMagic() throws Exception {
        PacketDecoder decoder = new PacketDecoder();
        byte[] malformed = new byte[28];
        ByteBuffer buf = ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0xDEADBEEF); // Bad magic
        buf.put((byte) 1);      // Version
        buf.put((byte) 1);      // Type
        buf.putShort((short) 0);// Flags
        buf.putInt(0);          // Length
        buf.putLong(0);         // UUID most
        buf.putLong(0);         // UUID least

        boolean threw = false;
        try {
            decoder.decode(new ByteArrayInputStream(malformed));
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Invalid magic") : "Expected invalid magic message, got: " + e.getMessage();
        }
        assert threw : "Decoder must throw ProtocolException for invalid magic";
    }

    /**
     * 8. Invalid version rejection.
     */
    private void testInvalidVersion() throws Exception {
        PacketDecoder decoder = new PacketDecoder();
        byte[] malformed = new byte[28];
        ByteBuffer buf = ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ProtocolConstants.MAGIC);
        buf.put((byte) 99); // Unsupported version 99
        buf.put((byte) 1);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putLong(0);
        buf.putLong(0);

        boolean threw = false;
        try {
            decoder.decode(new ByteArrayInputStream(malformed));
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Unsupported protocol version") : "Got: " + e.getMessage();
        }
        assert threw : "Decoder must throw ProtocolException for unsupported version";
    }

    /**
     * 9. Unknown packet type rejection.
     */
    private void testUnknownType() throws Exception {
        PacketDecoder decoder = new PacketDecoder();
        byte[] malformed = new byte[28];
        ByteBuffer buf = ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ProtocolConstants.MAGIC);
        buf.put(ProtocolConstants.CURRENT_VERSION);
        buf.put((byte) 0x7E); // Unknown packet type 0x7E
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putLong(0);
        buf.putLong(0);

        boolean threw = false;
        try {
            decoder.decode(new ByteArrayInputStream(malformed));
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Unknown packet type") : "Got: " + e.getMessage();
        }
        assert threw : "Decoder must throw ProtocolException for unknown packet type";
    }

    /**
     * 10. Oversized payload length rejection (protect against OutOfMemory attacks).
     */
    private void testOversizedPayloadDeclared() throws Exception {
        PacketDecoder decoder = new PacketDecoder();
        byte[] malformed = new byte[28];
        ByteBuffer buf = ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ProtocolConstants.MAGIC);
        buf.put(ProtocolConstants.CURRENT_VERSION);
        buf.put(PacketType.MESSAGE.getCode());
        buf.putShort((short) 0);
        buf.putInt(ProtocolConstants.MAX_PAYLOAD_SIZE + 1000); // 16 MiB + 1000 bytes
        buf.putLong(0);
        buf.putLong(0);

        boolean threw = false;
        try {
            decoder.decode(new ByteArrayInputStream(malformed));
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Illegal payload length") : "Got: " + e.getMessage();
        }
        assert threw : "Decoder must throw ProtocolException before allocating oversized array";
    }

    /**
     * 11. Truncated header (stream ends prematurely).
     */
    private void testTruncatedHeader() throws Exception {
        PacketDecoder decoder = new PacketDecoder();
        byte[] truncated = new byte[15]; // Only 15 of 28 bytes
        Arrays.fill(truncated, (byte) 0x01);

        boolean threw = false;
        try {
            decoder.decode(new ByteArrayInputStream(truncated));
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Unexpected EOF") : "Got: " + e.getMessage();
        }
        assert threw : "Decoder must throw ProtocolException for truncated header";
    }

    /**
     * 12. Truncated payload (header declared 50 bytes, stream provides only 10).
     */
    private void testTruncatedPayload() throws Exception {
        PacketDecoder decoder = new PacketDecoder();
        byte[] frame = new byte[28 + 10]; // Declares 50, but only provides 10
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ProtocolConstants.MAGIC);
        buf.put(ProtocolConstants.CURRENT_VERSION);
        buf.put(PacketType.MESSAGE.getCode());
        buf.putShort((short) 0);
        buf.putInt(50); // Declared length: 50 bytes
        buf.putLong(1);
        buf.putLong(2);
        // Payload has only 10 bytes available in frame buffer

        boolean threw = false;
        try {
            decoder.decode(new ByteArrayInputStream(frame));
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Unexpected EOF while reading packet payload") : "Got: " + e.getMessage();
        }
        assert threw : "Decoder must throw ProtocolException for truncated payload";
    }

    /**
     * 13. UUID preservation check.
     */
    private void testUuidPreservation() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        UUID expectedUuid = UUID.randomUUID();
        Packet original = Packet.of(PacketType.HELLO_RESPONSE, expectedUuid, new byte[0]);
        byte[] encoded = encoder.encodeToBytes(original);

        Packet decoded = decoder.decode(new ByteArrayInputStream(encoded));
        assert decoded != null : "Decoded packet must not be null";
        assert expectedUuid.equals(decoded.getRequestId()) : "UUID was not preserved: " + decoded.getRequestId();
    }

    /**
     * 14. Multiple packets concatenated in a single stream decoded sequentially.
     */
    private void testMultiplePacketsStream() throws Exception {
        PacketEncoder encoder = new PacketEncoder();
        PacketDecoder decoder = new PacketDecoder();

        Packet a = Packet.createHello();
        Packet b = Packet.createPing();
        Packet c = Packet.createMessage("Message C");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        encoder.encode(a, baos);
        encoder.encode(b, baos);
        encoder.encode(c, baos);

        ByteArrayInputStream in = new ByteArrayInputStream(baos.toByteArray());

        Packet decA = decoder.decode(in);
        Packet decB = decoder.decode(in);
        Packet decC = decoder.decode(in);
        Packet end = decoder.decode(in);

        assert decA != null && decA.getType() == PacketType.HELLO : "A mismatch";
        assert decB != null && decB.getType() == PacketType.PING : "B mismatch";
        assert decC != null && decC.getType() == PacketType.MESSAGE : "C mismatch";
        assert end == null : "Expected EOF after packet C";
    }
}
