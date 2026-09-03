package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies transfer of binary files containing random byte data.
 */
public class BinaryFileTransferTest {

    public void runAll() throws Exception {
        testBinaryFileTransfer();
    }

    private void testBinaryFileTransfer() throws Exception {
        Path baseDir = Files.createTempDirectory("binary-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Binary");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Binary");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        Path sourceFile = baseDir.resolve("random.bin");
        byte[] originalBytes = new byte[150 * 1024]; // 150 KiB
        new Random(888).nextBytes(originalBytes);
        Files.write(sourceFile, originalBytes);

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
            Transfer completedTransfer = future.get(10, TimeUnit.SECONDS);

            assert completedTransfer != null;
            assert completedTransfer.getState() == TransferState.COMPLETED;

            Path receivedFile = dlB.resolve("random.bin");
            assert Files.exists(receivedFile) : "Received binary file missing on Node B";
            byte[] receivedBytes = Files.readAllBytes(receivedFile);

            assert Arrays.equals(originalBytes, receivedBytes) : "Transferred binary bytes do not match source";
            assert HashUtils.sha256(receivedFile.toFile()).equalsIgnoreCase(expectedHash);

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
