package com.meshdrop.transfer;

import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.protocol.ProtocolException;

import java.util.UUID;

/**
 * Unit test for FILE_RESUME_REQUEST binary packet encoding and decoding.
 */
public class ResumeRequestCodecTest {

    private static final String SHA = "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

    public void runAll() throws Exception {
        testEncodeDecodeRoundTrip();
        testPacketIntegration();
        testTruncatedPayload();
        testNegativeValues();
    }

    private void testEncodeDecodeRoundTrip() throws ProtocolException {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();

        var req = new FileTransferCodec.ResumeRequestPayload(tid, sid, rid, 5000000L, 65536, SHA);
        byte[] encoded = FileTransferCodec.encodeResumeRequest(req);

        assert encoded.length == FileTransferCodec.RESUME_REQUEST_PAYLOAD_BYTES : "Encoded length must be exactly 124 bytes";

        var decoded = FileTransferCodec.decodeResumeRequest(encoded);
        assert decoded.transferId().equals(tid);
        assert decoded.senderId().equals(sid);
        assert decoded.recipientId().equals(rid);
        assert decoded.fileSize() == 5000000L;
        assert decoded.chunkSize() == 65536;
        assert decoded.expectedSha256().equals(SHA);
    }

    private void testPacketIntegration() throws ProtocolException {
        UUID tid = UUID.randomUUID();
        var req = new FileTransferCodec.ResumeRequestPayload(tid, UUID.randomUUID(), UUID.randomUUID(), 1024L, 512, SHA);
        Packet packet = Packet.createFileResumeRequest(req);

        assert packet.getType() == PacketType.FILE_RESUME_REQUEST;
        assert packet.getRequestId().equals(tid);

        var decoded = packet.decodeFileResumeRequest();
        assert decoded.transferId().equals(tid);
        assert decoded.fileSize() == 1024L;
    }

    private void testTruncatedPayload() {
        byte[] shortPayload = new byte[100];
        try {
            FileTransferCodec.decodeResumeRequest(shortPayload);
            assert false : "Expected ProtocolException for truncated payload";
        } catch (ProtocolException expected) {}
    }

    private void testNegativeValues() {
        try {
            new FileTransferCodec.ResumeRequestPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), -1L, 1024, SHA);
            FileTransferCodec.encodeResumeRequest(new FileTransferCodec.ResumeRequestPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), -1L, 1024, SHA));
            assert false : "Expected IllegalArgumentException for negative fileSize";
        } catch (IllegalArgumentException expected) {}
    }
}
