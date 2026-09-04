package com.meshdrop.transfer;

import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates sliding-window flow control, cumulative ACK advancement,
 * dropped ACK tolerance, duplicate chunk idempotency, and out-of-order chunk safety.
 */
public class SlidingWindowFlowControlTest {

    public void runAll() throws Exception {
        testCumulativeAckSlidesWindow();
        testDuplicateChunkHandling();
        testOutOfOrderChunkIgnoredWithoutAbort();
    }

    /**
     * Verifies that when intermediate ACKs are dropped and only a cumulative ACK arrives,
     * the sender correctly slides its window and completes the transfer.
     */
    private void testCumulativeAckSlidesWindow() throws Exception {
        int chunkSize = ProtocolConstants.MIN_FILE_CHUNK_SIZE; // 4096 bytes
        int numChunks = 8;
        int fileSize = chunkSize * numChunks; // 32 KiB
        byte[] data = new byte[fileSize];
        for (int i = 0; i < fileSize; i++) {
            data[i] = (byte) (i % 251);
        }

        Path tempFile = Files.createTempFile("cum-ack-test", ".bin");
        Files.write(tempFile, data);
        String expectedHash = HashUtils.sha256(tempFile.toFile());

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Socket clientSocket = new Socket("127.0.0.1", port);
            Socket serverSocket = ss.accept();

            TcpConnection senderConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
            TcpConnection receiverConn = new TcpConnection(serverSocket, ConnectionDirection.INBOUND);
            senderConn.setState(ConnectionState.READY);
            receiverConn.setState(ConnectionState.READY);

            FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), tempFile.getFileName().toString(), fileSize, expectedHash);
            Transfer senderTransfer = new Transfer(meta, TransferDirection.UPLOAD, tempFile);
            UUID tid = senderTransfer.getTransferId();

            // Sender with window size = 4
            FileSender sender = new FileSender(chunkSize, 4, 2000, 3);

            // Sender starts receiving ACKs over TCP connection
            senderConn.startReceiving((conn, packet) -> {
                if (packet.getType() == PacketType.FILE_ACK) {
                    try {
                        var ack = packet.decodeFileAck();
                        if (ack.isWindowAck()) {
                            sender.onAckReceived(ack.highestContiguousChunk(), ack.receiverOffset());
                        }
                    } catch (Exception ignored) {}
                }
            });

            // Receiver side simulates dropping ACKs for chunks 0, 1, 2, but sends cumulative ACK for chunk 3 (offset 16384)
            // Then sends cumulative ACK for chunk 7 (offset 32768)
            AtomicInteger receivedChunks = new AtomicInteger(0);
            receiverConn.startReceiving((conn, packet) -> {
                if (packet.getType() == PacketType.FILE_CHUNK) {
                    try {
                        FileChunk chunk = packet.decodeFileChunk();
                        int idx = chunk.chunkIndex();
                        int count = receivedChunks.incrementAndGet();

                        if (idx == 3) {
                            // Cumulative ACK acknowledging chunks 0, 1, 2, 3
                            receiverConn.sendPacket(Packet.createFileChunkAck(tid, 3, 16384));
                        } else if (idx == 7) {
                            // Cumulative ACK acknowledging up to chunk 7
                            receiverConn.sendPacket(Packet.createFileChunkAck(tid, 7, 32768));
                        }
                    } catch (Exception ignored) {}
                }
            });

            // Stream file on virtual thread
            Thread.ofVirtual().start(() -> {
                try {
                    sender.streamFile(tempFile, senderConn, senderTransfer, null);
                } catch (Exception ignored) {}
            });

            long deadline = System.currentTimeMillis() + 5000;
            while (senderTransfer.getBytesTransferred() < fileSize && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert senderTransfer.getBytesTransferred() == fileSize : "Sender should have completed transfer via cumulative ACKs";
            assert sender.getDebugInfo().highestAckedChunk() == 7 : "Highest acked chunk must be 7";

            senderConn.close();
            receiverConn.close();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Verifies that when duplicate chunks are received, FileReceiver ignores them idempotently
     * and produces a completely uncorrupted file matching the expected SHA-256 digest.
     */
    private void testDuplicateChunkHandling() throws Exception {
        Path tempDir = Files.createTempDirectory("dupe-chunk-test");
        Path downloadsDir = tempDir.resolve("downloads");
        Path recoveryDir = tempDir.resolve("recovery");
        Files.createDirectories(downloadsDir);
        Files.createDirectories(recoveryDir);

        try {
            int chunkSize = 512;
            byte[] c0Data = new byte[chunkSize];
            byte[] c1Data = new byte[chunkSize];
            for (int i = 0; i < chunkSize; i++) c0Data[i] = (byte) 10;
            for (int i = 0; i < chunkSize; i++) c1Data[i] = (byte) 20;

            byte[] fullFile = new byte[chunkSize * 2];
            System.arraycopy(c0Data, 0, fullFile, 0, chunkSize);
            System.arraycopy(c1Data, 0, fullFile, chunkSize, chunkSize);
            String expectedHash = HashUtils.sha256(fullFile);

            FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), "test-dupe.bin", fullFile.length, expectedHash);
            UUID tid = meta.transferId();
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, downloadsDir.resolve("test-dupe.bin"));
            RecoveryManager recoveryManager = new RecoveryManager(recoveryDir);

            FileReceiver receiver = new FileReceiver(meta, downloadsDir, recoveryDir, transfer, recoveryManager, null);

            FileChunk chunk0 = new FileChunk(tid, 0, 0, chunkSize, c0Data);
            FileChunk chunk1 = new FileChunk(tid, 1, chunkSize, chunkSize, c1Data);

            // Deliver chunk 0
            receiver.receiveChunk(chunk0);
            assert receiver.getExpectedChunkIndex() == 1 : "Expected chunk index must be 1";

            // Deliver duplicate chunk 0 again!
            receiver.receiveChunk(chunk0);
            assert receiver.getExpectedChunkIndex() == 1 : "Expected chunk index must remain 1 after duplicate";

            // Deliver chunk 1
            receiver.receiveChunk(chunk1);
            assert receiver.getExpectedChunkIndex() == 2 : "Expected chunk index must be 2";

            // Complete transfer
            Path dest = receiver.completeTransfer(2, fullFile.length, expectedHash);
            assert Files.exists(dest) : "Output file must exist";
            assert Files.size(dest) == fullFile.length : "File size mismatch";
            assert HashUtils.sha256(dest.toFile()).equalsIgnoreCase(expectedHash) : "Hash must match expected SHA-256";

        } finally {
            deleteDir(tempDir);
        }
    }

    /**
     * Verifies that out-of-order chunks are safely ignored without aborting the receiver session,
     * allowing subsequent in-order chunks to complete successfully.
     */
    private void testOutOfOrderChunkIgnoredWithoutAbort() throws Exception {
        Path tempDir = Files.createTempDirectory("ooo-chunk-test");
        Path downloadsDir = tempDir.resolve("downloads");
        Path recoveryDir = tempDir.resolve("recovery");
        Files.createDirectories(downloadsDir);
        Files.createDirectories(recoveryDir);

        try {
            int chunkSize = 256;
            byte[] c0Data = new byte[chunkSize];
            byte[] c1Data = new byte[chunkSize];
            for (int i = 0; i < chunkSize; i++) c0Data[i] = (byte) 'A';
            for (int i = 0; i < chunkSize; i++) c1Data[i] = (byte) 'B';

            byte[] fullFile = new byte[chunkSize * 2];
            System.arraycopy(c0Data, 0, fullFile, 0, chunkSize);
            System.arraycopy(c1Data, 0, fullFile, chunkSize, chunkSize);
            String expectedHash = HashUtils.sha256(fullFile);

            FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), "test-ooo.bin", fullFile.length, expectedHash);
            UUID tid = meta.transferId();
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, downloadsDir.resolve("test-ooo.bin"));
            RecoveryManager recoveryManager = new RecoveryManager(recoveryDir);

            FileReceiver receiver = new FileReceiver(meta, downloadsDir, recoveryDir, transfer, recoveryManager, null);

            FileChunk chunk0 = new FileChunk(tid, 0, 0, chunkSize, c0Data);
            FileChunk chunk1 = new FileChunk(tid, 1, chunkSize, chunkSize, c1Data);

            // Intentionally send chunk 1 BEFORE chunk 0
            boolean threw = false;
            try {
                receiver.receiveChunk(chunk1);
            } catch (IOException expected) {
                threw = true;
            }
            assert threw : "Expected IOException for out-of-order chunk";
            assert receiver.getExpectedChunkIndex() == 0 : "Expected chunk index must still be 0";
            assert !receiver.isClosed() : "Receiver must NOT be closed/aborted by out-of-order chunk";

            // Now deliver chunk 0 and chunk 1 in order
            receiver.receiveChunk(chunk0);
            assert receiver.getExpectedChunkIndex() == 1 : "Expected chunk index must advance to 1";

            receiver.receiveChunk(chunk1);
            assert receiver.getExpectedChunkIndex() == 2 : "Expected chunk index must advance to 2";

            Path dest = receiver.completeTransfer(2, fullFile.length, expectedHash);
            assert Files.exists(dest) : "Output file must exist";
            assert HashUtils.sha256(dest.toFile()).equalsIgnoreCase(expectedHash) : "Hash must match expected SHA-256";

        } finally {
            deleteDir(tempDir);
        }
    }

    private void deleteDir(Path dir) {
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
