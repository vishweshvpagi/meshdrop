package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end integration test of two running Nodes transferring a file,
 * surviving an intentional socket disconnect, reconnecting, and resuming to completion.
 */
public class TwoNodeResumeTest {

    public void runAll() throws Exception {
        testTwoNodeLiveTransferInterruptionAndResume();
    }

    private void testTwoNodeLiveTransferInterruptionAndResume() throws Exception {
        Path baseDir = Files.createTempDirectory("two-node-resume");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        Node nodeA = null;
        Node nodeB = null;

        try {
            // Setup two nodes
            UUID idA = UUID.randomUUID();
            UUID idB = UUID.randomUUID();

            NodeConfig cfgA = new NodeConfig(0, 0, false, null, 0, 0, 16 * 1024, 5000, baseDir, dlA, tmpA);
            NodeConfig cfgB = new NodeConfig(0, 0, false, null, 0, 0, 16 * 1024, 5000, baseDir, dlB, tmpB);

            nodeA = new Node(cfgA, NodeIdentity.of(idA, "NodeA"));
            nodeB = new Node(cfgB, NodeIdentity.of(idB, "NodeB"));

            nodeA.start();
            nodeB.start();

            // Connect A to B
            int portB = nodeB.getTcpServer().getLocalPort();
            var connA = nodeA.connectTo("127.0.0.1", portB);

            // Wait for handshake
            Thread.sleep(300);
            Peer peerB = nodeA.getPeerManager().findPeer(idB).orElseThrow();
            assert peerB.isConnected();

            // Create test file (48 KiB = 3 chunks of 16 KiB)
            byte[] fileBytes = new byte[48 * 1024];
            for (int i = 0; i < fileBytes.length; i++) {
                fileBytes[i] = (byte) (i % 239);
            }
            Path sourceFile = baseDir.resolve("payload.bin");
            Files.write(sourceFile, fileBytes);
            String sourceSha = HashUtils.sha256(fileBytes);

            // Sever connection during progress of first chunk
            AtomicBoolean severed = new AtomicBoolean(false);
            nodeA.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer transfer) {
                    if (severed.compareAndSet(false, true)) {
                        try {
                            // Sever connection
                            connA.close();
                        } catch (Exception ignored) {}
                    }
                }
            });

            // Start sending file from A to B
            CompletableFuture<Transfer> future = nodeA.sendFile(idB, sourceFile);

            // Wait for disconnect / interruption
            try {
                future.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            Thread.sleep(300);

            // Verify receiver B has partial file and is in RESUMABLE state
            var bResumable = nodeB.getFileTransferService().getTransferManager().getResumableTransfers();
            assert !bResumable.isEmpty() : "Node B must have a resumable transfer";
            Transfer bTransfer = bResumable.get(0);
            UUID tid = bTransfer.getTransferId();

            // Reconnect A to B
            var connA2 = nodeA.connectTo("127.0.0.1", portB);
            Thread.sleep(300);

            Peer peerBReconnected = nodeA.getPeerManager().findPeer(idB).orElseThrow();
            assert peerBReconnected.isConnected();

            // Resume transfer from Node A
            CompletableFuture<Transfer> resumeFuture = nodeA.getFileTransferService().resumeTransfer(tid, peerBReconnected);
            Transfer completed = resumeFuture.get(5, TimeUnit.SECONDS);

            assert completed.getState() == TransferState.COMPLETED : "Transfer must complete";
            assert completed.getBytesTransferred() == fileBytes.length;

            // Verify completed file on Node B
            Path receivedFile = dlB.resolve("payload.bin");
            assert Files.isRegularFile(receivedFile) : "Destination file must exist on Node B";
            assert Files.size(receivedFile) == fileBytes.length;
            assert Arrays.equals(Files.readAllBytes(receivedFile), fileBytes) : "Destination file content must match source";
            assert HashUtils.sha256(receivedFile.toFile()).equalsIgnoreCase(sourceSha);

        } finally {
            if (nodeA != null) nodeA.stop();
            if (nodeB != null) nodeB.stop();
            cleanupDir(baseDir);
        }
    }

    private void cleanupDir(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
