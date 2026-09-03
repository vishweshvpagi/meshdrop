package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies explicit transfer cancellation, transition to CANCELLED,
 * artifact cleanup (.part and .meta deletion), and listener notification.
 */
public class CancelTransferTest {

    public void runAll() throws Exception {
        testCancelCleansArtifactsAndNotifies();
    }

    private void testCancelCleansArtifactsAndNotifies() throws Exception {
        Path tempDir = Files.createTempDirectory("cancel-temp");
        Path dlDir = Files.createTempDirectory("cancel-dl");

        try {
            UUID localId = UUID.randomUUID();
            UUID remoteId = UUID.randomUUID();
            UUID tid = UUID.randomUUID();

            FileTransferService service = new FileTransferService(NodeIdentity.of(localId, "Local"), new PeerManager(localId), dlDir, tempDir);
            RecoveryManager rm = service.getRecoveryManager();

            AtomicBoolean cancelNotified = new AtomicBoolean(false);
            service.addListener(new TransferListener() {
                @Override
                public void onTransferCancelled(Transfer t) {
                    cancelNotified.set(true);
                }
            });

            String sha = HashUtils.sha256("cancel_data".getBytes());
            FileMetadata meta = new FileMetadata(tid, remoteId, localId, "cancel.dat", 1000L, System.currentTimeMillis(), sha);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            service.getTransferManager().registerTransfer(transfer);

            // Create partial and meta artifacts
            TransferCheckpoint cp = new TransferCheckpoint(
                    tid, remoteId, localId, "cancel.dat", 1000L, 100, 1, 100L, 100L, sha, System.currentTimeMillis()
            );
            rm.saveCheckpoint(cp);
            Files.write(rm.getPartFilePath(tid), new byte[100]);

            assert Files.exists(rm.getMetaFilePath(tid));
            assert Files.exists(rm.getPartFilePath(tid));

            // Cancel transfer
            service.cancelTransfer(tid);

            // Verification
            assert cancelNotified.get() : "onTransferCancelled listener must be notified";
            assert transfer.getState() == TransferState.CANCELLED : "Transfer state must be CANCELLED";

            // Temporary artifacts must be completely removed
            assert !Files.exists(rm.getMetaFilePath(tid)) : ".meta file must be deleted upon cancellation";
            assert !Files.exists(rm.getPartFilePath(tid)) : ".part file must be deleted upon cancellation";

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
