package com.meshdrop.integration;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;
import com.meshdrop.transfer.Transfer;
import com.meshdrop.transfer.TransferState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration test verifying file transfer between two live MeshDrop nodes.
 */
public class TwoNodeFileTransferTest {

    public void runAll() throws Exception {
        testTwoNodeFileTransfer();
    }

    private void testTwoNodeFileTransfer() throws Exception {
        Path baseDir = Files.createTempDirectory("two-node-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-FT");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-FT");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        Path sourceFile = baseDir.resolve("test-file.txt");
        String content = "Hello from MeshDrop File Transfer Phase 11! Pure Java 26 P2P file sharing.";
        Files.writeString(sourceFile, content, StandardCharsets.UTF_8);
        String expectedHash = HashUtils.sha256(sourceFile.toFile());

        try {
            nodeA.start();
            nodeB.start();

            int portA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int portB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portA);

            // Wait for mutual discovery and connection handshake
            long deadline = System.currentTimeMillis() + 10_000;
            Peer peerBFromA = null;
            while (System.currentTimeMillis() < deadline) {
                var match = nodeA.getPeerManager().findPeer(idB.nodeId());
                if (match.isPresent() && match.get().isConnected()) {
                    peerBFromA = match.get();
                    break;
                }
                Thread.sleep(50);
            }

            assert peerBFromA != null : "Node A failed to connect to Node B within timeout";

            // Initiate file transfer from Node A to Node B
            CompletableFuture<Transfer> future = nodeA.sendFile(idB.nodeId(), sourceFile);
            Transfer completedTransfer = future.get(10, TimeUnit.SECONDS);

            assert completedTransfer != null;
            assert completedTransfer.getState() == TransferState.COMPLETED : "Transfer state should be COMPLETED";
            assert completedTransfer.getBytesTransferred() == Files.size(sourceFile);

            // Verify file exists on Node B
            Path receivedFile = dlB.resolve("test-file.txt");
            assert Files.exists(receivedFile) : "Received file does not exist on Node B: " + receivedFile;
            assert Files.size(receivedFile) == Files.size(sourceFile) : "Received file size mismatch";
            assert Files.readString(receivedFile, StandardCharsets.UTF_8).equals(content) : "Received file content mismatch";
            assert HashUtils.sha256(receivedFile.toFile()).equalsIgnoreCase(expectedHash) : "SHA-256 hash mismatch on Node B";

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
