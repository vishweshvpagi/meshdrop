package com.meshdrop.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

/**
 * Decodes streaming binary data from an InputStream into validated Packet instances.
 *
 * Stream-oriented decoding rules:
 *   1. Clean EOF at packet boundary returns null (clean peer disconnect).
 *   2. Partial header or partial payload reads are looped until all required bytes arrive.
 *   3. Truncated frames throw ProtocolException.
 *   4. Invalid magic, unsupported version, unknown packet type, or oversized payload length
 *      are validated and throw ProtocolException before allocating memory buffers.
 */
public class PacketDecoder {

    /**
     * Decodes the next Packet from the input stream.
     *
     * @param in the InputStream to read from
     * @return the decoded Packet, or null if the stream reached clean EOF at a packet boundary
     * @throws IOException if a network error occurs or ProtocolException if the frame is malformed
     */
    public Packet decode(InputStream in) throws IOException {
        Objects.requireNonNull(in, "InputStream must not be null");

        // Read the first byte to detect clean EOF at a packet frame boundary
        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // Clean EOF
        }

        // Buffer for the 28-byte fixed header
        byte[] headerBuffer = new byte[ProtocolConstants.HEADER_LENGTH];
        headerBuffer[0] = (byte) firstByte;

        // Read the remaining 27 bytes of the header
        readFully(in, headerBuffer, 1, ProtocolConstants.HEADER_LENGTH - 1, "header");

        // Parse header fields using ByteBuffer in BIG_ENDIAN
        ByteBuffer buf = ByteBuffer.wrap(headerBuffer).order(ByteOrder.BIG_ENDIAN);

        int magic = buf.getInt();
        if (magic != ProtocolConstants.MAGIC) {
            throw new ProtocolException(String.format(
                    "Invalid magic bytes: 0x%08X (expected 0x%08X)", magic, ProtocolConstants.MAGIC));
        }

        byte version = buf.get();
        if (version != ProtocolConstants.CURRENT_VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + version +
                    " (expected: " + ProtocolConstants.CURRENT_VERSION + ")");
        }

        byte typeCode = buf.get();
        PacketType type;
        try {
            type = PacketType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown packet type: 0x" + Integer.toHexString(typeCode & 0xFF), e);
        }

        short flags = buf.getShort();

        int length = buf.getInt();
        if (length < 0 || length > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new ProtocolException("Illegal payload length: " + length +
                    " (max allowed: " + ProtocolConstants.MAX_PAYLOAD_SIZE + ")");
        }

        long mostSig = buf.getLong();
        long leastSig = buf.getLong();
        UUID requestId = new UUID(mostSig, leastSig);

        // Read payload if present
        byte[] payload;
        if (length > 0) {
            payload = new byte[length];
            readFully(in, payload, 0, length, "payload");
        } else {
            payload = new byte[0];
        }

        return new Packet(magic, version, type, flags, length, requestId, payload);
    }

    /**
     * Helper to read exactly 'length' bytes into the buffer, handling partial stream reads.
     */
    private void readFully(InputStream in, byte[] buffer, int offset, int length, String fieldName) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(buffer, offset + totalRead, length - totalRead);
            if (read == -1) {
                throw new ProtocolException("Unexpected EOF while reading packet " + fieldName +
                        ": expected " + length + " bytes, but only read " + totalRead + " bytes");
            }
            totalRead += read;
        }
    }
}
