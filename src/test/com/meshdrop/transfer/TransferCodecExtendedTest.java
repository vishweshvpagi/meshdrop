package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolException;

import java.util.UUID;

/**
 * Validates binary encoding and decoding of file chunks, cumulative ACKs,
 * completion, and resume messages across 32-bit and 64-bit boundaries (>2 GB, >4 GB, >100 GB).
 */
public class TransferCodecExtendedTest {

    public void runAll() throws Exception {
        testLargeOffsetChunkCodec();
        testExtendedChunkAckCodec();
        testCompleteCodecLargeFiles();
        testResumePayloadCodecLargeFiles();
        testNegativeValuesValidation();
    }

    private void testLargeOffsetChunkCodec() throws Exception {
        UUID tid = UUID.randomUUID();
        byte[] sampleData = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

        // Test 1: Offset > 2 GB (2.5 GB)
        long offset2_5GB = 2_684_354_560L;
        int chunkIdx1 = 40960;
        FileChunk chunk1 = new FileChunk(tid, chunkIdx1, offset2_5GB, sampleData.length, sampleData);
        byte[] encoded1 = FileTransferCodec.encodeChunk(chunk1);
        FileChunk decoded1 = FileTransferCodec.decodeChunk(encoded1);

        assert decoded1.transferId().equals(tid) : "Transfer ID mismatch";
        assert decoded1.chunkIndex() == chunkIdx1 : "Chunk index mismatch";
        assert decoded1.offset() == offset2_5GB : "Offset mismatch for >2GB";
        assert decoded1.length() == sampleData.length : "Length mismatch";
        assert java.util.Arrays.equals(decoded1.data(), sampleData) : "Payload mismatch";

        // Test 2: Offset > 4 GB (5 GB)
        long offset5GB = 5_368_709_120L;
        int chunkIdx2 = 81920;
        FileChunk chunk2 = new FileChunk(tid, chunkIdx2, offset5GB, sampleData.length, sampleData);
        byte[] encoded2 = FileTransferCodec.encodeChunk(chunk2);
        FileChunk decoded2 = FileTransferCodec.decodeChunk(encoded2);

        assert decoded2.chunkIndex() == chunkIdx2 : "Chunk index mismatch";
        assert decoded2.offset() == offset5GB : "Offset mismatch for >4GB";

        // Test 3: Offset > 100 GB (105 GB)
        long offset105GB = 112_742_891_520L;
        int chunkIdx3 = 1_720_320;
        FileChunk chunk3 = new FileChunk(tid, chunkIdx3, offset105GB, sampleData.length, sampleData);
        byte[] encoded3 = FileTransferCodec.encodeChunk(chunk3);
        FileChunk decoded3 = FileTransferCodec.decodeChunk(encoded3);

        assert decoded3.chunkIndex() == chunkIdx3 : "Chunk index mismatch for 100GB+";
        assert decoded3.offset() == offset105GB : "Offset mismatch for 100GB+";
    }

    private void testExtendedChunkAckCodec() throws Exception {
        UUID tid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // 1. Extended cumulative ACK: chunk 150,000 and offset 9.8 GB
        long highestChunk = 150_000L;
        long receiverOffset = 9_830_400_000L;
        byte[] encAck = FileTransferCodec.encodeChunkAck(tid, highestChunk, receiverOffset, now);
        FileTransferCodec.AckPayload ack = FileTransferCodec.decodeAck(encAck);

        assert ack.transferId().equals(tid) : "Transfer ID mismatch in ACK";
        assert ack.success() : "Success flag must be true";
        assert ack.isWindowAck() : "Must be identified as window ACK";
        assert ack.highestContiguousChunk() == highestChunk : "Highest contiguous chunk mismatch";
        assert ack.receiverOffset() == receiverOffset : "Receiver offset mismatch";
        assert ack.ackTimestamp() == now : "Timestamp mismatch";

        // 2. Legacy/Terminal ACK (25 bytes)
        byte[] legacyEnc = FileTransferCodec.encodeAck(tid, true, now);
        FileTransferCodec.AckPayload legacyAck = FileTransferCodec.decodeAck(legacyEnc);
        assert legacyAck.transferId().equals(tid) : "Transfer ID mismatch in legacy ACK";
        assert !legacyAck.isWindowAck() : "Legacy ACK must not be window ACK";
        assert legacyAck.highestContiguousChunk() == -1L : "Highest contiguous chunk should be -1";
    }

    private void testCompleteCodecLargeFiles() throws Exception {
        UUID tid = UUID.randomUUID();
        String sha = "a".repeat(64);
        int totalChunks = 2_000_000;
        long totalBytes = 131_072_000_000L; // 122 GB

        byte[] enc = FileTransferCodec.encodeComplete(tid, totalChunks, totalBytes, sha);
        FileTransferCodec.CompletePayload complete = FileTransferCodec.decodeComplete(enc);

        assert complete.transferId().equals(tid) : "Transfer ID mismatch";
        assert complete.totalChunks() == totalChunks : "Total chunks mismatch";
        assert complete.totalBytes() == totalBytes : "Total bytes mismatch";
        assert complete.sha256().equals(sha) : "SHA-256 mismatch";
    }

    private void testResumePayloadCodecLargeFiles() throws Exception {
        UUID tid = UUID.randomUUID();
        UUID sId = UUID.randomUUID();
        UUID rId = UUID.randomUUID();
        String sha = "b".repeat(64);

        // Resume request for 50 GB file
        long fileSize50GB = 53_687_091_200L;
        FileTransferCodec.ResumeRequestPayload req = new FileTransferCodec.ResumeRequestPayload(
                tid, sId, rId, fileSize50GB, 65536, sha
        );
        byte[] encReq = FileTransferCodec.encodeResumeRequest(req);
        FileTransferCodec.ResumeRequestPayload decReq = FileTransferCodec.decodeResumeRequest(encReq);

        assert decReq.transferId().equals(tid);
        assert decReq.fileSize() == fileSize50GB;
        assert decReq.expectedSha256().equals(sha);

        // Resume response with checkpoint at 25 GB
        long resumeOffset25GB = 26_843_545_600L;
        int nextChunk = 409600;
        FileTransferCodec.ResumeResponsePayload resp = new FileTransferCodec.ResumeResponsePayload(
                tid,
                ResumeStatus.RESUME_ACCEPTED,
                nextChunk,
                resumeOffset25GB,
                resumeOffset25GB,
                "OK"
        );
        byte[] encResp = FileTransferCodec.encodeResumeResponse(resp);
        FileTransferCodec.ResumeResponsePayload decResp = FileTransferCodec.decodeResumeResponse(encResp);

        assert decResp.transferId().equals(tid);
        assert decResp.status() == ResumeStatus.RESUME_ACCEPTED;
        assert decResp.nextExpectedChunk() == nextChunk;
        assert decResp.nextExpectedOffset() == resumeOffset25GB;
        assert decResp.bytesReceived() == resumeOffset25GB;
    }

    private void testNegativeValuesValidation() {
        UUID tid = UUID.randomUUID();
        // Negative total chunks in complete should throw ProtocolException
        boolean thrown = false;
        try {
            FileTransferCodec.encodeComplete(tid, -1, 100L, "c".repeat(64));
        } catch (IllegalArgumentException ignored) {
            thrown = true;
        }

        // Corrupted payload length
        boolean thrownDecode = false;
        try {
            FileTransferCodec.decodeAck(new byte[10]);
        } catch (ProtocolException e) {
            thrownDecode = true;
        }
        assert thrownDecode : "Decode of short ACK payload must throw ProtocolException";
    }
}
