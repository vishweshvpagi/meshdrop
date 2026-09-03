package com.meshdrop.transfer;

import java.util.UUID;

/**
 * Unit tests for ChunkManager calculations.
 */
public class ChunkManagerTest {

    public void runAll() throws Exception {
        testChunkCalculations();
        testPartialLastChunk();
    }

    private void testChunkCalculations() {
        long fileSize = 10 * 1024 * 1024; // 10 MiB
        int chunkSize = 1024 * 1024;      // 1 MiB
        int totalChunks = ChunkManager.calculateTotalChunks(fileSize, chunkSize);
        assert totalChunks == 10 : "Expected 10 chunks, got " + totalChunks;
    }

    private void testPartialLastChunk() {
        long fileSize = (10 * 1024 * 1024) + 500; // 10 MiB + 500 B
        int chunkSize = 1024 * 1024;
        int totalChunks = ChunkManager.calculateTotalChunks(fileSize, chunkSize);
        assert totalChunks == 11 : "Expected 11 chunks, got " + totalChunks;

        UUID transferId = UUID.randomUUID();
        Chunk lastChunk = ChunkManager.createChunk(transferId, 10, fileSize, chunkSize);
        assert lastChunk.length() == 500 : "Last chunk length should be 500 bytes, got " + lastChunk.length();
    }
}
