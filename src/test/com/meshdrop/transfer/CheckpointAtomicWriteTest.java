package com.meshdrop.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies crash-safe atomic updates of transfer checkpoints using RecoveryManager.
 */
public class CheckpointAtomicWriteTest {

    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public void runAll() throws Exception {
        testSingleAtomicWrite();
        testConcurrentCheckpointWrites();
    }

    private void testSingleAtomicWrite() throws IOException {
        Path tempDir = Files.createTempDirectory("atomic-chk-test");
        try {
            RecoveryManager rm = new RecoveryManager(tempDir);
            UUID tid = UUID.randomUUID();
            TransferCheckpoint cp = new TransferCheckpoint(
                    tid, UUID.randomUUID(), UUID.randomUUID(), "test.dat", 1000, 100, 2, 200, 200, SHA, System.currentTimeMillis()
            );

            rm.saveCheckpoint(cp);

            Path metaPath = rm.getMetaFilePath(tid);
            assert Files.isRegularFile(metaPath) : "Checkpoint .meta file must exist";

            // Verify tmp file does not linger
            Path tmpPath = tempDir.resolve(".transfer-" + tid + ".meta.tmp");
            assert !Files.exists(tmpPath) : "Temporary write file must be replaced/moved";

            var loaded = rm.loadCheckpoint(tid);
            assert loaded.isPresent() : "Saved checkpoint must load cleanly";
            assert loaded.get().nextExpectedChunk() == 2;
            assert loaded.get().nextExpectedOffset() == 200;

        } finally {
            cleanupDir(tempDir);
        }
    }

    private void testConcurrentCheckpointWrites() throws Exception {
        Path tempDir = Files.createTempDirectory("atomic-concurrent-test");
        try {
            RecoveryManager rm = new RecoveryManager(tempDir);
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            int threadCount = 10;
            int iterations = 20;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger errorCount = new AtomicInteger(0);

            try (ExecutorService pool = Executors.newFixedThreadPool(threadCount)) {
                for (int i = 0; i < threadCount; i++) {
                    final int threadId = i;
                    pool.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < iterations; j++) {
                                long bytes = (threadId * 100L) + j;
                                TransferCheckpoint cp = new TransferCheckpoint(
                                        tid, sid, rid, "file.bin", 10000L, 100, j, bytes, bytes, SHA, System.currentTimeMillis()
                                );
                                rm.saveCheckpoint(cp);

                                // Immediately verify uncorrupted read
                                var read = rm.loadCheckpoint(tid);
                                if (read.isEmpty()) {
                                    errorCount.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    });
                }

                latch.countDown();
                pool.shutdown();
                assert pool.awaitTermination(10, TimeUnit.SECONDS);
            }

            assert errorCount.get() == 0 : "No concurrent reads/writes should yield corrupted or empty checkpoints";

            var finalCheck = rm.loadCheckpoint(tid);
            assert finalCheck.isPresent() : "Final checkpoint must be readable and valid";

        } finally {
            cleanupDir(tempDir);
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
