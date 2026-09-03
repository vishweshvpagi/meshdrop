package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies that file collisions do not overwrite existing files,
 * incrementing suffixes safely (e.g. photo (1).jpg).
 */
public class CollisionTest {

    public void runAll() throws Exception {
        testCollisionSafePathResolution();
        testTwoConsecutiveTransfersCollision();
    }

    private void testCollisionSafePathResolution() throws Exception {
        Path tempDir = Files.createTempDirectory("collision-unit");
        try {
            Path f1 = tempDir.resolve("doc.txt");
            Files.writeString(f1, "original");

            Path resolved1 = FileReceiver.resolveCollisionSafePath(tempDir, "doc.txt");
            assert resolved1.getFileName().toString().equals("doc (1).txt") : "Expected doc (1).txt, got: " + resolved1.getFileName();

            Files.writeString(resolved1, "second");
            Path resolved2 = FileReceiver.resolveCollisionSafePath(tempDir, "doc.txt");
            assert resolved2.getFileName().toString().equals("doc (2).txt") : "Expected doc (2).txt, got: " + resolved2.getFileName();

        } finally {
            deleteDir(tempDir);
        }
    }

    private void testTwoConsecutiveTransfersCollision() throws Exception {
        Path baseDir = Files.createTempDirectory("collision-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Collision");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Collision");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        Path sourceFile = baseDir.resolve("duplicate.txt");
        Files.writeString(sourceFile, "version 1", StandardCharsets.UTF_8);

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

            assert peerB != null;

            // Transfer 1
            CompletableFuture<Transfer> f1 = nodeA.sendFile(idB.nodeId(), sourceFile);
            Transfer t1 = f1.get(10, TimeUnit.SECONDS);
            assert t1.getState() == TransferState.COMPLETED;

            // Transfer 2 with different content
            Files.writeString(sourceFile, "version 2", StandardCharsets.UTF_8);
            CompletableFuture<Transfer> f2 = nodeA.sendFile(idB.nodeId(), sourceFile);
            Transfer t2 = f2.get(10, TimeUnit.SECONDS);
            assert t2.getState() == TransferState.COMPLETED;

            Path received1 = dlB.resolve("duplicate.txt");
            Path received2 = dlB.resolve("duplicate (1).txt");

            assert Files.exists(received1) : "Original received file missing";
            assert Files.exists(received2) : "Collision-resolved file missing";

            assert Files.readString(received1, StandardCharsets.UTF_8).equals("version 1") : "Original file was overwritten!";
            assert Files.readString(received2, StandardCharsets.UTF_8).equals("version 2") : "Second file content mismatch";

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
