package com.meshdrop.transfer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for TransferManager lifecycle and thread safety.
 */
public class TransferManagerTest {

    public void runAll() throws Exception {
        testRegisterAndLookup();
        testDuplicateRejection();
        testCancelAndStop();
        testConcurrentRegistration();
    }

    private void testRegisterAndLookup() {
        TransferManager tm = new TransferManager();
        UUID tid = UUID.randomUUID();
        FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "test.iso", 1000L, 1000L, "0".repeat(64));
        Transfer t = new Transfer(meta, TransferDirection.UPLOAD, null);

        tm.registerTransfer(t);
        assert tm.getTransfer(tid).isPresent();
        assert tm.getTransfer(tid).get().equals(t);
        assert tm.getAllTransfers().size() == 1;
        assert tm.getActiveTransfers().size() == 1;

        tm.removeTransfer(tid);
        assert tm.getTransfer(tid).isEmpty();
    }

    private void testDuplicateRejection() {
        TransferManager tm = new TransferManager();
        UUID tid = UUID.randomUUID();
        FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "test.iso", 1000L, 1000L, "0".repeat(64));
        Transfer t1 = new Transfer(meta, TransferDirection.UPLOAD, null);
        Transfer t2 = new Transfer(meta, TransferDirection.DOWNLOAD, null);

        tm.registerTransfer(t1);
        boolean thrown = false;
        try {
            tm.registerTransfer(t2);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Duplicate transfer ID registration must be rejected";
    }

    private void testCancelAndStop() {
        TransferManager tm = new TransferManager();
        UUID tid = UUID.randomUUID();
        FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "test.iso", 1000L, 1000L, "0".repeat(64));
        Transfer t = new Transfer(meta, TransferDirection.UPLOAD, null);
        tm.registerTransfer(t);

        tm.cancelTransfer(tid);
        assert t.getState() == TransferState.CANCELLED;
        assert tm.getActiveTransfers().isEmpty();

        // Register another and test stop()
        UUID tid2 = UUID.randomUUID();
        FileMetadata meta2 = new FileMetadata(tid2, UUID.randomUUID(), UUID.randomUUID(), "test2.iso", 1000L, 1000L, "0".repeat(64));
        Transfer t2 = new Transfer(meta2, TransferDirection.UPLOAD, null);
        tm.registerTransfer(t2);

        tm.stop();
        assert t2.getState() == TransferState.CANCELLED;
        assert tm.getActiveTransfers().isEmpty();
    }

    private void testConcurrentRegistration() throws Exception {
        TransferManager tm = new TransferManager();
        int count = 50;
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    UUID tid = UUID.randomUUID();
                    FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "file-" + index + ".bin", 100L, 1000L, "0".repeat(64));
                    Transfer t = new Transfer(meta, TransferDirection.UPLOAD, null);
                    tm.registerTransfer(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assert finished : "Concurrent registrations timed out";
        assert tm.getAllTransfers().size() == count;
    }
}
