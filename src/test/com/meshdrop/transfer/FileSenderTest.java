package com.meshdrop.transfer;

import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketDecoder;
import com.meshdrop.protocol.PacketType;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies FileSender chunking, offset accuracy, streaming behavior, and progress updates.
 */
public class FileSenderTest {

    public void runAll() throws Exception {
        testChunkStreamingAndOffsets();
    }

    private void testChunkStreamingAndOffsets() throws Exception {
        int fileSize = 150 * 1024; // 150 KiB
        int chunkSize = 64 * 1024; // 64 KiB -> 3 chunks (64K, 64K, 22K)
        byte[] originalData = new byte[fileSize];
        new Random(42).nextBytes(originalData);

        Path tempFile = Files.createTempFile("sender-test", ".bin");
        try {
            Files.write(tempFile, originalData);

            try (ServerSocket ss = new ServerSocket(0)) {
                int port = ss.getLocalPort();
                Socket clientSocket = new Socket("127.0.0.1", port);
                Socket serverSocket = ss.accept();

                TcpConnection clientConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                clientConn.setState(ConnectionState.READY);

                UUID tid = UUID.randomUUID();
                FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), tempFile.getFileName().toString(), fileSize, "0".repeat(64));
                Transfer transfer = new Transfer(meta, TransferDirection.UPLOAD, tempFile);

                AtomicInteger progressCount = new AtomicInteger();
                TransferListener listener = new TransferListener() {
                    public void onTransferProgress(Transfer t) {
                        progressCount.incrementAndGet();
                    }
                };

                // Stream in background thread
                Thread senderThread = Thread.ofVirtual().start(() -> {
                    try {
                        FileSender sender = new FileSender(chunkSize);
                        sender.streamFile(tempFile, clientConn, transfer, listener);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                // Server reads packets using stream decoder
                InputStream serverIn = serverSocket.getInputStream();
                PacketDecoder decoder = new PacketDecoder();
                List<FileChunk> receivedChunks = new ArrayList<>();
                boolean gotComplete = false;

                while (!gotComplete) {
                    Packet p = decoder.decode(serverIn);
                    if (p == null) break;
                    if (p.getType() == PacketType.FILE_CHUNK) {
                        receivedChunks.add(p.decodeFileChunk());
                    } else if (p.getType() == PacketType.FILE_COMPLETE) {
                        gotComplete = true;
                    }
                }

                senderThread.join(5000);
                clientConn.close();
                serverSocket.close();

                assert receivedChunks.size() == 3 : "Expected 3 chunks, got " + receivedChunks.size();
                assert receivedChunks.get(0).offset() == 0L;
                assert receivedChunks.get(0).length() == 64 * 1024;
                assert receivedChunks.get(1).offset() == 64L * 1024;
                assert receivedChunks.get(1).length() == 64 * 1024;
                assert receivedChunks.get(2).offset() == 128L * 1024;
                assert receivedChunks.get(2).length() == 22 * 1024;

                assert gotComplete : "FILE_COMPLETE packet not received";
                assert progressCount.get() >= 3 : "Progress listener was not called";
                assert transfer.getState() == TransferState.VERIFYING;
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
