package com.meshdrop.discovery;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Comprehensive unit tests for DiscoveryMessage binary encoding, decoding, and defensive validation.
 */
public class DiscoveryMessageTest {

    public void runAll() throws Exception {
        test1_EncodeValidDiscoveryMessage();
        test2_DecodeValidDiscoveryMessage();
        test3_UuidPreserved();
        test4_TcpPortPreserved();
        test5_DisplayNamePreserved();
        test6_Utf8Names();
        test7_MaximumDisplayName();
        test8_OversizedDisplayNameRejected();
        test9_InvalidMagicRejected();
        test10_InvalidVersionRejected();
        test11_InvalidMessageTypeRejected();
        test12_InvalidPortRejected();
        test13_TruncatedMessageRejected();
        test14_InvalidLengthRejected();
        test15_TrailingBytesHandled();
        test16_MaximumPacketSizeEnforced();
    }

    // 1. Encode valid discovery message
    private void test1_EncodeValidDiscoveryMessage() {
        UUID id = UUID.randomUUID();
        DiscoveryMessage msg = DiscoveryMessage.beacon(id, 5000, "NodeAlpha");
        byte[] encoded = msg.encode();

        assert encoded != null : "Encoded bytes must not be null";
        int expectedLen = DiscoveryMessage.HEADER_SIZE + "NodeAlpha".getBytes(StandardCharsets.UTF_8).length;
        assert encoded.length == expectedLen : "Encoded size mismatch: expected " + expectedLen + ", got " + encoded.length;
    }

    // 2. Decode valid discovery message
    private void test2_DecodeValidDiscoveryMessage() throws Exception {
        UUID id = UUID.randomUUID();
        DiscoveryMessage original = DiscoveryMessage.beacon(id, 5000, "NodeAlpha");
        byte[] encoded = original.encode();

        DiscoveryMessage decoded = DiscoveryMessage.decode(encoded, encoded.length);
        assert decoded.nodeId().equals(id) : "Node ID mismatch";
        assert decoded.tcpPort() == 5000 : "TCP port mismatch";
        assert decoded.displayName().equals("NodeAlpha") : "Display name mismatch";
        assert decoded.version() == ProtocolConstants.CURRENT_VERSION : "Version mismatch";
        assert decoded.messageType() == DiscoveryMessage.TYPE_BEACON : "Type mismatch";
    }

    // 3. UUID preserved
    private void test3_UuidPreserved() throws Exception {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        DiscoveryMessage msg = DiscoveryMessage.beacon(id, 5000, "UuidTest");

        DiscoveryMessage decoded = DiscoveryMessage.decode(msg.encode());
        assert decoded.nodeId().equals(id) : "UUID wire mismatch: expected " + id + ", got " + decoded.nodeId();
    }

    // 4. TCP port preserved
    private void test4_TcpPortPreserved() throws Exception {
        DiscoveryMessage msg1 = DiscoveryMessage.beacon(UUID.randomUUID(), 1, "MinPort");
        DiscoveryMessage msg2 = DiscoveryMessage.beacon(UUID.randomUUID(), 65535, "MaxPort");

        assert DiscoveryMessage.decode(msg1.encode()).tcpPort() == 1 : "Min port 1 mismatch";
        assert DiscoveryMessage.decode(msg2.encode()).tcpPort() == 65535 : "Max port 65535 mismatch";
    }

    // 5. Display name preserved
    private void test5_DisplayNamePreserved() throws Exception {
        DiscoveryMessage msg = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, "Alice-Laptop-01");
        DiscoveryMessage decoded = DiscoveryMessage.decode(msg.encode());
        assert decoded.displayName().equals("Alice-Laptop-01") : "Name mismatch";
    }

    // 6. UTF-8 names
    private void test6_Utf8Names() throws Exception {
        String utf8Name = "MeshNode-🚀-🌟-测试";
        DiscoveryMessage msg = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, utf8Name);

        DiscoveryMessage decoded = DiscoveryMessage.decode(msg.encode());
        assert decoded.displayName().equals(utf8Name) : "UTF-8 name mismatch: " + decoded.displayName();
    }

    // 7. Maximum display name
    private void test7_MaximumDisplayName() throws Exception {
        char[] chars = new char[ProtocolConstants.MAX_DISPLAY_NAME_BYTES];
        Arrays.fill(chars, 'A');
        String maxName = new String(chars);

        DiscoveryMessage msg = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, maxName);
        DiscoveryMessage decoded = DiscoveryMessage.decode(msg.encode());
        assert decoded.displayName().equals(maxName) : "Max display name mismatch";
    }

    // 8. Oversized display name rejected
    private void test8_OversizedDisplayNameRejected() {
        char[] chars = new char[ProtocolConstants.MAX_DISPLAY_NAME_BYTES + 1];
        Arrays.fill(chars, 'B');
        String oversized = new String(chars);

        boolean rejected = false;
        try {
            DiscoveryMessage.beacon(UUID.randomUUID(), 5000, oversized);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected : "Oversized display name must be rejected by constructor";
    }

    // 9. Invalid magic rejected
    private void test9_InvalidMagicRejected() {
        byte[] data = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, "Node").encode();
        data[0] = (byte) 0x00; // Corrupt magic

        boolean threw = false;
        try {
            DiscoveryMessage.decode(data);
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Invalid discovery magic") : "Error: " + e.getMessage();
        }
        assert threw : "Corrupt magic must throw ProtocolException";
    }

    // 10. Invalid version rejected
    private void test10_InvalidVersionRejected() {
        byte[] data = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, "Node").encode();
        data[4] = (byte) 0x99; // Unsupported version

        boolean threw = false;
        try {
            DiscoveryMessage.decode(data);
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Unsupported discovery version") : "Error: " + e.getMessage();
        }
        assert threw : "Unsupported version must throw ProtocolException";
    }

    // 11. Invalid message type rejected
    private void test11_InvalidMessageTypeRejected() {
        byte[] data = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, "Node").encode();
        data[5] = (byte) 0x01; // Not TYPE_BEACON (0x06)

        boolean threw = false;
        try {
            DiscoveryMessage.decode(data);
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Unsupported discovery message type") : "Error: " + e.getMessage();
        }
        assert threw : "Unsupported message type must throw ProtocolException";
    }

    // 12. Invalid port rejected
    private void test12_InvalidPortRejected() {
        // Port 0 in decode
        ByteBuffer buf = ByteBuffer.allocate(30).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ProtocolConstants.MAGIC);
        buf.put(ProtocolConstants.CURRENT_VERSION);
        buf.put(DiscoveryMessage.TYPE_BEACON);
        buf.putLong(1L);
        buf.putLong(2L);
        buf.putShort((short) 0); // Port 0
        buf.putShort((short) 4);
        buf.put("Test".getBytes(StandardCharsets.UTF_8));

        boolean threw = false;
        try {
            DiscoveryMessage.decode(buf.array());
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Invalid TCP port") : "Error: " + e.getMessage();
        }
        assert threw : "Port 0 must throw ProtocolException";

        // Constructor reject invalid port (e.g. negative or 0)
        boolean constructorRejected = false;
        try {
            DiscoveryMessage.beacon(UUID.randomUUID(), 0, "Test");
        } catch (IllegalArgumentException e) {
            constructorRejected = true;
        }
        assert constructorRejected : "Port 0 must be rejected by constructor";
    }

    // 13. Truncated message rejected
    private void test13_TruncatedMessageRejected() {
        byte[] data = new byte[20]; // Less than 26-byte header
        boolean threw = false;
        try {
            DiscoveryMessage.decode(data);
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("too short") : "Error: " + e.getMessage();
        }
        assert threw : "Truncated header must throw ProtocolException";
    }

    // 14. Invalid length rejected
    private void test14_InvalidLengthRejected() {
        byte[] nameBytes = "Short".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(DiscoveryMessage.HEADER_SIZE + nameBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ProtocolConstants.MAGIC);
        buf.put(ProtocolConstants.CURRENT_VERSION);
        buf.put(DiscoveryMessage.TYPE_BEACON);
        buf.putLong(1L);
        buf.putLong(2L);
        buf.putShort((short) 5000);
        buf.putShort((short) 20); // Claims 20 bytes name length
        buf.put(nameBytes); // Only provides 5 bytes

        boolean threw = false;
        try {
            DiscoveryMessage.decode(buf.array(), buf.capacity());
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Truncated display name") : "Error: " + e.getMessage();
        }
        assert threw : "Length mismatch must throw ProtocolException";
    }

    // 15. Extra/trailing bytes handled according to the protocol specification
    private void test15_TrailingBytesHandled() {
        byte[] valid = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, "Node").encode();
        byte[] withExtra = new byte[valid.length + 10];
        System.arraycopy(valid, 0, withExtra, 0, valid.length);
        // Fill trailing bytes
        Arrays.fill(withExtra, valid.length, withExtra.length, (byte) 0xEE);

        boolean threw = false;
        try {
            DiscoveryMessage.decode(withExtra, withExtra.length);
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("Extraneous trailing bytes") : "Error: " + e.getMessage();
        }
        assert threw : "Trailing bytes must be rejected by decoder";
    }

    // 16. Maximum packet size enforced
    private void test16_MaximumPacketSizeEnforced() {
        byte[] oversized = new byte[DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE + 1];
        boolean threw = false;
        try {
            DiscoveryMessage.decode(oversized, oversized.length);
        } catch (ProtocolException e) {
            threw = true;
            assert e.getMessage().contains("exceeds maximum allowed size") : "Error: " + e.getMessage();
        }
        assert threw : "Oversized packet must throw ProtocolException";
    }
}
