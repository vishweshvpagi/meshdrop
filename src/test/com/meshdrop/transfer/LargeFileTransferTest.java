package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies memory-safe chunked streaming transfer of a multi-chunk file (500 KiB).
 */
public class LargeFileTransferTest {

    public void runAll() throws Exception {
        testLargeFileTransfer();
    }

    private void testLargeFileTransfer() throws Exception {
        Path baseDir = Files.createTempDirectory("large-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Large");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Large");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        Path sourceFile = baseDir.resolve("large-data.dat");
        int fileSize = 500 * 1024; // 500 KiB -> ~8 chunks of 64 KiB
        byte[] buffer = new byte[fileSize];
        new Random(777).nextBytes(buffer);
        Files.write(sourceFile, buffer);

        String expectedHash = HashUtils.sha256(sourceFile.toFile());

        try {
            nodeA.start();
            nodeB.start();

            int portA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int portB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portA);

            long deadline = System.currentTimeMillis() + 10_000;
            Peer peerB = null;
            while (System.currentTimeMillis() < deadline) {
                var match = nodeA.getPeerManager().findPeer(idB.nodeId());
                if (match.isPresent() && match.get().isConnected()) {
                    peerB = match.get();
                    break;
                }
                Thread.sleep(50);
            }

            assert peerB != null : "Node A failed to connect to Node B within timeout";

            CompletableFuture<Transfer> future = nodeA.sendFile(idB.nodeId(), sourceFile);
            Transfer completed = future.get(15, TimeUnit.SECONDS);

            assert completed != null;
            assert completed.getState() == TransferState.COMPLETED;
            assert completed.getBytesTransferred() == fileSize;

            Path receivedFile = dlB.resolve("large-data.dat");
            assert Files.exists(receivedFile) : "Large file not found on Node B";
            assert Files.size(receivedFile) == fileSize : "Large file size mismatch";
            assert HashUtils.sha256(receivedFile.toFile()).equalsIgnoreCase(expectedHash) : "Large file hash mismatch";

        } finally {
            nodeA.stop();
            nodeB.stop();
            deleteDir(baseDir);
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
