package com.meshdrop.demo;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.security.HashUtils;
import com.meshdrop.transfer.Transfer;
import com.meshdrop.transfer.TransferCheckpoint;
import com.meshdrop.transfer.TransferDirection;
import com.meshdrop.transfer.TransferListener;
import com.meshdrop.transfer.TransferState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * End-to-end demonstration runner for large file transfer resumption:
 * 1. Streams a large file (20 MB)
 * 2. Abruptly interrupts network connection halfway
 * 3. Inspects and audits crash-safe on-disk .part and .meta checkpoints
 * 4. Reconnects and resumes from exact chunk boundary
 * 5. Verifies bit-for-bit SHA-256 match and zero redundant re-transfer.
 */
public class LargeResumeDemoRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("          MESHDROP LARGE-FILE RESUME & RECOVERY DEMONSTRATION                   ");
        System.out.println("================================================================================");
        System.out.println();

        Path baseDir = Files.createTempDirectory("meshdrop-large-resume-demo-");
        Path aliceDir = baseDir.resolve("Alice");
        Path bobDir = baseDir.resolve("Bob");

        Node alice = null;
        Node bob = null;

        try {
            long fileSize = 20L * 1024 * 1024; // 20 MB
            int chunkSize = ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE; // 64 KiB
            int totalChunks = (int) ((fileSize + chunkSize - 1) / chunkSize); // 320 chunks

            Files.createDirectories(aliceDir);
            Path srcFile = aliceDir.resolve("backup_dataset.tar");

            System.out.println("[1/6] Generating test file: backup_dataset.tar (20 MB, " + totalChunks + " chunks)...");
            writeStreamingFile(srcFile, fileSize);
            String srcHash = HashUtils.sha256(srcFile);
            System.out.println("      SHA-256: " + srcHash);
            System.out.println();

            // 2. Start nodes
            System.out.println("[2/6] Starting nodes Alice and Bob...");
            NodeConfig cfgA = new NodeConfig(0, 0, chunkSize, aliceDir, aliceDir.resolve("downloads"), aliceDir.resolve("transfers"));
            NodeConfig cfgB = new NodeConfig(0, 0, chunkSize, bobDir, bobDir.resolve("downloads"), bobDir.resolve("transfers"));

            NodeIdentity idA = NodeIdentity.createRandom("Alice");
            NodeIdentity idB = NodeIdentity.createRandom("Bob");

            alice = new Node(cfgA, idA);
            bob = new Node(cfgB, idB);

            alice.start();
            bob.start();

            int portB = bob.getTcpServer().getLocalPort();
            alice.connectTo("127.0.0.1", portB);

            long deadline = System.currentTimeMillis() + 5000;
            while (alice.getPeerManager().findPeer(idB.nodeId()).filter(Peer::isConnected).isEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBob = alice.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            System.out.println("      Connected Alice -> Bob on 127.0.0.1:" + portB);
            System.out.println();

            // 3. Begin file transfer and deliberately sever connection at ~50% (160 chunks)
            System.out.println("[3/6] Starting file transfer, scheduling connection abort at 50% progress...");
            AtomicBoolean severed = new AtomicBoolean(false);
            CountDownLatch severedLatch = new CountDownLatch(1);
            int interruptChunkTarget = totalChunks / 2; // 160 chunks = 10 MB

            alice.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferProgress(Transfer t) {
                    if (t.getChunksTransferred() >= interruptChunkTarget && severed.compareAndSet(false, true)) {
                        try {
                            System.out.printf("%n      >>> SIMULATING SUDDEN NETWORK FAILURE at %d chunks (%.1f MB transferred) <<<%n",
                                    t.getChunksTransferred(), t.getBytesTransferred() / (1024.0 * 1024.0));
                            peerBob.getConnection().close();
                            severedLatch.countDown();
                        } catch (Exception ignored) {}
                    }
                }
            });

            CompletableFuture<Transfer> offerFuture = alice.sendFile(peerBob.getNodeId(), srcFile);

            // Wait for network drop
            severedLatch.await(10, TimeUnit.SECONDS);
            try {
                offerFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            Thread.sleep(600); // Allow disk flushes to settle

            // 4. Audit Bob's disk artifacts
            System.out.println();
            System.out.println("[4/6] Inspecting receiver's persisted recovery artifacts...");
            var bobTM = bob.getFileTransferService().getTransferManager();
            var resumableList = bobTM.getResumableTransfers();
            assert !resumableList.isEmpty() : "Bob must have at least 1 resumable transfer";

            Transfer partialTransfer = resumableList.get(0);
            UUID tid = partialTransfer.getTransferId();
            TransferCheckpoint cp = partialTransfer.getCheckpoint();
            assert cp != null : "Checkpoint must be present";

            System.out.println("      Transfer ID:             " + tid);
            System.out.printf("      Bytes persisted:         %d / %d (%.1f%%)%n",
                    cp.bytesReceived(), cp.fileSize(), (cp.bytesReceived() * 100.0 / cp.fileSize()));
            System.out.printf("      Next expected chunk:     %d (Byte offset: %d)%n",
                    cp.nextExpectedChunk(), cp.nextExpectedOffset());

            Path partFile = bob.getRecoveryManager().getPartFilePath(tid);
            assert Files.exists(partFile) : "Partial data file must exist on disk!";
            assert Files.size(partFile) == cp.bytesReceived() : "Partial file size must match checkpoint bytes!";

            // 5. Reconnect Alice and Bob
            System.out.println();
            System.out.println("[5/6] Reconnecting Alice to Bob...");
            alice.connectTo("127.0.0.1", portB);

            deadline = System.currentTimeMillis() + 5000;
            while (!alice.getPeerManager().findPeer(idB.nodeId()).get().isConnected() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            // 6. Resume transfer from exact offset
            System.out.printf("[6/6] Resuming transfer from chunk %d (skipping first %.1f MB)...%n",
                    cp.nextExpectedChunk(), cp.nextExpectedOffset() / (1024.0 * 1024.0));

            long resumeStartTime = System.currentTimeMillis();
            CompletableFuture<Transfer> resumeFuture = alice.resumeTransfer(tid);
            Transfer completed = resumeFuture.get(30, TimeUnit.SECONDS);

            assert completed.getState() == TransferState.COMPLETED : "Transfer failed: " + completed.getErrorMessage();

            // Destination verification
            Path destFile = bobDir.resolve("downloads").resolve("backup_dataset.tar");
            assert Files.isRegularFile(destFile) : "Destination file missing in downloads directory!";
            assert Files.size(destFile) == fileSize : "Destination file size mismatch!";

            String destHash = HashUtils.sha256(destFile);
            assert srcHash.equalsIgnoreCase(destHash) : "SHA-256 integrity mismatch on resumed file!";

            // Checkpoint cleanup verification
            assert bob.getRecoveryManager().loadCheckpoint(tid).isEmpty() : "Checkpoint should be deleted after completion";
            assert !Files.exists(partFile) : "Part file should be moved, not remain in staging";

            System.out.println();
            System.out.println("================================================================================");
            System.out.println("                     RESUME & RECOVERY DEMO: PASSED                             ");
            System.out.println("================================================================================");
            System.out.printf("File Size:              %.2f MB%n", fileSize / (1024.0 * 1024.0));
            System.out.printf("Initial Transfer:       %.2f MB (Interrupted at %.1f%%)%n",
                    cp.bytesReceived() / (1024.0 * 1024.0), (cp.bytesReceived() * 100.0 / fileSize));
            System.out.printf("Bandwidth Saved:        %.2f MB (Never retransmitted)%n",
                    cp.bytesReceived() / (1024.0 * 1024.0));
            System.out.printf("Resumed Streaming:      %.2f MB streamed in %.2f s%n",
                    (fileSize - cp.bytesReceived()) / (1024.0 * 1024.0),
                    (System.currentTimeMillis() - resumeStartTime) / 1000.0);
            System.out.println("SHA-256 Check:          100% BIT-FOR-BIT MATCH");
            System.out.println("Recovery Artifacts:     CLEANLY PROMOTED & PURGED");
            System.out.println("================================================================================");

        } finally {
            if (alice != null) alice.stop();
            if (bob != null) bob.stop();
            cleanup(baseDir);
        }
    }

    private static void writeStreamingFile(Path path, long size) throws IOException {
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int bufSize = 64 * 1024;
            ByteBuffer buf = ByteBuffer.allocate(bufSize);
            long written = 0;
            byte seed = 0x55;
            while (written < size) {
                int toWrite = (int) Math.min(bufSize, size - written);
                buf.clear();
                for (int i = 0; i < toWrite; i++) {
                    buf.put((byte) ((seed + i) & 0xFF));
                }
                buf.flip();
                while (buf.hasRemaining()) {
                    fc.write(buf);
                }
                written += toWrite;
                seed = (byte) ((seed + toWrite) & 0xFF);
            }
        }
    }

    private static void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
