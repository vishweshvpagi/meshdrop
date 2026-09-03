package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that chunks received with unexpected offsets or out-of-order indices are rejected.
 */
public class WrongOffsetTest {

    public void runAll() throws Exception {
        testSkippedChunkIndexRejected();
        testWrongOffsetRejected();
    }

    private void testSkippedChunkIndexRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("wrong-offset-temp1");
        Path dlDir = Files.createTempDirectory("wrong-offset-dl1");

        try {
            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "test.bin", 100, System.currentTimeMillis(),
                    HashUtils.sha256(new byte[100]));
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, null);

            // Attempt to send chunk 1 when expecting chunk 0
            try {
                receiver.receiveChunk(new FileChunk(tid, 1, 0, 10, new byte[10]));
                assert false : "Expected IOException for skipped chunk index";
            } catch (IOException expected) {}

        } finally {
            cleanupDir(tempDir);
            cleanupDir(dlDir);
        }
    }

    private void testWrongOffsetRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("wrong-offset-temp2");
        Path dlDir = Files.createTempDirectory("wrong-offset-dl2");

        try {
            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "test.bin", 100, System.currentTimeMillis(),
                    HashUtils.sha256(new byte[100]));
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, null);

            // Send chunk 0 with wrong offset (expected 0, sent 10)
            try {
                receiver.receiveChunk(new FileChunk(tid, 0, 10, 10, new byte[10]));
                assert false : "Expected IOException for mismatched chunk offset";
            } catch (IOException expected) {}

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
