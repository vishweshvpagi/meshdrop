package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies that when a receiving node completely crashes/shuts down mid-transfer,
 * restarts, and reconnects with the sender, the transfer resumes from checkpoint and completes.
 */
public class ResumeAfterRestartTest {

    public void runAll() throws Exception {
        testResumeAfterReceiverRestart();
    }

    private void testResumeAfterReceiverRestart() throws Exception {
        Path baseDir = Files.createTempDirectory("restart-resume-test");
        Path dlA = baseDir.resolve("dlA");
        Path tmpA = baseDir.resolve("tmpA");
        Path dlB = baseDir.resolve("dlB");
        Path tmpB = baseDir.resolve("tmpB");

        Node nodeA = null;
        Node nodeB = null;

        try {
            UUID idA = UUID.randomUUID();
            UUID idB = UUID.randomUUID();

            NodeConfig cfgA = new NodeConfig(0, 0, false, null, 0, 0, 16 * 1024, 5000, baseDir, dlA, tmpA);
            NodeConfig cfgB = new NodeConfig(0, 0, false, null, 0, 0, 16 * 1024, 5000, baseDir, dlB, tmpB);

            nodeA = new Node(cfgA, NodeIdentity.of(idA, "NodeA"));
            nodeB = new Node(cfgB, NodeIdentity.of(idB, "NodeB"));

            nodeA.start();
            nodeB.start();

            int portB1 = nodeB.getTcpServer().getLocalPort();
            nodeA.connectTo("127.0.0.1", portB1);
            Thread.sleep(300);

            byte[] fileBytes = new byte[64 * 1024]; // 4 chunks of 16 KiB
            for (int i = 0; i < fileBytes.length; i++) fileBytes[i] = (byte) (i % 211);
            Path sourceFile = baseDir.resolve("restart_file.bin");
            Files.write(sourceFile, fileBytes);
            String sha = HashUtils.sha256(fileBytes);

            // Sever Node B during chunk 0/1
            final Node bToStop = nodeB;
            AtomicBoolean stoppedB = new AtomicBoolean(false);
            nodeA.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer transfer) {
                    if (stoppedB.compareAndSet(false, true)) {
                        Thread.ofVirtual().start(() -> bToStop.stop());
                    }
                }
            });

            CompletableFuture<Transfer> future = nodeA.sendFile(idB, sourceFile);
            try {
                future.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            Thread.sleep(500);

            // Node B was stopped. Now start a fresh Node B on the same directories
            Node nodeB2 = new Node(cfgB, NodeIdentity.of(idB, "NodeB"));
            nodeB2.start();
            nodeB = nodeB2; // For finally cleanup

            // Verify Node B2 discovered resumable transfer at boot
            var b2Resumable = nodeB2.getFileTransferService().getTransferManager().getResumableTransfers();
            assert !b2Resumable.isEmpty() : "Node B2 must discover resumable transfer upon startup";
            UUID tid = b2Resumable.get(0).getTransferId();

            // Reconnect A to B2
            int portB2 = nodeB2.getTcpServer().getLocalPort();
            nodeA.connectTo("127.0.0.1", portB2);
            long deadline = System.currentTimeMillis() + 3000;
            Peer peerB2 = null;
            while (System.currentTimeMillis() < deadline) {
                peerB2 = nodeA.getPeerManager().findPeer(idB).orElse(null);
                if (peerB2 != null && peerB2.isConnected() && peerB2.getConnection() != null && peerB2.getConnection().isReady()) {
                    break;
                }
                Thread.sleep(50);
            }
            assert peerB2 != null && peerB2.isConnected() : "Peer NodeB2 must be connected";

            // Resume transfer
            CompletableFuture<Transfer> resumeFuture = nodeA.getFileTransferService().resumeTransfer(tid, peerB2);
            Transfer done = resumeFuture.get(5, TimeUnit.SECONDS);

            assert done.getState() == TransferState.COMPLETED;

            // Check final file
            Path destFile = dlB.resolve("restart_file.bin");
            assert Files.isRegularFile(destFile);
            assert Files.size(destFile) == fileBytes.length;
            assert Arrays.equals(Files.readAllBytes(destFile), fileBytes);

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
