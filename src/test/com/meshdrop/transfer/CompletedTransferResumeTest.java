package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.security.HashUtils;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies that when a resume request is received for an already completed transfer,
 * the receiver immediately responds with RESUME_COMPLETE.
 */
public class CompletedTransferResumeTest {

    public void runAll() throws Exception {
        testAlreadyCompletedTransferReturnsResumeComplete();
    }

    private void testAlreadyCompletedTransferReturnsResumeComplete() throws Exception {
        Path tempDir = Files.createTempDirectory("complete-resume-temp");
        Path dlDir = Files.createTempDirectory("complete-resume-dl");

        try {
            UUID localId = UUID.randomUUID();
            UUID remoteId = UUID.randomUUID();
            UUID tid = UUID.randomUUID();

            NodeIdentity localIdentity = NodeIdentity.of(localId, "LocalNode");
            NodeIdentity remoteIdentity = NodeIdentity.of(remoteId, "RemoteNode");

            PeerManager pm = new PeerManager(localId);
            FileTransferService service = new FileTransferService(localIdentity, pm, dlDir, tempDir);

            String sha = HashUtils.sha256("complete_data".getBytes());

            // Create and register an already COMPLETED transfer
            FileMetadata meta = new FileMetadata(tid, remoteId, localId, "done.txt", 1000L, System.currentTimeMillis(), sha);
            Transfer completedTransfer = new Transfer(meta, TransferDirection.DOWNLOAD, dlDir.resolve("done.txt"));
            completedTransfer.transitionTo(TransferState.ACCEPTED);
            completedTransfer.transitionTo(TransferState.TRANSFERRING);
            completedTransfer.transitionTo(TransferState.VERIFYING);
            completedTransfer.transitionTo(TransferState.COMPLETED);
            service.getTransferManager().registerTransfer(completedTransfer);

            try (ServerSocket ss = new ServerSocket(0)) {
                try (Socket clientSocket = new Socket("127.0.0.1", ss.getLocalPort());
                     Socket serverSocket = ss.accept()) {

                    TcpConnection clientConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                    clientConn.setRemoteIdentity(remoteIdentity);

                    // Send FILE_RESUME_REQUEST
                    var req = new FileTransferCodec.ResumeRequestPayload(tid, remoteId, localId, 1000L, 100, sha);
                    service.handleIncomingPacket(clientConn, Packet.createFileResumeRequest(req));

                    // Read response from server socket input stream
                    Packet respPacket = new com.meshdrop.protocol.PacketDecoder().decode(serverSocket.getInputStream());
                    assert respPacket != null;
                    assert respPacket.getType() == PacketType.FILE_RESUME_RESPONSE;

                    var resp = respPacket.decodeFileResumeResponse();
                    assert resp.status() == ResumeStatus.RESUME_COMPLETE : "Expected RESUME_COMPLETE status, got " + resp.status();
                    assert resp.nextExpectedOffset() == 1000L;
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
