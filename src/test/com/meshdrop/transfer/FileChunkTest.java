package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolConstants;

import java.util.UUID;

/**
 * Unit tests for FileChunk model.
 */
public class FileChunkTest {

    public void runAll() throws Exception {
        testValidChunk();
        testNegativeIndex();
        testNegativeOffset();
        testZeroLength();
        testLengthMismatch();
        testNullFields();
    }

    private void testValidChunk() {
        UUID tid = UUID.randomUUID();
        byte[] data = new byte[]{1, 2, 3, 4};
        FileChunk chunk = new FileChunk(tid, 0, 0L, 4, data);

        assert chunk.transferId().equals(tid);
        assert chunk.chunkIndex() == 0;
        assert chunk.offset() == 0L;
        assert chunk.length() == 4;
        assert chunk.data().length == 4;
    }

    private void testNegativeIndex() {
        boolean thrown = false;
        try {
            new FileChunk(UUID.randomUUID(), -1, 0L, 2, new byte[]{1, 2});
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Negative chunkIndex must be rejected";
    }

    private void testNegativeOffset() {
        boolean thrown = false;
        try {
            new FileChunk(UUID.randomUUID(), 0, -10L, 2, new byte[]{1, 2});
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Negative offset must be rejected";
    }

    private void testZeroLength() {
        boolean thrown = false;
        try {
            new FileChunk(UUID.randomUUID(), 0, 0L, 0, new byte[0]);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Zero length chunk must be rejected";
    }

    private void testLengthMismatch() {
        boolean thrown = false;
        try {
            new FileChunk(UUID.randomUUID(), 0, 0L, 10, new byte[5]);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Length mismatch must be rejected";
    }

    private void testNullFields() {
        boolean thrown = false;
        try {
            new FileChunk(null, 0, 0L, 2, new byte[2]);
        } catch (NullPointerException e) {
            thrown = true;
        }
        assert thrown : "Null transferId must be rejected";

        thrown = false;
        try {
            new FileChunk(UUID.randomUUID(), 0, 0L, 2, null);
        } catch (NullPointerException e) {
            thrown = true;
        }
        assert thrown : "Null data must be rejected";
    }
}
