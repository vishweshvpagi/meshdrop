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
 * Verifies file transfers with non-ASCII and Unicode file names.
 */
public class UnicodeFilenameTest {

    public void runAll() throws Exception {
        testUnicodeFilenameTransfer();
    }

    private void testUnicodeFilenameTransfer() throws Exception {
        Path baseDir = Files.createTempDirectory("unicode-ft");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Unicode");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Unicode");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        String unicodeName = "ನಮಸ್ಕಾರ_こんにちは.txt";
        Path sourceFile = baseDir.resolve(unicodeName);
        String content = "Testing Unicode filename transfer in MeshDrop!";
        Files.writeString(sourceFile, content, StandardCharsets.UTF_8);

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
            Transfer completed = future.get(10, TimeUnit.SECONDS);

            assert completed != null;
            assert completed.getState() == TransferState.COMPLETED;

            Path receivedFile = dlB.resolve(unicodeName);
            assert Files.exists(receivedFile) : "Unicode file missing on Node B: " + receivedFile;
            assert Files.readString(receivedFile, StandardCharsets.UTF_8).equals(content);
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
