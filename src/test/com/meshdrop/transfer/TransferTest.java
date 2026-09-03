package com.meshdrop.transfer;

import java.util.UUID;

/**
 * Unit tests for Transfer model and progress tracking.
 */
public class TransferTest {

    public void runAll() throws Exception {
        testTransferProgress();
    }

    private void testTransferProgress() {
        TransferMetadata metadata = new TransferMetadata(
                UUID.randomUUID(),
                "test.iso",
                1000L,
                100,
                10,
                "dummyhash",
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        Transfer transfer = new Transfer(metadata);
        assert transfer.getStatus() == TransferStatus.QUEUED : "Initial status should be QUEUED";
        assert transfer.getBytesTransferred() == 0 : "Initial bytes should be 0";

        transfer.addBytesTransferred(500L);
        assert Math.abs(transfer.getProgressPercentage() - 50.0) < 0.001 : "Progress should be 50%";
    }
}
