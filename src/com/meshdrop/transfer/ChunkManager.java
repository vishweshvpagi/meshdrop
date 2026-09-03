package com.meshdrop.transfer;

import java.util.UUID;

/**
 * Calculates chunk counts, boundaries, offsets, and chunk dimensions for files.
 */
public class ChunkManager {

    public static int calculateTotalChunks(long fileSize, int chunkSize) {
        if (fileSize <= 0 || chunkSize <= 0) {
            return 0;
        }
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    public static Chunk createChunk(UUID transferId, int chunkIndex, long fileSize, int chunkSize) {
        long offset = (long) chunkIndex * chunkSize;
        int length = (int) Math.min((long) chunkSize, fileSize - offset);
        return new Chunk(transferId, chunkIndex, offset, length);
    }
}
