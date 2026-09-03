package com.meshdrop.transfer;

import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.security.HashUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that FILE_RESUME_REQUEST with incompatible metadata (size, hash, recipient)
 * is rejected with RESUME_METADATA_MISMATCH or RESUME_HASH_MISMATCH.
 */
public class MetadataMismatchResumeTest {

    public void runAll() throws Exception {
        testMetadataMismatchRejection();
    }

    private void testMetadataMismatchRejection() throws Exception {
        Path tempDir = Files.createTempDirectory("meta-mismatch-temp");
        Path dlDir = Files.createTempDirectory("meta-mismatch-dl");

        try {
            UUID localId = UUID.randomUUID();
            UUID remoteId = UUID.randomUUID();
            UUID tid = UUID.randomUUID();

            NodeIdentity localIdentity = NodeIdentity.of(localId, "LocalNode");
            NodeIdentity remoteIdentity = NodeIdentity.of(remoteId, "RemoteNode");

            PeerManager pm = new PeerManager(localId);
            FileTransferService service = new FileTransferService(localIdentity, pm, dlDir, tempDir);

            String validSha = HashUtils.sha256("original".getBytes());

            // Save initial checkpoint
            TransferCheckpoint cp = new TransferCheckpoint(
                    tid, remoteId, localId, "test.dat", 1000L, 100, 1, 100L, 100L, validSha, System.currentTimeMillis()
            );
            service.getRecoveryManager().saveCheckpoint(cp);
            Files.write(service.getRecoveryManager().getPartFilePath(tid), new byte[100]);

            // Real loopback connection
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                try (Socket clientSocket = new Socket("127.0.0.1", ss.getLocalPort());
                     Socket serverSocket = ss.accept()) {

                    TcpConnection clientConn = new TcpConnection(clientSocket, com.meshdrop.network.ConnectionDirection.OUTBOUND);
                    clientConn.setRemoteIdentity(remoteIdentity);
                    com.meshdrop.protocol.PacketDecoder decoder = new com.meshdrop.protocol.PacketDecoder();

                    // 1. File size mismatch
                    var sizeMismatchReq = new FileTransferCodec.ResumeRequestPayload(tid, remoteId, localId, 2000L, 100, validSha);
                    service.handleIncomingPacket(clientConn, Packet.createFileResumeRequest(sizeMismatchReq));

                    // Read response on server socket
                    Packet respPacket1 = decoder.decode(serverSocket.getInputStream());
                    assert respPacket1 != null : "Response packet must be sent";
                    assert respPacket1.getType() == PacketType.FILE_RESUME_RESPONSE;
                    var resp1 = respPacket1.decodeFileResumeResponse();
                    assert resp1.status() == ResumeStatus.RESUME_METADATA_MISMATCH : "Expected RESUME_METADATA_MISMATCH for fileSize difference";

                    // 2. Hash mismatch
                    String differentSha = HashUtils.sha256("different".getBytes());
                    var hashMismatchReq = new FileTransferCodec.ResumeRequestPayload(tid, remoteId, localId, 1000L, 100, differentSha);
                    service.handleIncomingPacket(clientConn, Packet.createFileResumeRequest(hashMismatchReq));

                    Packet respPacket2 = decoder.decode(serverSocket.getInputStream());
                    var resp2 = respPacket2.decodeFileResumeResponse();
                    assert resp2.status() == ResumeStatus.RESUME_HASH_MISMATCH : "Expected RESUME_HASH_MISMATCH for SHA difference";

                    // 3. Unknown transferId
                    var unknownReq = new FileTransferCodec.ResumeRequestPayload(UUID.randomUUID(), remoteId, localId, 1000L, 100, validSha);
                    service.handleIncomingPacket(clientConn, Packet.createFileResumeRequest(unknownReq));

                    Packet respPacket3 = decoder.decode(serverSocket.getInputStream());
                    var resp3 = respPacket3.decodeFileResumeResponse();
                    assert resp3.status() == ResumeStatus.RESUME_NOT_FOUND : "Expected RESUME_NOT_FOUND for non-existent transferId";
                }
            }

        } finally {
            cleanupDir(tempDir);
            cleanupDir(dlDir);
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
