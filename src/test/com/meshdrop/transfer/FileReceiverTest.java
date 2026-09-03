package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

/**
 * Verifies FileReceiver chunk assembly, .part file staging, SHA-256 validation, and cleanup.
 */
public class FileReceiverTest {

    public void runAll() throws Exception {
        testSuccessfulReconstruction();
        testCleanupOnHashMismatch();
        testCleanupOnAbort();
    }

    private void testSuccessfulReconstruction() throws Exception {
        Path tempDir = Files.createTempDirectory("rcv-tmp");
        Path dlDir = Files.createTempDirectory("rcv-dl");

        try {
            int fileSize = 100 * 1024; // 100 KiB
            byte[] data = new byte[fileSize];
            new Random(99).nextBytes(data);
            String expectedHash = HashUtils.sha256(data);

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(),
                    "output.bin", fileSize, 1000L, expectedHash);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);

            Path finalPath;
            try (FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, null)) {
                // Check temp file exists
                Path partFile = tempDir.resolve(".transfer-" + tid + ".part");
                assert Files.exists(partFile) : ".part file must exist during transfer";

                // Feed 2 chunks (64K and 36K)
                byte[] chunk0 = new byte[64 * 1024];
                System.arraycopy(data, 0, chunk0, 0, chunk0.length);
                receiver.receiveChunk(new FileChunk(tid, 0, 0L, chunk0.length, chunk0));

                byte[] chunk1 = new byte[36 * 1024];
                System.arraycopy(data, 64 * 1024, chunk1, 0, chunk1.length);
                receiver.receiveChunk(new FileChunk(tid, 1, 64L * 1024, chunk1.length, chunk1));

                finalPath = receiver.completeTransfer(2, fileSize, expectedHash);
            }

            assert Files.exists(finalPath) : "Final file must exist";
            assert Files.size(finalPath) == fileSize : "Final file size mismatch";
            assert HashUtils.sha256(finalPath.toFile()).equalsIgnoreCase(expectedHash) : "Final file hash mismatch";
            assert !Files.exists(tempDir.resolve(".transfer-" + tid + ".part")) : "Temporary .part file must be removed";
            assert transfer.getState() == TransferState.COMPLETED;

        } finally {
            deleteDir(tempDir);
            deleteDir(dlDir);
        }
    }

    private void testCleanupOnHashMismatch() throws Exception {
        Path tempDir = Files.createTempDirectory("rcv-tmp-mismatch");
        Path dlDir = Files.createTempDirectory("rcv-dl-mismatch");

        try {
            byte[] data = "real content".getBytes();
            String fakeHash = "0".repeat(64);

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(),
                    "bad.txt", data.length, 1000L, fakeHash);
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);

            boolean thrown = false;
            try (FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, null)) {
                receiver.receiveChunk(new FileChunk(tid, 0, 0L, data.length, data));
                receiver.completeTransfer(1, data.length, fakeHash);
            } catch (IOException e) {
                thrown = true;
            }

            assert thrown : "SHA-256 mismatch must throw IOException";
            assert !Files.exists(tempDir.resolve(".transfer-" + tid + ".part")) : "Temp file must be deleted on hash mismatch";
            assert !Files.exists(dlDir.resolve("bad.txt")) : "Corrupt file must NOT be published in downloads";
            assert transfer.getState() == TransferState.FAILED;

        } finally {
            deleteDir(tempDir);
            deleteDir(dlDir);
        }
    }

    private void testCleanupOnAbort() throws Exception {
        Path tempDir = Files.createTempDirectory("rcv-tmp-abort");
        Path dlDir = Files.createTempDirectory("rcv-dl-abort");

        try {
            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(),
                    "abort.txt", 100L, 1000L, "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);

            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, null);
            assert Files.exists(tempDir.resolve(".transfer-" + tid + ".part"));

            receiver.abort("User cancelled");
            assert !Files.exists(tempDir.resolve(".transfer-" + tid + ".part")) : "Temp file must be deleted on abort";
            assert transfer.getState() == TransferState.FAILED;

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
