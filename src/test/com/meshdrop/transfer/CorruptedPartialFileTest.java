package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that if the byte contents of a partial file on disk are tampered with or corrupted,
 * the final SHA-256 validation catches the corruption, aborts the transfer, and deletes the temporary files.
 */
public class CorruptedPartialFileTest {

    public void runAll() throws Exception {
        testTamperedPartialFileFailsVerification();
    }

    private void testTamperedPartialFileFailsVerification() throws Exception {
        Path tempDir = Files.createTempDirectory("corrupt-temp");
        Path dlDir = Files.createTempDirectory("corrupt-dl");

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            byte[] originalChunk0 = "CHUNK_0_ORIGINAL_BYTES".getBytes();
            byte[] originalChunk1 = "CHUNK_1_ORIGINAL_BYTES".getBytes();
            int totalBytes = originalChunk0.length + originalChunk1.length;

            byte[] fullOriginal = new byte[totalBytes];
            System.arraycopy(originalChunk0, 0, fullOriginal, 0, originalChunk0.length);
            System.arraycopy(originalChunk1, 0, fullOriginal, originalChunk0.length, originalChunk1.length);
            String correctSha = HashUtils.sha256(fullOriginal);

            FileMetadata meta = new FileMetadata(tid, sid, rid, "tampered.bin", totalBytes, System.currentTimeMillis(), correctSha);
            RecoveryManager rm = new RecoveryManager(tempDir);

            // Save checkpoint for chunk 0
            TransferCheckpoint cp = new TransferCheckpoint(
                    tid, sid, rid, "tampered.bin", totalBytes, originalChunk0.length, 1,
                    (long) originalChunk0.length, (long) originalChunk0.length, correctSha, System.currentTimeMillis()
            );
            rm.saveCheckpoint(cp);

            // Write TAMPERED bytes to .part file (same length, corrupted bytes)
            byte[] tamperedChunk0 = new byte[originalChunk0.length];
            System.arraycopy(originalChunk0, 0, tamperedChunk0, 0, originalChunk0.length);
            tamperedChunk0[0] ^= 0xFF; // flip bits in first byte
            Files.write(rm.getPartFilePath(tid), tamperedChunk0);

            // Resume transfer
            Transfer transfer = Transfer.fromCheckpoint(cp, rm.getPartFilePath(tid));
            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, cp, null);

            // Receive chunk 1
            receiver.receiveChunk(new FileChunk(tid, 1, originalChunk0.length, originalChunk1.length, originalChunk1));

            // Complete transfer should fail SHA-256 verification
            try {
                receiver.completeTransfer(2, totalBytes, correctSha);
                assert false : "Expected IOException due to SHA-256 mismatch from tampered partial file";
            } catch (IOException expected) {}

            assert transfer.getState() == TransferState.FAILED : "Transfer state must be FAILED after SHA failure";

            // Temporary part file must be deleted upon abort
            assert !Files.exists(rm.getPartFilePath(tid)) : "Tampered part file must be removed on abort";

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
