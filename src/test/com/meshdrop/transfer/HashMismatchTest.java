package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that corrupt transfers with mismatched hashes fail verification,
 * do not publish corrupted files to the download directory, and clean up temp files.
 */
public class HashMismatchTest {

    public void runAll() throws Exception {
        testCorruptDataFailsVerification();
    }

    private void testCorruptDataFailsVerification() throws Exception {
        Path tempDir = Files.createTempDirectory("hash-mismatch-tmp");
        Path dlDir = Files.createTempDirectory("hash-mismatch-dl");

        try {
            byte[] genuineBytes = "Genuine file contents".getBytes();
            String genuineHash = HashUtils.sha256(genuineBytes);

            byte[] corruptedBytes = "Tampered file contents".getBytes();

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(),
                    "tampered.txt", corruptedBytes.length, 1000L, genuineHash);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);

            boolean threw = false;
            try (FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, null)) {
                receiver.receiveChunk(new FileChunk(tid, 0, 0L, corruptedBytes.length, corruptedBytes));
                receiver.completeTransfer(1, corruptedBytes.length, genuineHash);
            } catch (IOException e) {
                threw = true;
            }

            assert threw : "Expected SHA-256 mismatch to throw IOException";
            assert transfer.getState() == TransferState.FAILED : "Transfer state should be FAILED";
            assert !Files.exists(dlDir.resolve("tampered.txt")) : "Corrupted file must NOT be published in downloads";
            assert !Files.exists(tempDir.resolve(".transfer-" + tid + ".part")) : "Temporary file must be deleted on failure";

        } finally {
            deleteDir(tempDir);
            deleteDir(dlDir);
        }
    }

    private void deleteDir(Path dir) {
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
