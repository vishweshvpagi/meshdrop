package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that multiple distinct interrupted transfers can be resumed concurrently
 * without cross-transfer interference, corruption, or race conditions.
 */
public class ConcurrentResumeTest {

    public void runAll() throws Exception {
        testMultipleConcurrentResumes();
    }

    private void testMultipleConcurrentResumes() throws Exception {
        Path tempDir = Files.createTempDirectory("concurrent-resume-temp");
        Path dlDir = Files.createTempDirectory("concurrent-resume-dl");

        int count = 5;
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            RecoveryManager rm = new RecoveryManager(tempDir);
            List<UUID> transferIds = new ArrayList<>();
            List<byte[]> dataList = new ArrayList<>();
            List<FileMetadata> metaList = new ArrayList<>();
            List<TransferCheckpoint> checkpoints = new ArrayList<>();

            // Prepare 5 partial transfers, each 32 KiB, interrupted after 16 KiB
            for (int i = 0; i < count; i++) {
                UUID tid = UUID.randomUUID();
                transferIds.add(tid);

                byte[] data = new byte[32 * 1024];
                Arrays.fill(data, (byte) (i + 1));
                dataList.add(data);

                String sha = HashUtils.sha256(data);
                FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "file_" + i + ".bin", data.length, System.currentTimeMillis(), sha);
                metaList.add(meta);

                TransferCheckpoint cp = new TransferCheckpoint(
                        tid, meta.senderId(), meta.recipientId(), meta.fileName(), data.length, 16 * 1024, 1, 16384L, 16384L, sha, System.currentTimeMillis()
                );
                checkpoints.add(cp);
                rm.saveCheckpoint(cp);

                // Write first 16 KiB to part file
                Files.write(rm.getPartFilePath(tid), Arrays.copyOfRange(data, 0, 16384));
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger completedCount = new AtomicInteger(0);

            for (int i = 0; i < count; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        FileMetadata meta = metaList.get(idx);
                        TransferCheckpoint cp = checkpoints.get(idx);
                        byte[] fullData = dataList.get(idx);

                        Transfer transfer = Transfer.fromCheckpoint(cp, rm.getPartFilePath(cp.transferId()));
                        FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, transfer, rm, cp, null);

                        // Send chunk 1 (second 16 KiB)
                        byte[] chunk1Data = Arrays.copyOfRange(fullData, 16384, fullData.length);
                        receiver.receiveChunk(new FileChunk(meta.transferId(), 1, 16384, chunk1Data.length, chunk1Data));

                        Path finalPath = receiver.completeTransfer(2, fullData.length, meta.sha256());
                        if (Files.isRegularFile(finalPath) && Files.size(finalPath) == fullData.length) {
                            completedCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            startLatch.countDown();
            pool.shutdown();
            assert pool.awaitTermination(10, TimeUnit.SECONDS);

            assert completedCount.get() == count : "All " + count + " concurrent resumes must succeed";

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
