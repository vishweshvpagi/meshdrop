package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Verifies that shutting down a node during an active transfer terminates cleanly without deadlocks.
 */
public class ShutdownDuringTransferTest {

    public void runAll() throws Exception {
        testShutdownDuringActiveTransfer();
    }

    private void testShutdownDuringActiveTransfer() throws Exception {
        Path baseDir = Files.createTempDirectory("shutdown-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Shutdown");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Shutdown");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        Path sourceFile = baseDir.resolve("big-test.bin");
        Files.write(sourceFile, new byte[256 * 1024]); // 256 KiB

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

            // Initiate transfer
            CompletableFuture<Transfer> future = nodeA.sendFile(idB.nodeId(), sourceFile);

            // Shutdown Node A immediately
            Thread.sleep(15);
            nodeA.stop();
            nodeB.stop();

            assert !nodeA.getFileTransferService().isRunning() : "Node A transfer service should be stopped";
            assert !nodeB.getFileTransferService().isRunning() : "Node B transfer service should be stopped";

        } finally {
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
