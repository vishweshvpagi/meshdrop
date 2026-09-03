package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolException;

import java.util.UUID;

/**
 * Unit tests for FileMetadata binary encoding and decoding.
 */
public class FileMetadataCodecTest {

    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public void runAll() throws Exception {
        testEncodeDecodeRoundTrip();
        testUnicodeFilenameRoundTrip();
        testTruncatedPayloadRejection();
        testCorruptedLengthRejection();
        testAcceptAndRejectCodecs();
    }

    private void testEncodeDecodeRoundTrip() throws Exception {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        FileMetadata original = new FileMetadata(tid, sid, rid, "presentation.pdf", 987654L, 1700000000000L, HASH);

        byte[] bytes = FileTransferCodec.encodeOffer(original);
        FileMetadata decoded = FileTransferCodec.decodeOffer(bytes);

        assert decoded.transferId().equals(tid);
        assert decoded.senderId().equals(sid);
        assert decoded.recipientId().equals(rid);
        assert decoded.fileName().equals("presentation.pdf");
        assert decoded.fileSize() == 987654L;
        assert decoded.createdAt() == 1700000000000L;
        assert decoded.sha256().equals(HASH);
    }

    private void testUnicodeFilenameRoundTrip() throws Exception {
        String unicodeName = "ಕನ್ನಡ_ದಸ್ತಾವೇಜು_日本語_éàç.tar.gz";
        FileMetadata original = new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                unicodeName, 12345L, 1000L, HASH);

        byte[] bytes = FileTransferCodec.encodeOffer(original);
        FileMetadata decoded = FileTransferCodec.decodeOffer(bytes);

        assert decoded.fileName().equals(unicodeName) : "Decoded Unicode filename mismatch! Got: " + decoded.fileName();
    }

    private void testTruncatedPayloadRejection() {
        byte[] shortBytes = new byte[50];
        boolean thrown = false;
        try {
            FileTransferCodec.decodeOffer(shortBytes);
        } catch (ProtocolException e) {
            thrown = true;
        }
        assert thrown : "Truncated offer payload must throw ProtocolException";
    }

    private void testCorruptedLengthRejection() throws Exception {
        FileMetadata original = new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "test.bin", 100L, 1000L, HASH);
        byte[] bytes = FileTransferCodec.encodeOffer(original);

        // Append 1 byte to corrupt size match
        byte[] corrupt = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, corrupt, 0, bytes.length);

        boolean thrown = false;
        try {
            FileTransferCodec.decodeOffer(corrupt);
        } catch (ProtocolException e) {
            thrown = true;
        }
        assert thrown : "Payload length mismatch must throw ProtocolException";
    }

    private void testAcceptAndRejectCodecs() throws Exception {
        UUID tid = UUID.randomUUID();
        byte[] acceptBytes = FileTransferCodec.encodeAccept(tid);
        UUID decodedTid = FileTransferCodec.decodeAccept(acceptBytes);
        assert decodedTid.equals(tid);

        byte[] rejectBytes = FileTransferCodec.encodeReject(tid, "INSUFFICIENT_STORAGE");
        var reject = FileTransferCodec.decodeReject(rejectBytes);
        assert reject.transferId().equals(tid);
        assert reject.reason().equals("INSUFFICIENT_STORAGE");
    }
}
