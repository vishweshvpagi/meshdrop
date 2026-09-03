package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies that multiple concurrent file transfers operate independently and complete successfully.
 */
public class ConcurrentTransfersTest {

    public void runAll() throws Exception {
        testTwoSimultaneousTransfers();
    }

    private void testTwoSimultaneousTransfers() throws Exception {
        Path baseDir = Files.createTempDirectory("concurrent-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Concurrent");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Concurrent");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        Path file1 = baseDir.resolve("concurrent-1.txt");
        Path file2 = baseDir.resolve("concurrent-2.txt");

        Files.writeString(file1, "Content of Concurrent File 1: Pure Virtual Threads P2P", StandardCharsets.UTF_8);
        Files.writeString(file2, "Content of Concurrent File 2: Non-blocking Chunked Streaming", StandardCharsets.UTF_8);

        String hash1 = HashUtils.sha256(file1.toFile());
        String hash2 = HashUtils.sha256(file2.toFile());

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

            // Launch both transfers concurrently!
            CompletableFuture<Transfer> f1 = nodeA.sendFile(idB.nodeId(), file1);
            CompletableFuture<Transfer> f2 = nodeA.sendFile(idB.nodeId(), file2);

            CompletableFuture.allOf(f1, f2).get(15, TimeUnit.SECONDS);

            Transfer t1 = f1.get();
            Transfer t2 = f2.get();

            assert t1.getState() == TransferState.COMPLETED : "Transfer 1 state should be COMPLETED";
            assert t2.getState() == TransferState.COMPLETED : "Transfer 2 state should be COMPLETED";
            assert !t1.getTransferId().equals(t2.getTransferId()) : "Transfer IDs must be independent";

            Path received1 = dlB.resolve("concurrent-1.txt");
            Path received2 = dlB.resolve("concurrent-2.txt");

            assert Files.exists(received1) && Files.exists(received2) : "Both files must exist on Node B";
            assert HashUtils.sha256(received1.toFile()).equalsIgnoreCase(hash1);
            assert HashUtils.sha256(received2.toFile()).equalsIgnoreCase(hash2);

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
