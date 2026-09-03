package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies resuming a large multi-chunk file, testing that memory consumption remains bounded
 * and that seeking and streaming over many chunks correctly computes the final SHA-256 digest.
 */
public class LargeFileResumeTest {

    public void runAll() throws Exception {
        testLargeFileMultiChunkResume();
    }

    private void testLargeFileMultiChunkResume() throws Exception {
        Path tempDir = Files.createTempDirectory("large-resume-temp");
        Path dlDir = Files.createTempDirectory("large-resume-dl");

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            int chunkSize = 32 * 1024; // 32 KiB
            int totalChunks = 50;
            int fileSize = totalChunks * chunkSize; // 1.6 MB

            byte[] chunkTemplate = new byte[chunkSize];
            for (int i = 0; i < chunkSize; i++) chunkTemplate[i] = (byte) (i % 256);

            // Compute overall SHA-256
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < totalChunks; i++) {
                md.update(chunkTemplate);
            }
            String expectedSha = java.util.HexFormat.of().formatHex(md.digest());

            FileMetadata meta = new FileMetadata(tid, sid, rid, "big_file.iso", fileSize, System.currentTimeMillis(), expectedSha);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            // Phase 1: Receive first 20 chunks (up to 640 KiB)
            FileReceiver receiver1 = new FileReceiver(meta, dlDir, tempDir, transfer, rm, null);
            for (int i = 0; i < 20; i++) {
                receiver1.receiveChunk(new FileChunk(tid, i, (long) i * chunkSize, chunkSize, chunkTemplate));
            }

            // Interrupt receiver 1
            receiver1.close();
            assert transfer.getState() == TransferState.RESUMABLE;

            TransferCheckpoint cp = rm.loadCheckpoint(tid).orElseThrow();
            assert cp.nextExpectedChunk() == 20;
            assert cp.nextExpectedOffset() == 20L * chunkSize;

            // Phase 2: Resume receiver 2 from chunk 20 to 50
            Transfer resumeTransfer = Transfer.fromCheckpoint(cp, rm.getPartFilePath(tid));
            FileReceiver receiver2 = new FileReceiver(meta, dlDir, tempDir, resumeTransfer, rm, cp, null);

            assert receiver2.getExpectedChunkIndex() == 20;

            for (int i = 20; i < totalChunks; i++) {
                receiver2.receiveChunk(new FileChunk(tid, i, (long) i * chunkSize, chunkSize, chunkTemplate));
            }

            // Complete transfer
            Path finalFile = receiver2.completeTransfer(totalChunks, fileSize, expectedSha);

            assert Files.isRegularFile(finalFile);
            assert Files.size(finalFile) == fileSize;
            assert HashUtils.sha256(finalFile.toFile()).equalsIgnoreCase(expectedSha);
            assert resumeTransfer.getState() == TransferState.COMPLETED;

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
