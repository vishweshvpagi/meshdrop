package com.meshdrop.transfer;

import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.protocol.ProtocolException;

import java.util.UUID;

/**
 * Unit test for FILE_RESUME_RESPONSE binary packet encoding and decoding.
 */
public class ResumeResponseCodecTest {

    public void runAll() throws Exception {
        testAllStatusesRoundTrip();
        testPacketIntegration();
        testInvalidStatusAndTruncation();
    }

    private void testAllStatusesRoundTrip() throws ProtocolException {
        UUID tid = UUID.randomUUID();

        for (ResumeStatus status : ResumeStatus.values()) {
            String reason = "Reason for status: " + status.name() + " 🚀";
            var resp = new FileTransferCodec.ResumeResponsePayload(tid, status, 10, 655360L, 655360L, reason);

            byte[] encoded = FileTransferCodec.encodeResumeResponse(resp);
            var decoded = FileTransferCodec.decodeResumeResponse(encoded);

            assert decoded.transferId().equals(tid);
            assert decoded.status() == status;
            assert decoded.nextExpectedChunk() == 10;
            assert decoded.nextExpectedOffset() == 655360L;
            assert decoded.bytesReceived() == 655360L;
            assert decoded.reason().equals(reason);
        }
    }

    private void testPacketIntegration() throws ProtocolException {
        UUID tid = UUID.randomUUID();
        var resp = new FileTransferCodec.ResumeResponsePayload(tid, ResumeStatus.RESUME_ACCEPTED, 5, 51200L, 51200L, "OK");
        Packet packet = Packet.createFileResumeResponse(resp);

        assert packet.getType() == PacketType.FILE_RESUME_RESPONSE;
        assert packet.getRequestId().equals(tid);

        var decoded = packet.decodeFileResumeResponse();
        assert decoded.status() == ResumeStatus.RESUME_ACCEPTED;
        assert decoded.nextExpectedChunk() == 5;
    }

    private void testInvalidStatusAndTruncation() {
        byte[] shortPayload = new byte[20];
        try {
            FileTransferCodec.decodeResumeResponse(shortPayload);
            assert false : "Expected ProtocolException for truncated response";
        } catch (ProtocolException expected) {}

        // Invalid status byte code (e.g. 0x99)
        byte[] invalidStatusPayload = new byte[FileTransferCodec.RESUME_RESPONSE_FIXED_HEADER_BYTES];
        invalidStatusPayload[16] = (byte) 0x99; // invalid code
        try {
            FileTransferCodec.decodeResumeResponse(invalidStatusPayload);
            assert false : "Expected ProtocolException for unknown status code";
        } catch (ProtocolException expected) {}
    }
}
