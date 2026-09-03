package com.meshdrop.discovery;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of a binary MeshDrop UDP discovery beacon message.
 *
 * Wire Structure (26 + N bytes):
 * +----------------------------+
 * | MAGIC          4 bytes     |  0x4D 0x44 0x52 0x50 ("MDRP")
 * +----------------------------+
 * | VERSION        1 byte      |  0x01
 * +----------------------------+
 * | MESSAGE TYPE   1 byte      |  0x06 (DISCOVERY_BEACON)
 * +----------------------------+
 * | NODE ID       16 bytes     |  UUID (8 bytes most-sig, 8 bytes least-sig)
 * +----------------------------+
 * | TCP PORT       2 bytes     |  Big-Endian uint16 (1 - 65535)
 * +----------------------------+
 * | NAME LENGTH    2 bytes     |  Big-Endian uint16 (0 - 128)
 * +----------------------------+
 * | DISPLAY NAME   N bytes     |  UTF-8 encoded string bytes
 * +----------------------------+
 */
public record DiscoveryMessage(
        byte version,
        byte messageType,
        UUID nodeId,
        int tcpPort,
        String displayName
) {
    public static final byte TYPE_BEACON = 0x06;
    public static final int HEADER_SIZE = 26; // 4 + 1 + 1 + 16 + 2 + 2 = 26 bytes

    public DiscoveryMessage {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid TCP port: " + tcpPort + " (must be 1-65535)");
        }
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > ProtocolConstants.MAX_DISPLAY_NAME_BYTES) {
            throw new IllegalArgumentException("Display name exceeds maximum allowed byte length (" +
                    nameBytes.length + " > " + ProtocolConstants.MAX_DISPLAY_NAME_BYTES + ")");
        }
    }

    /**
     * Creates a standard discovery beacon message for the specified node.
     */
    public static DiscoveryMessage beacon(UUID nodeId, int tcpPort, String displayName) {
        return new DiscoveryMessage(ProtocolConstants.CURRENT_VERSION, TYPE_BEACON, nodeId, tcpPort, displayName);
    }

    /**
     * Serializes this discovery message into big-endian binary wire format.
     *
     * @return byte array containing the serialized discovery beacon
     */
    public byte[] encode() {
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + nameBytes.length).order(ByteOrder.BIG_ENDIAN);

        buf.putInt(ProtocolConstants.MAGIC);
        buf.put(version);
        buf.put(messageType);
        buf.putLong(nodeId.getMostSignificantBits());
        buf.putLong(nodeId.getLeastSignificantBits());
        buf.putShort((short) tcpPort);
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        return buf.array();
    }

    /**
     * Deserializes and validates a binary discovery message from raw UDP packet data.
     *
     * @param data raw packet bytes
     * @param length number of valid bytes in packet
     * @return parsed and validated DiscoveryMessage
     * @throws ProtocolException if the packet is malformed, truncated, or violates protocol limits
     */
    public static DiscoveryMessage decode(byte[] data, int length) throws ProtocolException {
        if (data == null || length < HEADER_SIZE) {
            throw new ProtocolException("Discovery packet too short: expected at least " + HEADER_SIZE + " bytes, got " + length);
        }
        if (length > DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE) {
            throw new ProtocolException("Discovery packet exceeds maximum allowed size (" + length + " > " + DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);

        int magic = buf.getInt();
        if (magic != ProtocolConstants.MAGIC) {
            throw new ProtocolException("Invalid discovery magic: 0x" + Integer.toHexString(magic));
        }

        byte version = buf.get();
        if (version != ProtocolConstants.CURRENT_VERSION) {
            throw new ProtocolException("Unsupported discovery version: " + version);
        }

        byte msgType = buf.get();
        if (msgType != TYPE_BEACON) {
            throw new ProtocolException("Unsupported discovery message type: 0x" + Integer.toHexString(msgType & 0xFF));
        }

        long mostSig = buf.getLong();
        long leastSig = buf.getLong();
        UUID nodeId = new UUID(mostSig, leastSig);

        int tcpPort = buf.getShort() & 0xFFFF;
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new ProtocolException("Invalid TCP port in discovery message: " + tcpPort);
        }

        int nameLength = buf.getShort() & 0xFFFF;
        if (nameLength > ProtocolConstants.MAX_DISPLAY_NAME_BYTES) {
            throw new ProtocolException("Display name length in discovery packet exceeds maximum limit: " + nameLength);
        }

        if (buf.remaining() != nameLength) {
            if (buf.remaining() < nameLength) {
                throw new ProtocolException("Truncated display name in discovery packet: expected " + nameLength + " bytes, remaining: " + buf.remaining());
            } else {
                throw new ProtocolException("Extraneous trailing bytes in discovery packet: expected " + (HEADER_SIZE + nameLength) + " total bytes, got " + length);
            }
        }

        byte[] nameBytes = new byte[nameLength];
        buf.get(nameBytes);
        String displayName = new String(nameBytes, StandardCharsets.UTF_8);

        return new DiscoveryMessage(version, msgType, nodeId, tcpPort, displayName);
    }

    public static DiscoveryMessage decode(byte[] data) throws ProtocolException {
        return decode(data, data != null ? data.length : 0);
    }
}
