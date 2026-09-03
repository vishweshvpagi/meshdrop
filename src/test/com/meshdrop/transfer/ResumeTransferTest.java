package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Verifies end-to-end resuming of an interrupted file transfer from verified checkpoint.
 */
public class ResumeTransferTest {

    public void runAll() throws Exception {
        testSuccessfulResumeToEnd();
    }

    private void testSuccessfulResumeToEnd() throws Exception {
        Path tempDir = Files.createTempDirectory("resume-test-temp");
        Path dlDir = Files.createTempDirectory("resume-test-dl");

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            byte[] fullData = new byte[64 * 1024 + 100]; // 65636 bytes (2 chunks)
            for (int i = 0; i < fullData.length; i++) {
                fullData[i] = (byte) (i % 251);
            }
            String sha = HashUtils.sha256(fullData);

            FileMetadata meta = new FileMetadata(tid, sid, rid, "resumable.bin", fullData.length, System.currentTimeMillis(), sha);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            // Phase 1: Receive Chunk 0 (64 KiB)
            FileReceiver receiver1 = new FileReceiver(meta, dlDir, tempDir, transfer, rm, null);
            byte[] chunk0Data = Arrays.copyOfRange(fullData, 0, 65536);
            receiver1.receiveChunk(new FileChunk(tid, 0, 0, 65536, chunk0Data));

            // Interrupt receiver 1
            receiver1.close();
            assert transfer.getState() == TransferState.RESUMABLE;

            TransferCheckpoint cp = rm.loadCheckpoint(tid).orElseThrow();
            assert cp.nextExpectedChunk() == 1;
            assert cp.nextExpectedOffset() == 65536L;

            // Phase 2: Resume with receiver 2 using saved checkpoint
            Transfer resumeTransfer = Transfer.fromCheckpoint(cp, rm.getPartFilePath(tid));
            FileReceiver receiver2 = new FileReceiver(meta, dlDir, tempDir, resumeTransfer, rm, cp, null);

            assert receiver2.getExpectedChunkIndex() == 1;
            assert receiver2.getExpectedOffset() == 65536L;

            // Send remaining Chunk 1 (100 bytes)
            byte[] chunk1Data = Arrays.copyOfRange(fullData, 65536, fullData.length);
            receiver2.receiveChunk(new FileChunk(tid, 1, 65536, chunk1Data.length, chunk1Data));

            // Complete transfer
            Path finalFile = receiver2.completeTransfer(2, fullData.length, sha);

            assert Files.isRegularFile(finalFile) : "Final file must be created";
            assert Files.size(finalFile) == fullData.length : "Final file size must match original data";
            assert Arrays.equals(Files.readAllBytes(finalFile), fullData) : "Final file content must match original bytes";
            assert resumeTransfer.getState() == TransferState.COMPLETED;

            // Checkpoint must be deleted upon completion
            assert !Files.exists(rm.getMetaFilePath(tid)) : "Checkpoint must be removed after successful completion";

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
