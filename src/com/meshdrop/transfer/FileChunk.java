package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolConstants;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain model representing a slice of binary data for a file transfer.
 */
public record FileChunk(
        UUID transferId,
        int chunkIndex,
        long offset,
        int length,
        byte[] data
) {

    public FileChunk {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(data, "data must not be null");

        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be non-negative: " + chunkIndex);
        }

        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative: " + offset);
        }

        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }

        if (length > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("length exceeds maximum payload size: " + length);
        }

        if (data.length != length) {
            throw new IllegalArgumentException("data array length (" + data.length + ") does not match specified length (" + length + ")");
        }
    }
}
