package com.meshdrop.transfer;

import java.io.Serializable;
import java.util.UUID;

/**
 * Metadata descriptor for in-progress or completed file transfers.
 */
public record TransferMetadata(
        UUID transferId,
        String filename,
        long fileSize,
        int chunkSize,
        int totalChunks,
        String sha256Hash,
        UUID senderNodeId,
        UUID receiverNodeId
) implements Serializable {}
