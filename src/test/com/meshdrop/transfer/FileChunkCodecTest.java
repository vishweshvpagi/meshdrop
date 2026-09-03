package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolException;

import java.util.UUID;

/**
 * Unit tests for FileChunk, FILE_COMPLETE, FILE_ACK, and FILE_ERROR binary codecs.
 */
public class FileChunkCodecTest {

    public void runAll() throws Exception {
        testChunkEncodeDecode();
        testTruncatedChunkPayload();
        testChunkPayloadMismatch();
        testCompleteCodec();
        testAckCodec();
        testErrorCodec();
    }

    private void testChunkEncodeDecode() throws Exception {
        UUID tid = UUID.randomUUID();
        byte[] data = new byte[]{10, 20, 30, 40, 50};
        FileChunk original = new FileChunk(tid, 5, 327680L, data.length, data);

        byte[] payload = FileTransferCodec.encodeChunk(original);
        FileChunk decoded = FileTransferCodec.decodeChunk(payload);

        assert decoded.transferId().equals(tid);
        assert decoded.chunkIndex() == 5;
        assert decoded.offset() == 327680L;
        assert decoded.length() == data.length;
        assert java.util.Arrays.equals(decoded.data(), data);
    }

    private void testTruncatedChunkPayload() {
        byte[] shortBytes = new byte[20];
        boolean thrown = false;
        try {
            FileTransferCodec.decodeChunk(shortBytes);
        } catch (ProtocolException e) {
            thrown = true;
        }
        assert thrown : "Truncated chunk payload must throw ProtocolException";
    }

    private void testChunkPayloadMismatch() {
        UUID tid = UUID.randomUUID();
        byte[] data = new byte[]{1, 2, 3};
        FileChunk chunk = new FileChunk(tid, 0, 0L, 3, data);
        byte[] encoded = FileTransferCodec.encodeChunk(chunk);

        // Corrupt by truncating 1 byte of payload
        byte[] corrupt = new byte[encoded.length - 1];
        System.arraycopy(encoded, 0, corrupt, 0, corrupt.length);

        boolean thrown = false;
        try {
            FileTransferCodec.decodeChunk(corrupt);
        } catch (ProtocolException e) {
            thrown = true;
        }
        assert thrown : "Mismatched payload length must throw ProtocolException";
    }

    private void testCompleteCodec() throws Exception {
        UUID tid = UUID.randomUUID();
        String hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        byte[] bytes = FileTransferCodec.encodeComplete(tid, 10, 655360L, hash);

        var decoded = FileTransferCodec.decodeComplete(bytes);
        assert decoded.transferId().equals(tid);
        assert decoded.totalChunks() == 10;
        assert decoded.totalBytes() == 655360L;
        assert decoded.sha256().equals(hash);
    }

    private void testAckCodec() throws Exception {
        UUID tid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        byte[] bytes = FileTransferCodec.encodeAck(tid, true, now);

        var decoded = FileTransferCodec.decodeAck(bytes);
        assert decoded.transferId().equals(tid);
        assert decoded.success();
        assert decoded.ackTimestamp() == now;
    }

    private void testErrorCodec() throws Exception {
        UUID tid = UUID.randomUUID();
        byte[] bytes = FileTransferCodec.encodeError(tid, "Disk full");

        var decoded = FileTransferCodec.decodeError(bytes);
        assert decoded.transferId().equals(tid);
        assert decoded.message().equals("Disk full");
    }
}
