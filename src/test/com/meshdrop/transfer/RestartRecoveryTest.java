package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that a restarted node scans its recovery directory, discovers consistent
 * .part + .meta pairs, and registers them as RESUMABLE transfers without auto-starting them.
 */
public class RestartRecoveryTest {

    public void runAll() throws Exception {
        testBootScanRecoversInterruptedTransfer();
    }

    private void testBootScanRecoversInterruptedTransfer() throws Exception {
        Path tempDir = Files.createTempDirectory("restart-rec-temp");
        Path dlDir = Files.createTempDirectory("restart-rec-dl");

        try {
            UUID localId = UUID.randomUUID();
            UUID remoteId = UUID.randomUUID();
            UUID tid1 = UUID.randomUUID();
            UUID tid2Corrupt = UUID.randomUUID();

            NodeIdentity localIdentity = NodeIdentity.of(localId, "NodeA");
            RecoveryManager rm = new RecoveryManager(tempDir);

            String sha = HashUtils.sha256("part1_data".getBytes());

            // 1. Valid interrupted transfer (100 bytes)
            TransferCheckpoint cp1 = new TransferCheckpoint(
                    tid1, remoteId, localId, "part1.bin", 500L, 100, 1, 100L, 100L, sha, System.currentTimeMillis()
            );
            rm.saveCheckpoint(cp1);
            Files.write(rm.getPartFilePath(tid1), new byte[100]);

            // 2. Corrupted transfer (checkpoint says 200 bytes, part file has 50 bytes)
            TransferCheckpoint cp2 = new TransferCheckpoint(
                    tid2Corrupt, remoteId, localId, "part2.bin", 500L, 100, 2, 200L, 200L, sha, System.currentTimeMillis()
            );
            rm.saveCheckpoint(cp2);
            Files.write(rm.getPartFilePath(tid2Corrupt), new byte[50]);

            // Now start a fresh FileTransferService (simulating node boot)
            PeerManager pm = new PeerManager(localId);
            FileTransferService newService = new FileTransferService(localIdentity, pm, dlDir, tempDir);

            // Execute boot scan
            newService.scanAndRegisterRecoverableTransfers();

            TransferManager tm = newService.getTransferManager();

            // Verification
            var resumable = tm.getResumableTransfers();
            assert resumable.size() == 1 : "Expected exactly 1 valid recoverable transfer, got " + resumable.size();

            Transfer recovered = resumable.get(0);
            assert recovered.getTransferId().equals(tid1) : "Recovered transfer ID mismatch";
            assert recovered.getState() == TransferState.RESUMABLE : "State must be RESUMABLE";
            assert recovered.getBytesTransferred() == 100L : "Bytes transferred must match checkpoint";
            assert recovered.getChunksTransferred() == 1 : "Chunks transferred must match checkpoint";

            // Corrupted transfer must NOT be registered
            assert tm.getTransfer(tid2Corrupt).isEmpty() : "Corrupted transfer must not be registered";

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
