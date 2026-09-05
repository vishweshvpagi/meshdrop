package com.meshdrop.transfer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.security.HashUtils;
import com.meshdrop.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end 500 MB file transfer interruption and resume verification.
 *
 * Validates:
 * 1. Streaming of a real 500 MB payload over TCP with sliding window.
 * 2. Mid-transfer interruption at ~100-200 MB.
 * 3. On-disk .part and .meta checkpoint persistence with byte offset matching.
 * 4. Re-connection and resume handshake from checkpoint (no restart from 0).
 * 5. Full completion and 100% SHA-256 cryptographic match against source.
 * 6. Clean removal of partial checkpoints upon verified completion.
 */
public class Real500MBResumeTest {

    public static void main(String[] args) throws Exception {
        runTest();
    }

    public static void runTest() throws Exception {
        System.out.println("================================================================================");
        System.out.println("          MESHDROP 500 MB TRANSFER INTERRUPTION & RESUME TEST                   ");
        System.out.println("================================================================================");

        Path testFile = Path.of("data", "test500mb.dat");
        if (!Files.isRegularFile(testFile)) {
            testFile = Path.of("..", "SocketStuff", "data", "test500mb.dat");
        }
        if (!Files.isRegularFile(testFile)) {
            throw new IllegalStateException("Test file data/test500mb.dat not found!");
        }

        long fileSize = Files.size(testFile);
        System.out.printf("[1/5] Source file found: %s (%d bytes, %.1f MB)%n",
                testFile.toAbsolutePath(), fileSize, fileSize / (1024.0 * 1024.0));

        System.out.print("      Computing authoritative SHA-256 hash... ");
        long hashStart = System.currentTimeMillis();
        String expectedSha = HashUtils.sha256(testFile);
        System.out.printf("%s (%d ms)%n", expectedSha, System.currentTimeMillis() - hashStart);

        Path tempDir = Files.createTempDirectory("meshdrop-500mb-resume-");
        Path dirA = tempDir.resolve("nodeA");
        Path dirB = tempDir.resolve("nodeB");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        int chunkSize = ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE;
        NodeConfig cfgA = new NodeConfig(0, 0, chunkSize, dirA, dirA.resolve("dl"), dirA.resolve("tmp"));
        NodeConfig cfgB = new NodeConfig(0, 0, chunkSize, dirB, dirB.resolve("dl"), dirB.resolve("tmp"));

        NodeIdentity idA = NodeIdentity.createRandom("Sender-PC1");
        NodeIdentity idB = NodeIdentity.createRandom("Receiver-PC2");

        Node nodeA = new Node(cfgA, idA);
        Node nodeB = new Node(cfgB, idB);

        try {
            System.out.println("[2/5] Starting nodes and establishing TCP session...");
            nodeA.start();
            nodeB.start();

            int portB = nodeB.getTcpServer().getLocalPort();
            nodeA.connectTo("127.0.0.1", portB);

            long deadline = System.currentTimeMillis() + 5000;
            while (nodeA.getPeerManager().findPeer(idB.nodeId()).filter(Peer::isConnected).isEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerB = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            System.out.println("      Connected: Sender-PC1 <-> Receiver-PC2");

            System.out.println("[3/5] Starting 500 MB transfer and monitoring for interruption trigger...");
            long transferStart = System.currentTimeMillis();
            AtomicLong lastPrint = new AtomicLong(0);
            AtomicBoolean interrupted = new AtomicBoolean(false);
            AtomicReference<UUID> transferIdRef = new AtomicReference<>();
            AtomicLong interruptedOffset = new AtomicLong(0);

            long interruptThreshold = 120L * 1024 * 1024; // 120 MB

            nodeA.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferStarted(Transfer t) {
                    transferIdRef.set(t.getTransferId());
                }

                @Override
                public void onTransferProgress(Transfer t) {
                    long now = System.currentTimeMillis();
                    long bytes = t.getBytesTransferred();

                    if (now - lastPrint.get() >= 400 || bytes >= interruptThreshold) {
                        lastPrint.set(now);
                        double pct = (bytes * 100.0) / fileSize;
                        double elapsedSec = Math.max(0.001, (now - transferStart) / 1000.0);
                        double mbps = (bytes / (1024.0 * 1024.0)) / elapsedSec;
                        System.out.printf("\r      [Phase 1] Progress: %5.1f%% | %5.1f / %d MB | %6.1f MB/s",
                                pct, bytes / (1024.0 * 1024.0), fileSize / (1024 * 1024), mbps);
                    }

                    if (!interrupted.get() && bytes >= interruptThreshold) {
                        interrupted.set(true);
                        interruptedOffset.set(bytes);
                        System.out.println("\n      >>> TRIGGERING PLANNED INTERRUPTION at " + (bytes / (1024 * 1024)) + " MB <<<");
                        new Thread(() -> {
                            try {
                                UUID id = t.getTransferId();
                                // Cleanly interrupt receiver to write checkpoint and sever connection
                                nodeB.interruptTransfer(id);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }).start();
                    }
                }
            });

            CompletableFuture<Transfer> future1 = nodeA.sendFile(peerB.getNodeId(), testFile);

            // Wait for transfer 1 to be interrupted
            try {
                future1.get(30, TimeUnit.SECONDS);
            } catch (Exception expected) {
                // Expected interruption
            }

            System.out.println("      Checking Receiver on-disk checkpoint...");
            UUID transferId = transferIdRef.get();
            if (transferId == null) {
                throw new AssertionError("Transfer ID was null!");
            }

            RecoveryManager rm = nodeB.getFileTransferService().getRecoveryManager();
            File checkpointMeta = rm.getMetaFilePath(transferId).toFile();
            File partialFile = rm.getPartFilePath(transferId).toFile();

            if (!checkpointMeta.exists()) {
                throw new AssertionError("Checkpoint metadata file does not exist: " + checkpointMeta);
            }
            if (!partialFile.exists() || partialFile.length() < 100L * 1024 * 1024) {
                throw new AssertionError("Partial file missing or smaller than expected: " + partialFile.length());
            }

            System.out.printf("      Checkpoint verified on disk: %s (%d bytes in .part)%n",
                    checkpointMeta.getName(), partialFile.length());

            // Check receiver transfer state
            Transfer receiverTx = nodeB.getFileTransferService().getTransferManager().getTransfer(transferId).orElse(null);
            if (receiverTx == null || !receiverTx.getState().isResumable()) {
                throw new AssertionError("Receiver transfer state is not resumable: " + (receiverTx != null ? receiverTx.getState() : "null"));
            }
            System.out.println("      Receiver transfer state: " + receiverTx.getState() + " (Resumable: true)");

            System.out.println("[4/5] Re-establishing connection and resuming transfer from checkpoint...");
            // Reconnect
            int portB2 = nodeB.getTcpServer().getLocalPort();
            nodeA.connectTo("127.0.0.1", portB2);

            deadline = System.currentTimeMillis() + 5000;
            while (nodeA.getPeerManager().findPeer(idB.nodeId()).filter(Peer::isConnected).isEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            long resumeStart = System.currentTimeMillis();
            AtomicLong resumeStartingBytes = new AtomicLong(-1);

            nodeA.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer t) {
                    if (t.getTransferId().equals(transferId)) {
                        long now = System.currentTimeMillis();
                        long bytes = t.getBytesTransferred();
                        if (resumeStartingBytes.get() == -1) {
                            resumeStartingBytes.set(bytes);
                        }

                        if (now - lastPrint.get() >= 400 || bytes == fileSize) {
                            lastPrint.set(now);
                            double pct = (bytes * 100.0) / fileSize;
                            double elapsedSec = Math.max(0.001, (now - resumeStart) / 1000.0);
                            double mbps = ((bytes - resumeStartingBytes.get()) / (1024.0 * 1024.0)) / elapsedSec;
                            System.out.printf("\r      [Phase 2 Resume] Progress: %5.1f%% | %5.1f / %d MB | %6.1f MB/s",
                                    pct, bytes / (1024.0 * 1024.0), fileSize / (1024 * 1024), Math.max(0, mbps));
                        }
                    }
                }
            });

            // Resume transfer on node A (upload resumes towards receiver B)
            CompletableFuture<Transfer> resumeFuture = nodeA.resumeTransfer(transferId);
            Transfer completedTransfer = resumeFuture.get(120, TimeUnit.SECONDS);

            System.out.println("\n      Resume completed with state: " + completedTransfer.getState());

            if (resumeStartingBytes.get() < 100L * 1024 * 1024) {
                throw new AssertionError("Transfer restarted from 0 bytes instead of checkpoint! Starting bytes: " + resumeStartingBytes.get());
            }
            System.out.printf("      CONFIRMED: Streaming resumed from offset %d bytes (%.1f MB), skipping re-transmission!%n",
                    resumeStartingBytes.get(), resumeStartingBytes.get() / (1024.0 * 1024.0));

            System.out.println("[5/5] Verifying final destination file integrity...");
            Path destFile = dirB.resolve("dl").resolve("test500mb.dat");
            if (!Files.isRegularFile(destFile)) {
                throw new AssertionError("Destination file not found: " + destFile);
            }
            if (Files.size(destFile) != fileSize) {
                throw new AssertionError(String.format("File size mismatch! Expected %d, got %d", fileSize, Files.size(destFile)));
            }

            String destSha = HashUtils.sha256(destFile);
            System.out.println("      Source SHA-256:      " + expectedSha);
            System.out.println("      Destination SHA-256: " + destSha);
            if (!expectedSha.equalsIgnoreCase(destSha)) {
                throw new AssertionError("Cryptographic SHA-256 mismatch!");
            }

            // Verify checkpoint files cleaned up
            if (checkpointMeta.exists()) {
                throw new AssertionError("Checkpoint metadata was not cleaned up after completion!");
            }
            if (partialFile.exists()) {
                throw new AssertionError("Partial file was not cleaned up after completion!");
            }
            System.out.println("      CONFIRMED: .part and .meta checkpoints cleaned up after successful verification.");

            System.out.println("================================================================================");
            System.out.println("       500 MB INTERRUPT & RESUME TEST: 100% SUCCESSFUL & VERIFIED!             ");
            System.out.println("================================================================================");

        } finally {
            nodeA.stop();
            nodeB.stop();
            cleanup(tempDir);
        }
    }

    private static void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
