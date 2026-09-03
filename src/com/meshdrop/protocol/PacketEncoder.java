package com.meshdrop.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Encodes Packet objects into standard binary streams using big-endian network byte order.
 *
 * Wire format (28 bytes fixed header + N bytes payload):
 *   - MAGIC (4 bytes, int)
 *   - VERSION (1 byte, byte)
 *   - TYPE (1 byte, byte)
 *   - FLAGS (2 bytes, short)
 *   - LENGTH (4 bytes, int)
 *   - REQUEST ID (16 bytes: 8 bytes mostSigBits, 8 bytes leastSigBits)
 *   - PAYLOAD (N bytes)
 */
public class PacketEncoder {

    /**
     * Encodes a Packet and writes the framed binary data directly to an OutputStream.
     *
     * @param packet the Packet to encode
     * @param out the OutputStream to write to
     * @throws IOException if I/O error occurs or packet is invalid
     */
    public void encode(Packet packet, OutputStream out) throws IOException {
        Objects.requireNonNull(packet, "packet must not be null");
        Objects.requireNonNull(out, "out must not be null");

        if (packet.getLength() > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new ProtocolException("Payload length " + packet.getLength() +
                    " exceeds maximum allowable size " + ProtocolConstants.MAX_PAYLOAD_SIZE);
        }

        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(packet.getMagic());
        dos.writeByte(packet.getVersion());
        dos.writeByte(packet.getType().getCode());
        dos.writeShort(packet.getFlags());
        dos.writeInt(packet.getLength());
        dos.writeLong(packet.getRequestId().getMostSignificantBits());
        dos.writeLong(packet.getRequestId().getLeastSignificantBits());
        if (packet.getLength() > 0) {
            dos.write(packet.getPayload());
        }
        dos.flush();
    }

    /**
     * Encodes a Packet into a new byte array.
     *
     * @param packet the Packet to encode
     * @return byte array containing the complete binary frame
     * @throws IOException if packet is invalid
     */
    public byte[] encodeToBytes(Packet packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(packet.getTotalFrameSize());
        encode(packet, baos);
        return baos.toByteArray();
    }
}
