package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end integration test verifying that a real multi-megabyte file transfer interrupted
 * by an abrupt network disconnection cleanly creates a checkpoint, transitions to a resumable state,
 * and successfully resumes across a newly established connection with bit-for-bit SHA-256 verification.
 */
public class RealInterruptedResumeTest {

    public void runAll() throws Exception {
        testTransferInterruptionAndResume();
    }

    private void testTransferInterruptionAndResume() throws Exception {
        Path baseDir = Files.createTempDirectory("interrupted-resume-test");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Resume");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Resume");

        NodeConfig configA = NodeConfig.forTesting(0, 0, dlA, tmpA);
        NodeConfig configB = NodeConfig.forTesting(0, 0, dlB, tmpB);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        // 2 MB test file
        int fileSize = 2 * 1024 * 1024;
        byte[] payload = new byte[fileSize];
        for (int i = 0; i < fileSize; i++) {
            payload[i] = (byte) ((i * 31 + 17) % 256);
        }

        Path sourceFile = baseDir.resolve("interrupted-movie.mp4");
        Files.write(sourceFile, payload);
        String expectedSha256 = HashUtils.sha256(sourceFile.toFile());

        try {
            nodeA.start();
            nodeB.start();

            // Connect Node A and Node B
            int portA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int portB = nodeB.getDiscoveryService().getUdpDiscoveryPort();
            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portA);

            Peer peerB = waitForConnectedPeer(nodeA, idB.nodeId(), 10_000);
            assert peerB != null : "Failed to connect Node A to Node B";

            AtomicBoolean severed = new AtomicBoolean(false);
            CountDownLatch severedLatch = new CountDownLatch(1);

            // Add listener to interrupt transfer midway once at least 256 KB is sent
            nodeA.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer transfer) {
                    if (transfer.getBytesTransferred() >= 256 * 1024 && severed.compareAndSet(false, true)) {
                        try {
                            // Sever active TCP connection abruptly
                            var conn = peerB.getConnection();
                            if (conn != null) {
                                conn.close();
                            }
                        } catch (Exception ignored) {
                        } finally {
                            severedLatch.countDown();
                        }
                    }
                }
            });

            // Start file transfer
            CompletableFuture<Transfer> initialTransferFuture = nodeA.sendFile(idB.nodeId(), sourceFile);

            // Wait for severance
            assert severedLatch.await(10, TimeUnit.SECONDS) : "Transfer did not reach severance threshold in time";

            // Expect the initial transfer future to fail due to broken socket
            boolean initialFailed = false;
            Transfer initialTransfer = null;
            try {
                initialTransfer = initialTransferFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                initialFailed = true;
            }
            assert initialFailed : "Initial transfer should have failed when connection was severed";

            // Allow receiver on Node B to process disconnection and persist checkpoint
            Thread.sleep(1000);

            // Verify receiver on Node B has created a checkpoint and part file
            var recoverableTransfers = nodeB.getRecoveryManager().scanRecoverableCheckpoints();
            assert !recoverableTransfers.isEmpty() : "Node B must have at least one recoverable checkpoint on disk";
            TransferCheckpoint cp = recoverableTransfers.getFirst();
            assert cp.bytesReceived() >= 64 * 1024 : "Checkpoint must have saved progress (>64KB), got: " + cp.bytesReceived();
            assert Files.exists(nodeB.getRecoveryManager().getPartFilePath(cp.transferId())) : "Part file must exist on Node B";

            // Re-establish connection between Node A and Node B
            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portA);

            Peer reconnectedPeerB = waitForConnectedPeer(nodeA, idB.nodeId(), 10_000);
            assert reconnectedPeerB != null : "Failed to re-establish connection between Node A and Node B";

            // Resume transfer from Node A
            UUID transferId = cp.transferId();
            CompletableFuture<Transfer> resumeFuture = nodeA.resumeTransfer(transferId);
            Transfer completedTransfer = resumeFuture.get(15, TimeUnit.SECONDS);

            assert completedTransfer != null : "Resumed transfer returned null";
            assert completedTransfer.getState() == TransferState.COMPLETED : "Resumed transfer state must be COMPLETED";
            assert completedTransfer.getBytesTransferred() == fileSize : "Resumed transfer total bytes transferred mismatch";

            // Verify file on Node B
            Path receivedFile = dlB.resolve("interrupted-movie.mp4");
            assert Files.exists(receivedFile) : "Completed file must exist in Node B downloads";
            assert Files.size(receivedFile) == fileSize : "Completed file size mismatch";
            assert HashUtils.sha256(receivedFile.toFile()).equalsIgnoreCase(expectedSha256) : "Bit-for-bit SHA-256 verification failed on Node B";

        } finally {
            nodeA.stop();
            nodeB.stop();
            deleteDir(baseDir);
        }
    }

    private Peer waitForConnectedPeer(Node node, UUID peerId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var peerOpt = node.getPeerManager().findPeer(peerId);
            if (peerOpt.isPresent() && peerOpt.get().isConnected() && peerOpt.get().getConnection() != null && peerOpt.get().getConnection().isReady()) {
                return peerOpt.get();
            }
            Thread.sleep(50);
        }
        return null;
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
