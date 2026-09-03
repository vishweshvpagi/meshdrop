package com.meshdrop.transfer;

import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies clean failure and resource cleanup when a connection abruptly disconnects during transfer.
 */
public class DisconnectDuringTransferTest {

    public void runAll() throws Exception {
        testSenderHandlesSuddenDisconnect();
    }

    private void testSenderHandlesSuddenDisconnect() throws Exception {
        Path tempFile = Files.createTempFile("disc-test", ".bin");
        Files.write(tempFile, new byte[128 * 1024]); // 128 KiB

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Socket clientSocket = new Socket("127.0.0.1", port);
            Socket serverSocket = ss.accept();

            TcpConnection clientConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
            clientConn.setState(ConnectionState.READY);

            UUID tid = UUID.randomUUID();
            FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), tempFile.getFileName().toString(), 128 * 1024, "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.UPLOAD, tempFile);

            AtomicBoolean failedNotified = new AtomicBoolean(false);

            boolean caught = false;
            try {
                FileSender sender = new FileSender(4096);
                sender.streamFile(tempFile, clientConn, transfer, new TransferListener() {
                    @Override
                    public void onTransferProgress(Transfer t) {
                        // Abruptly sever socket during active transfer
                        try {
                            clientSocket.close();
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onTransferFailed(Transfer t, String r) {
                        failedNotified.set(true);
                    }
                });
            } catch (IOException e) {
                caught = true;
            }

            serverSocket.close();
            clientConn.close();

            assert caught : "Expected IOException when socket is closed during streaming";
            assert transfer.getState() == TransferState.FAILED : "Transfer state should be FAILED";
            assert failedNotified.get() : "onTransferFailed listener callback should be invoked";

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
