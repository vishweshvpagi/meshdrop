package com.meshdrop.protocol;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an immutable framed binary protocol message in MeshDrop.
 *
 * Header Format (28 bytes fixed):
 *   - MAGIC (4 bytes): 0x4D445250 ("MDRP")
 *   - VERSION (1 byte): Protocol version (0x01)
 *   - TYPE (1 byte): PacketType wire code
 *   - FLAGS (2 bytes): 16-bit flags
 *   - LENGTH (4 bytes): Payload length in bytes (0 to 16 MiB)
 *   - REQUEST ID (16 bytes): UUID (most significant 8 bytes, least significant 8 bytes)
 * Followed by PAYLOAD (N bytes).
 */
public final class Packet {
    private final int magic;
    private final byte version;
    private final PacketType type;
    private final short flags;
    private final int length;
    private final UUID requestId;
    private final byte[] payload;

    public Packet(int magic, byte version, PacketType type, short flags, int length, UUID requestId, byte[] payload) {
        if (magic != ProtocolConstants.MAGIC) {
            throw new IllegalArgumentException(String.format("Invalid magic: 0x%08X (expected 0x%08X)", magic, ProtocolConstants.MAGIC));
        }
        if (version != ProtocolConstants.CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");

        byte[] safePayload = (payload != null) ? payload : new byte[0];
        if (length < 0 || length > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Illegal payload length: " + length + " (max: " + ProtocolConstants.MAX_PAYLOAD_SIZE + ")");
        }
        if (safePayload.length != length) {
            throw new IllegalArgumentException("Declared length (" + length + ") does not match payload length (" + safePayload.length + ")");
        }

        this.magic = magic;
        this.version = version;
        this.flags = flags;
        this.length = length;
        this.payload = safePayload.clone(); // Defensive copy
    }

    public static Packet of(PacketType type, UUID requestId, byte[] payload) {
        byte[] data = payload != null ? payload : new byte[0];
        return new Packet(
                ProtocolConstants.MAGIC,
                ProtocolConstants.CURRENT_VERSION,
                type,
                PacketFlags.NONE,
                data.length,
                requestId,
                data
        );
    }

    public static Packet of(PacketType type, byte[] payload) {
        return of(type, UUID.randomUUID(), payload);
    }

    public static Packet of(PacketType type) {
        return of(type, UUID.randomUUID(), new byte[0]);
    }

    public static Packet createHello(NodeIdentity identity) {
        return createHello(UUID.randomUUID(), identity);
    }

    public static Packet createHello(UUID requestId, NodeIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        return of(PacketType.HELLO, requestId, identity.encode());
    }

    public static Packet createHello() {
        return createHello(NodeIdentity.createRandom());
    }

    public static Packet createHello(UUID requestId) {
        return createHello(requestId, NodeIdentity.createRandom());
    }

    public static Packet createHelloResponse(UUID requestId, NodeIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        return of(PacketType.HELLO_RESPONSE, requestId, identity.encode());
    }

    public static Packet createHelloResponse(UUID requestId) {
        return createHelloResponse(requestId, NodeIdentity.createRandom());
    }

    public static Packet createPing() {
        return createPing(UUID.randomUUID());
    }

    public static Packet createPing(UUID requestId) {
        return of(PacketType.PING, requestId, new byte[0]);
    }

    public static Packet createPong(UUID requestId) {
        return of(PacketType.PONG, requestId, new byte[0]);
    }

    public static Packet createMessage(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        byte[] payload = com.meshdrop.message.MessageCodec.encode(message);
        return of(PacketType.MESSAGE, message.messageId(), payload);
    }

    public static Packet createMessage(String text) {
        return createMessage(UUID.randomUUID(), text);
    }

    public static Packet createMessage(UUID requestId, String text) {
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return of(PacketType.MESSAGE, requestId, bytes);
    }

    public static Packet createMessageAck(UUID messageId) {
        return createMessageAck(messageId, System.currentTimeMillis());
    }

    public static Packet createMessageAck(UUID messageId, long ackTimestamp) {
        byte[] payload = com.meshdrop.message.MessageCodec.encodeAck(messageId, ackTimestamp);
        return of(PacketType.MESSAGE_ACK, messageId, payload);
    }

    public static Packet createFileOffer(com.meshdrop.transfer.FileMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeOffer(metadata);
        return of(PacketType.FILE_OFFER, metadata.transferId(), payload);
    }

    public static Packet createFileAccept(UUID transferId) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeAccept(transferId);
        return of(PacketType.FILE_ACCEPT, transferId, payload);
    }

    public static Packet createFileReject(UUID transferId, String reason) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeReject(transferId, reason);
        return of(PacketType.FILE_REJECT, transferId, payload);
    }

    public static Packet createFileChunk(com.meshdrop.transfer.FileChunk chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeChunk(chunk);
        return of(PacketType.FILE_CHUNK, chunk.transferId(), payload);
    }

    public static Packet createFileComplete(UUID transferId, int totalChunks, long totalBytes, String sha256) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeComplete(transferId, totalChunks, totalBytes, sha256);
        return of(PacketType.FILE_COMPLETE, transferId, payload);
    }

    public static Packet createFileAck(UUID transferId, boolean success) {
        return createFileAck(transferId, success, System.currentTimeMillis());
    }

    public static Packet createFileAck(UUID transferId, boolean success, long timestamp) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeAck(transferId, success, timestamp);
        return of(PacketType.FILE_ACK, transferId, payload);
    }

    public static Packet createFileChunkAck(UUID transferId, long highestContiguousChunk, long receiverOffset) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeChunkAck(
                transferId, highestContiguousChunk, receiverOffset, System.currentTimeMillis());
        return of(PacketType.FILE_ACK, transferId, payload);
    }

    public static Packet createFileError(UUID transferId, String message) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeError(transferId, message);
        return of(PacketType.FILE_ERROR, transferId, payload);
    }

    public static Packet createFileResumeRequest(com.meshdrop.transfer.FileTransferCodec.ResumeRequestPayload request) {
        Objects.requireNonNull(request, "request must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeResumeRequest(request);
        return of(PacketType.FILE_RESUME_REQUEST, request.transferId(), payload);
    }

    public static Packet createFileResumeResponse(com.meshdrop.transfer.FileTransferCodec.ResumeResponsePayload response) {
        Objects.requireNonNull(response, "response must not be null");
        byte[] payload = com.meshdrop.transfer.FileTransferCodec.encodeResumeResponse(response);
        return of(PacketType.FILE_RESUME_RESPONSE, response.transferId(), payload);
    }

    /**
     * Decodes the payload as a Phase 10 Message.
     */
    public Message decodeMessage() throws ProtocolException {
        return com.meshdrop.message.MessageCodec.decode(payload);
    }

    /**
     * Decodes the payload as a MESSAGE_ACK.
     */
    public com.meshdrop.message.MessageCodec.AckPayload decodeMessageAck() throws ProtocolException {
        return com.meshdrop.message.MessageCodec.decodeAck(payload);
    }

    public com.meshdrop.transfer.FileMetadata decodeFileOffer() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeOffer(payload);
    }

    public UUID decodeFileAccept() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeAccept(payload);
    }

    public com.meshdrop.transfer.FileTransferCodec.RejectPayload decodeFileReject() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeReject(payload);
    }

    public com.meshdrop.transfer.FileChunk decodeFileChunk() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeChunk(payload);
    }

    public com.meshdrop.transfer.FileTransferCodec.CompletePayload decodeFileComplete() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeComplete(payload);
    }

    public com.meshdrop.transfer.FileTransferCodec.AckPayload decodeFileAck() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeAck(payload);
    }

    public com.meshdrop.transfer.FileTransferCodec.ErrorPayload decodeFileError() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeError(payload);
    }

    public com.meshdrop.transfer.FileTransferCodec.ResumeRequestPayload decodeFileResumeRequest() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeResumeRequest(payload);
    }

    public com.meshdrop.transfer.FileTransferCodec.ResumeResponsePayload decodeFileResumeResponse() throws ProtocolException {
        return com.meshdrop.transfer.FileTransferCodec.decodeResumeResponse(payload);
    }

    public static Packet createError(UUID requestId, String errorMessage) {
        byte[] bytes = errorMessage != null ? errorMessage.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return of(PacketType.ERROR, requestId, bytes);
    }

    public int getMagic() {
        return magic;
    }

    public byte getVersion() {
        return version;
    }

    public PacketType getType() {
        return type;
    }

    public short getFlags() {
        return flags;
    }

    public int getLength() {
        return length;
    }

    public UUID getRequestId() {
        return requestId;
    }

    /**
     * Returns a defensive copy of the payload bytes.
     */
    public byte[] getPayload() {
        return payload.clone();
    }

    /**
     * Decodes the payload as a NodeIdentity (for HELLO / HELLO_RESPONSE packets).
     */
    public NodeIdentity decodeIdentity() throws ProtocolException {
        return NodeIdentity.decode(payload);
    }

    public int getTotalFrameSize() {
        return ProtocolConstants.HEADER_LENGTH + length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Packet packet)) return false;
        return magic == packet.magic &&
                version == packet.version &&
                flags == packet.flags &&
                length == packet.length &&
                type == packet.type &&
                requestId.equals(packet.requestId) &&
                Arrays.equals(payload, packet.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(magic, version, type, flags, length, requestId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "Packet{" +
                "type=" + type +
                ", version=" + version +
                ", flags=" + flags +
                ", length=" + length +
                ", requestId=" + requestId +
                '}';
    }
}
