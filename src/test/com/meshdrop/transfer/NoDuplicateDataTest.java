package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Verifies that duplicate chunks (chunks with index < expectedChunkIndex) are safely
 * and idempotently ignored without double-writing data or corrupting the stream.
 */
public class NoDuplicateDataTest {

    public void runAll() throws Exception {
        testDuplicateChunkIgnored();
    }

    private void testDuplicateChunkIgnored() throws Exception {
        Path tempDir = Files.createTempDirectory("no-dup-temp");
        Path dlDir = Files.createTempDirectory("no-dup-dl");

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            byte[] fullData = "CHUNK0_DATA_____CHUNK1_DATA_____".getBytes(); // 32 bytes (two 16B chunks)
            String sha = HashUtils.sha256(fullData);

            FileMetadata meta = new FileMetadata(tid, sid, rid, "nodup.txt", 32, System.currentTimeMillis(), sha);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, null);

            byte[] c0 = Arrays.copyOfRange(fullData, 0, 16);
            byte[] c1 = Arrays.copyOfRange(fullData, 16, 32);

            // 1. Receive chunk 0
            receiver.receiveChunk(new FileChunk(tid, 0, 0, 16, c0));
            assert receiver.getExpectedChunkIndex() == 1;
            assert receiver.getExpectedOffset() == 16;

            // 2. Resend chunk 0 (duplicate retransmission)
            receiver.receiveChunk(new FileChunk(tid, 0, 0, 16, c0));

            // State must remain at chunk 1, offset 16 (not 32)
            assert receiver.getExpectedChunkIndex() == 1 : "Expected chunk index must still be 1";
            assert receiver.getExpectedOffset() == 16 : "Expected offset must still be 16";
            assert Files.size(rm.getPartFilePath(tid)) == 16 : "Part file size must remain 16 bytes";

            // 3. Send chunk 1
            receiver.receiveChunk(new FileChunk(tid, 1, 16, 16, c1));

            // Complete transfer
            Path finalFile = receiver.completeTransfer(2, 32, sha);
            assert Files.size(finalFile) == 32 : "Final file must be exactly 32 bytes";
            assert Arrays.equals(Files.readAllBytes(finalFile), fullData) : "Final file content must match original without duplicates";

        } finally {
            cleanupDir(tempDir);
            cleanupDir(dlDir);
        }
    }

    private void cleanupDir(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
