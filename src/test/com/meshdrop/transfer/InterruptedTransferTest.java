package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies that a severed connection mid-transfer preserves the partial file,
 * retains the atomic checkpoint, transitions state to RESUMABLE, and notifies listener.
 */
public class InterruptedTransferTest {

    public void runAll() throws Exception {
        testReceiverPreservesArtifactsOnInterruption();
    }

    private void testReceiverPreservesArtifactsOnInterruption() throws Exception {
        Path tempDir = Files.createTempDirectory("interrupted-test-temp");
        Path dlDir = Files.createTempDirectory("interrupted-test-dl");

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            byte[] fullData = "0123456789ABCDEF0123456789ABCDEF".getBytes(); // 32 bytes
            String sha = HashUtils.sha256(fullData);

            FileMetadata meta = new FileMetadata(tid, sid, rid, "data.bin", fullData.length, System.currentTimeMillis(), sha);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            AtomicBoolean interruptedCalled = new AtomicBoolean(false);
            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, new TransferListener() {
                @Override
                public void onTransferInterrupted(Transfer t) {
                    interruptedCalled.set(true);
                }
            });

            // Receive first chunk (16 bytes)
            byte[] chunk1Data = new byte[16];
            System.arraycopy(fullData, 0, chunk1Data, 0, 16);
            FileChunk chunk1 = new FileChunk(tid, 0, 0, 16, chunk1Data);
            receiver.receiveChunk(chunk1);

            assert receiver.getExpectedOffset() == 16;
            assert receiver.getExpectedChunkIndex() == 1;

            // Now simulate abrupt disconnect: receiver is closed before completeTransfer
            receiver.close();

            // Verification
            assert interruptedCalled.get() : "onTransferInterrupted must be notified on disconnect";
            assert transfer.getState() == TransferState.RESUMABLE : "Transfer state must be RESUMABLE, was " + transfer.getState();

            // Crucial: .part file must NOT be deleted!
            Path partPath = rm.getPartFilePath(tid);
            assert Files.isRegularFile(partPath) : "Staging part file must NOT be deleted on disconnect";
            assert Files.size(partPath) == 16 : "Part file size must equal received bytes (16)";

            // Crucial: .meta file must exist and match
            Path metaPath = rm.getMetaFilePath(tid);
            assert Files.isRegularFile(metaPath) : "Metadata checkpoint file must exist";

            var loadedCp = rm.loadCheckpoint(tid);
            assert loadedCp.isPresent() : "Checkpoint must be readable";
            assert loadedCp.get().nextExpectedChunk() == 1;
            assert loadedCp.get().nextExpectedOffset() == 16;
            assert loadedCp.get().bytesReceived() == 16;

            assert rm.verifyConsistency(loadedCp.get()) : "RecoveryManager must verify consistency as valid";

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
