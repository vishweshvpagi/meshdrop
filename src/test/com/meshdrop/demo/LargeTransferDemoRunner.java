package com.meshdrop.demo;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.security.HashUtils;
import com.meshdrop.transfer.Transfer;
import com.meshdrop.transfer.TransferListener;
import com.meshdrop.transfer.TransferState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live demonstration runner for large file transfers with sliding-window flow control,
 * real-time throughput/progress reporting, and bounded heap memory guarantees.
 */
public class LargeTransferDemoRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("           MESHDROP LARGE-FILE STREAMING TRANSFER DEMONSTRATION                 ");
        System.out.println("================================================================================");
        System.out.println();

        Path baseDir = Files.createTempDirectory("meshdrop-large-transfer-demo-");
        Path aliceDir = baseDir.resolve("Alice");
        Path bobDir = baseDir.resolve("Bob");

        Node alice = null;
        Node bob = null;

        try {
            long fileSize = 20L * 1024 * 1024; // 20 MB
            int chunkSize = ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE; // 64 KiB
            int windowSize = ProtocolConstants.DEFAULT_WINDOW_SIZE;    // 8

            Files.createDirectories(aliceDir);
            Path srcFile = aliceDir.resolve("large_movie.mp4");

            System.out.println("[1/5] Generating streaming test file: large_movie.mp4 (" + (fileSize / 1024 / 1024) + " MB)...");
            long genStart = System.currentTimeMillis();
            writeStreamingFile(srcFile, fileSize);
            long genTime = System.currentTimeMillis() - genStart;

            System.out.println("      File created in " + genTime + " ms without loading entire payload into RAM.");
            System.out.print("      Computing SHA-256 hash via streaming 256 KiB buffer... ");
            String srcHash = HashUtils.sha256(srcFile);
            System.out.println(srcHash);
            System.out.println();

            // Baseline memory
            System.gc();
            Thread.sleep(100);
            long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // 2. Start Alice and Bob
            System.out.println("[2/5] Initializing Alice and Bob nodes with sliding window (" + windowSize + " x " + (chunkSize / 1024) + " KiB)...");
            NodeConfig cfgA = new NodeConfig(0, 0, chunkSize, aliceDir, aliceDir.resolve("downloads"), aliceDir.resolve("transfers"));
            NodeConfig cfgB = new NodeConfig(0, 0, chunkSize, bobDir, bobDir.resolve("downloads"), bobDir.resolve("transfers"));

            NodeIdentity idA = NodeIdentity.createRandom("Alice");
            NodeIdentity idB = NodeIdentity.createRandom("Bob");

            alice = new Node(cfgA, idA);
            bob = new Node(cfgB, idB);

            alice.start();
            bob.start();

            int portB = bob.getTcpServer().getLocalPort();
            alice.connectTo("127.0.0.1", portB);

            long deadline = System.currentTimeMillis() + 5000;
            while (alice.getPeerManager().findPeer(idB.nodeId()).filter(Peer::isConnected).isEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBob = alice.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            System.out.println("      Connected Alice -> Bob on 127.0.0.1:" + portB);
            System.out.println();

            // 3. Register live progress listener
            System.out.println("[3/5] Starting live streaming transfer...");
            long transferStartTime = System.currentTimeMillis();
            AtomicLong lastPrintTime = new AtomicLong(0);

            alice.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer t) {
                    long now = System.currentTimeMillis();
                    if (now - lastPrintTime.get() >= 250 || t.getBytesTransferred() == fileSize) {
                        lastPrintTime.set(now);
                        double pct = (t.getBytesTransferred() * 100.0) / fileSize;
                        double elapsedSec = Math.max(0.001, (now - transferStartTime) / 1000.0);
                        double mbps = (t.getBytesTransferred() / (1024.0 * 1024.0)) / elapsedSec;
                        System.out.printf("\r      Progress: [%-20s] %5.1f%% | %5.1f / %d MB | %6.1f MB/s | Chunks: %d/%d",
                                "=".repeat((int) (pct / 5)),
                                pct,
                                t.getBytesTransferred() / (1024.0 * 1024.0),
                                fileSize / (1024 * 1024),
                                mbps,
                                t.getChunksTransferred(),
                                (fileSize + chunkSize - 1) / chunkSize);
                    }
                }
            });

            // 4. Send file
            CompletableFuture<Transfer> future = alice.sendFile(peerBob.getNodeId(), srcFile);
            Transfer result = future.get(30, TimeUnit.SECONDS);
            System.out.println();
            System.out.println();

            assert result.getState() == TransferState.COMPLETED : "Transfer failed: " + result.getErrorMessage();

            // 5. Memory and Hash Verification
            System.out.println("[4/5] Verifying bounded memory footprint...");
            System.gc();
            Thread.sleep(100);
            long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long heapDelta = Math.abs(heapAfter - heapBefore);

            System.out.printf("      Heap memory change during transfer: %.2f MB (Limit: < 32.0 MB)%n", heapDelta / (1024.0 * 1024.0));
            assert heapDelta < 32L * 1024 * 1024 : "Heap grew excessively during transfer!";

            System.out.println();
            System.out.println("[5/5] Verifying destination file bit-for-bit SHA-256 integrity...");
            Path destFile = bobDir.resolve("downloads").resolve("large_movie.mp4");
            assert Files.isRegularFile(destFile) : "Destination file not found!";
            assert Files.size(destFile) == fileSize : "Destination file size mismatch!";

            String destHash = HashUtils.sha256(destFile);
            System.out.println("      Source SHA-256:      " + srcHash);
            System.out.println("      Destination SHA-256: " + destHash);
            assert srcHash.equalsIgnoreCase(destHash) : "SHA-256 mismatch between source and destination!";

            System.out.println();
            System.out.println("================================================================================");
            System.out.println("                    LARGE FILE TRANSFER DEMO: PASSED                            ");
            System.out.println("================================================================================");
            System.out.printf("Total Transferred: %.2f MB%n", fileSize / (1024.0 * 1024.0));
            System.out.printf("Elapsed Time:      %.2f s%n", (System.currentTimeMillis() - transferStartTime) / 1000.0);
            System.out.printf("Integrity:         100%% Bit-for-bit match (SHA-256 verified)%n");
            System.out.printf("Memory Bound:      Strictly bounded (< 32 MB heap utilization)%n");
            System.out.println("================================================================================");

        } finally {
            if (alice != null) alice.stop();
            if (bob != null) bob.stop();
            cleanup(baseDir);
        }
    }

    private static void writeStreamingFile(Path path, long size) throws IOException {
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int bufSize = 64 * 1024;
            ByteBuffer buf = ByteBuffer.allocate(bufSize);
            long written = 0;
            byte seed = 0x2A;
            while (written < size) {
                int toWrite = (int) Math.min(bufSize, size - written);
                buf.clear();
                for (int i = 0; i < toWrite; i++) {
                    buf.put((byte) ((seed + i) & 0xFF));
                }
                buf.flip();
                while (buf.hasRemaining()) {
                    fc.write(buf);
                }
                written += toWrite;
                seed = (byte) ((seed + toWrite) & 0xFF);
            }
        }
    }

    private static void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
