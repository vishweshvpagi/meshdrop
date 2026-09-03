package com.meshdrop.transfer;

import java.util.UUID;

/**
 * Unit test for TransferCheckpoint creation, validation, and key-value serialization.
 */
public class TransferCheckpointTest {

    private static final String VALID_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public void runAll() throws Exception {
        testValidCreationAndSerialization();
        testWithProgress();
        testValidationRules();
        testMalformedDeserialization();
    }

    private void testValidCreationAndSerialization() {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();

        TransferCheckpoint cp = new TransferCheckpoint(
                tid, sid, rid, "photo.jpg", 10000L, 1024, 5, 5120L, 5120L, VALID_SHA, System.currentTimeMillis()
        );

        assert cp.transferId().equals(tid);
        assert cp.senderId().equals(sid);
        assert cp.recipientId().equals(rid);
        assert cp.fileName().equals("photo.jpg");
        assert cp.fileSize() == 10000L;
        assert cp.chunkSize() == 1024;
        assert cp.nextExpectedChunk() == 5;
        assert cp.nextExpectedOffset() == 5120L;
        assert cp.bytesReceived() == 5120L;
        assert cp.expectedSha256().equals(VALID_SHA);

        String serialized = cp.serialize();
        TransferCheckpoint deserialized = TransferCheckpoint.deserialize(serialized);

        assert cp.transferId().equals(deserialized.transferId());
        assert cp.senderId().equals(deserialized.senderId());
        assert cp.recipientId().equals(deserialized.recipientId());
        assert cp.fileName().equals(deserialized.fileName());
        assert cp.fileSize() == deserialized.fileSize();
        assert cp.chunkSize() == deserialized.chunkSize();
        assert cp.nextExpectedChunk() == deserialized.nextExpectedChunk();
        assert cp.nextExpectedOffset() == deserialized.nextExpectedOffset();
        assert cp.bytesReceived() == deserialized.bytesReceived();
        assert cp.expectedSha256().equals(deserialized.expectedSha256());
    }

    private void testWithProgress() {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();

        TransferCheckpoint cp = new TransferCheckpoint(
                tid, sid, rid, "doc.pdf", 2048L, 512, 0, 0L, 0L, VALID_SHA, 1000L
        );

        TransferCheckpoint updated = cp.withProgress(1, 512L, 512L);
        assert updated.nextExpectedChunk() == 1;
        assert updated.nextExpectedOffset() == 512L;
        assert updated.bytesReceived() == 512L;
        assert updated.lastUpdated() >= 1000L;
    }

    private void testValidationRules() {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();

        // Path traversal in filename
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "../test.txt", 100, 10, 0, 0, 0, VALID_SHA, 1));
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "folder/test.txt", 100, 10, 0, 0, 0, VALID_SHA, 1));
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "c:\\test.txt", 100, 10, 0, 0, 0, VALID_SHA, 1));
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, " ", 100, 10, 0, 0, 0, VALID_SHA, 1));

        // Negative values
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", -1, 10, 0, 0, 0, VALID_SHA, 1));
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", 100, 0, 0, 0, 0, VALID_SHA, 1));
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", 100, 10, -1, 0, 0, VALID_SHA, 1));
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", 100, 10, 0, -1, 0, VALID_SHA, 1));

        // Offset mismatch with bytesReceived
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", 100, 10, 1, 10, 20, VALID_SHA, 1));

        // Offset exceeding fileSize
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", 100, 10, 1, 150, 150, VALID_SHA, 1));

        // Invalid SHA
        expectThrows(() -> new TransferCheckpoint(tid, sid, rid, "a.bin", 100, 10, 0, 0, 0, "invalid-sha", 1));
    }

    private void testMalformedDeserialization() {
        expectThrows(() -> TransferCheckpoint.deserialize(null));
        expectThrows(() -> TransferCheckpoint.deserialize(""));
        expectThrows(() -> TransferCheckpoint.deserialize("keywithoutvalue"));
        expectThrows(() -> TransferCheckpoint.deserialize("fileSize=not_a_number\n"));
    }

    private void expectThrows(Runnable r) {
        try {
            r.run();
            assert false : "Expected IllegalArgumentException but none was thrown";
        } catch (IllegalArgumentException expected) {}
    }
}
