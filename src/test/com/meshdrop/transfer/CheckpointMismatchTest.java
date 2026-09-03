package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that when actual on-disk partial file length does not match checkpoint bytes,
 * the inconsistency is caught and resume is rejected.
 */
public class CheckpointMismatchTest {

    public void runAll() throws Exception {
        testMismatchRejected();
    }

    private void testMismatchRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("mismatch-temp");
        Path dlDir = Files.createTempDirectory("mismatch-dl");

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            RecoveryManager rm = new RecoveryManager(tempDir);
            String sha = HashUtils.sha256("dummy".getBytes());

            // Checkpoint claims 100 bytes received
            TransferCheckpoint cp = new TransferCheckpoint(
                    tid, sid, rid, "file.dat", 1000L, 100, 1, 100L, 100L, sha, System.currentTimeMillis()
            );
            rm.saveCheckpoint(cp);

            // But partial file has only 50 bytes
            Path partPath = rm.getPartFilePath(tid);
            Files.write(partPath, new byte[50]);

            // 1. Verify RecoveryManager flags inconsistency
            assert !rm.verifyConsistency(cp) : "Consistency check must fail when disk size != checkpoint bytes";

            // 2. Initializing FileReceiver with inconsistent checkpoint throws IOException
            FileMetadata meta = new FileMetadata(tid, sid, rid, "file.dat", 1000L, System.currentTimeMillis(), sha);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);

            try {
                new FileReceiver(meta, dlDir, tempDir, transfer, rm, cp, null);
                assert false : "Expected IOException when resuming with mismatched file size";
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
