package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.security.HashUtils;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Real500MBTransferTest {

    public static void main(String[] args) throws Exception {
        runTest();
    }

    public static void runTest() throws Exception {
        System.out.println("================================================================================");
        System.out.println("                 MESHDROP REAL 500 MB TRANSFER TEST                             ");
        System.out.println("================================================================================");

        Path baseDir = Files.createTempDirectory("meshdrop-500mb-");
        Path dirA = baseDir.resolve("A");
        Path dirB = baseDir.resolve("B");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        Path srcFile = dirA.resolve("payload_500mb.bin");
        long fileSize = 500L * 1024 * 1024; // 500 MB

        System.out.println("[1/4] Generating 500 MB streaming test file...");
        long genStart = System.currentTimeMillis();
        generateFile(srcFile, fileSize);
        System.out.printf("      File generated in %d ms.%n", System.currentTimeMillis() - genStart);

        System.out.print("      Computing SHA-256 hash... ");
        long hashStart = System.currentTimeMillis();
        String srcSha = HashUtils.sha256(srcFile);
        System.out.printf("%s (%d ms)%n", srcSha, System.currentTimeMillis() - hashStart);

        int chunkSize = ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE;
        NodeConfig cfgA = new NodeConfig(0, 0, chunkSize, dirA, dirA.resolve("dl"), dirA.resolve("tmp"));
        NodeConfig cfgB = new NodeConfig(0, 0, chunkSize, dirB, dirB.resolve("dl"), dirB.resolve("tmp"));

        NodeIdentity idA = NodeIdentity.createRandom("Sender-A");
        NodeIdentity idB = NodeIdentity.createRandom("Receiver-B");

        Node nodeA = new Node(cfgA, idA);
        Node nodeB = new Node(cfgB, idB);

        try {
            System.out.println("[2/4] Starting nodes and connecting...");
            nodeA.start();
            nodeB.start();

            int portB = nodeB.getTcpServer().getLocalPort();
            nodeA.connectTo("127.0.0.1", portB);

            long deadline = System.currentTimeMillis() + 5000;
            while (nodeA.getPeerManager().findPeer(idB.nodeId()).filter(Peer::isConnected).isEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerB = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            System.out.println("      Connected: Sender-A -> Receiver-B on port " + portB);

            System.out.println("[3/4] Streaming 500 MB file with sliding window...");
            long transferStart = System.currentTimeMillis();
            AtomicLong lastPrint = new AtomicLong(0);

            nodeA.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer t) {
                    long now = System.currentTimeMillis();
                    if (now - lastPrint.get() >= 500 || t.getBytesTransferred() == fileSize) {
                        lastPrint.set(now);
                        double pct = (t.getBytesTransferred() * 100.0) / fileSize;
                        double elapsedSec = Math.max(0.001, (now - transferStart) / 1000.0);
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

            CompletableFuture<Transfer> future = nodeA.sendFile(peerB.getNodeId(), srcFile);

            Transfer result = future.get(120, TimeUnit.SECONDS);
            System.out.println();
            System.out.println("      Transfer state: " + result.getState());

            System.out.println("[4/4] Verifying destination integrity...");
            Path destFile = dirB.resolve("dl").resolve("payload_500mb.bin");
            if (!Files.isRegularFile(destFile)) {
                throw new AssertionError("Destination file does not exist: " + destFile);
            }
            if (Files.size(destFile) != fileSize) {
                throw new AssertionError(String.format("Size mismatch: expected %d, got %d", fileSize, Files.size(destFile)));
            }

            String destSha = HashUtils.sha256(destFile);
            System.out.println("      Source SHA-256:      " + srcSha);
            System.out.println("      Destination SHA-256: " + destSha);
            if (!srcSha.equalsIgnoreCase(destSha)) {
                throw new AssertionError("SHA-256 mismatch!");
            }

            double totalSec = (System.currentTimeMillis() - transferStart) / 1000.0;
            double throughput = (fileSize / (1024.0 * 1024.0)) / totalSec;
            System.out.println("================================================================================");
            System.out.println("                 500 MB TRANSFER TEST: PASSED!                                  ");
            System.out.printf("Total Transferred: %.2f MB%n", fileSize / (1024.0 * 1024.0));
            System.out.printf("Elapsed Time:      %.2f s%n", totalSec);
            System.out.printf("Average Speed:     %.2f MB/s%n", throughput);
            System.out.println("Integrity:         100% Bit-for-bit match (SHA-256 verified)");
            System.out.println("================================================================================");

        } finally {
            nodeA.stop();
            nodeB.stop();
            cleanup(baseDir);
        }
    }

    private static void generateFile(Path path, long size) throws IOException {
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            for (int i = 0; i < buf.capacity(); i++) {
                buf.put((byte) (i & 0xFF));
            }
            long written = 0;
            while (written < size) {
                buf.rewind();
                int toWrite = (int) Math.min(buf.capacity(), size - written);
                buf.limit(toWrite);
                fc.write(buf);
                written += toWrite;
            }
        }
    }

    private static void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
