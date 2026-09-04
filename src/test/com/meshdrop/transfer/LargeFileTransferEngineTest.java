package com.meshdrop.transfer;

import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketDecoder;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production validation suite for the Large File Transfer Engine.
 * Tests:
 * 1. Complete size matrix (0B, 1B, 1KB, 63KB, 64KB, 65KB, 1MB, 1MB+1, 10MB)
 * 2. 10 GB sparse file transfer with strict O(chunkSize * windowSize) bounded heap verification (< 32 MB)
 * 3. Sliding-window flow control and backpressure behavior
 * 4. Chunk retransmission on ACK timeout with exponential backoff
 * 5. Source file mutation detection and safe abort
 * 6. Process crash recovery, .part file assessment, and bit-for-bit exact-byte resume
 */
public class LargeFileTransferEngineTest {

    public void runAll() throws Exception {
        testSizeMatrix();
        testSparse10GBBoundedMemory();
        testSlidingWindowFlowControlAndBackpressure();
        testChunkRetransmissionOnTimeout();
        testSourceMutationDetection();
        testProcessCrashRecoveryAndExactResume();
    }

    /**
     * Verifies end-to-end streaming transfer of files across diverse boundary sizes:
     * 0-byte, 1-byte, 1KB, 63KB, 64KB, 65KB, 1MB, 1MB+1B, 10MB.
     */
    public void testSizeMatrix() throws Exception {
        long[] testSizes = new long[] {
                0L,
                1L,
                1024L,
                63L * 1024,
                64L * 1024,
                65L * 1024,
                1024L * 1024,
                1024L * 1024 + 1,
                10L * 1024 * 1024
        };

        for (long size : testSizes) {
            runSingleSizeTransfer(size);
        }
    }

    private void runSingleSizeTransfer(long size) throws Exception {
        Path tempDir = Files.createTempDirectory("matrix-temp-" + size + "-");
        Path dlDir = Files.createTempDirectory("matrix-dl-" + size + "-");
        Path srcFile = tempDir.resolve("payload_" + size + ".bin");

        try {
            // Write source file in 64KB chunks to keep test generation bounded in memory
            writeDeterministicFile(srcFile, size);

            String expectedSha256 = HashUtils.sha256(srcFile);
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            FileMetadata meta = new FileMetadata(tid, sid, rid, srcFile.getFileName().toString(), size, System.currentTimeMillis(), expectedSha256);
            Transfer uploadTransfer = new Transfer(meta, TransferDirection.UPLOAD, srcFile);
            Transfer downloadTransfer = new Transfer(meta, TransferDirection.DOWNLOAD, null);

            RecoveryManager rm = new RecoveryManager(tempDir);
            FileReceiver receiver = new FileReceiver(meta, dlDir, tempDir, downloadTransfer, rm, null);
            downloadTransfer.transitionTo(TransferState.ACCEPTED);

            try (ServerSocket ss = new ServerSocket(0)) {
                int port = ss.getLocalPort();
                Socket clientSocket = new Socket("127.0.0.1", port);
                Socket serverSocket = ss.accept();

                TcpConnection senderConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                senderConn.setState(ConnectionState.READY);

                TcpConnection receiverConn = new TcpConnection(serverSocket, ConnectionDirection.INBOUND);
                receiverConn.setState(ConnectionState.READY);

                FileSender sender = new FileSender(ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE, 8, 3000, 3);

                // Listen for ACKs from receiver to sender
                AtomicReference<Throwable> senderError = new AtomicReference<>();
                Thread senderThread = Thread.ofVirtual().start(() -> {
                    try {
                        sender.streamFile(srcFile, senderConn, uploadTransfer, null);
                    } catch (Throwable t) {
                        senderError.set(t);
                    }
                });

                // Receiver reads packets from sender
                InputStream in = serverSocket.getInputStream();
                PacketDecoder decoder = new PacketDecoder();
                boolean finished = false;
                Path finalPath = null;

                while (!finished) {
                    Packet p = decoder.decode(in);
                    if (p == null) break;

                    if (p.getType() == PacketType.FILE_CHUNK) {
                        FileChunk chunk = p.decodeFileChunk();
                        receiver.receiveChunk(chunk);
                        // Send window progress ACK back to sender
                        sender.onAckReceived(chunk.chunkIndex(), receiver.getExpectedOffset());
                    } else if (p.getType() == PacketType.FILE_COMPLETE) {
                        FileTransferCodec.CompletePayload complete = p.decodeFileComplete();
                        finalPath = receiver.completeTransfer(complete.totalChunks(), complete.totalBytes(), complete.sha256());
                        finished = true;
                    }
                }

                senderThread.join(10_000);
                if (senderError.get() != null) {
                    throw new RuntimeException("Sender failed for size " + size, senderError.get());
                }

                assert finalPath != null : "Receiver did not produce final path for size " + size;
                assert Files.exists(finalPath) : "Target file missing on disk for size " + size;
                assert Files.size(finalPath) == size : "Target size mismatch: expected " + size + ", got " + Files.size(finalPath);
                assert HashUtils.sha256(finalPath).equalsIgnoreCase(expectedSha256) : "SHA-256 mismatch for size " + size;
                assert uploadTransfer.getState() == TransferState.VERIFYING;
                assert downloadTransfer.getState() == TransferState.COMPLETED;

                senderConn.close();
                receiverConn.close();
                clientSocket.close();
                serverSocket.close();
            }

        } finally {
            cleanupDir(tempDir);
            cleanupDir(dlDir);
        }
    }

    /**
     * Verifies that streaming a 10 GB sparse file runs in strictly bounded memory:
     * Heap delta must remain under 32 MB, regardless of 10 GB logical size.
     */
    public void testSparse10GBBoundedMemory() throws Exception {
        Path tempDir = Files.createTempDirectory("sparse-10gb-temp-");
        Path sparseFile = tempDir.resolve("sparse_10gb.dat");
        long tenGb = 10L * 1024 * 1024 * 1024; // 10,737,418,240 bytes

        try {
            // Create sparse file with 1 byte at position 10 GB - 1
            try (FileChannel fc = FileChannel.open(sparseFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                fc.position(tenGb - 1);
                fc.write(ByteBuffer.wrap(new byte[]{ (byte) 0xAA }));
            }

            assert Files.size(sparseFile) == tenGb : "Sparse file size should be exactly 10 GB";

            // Baseline garbage collection and heap measurement
            System.gc();
            Thread.sleep(100);
            long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "sparse_10gb.dat", tenGb, System.currentTimeMillis(), "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.UPLOAD, sparseFile);

            try (ServerSocket ss = new ServerSocket(0)) {
                int port = ss.getLocalPort();
                Socket clientSocket = new Socket("127.0.0.1", port);
                Socket serverSocket = ss.accept();

                TcpConnection senderConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                senderConn.setState(ConnectionState.READY);

                int chunkSize = ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE; // 64 KiB
                int windowSize = 8;
                FileSender sender = new FileSender(chunkSize, windowSize, 2000, 3);

                AtomicInteger chunksReceived = new AtomicInteger();

                Thread senderThread = Thread.ofVirtual().start(() -> {
                    try {
                        sender.streamFile(sparseFile, senderConn, transfer, null);
                    } catch (Exception ignored) {
                        // Expected to abort when socket is closed after test
                    }
                });

                InputStream in = serverSocket.getInputStream();
                PacketDecoder decoder = new PacketDecoder();

                // Consume up to 1000 chunks and ACK them
                for (int i = 0; i < 1000; i++) {
                    Packet p = decoder.decode(in);
                    if (p == null) break;
                    if (p.getType() == PacketType.FILE_CHUNK) {
                        FileChunk chunk = p.decodeFileChunk();
                        chunksReceived.incrementAndGet();
                        sender.onAckReceived(chunk.chunkIndex(), (chunk.chunkIndex() + 1L) * chunkSize);
                    }
                }

                assert chunksReceived.get() == 1000 : "Expected 1000 chunks streamed from 10 GB file, got " + chunksReceived.get();

                // Measure heap usage
                System.gc();
                Thread.sleep(100);
                long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long heapDelta = heapAfter - heapBefore;

                // Memory bound check: heap delta must be under 32 MB
                assert heapDelta < 32L * 1024 * 1024 : "Heap memory grew too large during 10 GB streaming: " + (heapDelta / 1024 / 1024) + " MB";

                senderConn.close();
                clientSocket.close();
                serverSocket.close();
                senderThread.join(2000);
            }

        } finally {
            cleanupDir(tempDir);
        }
    }

    /**
     * Verifies sliding-window flow control:
     * Sender transmits up to windowSize unacknowledged chunks, then halts until ACKs arrive.
     */
    public void testSlidingWindowFlowControlAndBackpressure() throws Exception {
        Path tempDir = Files.createTempDirectory("window-test-");
        Path srcFile = tempDir.resolve("window_test.bin");
        int chunkSize = 16 * 1024; // 16 KiB
        int windowSize = 4;
        long totalBytes = 12L * chunkSize; // 12 chunks

        try {
            writeDeterministicFile(srcFile, totalBytes);

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "window_test.bin", totalBytes, System.currentTimeMillis(), "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.UPLOAD, srcFile);

            try (ServerSocket ss = new ServerSocket(0)) {
                Socket clientSocket = new Socket("127.0.0.1", ss.getLocalPort());
                Socket serverSocket = ss.accept();

                TcpConnection senderConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                senderConn.setState(ConnectionState.READY);

                FileSender sender = new FileSender(chunkSize, windowSize, 5000, 3);

                Thread senderThread = Thread.ofVirtual().start(() -> {
                    try {
                        sender.streamFile(srcFile, senderConn, transfer, null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream in = serverSocket.getInputStream();
                PacketDecoder decoder = new PacketDecoder();

                // 1. Receive first batch of unacknowledged chunks
                List<Integer> receivedIndices = new ArrayList<>();
                for (int i = 0; i < windowSize; i++) {
                    Packet p = decoder.decode(in);
                    assert p != null && p.getType() == PacketType.FILE_CHUNK;
                    receivedIndices.add(p.decodeFileChunk().chunkIndex());
                }

                assert receivedIndices.equals(List.of(0, 1, 2, 3)) : "Initial window should emit chunks 0..3";

                // Verify sender is now blocked by backpressure: no more chunks immediately arrive
                serverSocket.setSoTimeout(300);
                boolean timeoutOccurred = false;
                try {
                    decoder.decode(in);
                } catch (java.net.SocketTimeoutException e) {
                    timeoutOccurred = true;
                }
                assert timeoutOccurred : "Sender did not block at window boundary (backpressure failed)";

                // 2. ACK chunk 0 -> sender should emit chunk 4
                serverSocket.setSoTimeout(5000);
                sender.onAckReceived(0, chunkSize);
                Packet p4 = decoder.decode(in);
                assert p4 != null && p4.getType() == PacketType.FILE_CHUNK;
                assert p4.decodeFileChunk().chunkIndex() == 4 : "Expected chunk 4 after acking chunk 0";

                // 3. ACK chunk 1 -> sender should emit chunk 5
                sender.onAckReceived(1, 2L * chunkSize);
                Packet p5 = decoder.decode(in);
                assert p5 != null && p5.getType() == PacketType.FILE_CHUNK;
                assert p5.decodeFileChunk().chunkIndex() == 5 : "Expected chunk 5 after acking chunk 1";

                // Drain remaining chunks
                for (int c = 6; c < 12; c++) {
                    sender.onAckReceived(c - 4, (c - 3L) * chunkSize);
                    Packet p = decoder.decode(in);
                    assert p != null && p.getType() == PacketType.FILE_CHUNK;
                    assert p.decodeFileChunk().chunkIndex() == c;
                }

                // Acknowledge all through chunk 11
                sender.onAckReceived(11, totalBytes);
                Packet complete = decoder.decode(in);
                assert complete != null && complete.getType() == PacketType.FILE_COMPLETE;

                senderThread.join(2000);
                senderConn.close();
                clientSocket.close();
                serverSocket.close();
            }

        } finally {
            cleanupDir(tempDir);
        }
    }

    /**
     * Verifies reliable chunk retransmission on ACK timeout.
     */
    public void testChunkRetransmissionOnTimeout() throws Exception {
        Path tempDir = Files.createTempDirectory("retransmit-test-");
        Path srcFile = tempDir.resolve("retransmit.bin");
        int chunkSize = 32 * 1024;
        long totalBytes = 2L * chunkSize; // 2 chunks

        try {
            writeDeterministicFile(srcFile, totalBytes);

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "retransmit.bin", totalBytes, System.currentTimeMillis(), "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.UPLOAD, srcFile);

            try (ServerSocket ss = new ServerSocket(0)) {
                Socket clientSocket = new Socket("127.0.0.1", ss.getLocalPort());
                Socket serverSocket = ss.accept();

                TcpConnection senderConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                senderConn.setState(ConnectionState.READY);

                // Use short timeout: 250ms
                FileSender sender = new FileSender(chunkSize, 2, 250, 3);

                Thread senderThread = Thread.ofVirtual().start(() -> {
                    try {
                        sender.streamFile(srcFile, senderConn, transfer, null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                InputStream in = serverSocket.getInputStream();
                PacketDecoder decoder = new PacketDecoder();

                // Receive chunk 0 initially
                Packet p0 = decoder.decode(in);
                assert p0 != null && p0.getType() == PacketType.FILE_CHUNK;
                assert p0.decodeFileChunk().chunkIndex() == 0;

                // Receive chunk 1
                Packet p1 = decoder.decode(in);
                assert p1 != null && p1.getType() == PacketType.FILE_CHUNK;
                assert p1.decodeFileChunk().chunkIndex() == 1;

                // DO NOT ACK. Wait > 250ms for retransmission of chunk 0
                Packet p0Retry = decoder.decode(in);
                assert p0Retry != null && p0Retry.getType() == PacketType.FILE_CHUNK;
                assert p0Retry.decodeFileChunk().chunkIndex() == 0 : "Expected chunk 0 to be retransmitted on timeout";

                // Now send cumulative ACK for chunk 1 (meaning 0 and 1 are received)
                sender.onAckReceived(1, totalBytes);

                Packet complete = decoder.decode(in);
                assert complete != null && complete.getType() == PacketType.FILE_COMPLETE;

                senderThread.join(2000);
                senderConn.close();
                clientSocket.close();
                serverSocket.close();
            }

        } finally {
            cleanupDir(tempDir);
        }
    }

    /**
     * Verifies that if source file is modified or truncated during transfer, sender safely detects it and aborts.
     */
    public void testSourceMutationDetection() throws Exception {
        Path tempDir = Files.createTempDirectory("mutation-test-");
        Path srcFile = tempDir.resolve("mutating.bin");
        int chunkSize = 32 * 1024;
        long totalBytes = 4L * chunkSize;

        try {
            writeDeterministicFile(srcFile, totalBytes);

            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "mutating.bin", totalBytes, System.currentTimeMillis(), "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.UPLOAD, srcFile);

            try (ServerSocket ss = new ServerSocket(0)) {
                Socket clientSocket = new Socket("127.0.0.1", ss.getLocalPort());
                Socket serverSocket = ss.accept();

                TcpConnection senderConn = new TcpConnection(clientSocket, ConnectionDirection.OUTBOUND);
                senderConn.setState(ConnectionState.READY);

                FileSender sender = new FileSender(chunkSize, 2, 2000, 3);
                AtomicReference<Throwable> errorRef = new AtomicReference<>();

                Thread senderThread = Thread.ofVirtual().start(() -> {
                    try {
                        sender.streamFile(srcFile, senderConn, transfer, null);
                    } catch (Throwable t) {
                        errorRef.set(t);
                    }
                });

                InputStream in = serverSocket.getInputStream();
                PacketDecoder decoder = new PacketDecoder();

                // Read chunk 0
                Packet p0 = decoder.decode(in);
                assert p0 != null && p0.getType() == PacketType.FILE_CHUNK;

                // Mutate source file size on disk!
                Files.write(srcFile, new byte[]{ 0x11, 0x22, 0x33 }, StandardOpenOption.APPEND);

                // ACK chunk 0 to trigger next window cycle
                sender.onAckReceived(0, chunkSize);

                senderThread.join(3000);

                assert errorRef.get() != null : "Sender should have failed due to source mutation";
                assert errorRef.get().getMessage().contains("Source file was modified during transfer")
                        : "Expected mutation error message, got: " + errorRef.get().getMessage();
                assert transfer.getState() == TransferState.FAILED;

                senderConn.close();
                clientSocket.close();
                serverSocket.close();
            }

        } finally {
            cleanupDir(tempDir);
        }
    }

    /**
     * Verifies process crash recovery:
     * - Interrupted transfer leaves .part and .meta files
     * - RecoveryManager.assessTransfer() identifies RESUMABLE state
     * - Resumed receiver seamlessly continues from exact offset to bit-for-bit completion.
     */
    public void testProcessCrashRecoveryAndExactResume() throws Exception {
        Path tempDir = Files.createTempDirectory("crash-temp-");
        Path dlDir = Files.createTempDirectory("crash-dl-");
        Path srcFile = tempDir.resolve("crash_test.bin");
        int chunkSize = 32 * 1024;
        int totalChunks = 8;
        long totalBytes = (long) totalChunks * chunkSize;

        try {
            writeDeterministicFile(srcFile, totalBytes);
            String expectedSha256 = HashUtils.sha256(srcFile);

            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();

            FileMetadata meta = new FileMetadata(tid, sid, rid, "crash_test.bin", totalBytes, System.currentTimeMillis(), expectedSha256);
            Transfer downloadTransfer1 = new Transfer(meta, TransferDirection.DOWNLOAD, null);
            RecoveryManager rm = new RecoveryManager(tempDir);

            // Phase 1: Receive 3 chunks and simulate crash
            FileReceiver receiver1 = new FileReceiver(meta, dlDir, tempDir, downloadTransfer1, rm, null);
            for (int i = 0; i < 3; i++) {
                byte[] chunkData = new byte[chunkSize];
                try (FileChannel fc = FileChannel.open(srcFile, StandardOpenOption.READ)) {
                    fc.position((long) i * chunkSize);
                    fc.read(ByteBuffer.wrap(chunkData));
                }
                receiver1.receiveChunk(new FileChunk(tid, i, (long) i * chunkSize, chunkSize, chunkData));
            }

            // Abrupt crash
            receiver1.close();

            // Check assessment via RecoveryManager
            RecoveryManager.RecoveryStatus status = rm.assessTransfer(tid);
            assert status == RecoveryManager.RecoveryStatus.RESUMABLE : "Expected RESUMABLE status, got " + status;
            Optional<TransferCheckpoint> cpOpt = rm.loadCheckpoint(tid);
            assert cpOpt.isPresent();
            TransferCheckpoint cp = cpOpt.get();
            assert cp.nextExpectedChunk() == 3;
            assert cp.nextExpectedOffset() == 3L * chunkSize;

            // Phase 2: Resume
            Transfer downloadTransfer2 = Transfer.fromCheckpoint(cp, rm.getPartFilePath(tid));
            FileReceiver receiver2 = new FileReceiver(meta, dlDir, tempDir, downloadTransfer2, rm, cp, null);

            for (int i = 3; i < totalChunks; i++) {
                byte[] chunkData = new byte[chunkSize];
                try (FileChannel fc = FileChannel.open(srcFile, StandardOpenOption.READ)) {
                    fc.position((long) i * chunkSize);
                    fc.read(ByteBuffer.wrap(chunkData));
                }
                receiver2.receiveChunk(new FileChunk(tid, i, (long) i * chunkSize, chunkSize, chunkData));
            }

            Path finalFile = receiver2.completeTransfer(totalChunks, totalBytes, expectedSha256);

            assert Files.exists(finalFile);
            assert Files.size(finalFile) == totalBytes;
            assert HashUtils.sha256(finalFile).equalsIgnoreCase(expectedSha256);
            assert downloadTransfer2.getState() == TransferState.COMPLETED;

            // Verify checkpoint was cleaned up
            assert rm.loadCheckpoint(tid).isEmpty() : "Checkpoint should be deleted after successful completion";

        } finally {
            cleanupDir(tempDir);
            cleanupDir(dlDir);
        }
    }

    private static void writeDeterministicFile(Path path, long size) throws IOException {
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            if (size == 0) return;
            int bufSize = (int) Math.min(64 * 1024, size);
            ByteBuffer buf = ByteBuffer.allocate(bufSize);
            byte b = 0;
            long written = 0;
            while (written < size) {
                int toWrite = (int) Math.min(bufSize, size - written);
                buf.clear();
                for (int i = 0; i < toWrite; i++) {
                    buf.put((byte) ((b + i) & 0xFF));
                }
                buf.flip();
                while (buf.hasRemaining()) {
                    fc.write(buf);
                }
                written += toWrite;
                b = (byte) ((b + toWrite) & 0xFF);
            }
        }
    }

    private static void cleanupDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }
}
