package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that transient network drops, failed socket connections, and aborted transfers
 * do not corrupt existing checkpoints or leave partially flushed states.
 */
public class NetworkFailureTest {

    public void runAll() throws Exception {
        testNetworkFailurePreservesValidCheckpoint();
    }

    private void testNetworkFailurePreservesValidCheckpoint() throws Exception {
        Path tempDir = Files.createTempDirectory("net-fail-temp");
        Path dlDir = Files.createTempDirectory("net-fail-dl");

        try {
            UUID localId = UUID.randomUUID();
            UUID remoteId = UUID.randomUUID();
            UUID tid = UUID.randomUUID();

            FileTransferService service = new FileTransferService(NodeIdentity.of(localId, "Local"), new PeerManager(localId), dlDir, tempDir);
            RecoveryManager rm = service.getRecoveryManager();

            byte[] validData = "VALID_PARTIAL_BLOCK".getBytes();
            String sha = HashUtils.sha256(validData);

            TransferCheckpoint cp = new TransferCheckpoint(
                    tid, remoteId, localId, "netfail.bin", 1000L, 100, 1, (long) validData.length, (long) validData.length, sha, System.currentTimeMillis()
            );
            rm.saveCheckpoint(cp);
            Files.write(rm.getPartFilePath(tid), validData);

            // Attempt to resume with corrupted network input that fails mid-stream
            FileMetadata meta = new FileMetadata(tid, remoteId, localId, "netfail.bin", 1000L, System.currentTimeMillis(), sha);
            Transfer transfer = Transfer.fromCheckpoint(cp, rm.getPartFilePath(tid));

            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, cp, null);

            // Simulate sudden network abort
            receiver.pauseForInterruption("Network connection timeout");

            // Verify checkpoint and part file remain completely intact and valid
            assert rm.verifyConsistency(cp) : "Checkpoint must remain consistent after network failure";
            assert Files.size(rm.getPartFilePath(tid)) == validData.length;
            assert transfer.getState() == TransferState.RESUMABLE;

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
