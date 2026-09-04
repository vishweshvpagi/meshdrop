package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Binary serialization and deserialization codec for all file transfer packet payloads.
 *
 * Enforces explicit field lengths, big-endian byte order, bounds validation,
 * and UTF-8 encoding. Never throws raw ArrayIndexOutOfBounds exceptions.
 */
public final class FileTransferCodec {

    public static final int OFFER_FIXED_HEADER_BYTES = 130;
    public static final int ACCEPT_PAYLOAD_BYTES = 16;
    public static final int CHUNK_FIXED_HEADER_BYTES = 32;
    public static final int COMPLETE_PAYLOAD_BYTES = 92;
    public static final int ACK_PAYLOAD_BYTES = 25;
    public static final int EXTENDED_ACK_PAYLOAD_BYTES = 41;
    public static final int RESUME_REQUEST_PAYLOAD_BYTES = 124;
    public static final int RESUME_RESPONSE_FIXED_HEADER_BYTES = 39;

    private FileTransferCodec() {}

    // ========================================================================
    // 1. FILE_OFFER (0x10)
    // ========================================================================

    public static byte[] encodeOffer(FileMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");

        byte[] nameBytes = metadata.fileName().getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 255) {
            throw new IllegalArgumentException("Encoded filename exceeds 255 bytes: " + nameBytes.length);
        }

        byte[] shaBytes = metadata.sha256().getBytes(StandardCharsets.US_ASCII);
        if (shaBytes.length != 64) {
            throw new IllegalArgumentException("SHA-256 string must be exactly 64 ASCII characters");
        }

        ByteBuffer buf = ByteBuffer.allocate(OFFER_FIXED_HEADER_BYTES + nameBytes.length);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putLong(metadata.transferId().getMostSignificantBits());
        buf.putLong(metadata.transferId().getLeastSignificantBits());

        buf.putLong(metadata.senderId().getMostSignificantBits());
        buf.putLong(metadata.senderId().getLeastSignificantBits());

        buf.putLong(metadata.recipientId().getMostSignificantBits());
        buf.putLong(metadata.recipientId().getLeastSignificantBits());

        buf.putLong(metadata.fileSize());
        buf.putLong(metadata.createdAt());

        buf.put(shaBytes); // 64 bytes

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        return buf.array();
    }

    public static FileMetadata decodeOffer(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < OFFER_FIXED_HEADER_BYTES) {
            throw new ProtocolException("FILE_OFFER payload truncated: expected at least " +
                    OFFER_FIXED_HEADER_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);

        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        UUID senderId = new UUID(buf.getLong(), buf.getLong());
        UUID recipientId = new UUID(buf.getLong(), buf.getLong());

        long fileSize = buf.getLong();
        if (fileSize < 0) {
            throw new ProtocolException("Negative file size in FILE_OFFER: " + fileSize);
        }

        long createdAt = buf.getLong();
        if (createdAt <= 0) {
            throw new ProtocolException("Invalid createdAt timestamp in FILE_OFFER: " + createdAt);
        }

        byte[] shaBytes = new byte[64];
        buf.get(shaBytes);
        String sha256 = new String(shaBytes, StandardCharsets.US_ASCII);

        short nameLen = buf.getShort();
        if (nameLen <= 0 || nameLen > 255) {
            throw new ProtocolException("Invalid filename length in FILE_OFFER: " + nameLen);
        }

        if (payload.length != OFFER_FIXED_HEADER_BYTES + nameLen) {
            throw new ProtocolException("FILE_OFFER size mismatch: expected " +
                    (OFFER_FIXED_HEADER_BYTES + nameLen) + " bytes, got " + payload.length);
        }

        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String fileName = new String(nameBytes, StandardCharsets.UTF_8);

        try {
            return new FileMetadata(transferId, senderId, recipientId, fileName, fileSize, createdAt, sha256);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid metadata in FILE_OFFER: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // 2. FILE_ACCEPT (0x15)
    // ========================================================================

    public static byte[] encodeAccept(UUID transferId) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        ByteBuffer buf = ByteBuffer.allocate(ACCEPT_PAYLOAD_BYTES);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        return buf.array();
    }

    public static UUID decodeAccept(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length != ACCEPT_PAYLOAD_BYTES) {
            throw new ProtocolException("Invalid FILE_ACCEPT payload length: expected " +
                    ACCEPT_PAYLOAD_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        return new UUID(buf.getLong(), buf.getLong());
    }

    // ========================================================================
    // 3. FILE_REJECT (0x16)
    // ========================================================================

    public static byte[] encodeReject(UUID transferId, String reason) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        String safeReason = reason != null ? reason : "DECLINED";
        byte[] reasonBytes = safeReason.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(18 + reasonBytes.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.putShort((short) reasonBytes.length);
        buf.put(reasonBytes);
        return buf.array();
    }

    public record RejectPayload(UUID transferId, String reason) {}

    public static RejectPayload decodeReject(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 18) {
            throw new ProtocolException("Invalid FILE_REJECT payload length: expected at least 18 bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        short reasonLen = buf.getShort();
        if (reasonLen < 0 || payload.length != 18 + reasonLen) {
            throw new ProtocolException("FILE_REJECT size mismatch: expected " + (18 + reasonLen) + " bytes, got " + payload.length);
        }
        byte[] reasonBytes = new byte[reasonLen];
        buf.get(reasonBytes);
        return new RejectPayload(transferId, new String(reasonBytes, StandardCharsets.UTF_8));
    }

    // ========================================================================
    // 4. FILE_CHUNK (0x11)
    // ========================================================================

    public static byte[] encodeChunk(FileChunk chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        ByteBuffer buf = ByteBuffer.allocate(CHUNK_FIXED_HEADER_BYTES + chunk.length());
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putLong(chunk.transferId().getMostSignificantBits());
        buf.putLong(chunk.transferId().getLeastSignificantBits());
        buf.putInt(chunk.chunkIndex());
        buf.putLong(chunk.offset());
        buf.putInt(chunk.length());
        buf.put(chunk.data());

        return buf.array();
    }

    public static FileChunk decodeChunk(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < CHUNK_FIXED_HEADER_BYTES) {
            throw new ProtocolException("FILE_CHUNK payload truncated: expected at least " +
                    CHUNK_FIXED_HEADER_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);

        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        int chunkIndex = buf.getInt();
        long offset = buf.getLong();
        int length = buf.getInt();

        if (chunkIndex < 0) {
            throw new ProtocolException("Negative chunkIndex in FILE_CHUNK: " + chunkIndex);
        }
        if (offset < 0) {
            throw new ProtocolException("Negative offset in FILE_CHUNK: " + offset);
        }
        if (length <= 0) {
            throw new ProtocolException("Non-positive length in FILE_CHUNK: " + length);
        }
        if (length > ProtocolConstants.MAX_FILE_CHUNK_SIZE) {
            throw new ProtocolException("Chunk length exceeds maximum allowable chunk size: " + length);
        }
        if (payload.length != CHUNK_FIXED_HEADER_BYTES + length) {
            throw new ProtocolException("FILE_CHUNK length mismatch: expected " +
                    (CHUNK_FIXED_HEADER_BYTES + length) + " bytes, got " + payload.length);
        }

        byte[] data = new byte[length];
        buf.get(data);

        return new FileChunk(transferId, chunkIndex, offset, length, data);
    }

    // ========================================================================
    // 5. FILE_COMPLETE (0x12)
    // ========================================================================

    public record CompletePayload(UUID transferId, int totalChunks, long totalBytes, String sha256) {}

    public static byte[] encodeComplete(UUID transferId, int totalChunks, long totalBytes, String sha256) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");

        byte[] shaBytes = sha256.getBytes(StandardCharsets.US_ASCII);
        if (shaBytes.length != 64) {
            throw new IllegalArgumentException("SHA-256 string must be exactly 64 ASCII characters");
        }

        ByteBuffer buf = ByteBuffer.allocate(COMPLETE_PAYLOAD_BYTES);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.putInt(totalChunks);
        buf.putLong(totalBytes);
        buf.put(shaBytes);

        return buf.array();
    }

    public static CompletePayload decodeComplete(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length != COMPLETE_PAYLOAD_BYTES) {
            throw new ProtocolException("Invalid FILE_COMPLETE payload length: expected " +
                    COMPLETE_PAYLOAD_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);

        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        int totalChunks = buf.getInt();
        long totalBytes = buf.getLong();

        if (totalChunks < 0) {
            throw new ProtocolException("Negative totalChunks in FILE_COMPLETE: " + totalChunks);
        }
        if (totalBytes < 0) {
            throw new ProtocolException("Negative totalBytes in FILE_COMPLETE: " + totalBytes);
        }

        byte[] shaBytes = new byte[64];
        buf.get(shaBytes);
        String sha256 = new String(shaBytes, StandardCharsets.US_ASCII);

        return new CompletePayload(transferId, totalChunks, totalBytes, sha256);
    }

    // ========================================================================
    // 6. FILE_ACK (0x17)
    // ========================================================================

    public record AckPayload(
            UUID transferId,
            boolean success,
            long ackTimestamp,
            long highestContiguousChunk,
            long receiverOffset
    ) {
        public AckPayload(UUID transferId, boolean success, long ackTimestamp) {
            this(transferId, success, ackTimestamp, -1L, -1L);
        }

        public boolean isWindowAck() {
            return highestContiguousChunk >= 0;
        }
    }

    public static byte[] encodeAck(UUID transferId, boolean success, long ackTimestamp) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        ByteBuffer buf = ByteBuffer.allocate(ACK_PAYLOAD_BYTES);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.put((byte) (success ? 1 : 0));
        buf.putLong(ackTimestamp);
        return buf.array();
    }

    public static byte[] encodeChunkAck(UUID transferId, long highestContiguousChunk, long receiverOffset, long ackTimestamp) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        ByteBuffer buf = ByteBuffer.allocate(EXTENDED_ACK_PAYLOAD_BYTES);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.put((byte) 1);
        buf.putLong(ackTimestamp);
        buf.putLong(highestContiguousChunk);
        buf.putLong(receiverOffset);
        return buf.array();
    }

    public static AckPayload decodeAck(byte[] payload) throws ProtocolException {
        if (payload == null || (payload.length != ACK_PAYLOAD_BYTES && payload.length != EXTENDED_ACK_PAYLOAD_BYTES)) {
            throw new ProtocolException("Invalid FILE_ACK payload length: expected " +
                    ACK_PAYLOAD_BYTES + " or " + EXTENDED_ACK_PAYLOAD_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        boolean success = buf.get() == 1;
        long ackTimestamp = buf.getLong();
        long highestContiguousChunk = -1L;
        long receiverOffset = -1L;
        if (payload.length == EXTENDED_ACK_PAYLOAD_BYTES) {
            highestContiguousChunk = buf.getLong();
            receiverOffset = buf.getLong();
        }

        return new AckPayload(transferId, success, ackTimestamp, highestContiguousChunk, receiverOffset);
    }

    // ========================================================================
    // 7. FILE_ERROR (0x18)
    // ========================================================================

    public record ErrorPayload(UUID transferId, String message) {}

    public static byte[] encodeError(UUID transferId, String message) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        String safeMsg = message != null ? message : "Unknown error";
        byte[] msgBytes = safeMsg.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(18 + msgBytes.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());
        buf.putShort((short) msgBytes.length);
        buf.put(msgBytes);

        return buf.array();
    }

    public static ErrorPayload decodeError(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 18) {
            throw new ProtocolException("Invalid FILE_ERROR payload length: expected at least 18 bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        short len = buf.getShort();
        if (len < 0 || payload.length != 18 + len) {
            throw new ProtocolException("FILE_ERROR size mismatch: expected " + (18 + len) + " bytes, got " + payload.length);
        }
        byte[] msgBytes = new byte[len];
        buf.get(msgBytes);
        return new ErrorPayload(transferId, new String(msgBytes, StandardCharsets.UTF_8));
    }

    // ========================================================================
    // 8. FILE_RESUME_REQUEST (0x13)
    // ========================================================================

    public record ResumeRequestPayload(
            UUID transferId,
            UUID senderId,
            UUID recipientId,
            long fileSize,
            int chunkSize,
            String expectedSha256
    ) {}

    public static byte[] encodeResumeRequest(ResumeRequestPayload request) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.transferId(), "transferId must not be null");
        Objects.requireNonNull(request.senderId(), "senderId must not be null");
        Objects.requireNonNull(request.recipientId(), "recipientId must not be null");
        Objects.requireNonNull(request.expectedSha256(), "expectedSha256 must not be null");

        if (request.fileSize() < 0) {
            throw new IllegalArgumentException("fileSize must not be negative: " + request.fileSize());
        }
        if (request.chunkSize() <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + request.chunkSize());
        }
        byte[] shaBytes = request.expectedSha256().getBytes(StandardCharsets.US_ASCII);
        if (shaBytes.length != 64) {
            throw new IllegalArgumentException("expectedSha256 must be exactly 64 ASCII characters");
        }

        ByteBuffer buf = ByteBuffer.allocate(RESUME_REQUEST_PAYLOAD_BYTES);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(request.transferId().getMostSignificantBits());
        buf.putLong(request.transferId().getLeastSignificantBits());
        buf.putLong(request.senderId().getMostSignificantBits());
        buf.putLong(request.senderId().getLeastSignificantBits());
        buf.putLong(request.recipientId().getMostSignificantBits());
        buf.putLong(request.recipientId().getLeastSignificantBits());
        buf.putLong(request.fileSize());
        buf.putInt(request.chunkSize());
        buf.put(shaBytes);

        return buf.array();
    }

    public static ResumeRequestPayload decodeResumeRequest(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length != RESUME_REQUEST_PAYLOAD_BYTES) {
            throw new ProtocolException("Invalid FILE_RESUME_REQUEST payload length: expected " +
                    RESUME_REQUEST_PAYLOAD_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        UUID senderId = new UUID(buf.getLong(), buf.getLong());
        UUID recipientId = new UUID(buf.getLong(), buf.getLong());
        long fileSize = buf.getLong();
        int chunkSize = buf.getInt();

        if (fileSize < 0) {
            throw new ProtocolException("Negative fileSize in FILE_RESUME_REQUEST: " + fileSize);
        }
        if (chunkSize <= 0) {
            throw new ProtocolException("Invalid chunkSize in FILE_RESUME_REQUEST: " + chunkSize);
        }

        byte[] shaBytes = new byte[64];
        buf.get(shaBytes);
        String expectedSha256 = new String(shaBytes, StandardCharsets.US_ASCII);

        return new ResumeRequestPayload(transferId, senderId, recipientId, fileSize, chunkSize, expectedSha256);
    }

    // ========================================================================
    // 9. FILE_RESUME_RESPONSE (0x14)
    // ========================================================================

    public record ResumeResponsePayload(
            UUID transferId,
            ResumeStatus status,
            int nextExpectedChunk,
            long nextExpectedOffset,
            long bytesReceived,
            String reason
    ) {}

    public static byte[] encodeResumeResponse(ResumeResponsePayload response) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(response.transferId(), "transferId must not be null");
        Objects.requireNonNull(response.status(), "status must not be null");

        String safeReason = response.reason() != null ? response.reason() : "";
        byte[] reasonBytes = safeReason.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(RESUME_RESPONSE_FIXED_HEADER_BYTES + reasonBytes.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(response.transferId().getMostSignificantBits());
        buf.putLong(response.transferId().getLeastSignificantBits());
        buf.put(response.status().getCode());
        buf.putInt(response.nextExpectedChunk());
        buf.putLong(response.nextExpectedOffset());
        buf.putLong(response.bytesReceived());
        buf.putShort((short) reasonBytes.length);
        buf.put(reasonBytes);

        return buf.array();
    }

    public static ResumeResponsePayload decodeResumeResponse(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < RESUME_RESPONSE_FIXED_HEADER_BYTES) {
            throw new ProtocolException("FILE_RESUME_RESPONSE payload truncated: expected at least " +
                    RESUME_RESPONSE_FIXED_HEADER_BYTES + " bytes, got " + (payload == null ? "null" : payload.length));
        }

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        UUID transferId = new UUID(buf.getLong(), buf.getLong());
        byte statusCode = buf.get();
        ResumeStatus status;
        try {
            status = ResumeStatus.fromCode(statusCode);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Invalid status code in FILE_RESUME_RESPONSE: " + statusCode, e);
        }

        int nextExpectedChunk = buf.getInt();
        long nextExpectedOffset = buf.getLong();
        long bytesReceived = buf.getLong();
        short reasonLen = buf.getShort();

        if (nextExpectedChunk < 0) {
            throw new ProtocolException("Negative nextExpectedChunk in FILE_RESUME_RESPONSE: " + nextExpectedChunk);
        }
        if (nextExpectedOffset < 0) {
            throw new ProtocolException("Negative nextExpectedOffset in FILE_RESUME_RESPONSE: " + nextExpectedOffset);
        }
        if (bytesReceived < 0) {
            throw new ProtocolException("Negative bytesReceived in FILE_RESUME_RESPONSE: " + bytesReceived);
        }
        if (reasonLen < 0 || payload.length != RESUME_RESPONSE_FIXED_HEADER_BYTES + reasonLen) {
            throw new ProtocolException("FILE_RESUME_RESPONSE reason length mismatch: expected " +
                    (RESUME_RESPONSE_FIXED_HEADER_BYTES + reasonLen) + " bytes, got " + payload.length);
        }

        byte[] reasonBytes = new byte[reasonLen];
        buf.get(reasonBytes);
        String reason = new String(reasonBytes, StandardCharsets.UTF_8);

        return new ResumeResponsePayload(transferId, status, nextExpectedChunk, nextExpectedOffset, bytesReceived, reason);
    }
}
