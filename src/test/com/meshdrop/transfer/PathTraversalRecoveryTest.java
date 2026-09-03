package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.PeerManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that the recovery scanning engine strictly blocks path traversal payloads
 * injected into checkpoint metadata files.
 */
public class PathTraversalRecoveryTest {

    public void runAll() throws Exception {
        testPathTraversalCheckpointsRejected();
    }

    private void testPathTraversalCheckpointsRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("path-trav-temp");
        Path dlDir = Files.createTempDirectory("path-trav-dl");

        try {
            RecoveryManager rm = new RecoveryManager(tempDir);
            UUID tid = UUID.randomUUID();

            // Malicious metadata string attempting path traversal
            String maliciousMeta =
                    "transferId=" + tid + "\n" +
                    "senderId=" + UUID.randomUUID() + "\n" +
                    "recipientId=" + UUID.randomUUID() + "\n" +
                    "fileName=../../../../evil.sh\n" +
                    "fileSize=100\n" +
                    "chunkSize=50\n" +
                    "nextExpectedChunk=1\n" +
                    "nextExpectedOffset=50\n" +
                    "bytesReceived=50\n" +
                    "expectedSha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
                    "lastUpdated=1000\n";

            // Write directly to recovery directory
            Path metaPath = rm.getMetaFilePath(tid);
            Files.writeString(metaPath, maliciousMeta);
            Files.write(rm.getPartFilePath(tid), new byte[50]);

            // 1. Direct deserialize must throw IllegalArgumentException
            try {
                TransferCheckpoint.deserialize(maliciousMeta);
                assert false : "Expected IllegalArgumentException for path traversal filename in checkpoint";
            } catch (IllegalArgumentException expected) {}

            // 2. Recovery scanner must safely ignore this checkpoint
            var recovered = rm.scanRecoverableCheckpoints();
            assert recovered.isEmpty() : "Malicious checkpoint must not be discovered or registered";

            // 3. Service boot scan must ignore it
            FileTransferService service = new FileTransferService(NodeIdentity.of(UUID.randomUUID(), "Node"), new PeerManager(UUID.randomUUID()), dlDir, tempDir);
            service.scanAndRegisterRecoverableTransfers();

            assert service.getTransferManager().getResumableTransfers().isEmpty();

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
