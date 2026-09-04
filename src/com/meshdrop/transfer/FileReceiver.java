package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.storage.StorageManager;
import com.meshdrop.util.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Handles sequential chunk reception, temporary file staging, atomic checkpoint persistence,
 * SHA-256 pre-hashing and verification, and safe collision-free promotion.
 *
 * Fully supports resuming interrupted transfers from verified on-disk checkpoints.
 */
public class FileReceiver implements AutoCloseable {

    private final FileMetadata metadata;
    private final Path downloadsDir;
    private final Path tempDir;
    private final Path tempFilePath;
    private final Transfer transfer;
    private final RecoveryManager recoveryManager;
    private final TransferListener listener;
    private final MessageDigest digest;

    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int CHECKPOINT_CHUNK_INTERVAL = 32; // Checkpoint every ~2MB
    private static final long CHECKPOINT_MS_INTERVAL = 1000;  // Or at least every 1s

    private OutputStream out;
    private TransferCheckpoint checkpoint;
    private long expectedOffset = 0;
    private int expectedChunkIndex = 0;
    private long lastCheckpointTime = System.currentTimeMillis();
    private boolean completed = false;
    private boolean closed = false;

    /**
     * Creates a new receiver for a fresh transfer.
     */
    public FileReceiver(
            FileMetadata metadata,
            Path downloadsDir,
            Path tempDir,
            Transfer transfer,
            RecoveryManager recoveryManager,
            TransferListener listener
    ) throws IOException {
        this(metadata, downloadsDir, tempDir, transfer, recoveryManager, null, listener);
    }

    public FileReceiver(FileMetadata metadata, Path downloadsDir, Path tempDir, Transfer transfer, TransferListener listener) throws IOException {
        this(metadata, downloadsDir, tempDir, transfer, new RecoveryManager(tempDir), null, listener);
    }

    /**
     * Main constructor supporting both fresh transfers (resumeCheckpoint == null)
     * and resuming an interrupted transfer (resumeCheckpoint != null).
     */
    public FileReceiver(
            FileMetadata metadata,
            Path downloadsDir,
            Path tempDir,
            Transfer transfer,
            RecoveryManager recoveryManager,
            TransferCheckpoint resumeCheckpoint,
            TransferListener listener
    ) throws IOException {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.downloadsDir = Objects.requireNonNull(downloadsDir, "downloadsDir must not be null");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir must not be null");
        this.transfer = Objects.requireNonNull(transfer, "transfer must not be null");
        this.recoveryManager = recoveryManager != null ? recoveryManager : new RecoveryManager(tempDir);
        this.listener = listener;

        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }

        Files.createDirectories(downloadsDir);
        Files.createDirectories(tempDir);

        this.tempFilePath = this.recoveryManager.getPartFilePath(metadata.transferId());

        if (resumeCheckpoint != null) {
            // Resume existing partial transfer
            if (!Files.isRegularFile(tempFilePath)) {
                throw new IOException("Cannot resume: staging part file not found: " + tempFilePath);
            }

            long actualSize = Files.size(tempFilePath);
            if (actualSize != resumeCheckpoint.bytesReceived()) {
                throw new IOException("Cannot resume: part file size (" + actualSize +
                        ") does not match checkpoint bytes (" + resumeCheckpoint.bytesReceived() + ")");
            }

            // Pre-hash existing bytes into digest so final digest verifies entire file
            try (InputStream in = Files.newInputStream(tempFilePath)) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }

            this.expectedOffset = resumeCheckpoint.nextExpectedOffset();
            this.expectedChunkIndex = resumeCheckpoint.nextExpectedChunk();
            this.checkpoint = resumeCheckpoint;

            // Open in APPEND mode
            this.out = new BufferedOutputStream(Files.newOutputStream(tempFilePath, StandardOpenOption.WRITE, StandardOpenOption.APPEND), BUFFER_SIZE);

            transfer.setLocalPath(tempFilePath);
            transfer.setBytesTransferred(expectedOffset);
            transfer.setChunksTransferred(expectedChunkIndex);
            transfer.setCheckpoint(checkpoint);

            Logger.info("[TRANSFER] Resuming receiver for " + metadata.fileName() +
                    " at chunk " + expectedChunkIndex + " (offset " + expectedOffset + " bytes)");
        } else {
            // Fresh transfer initialization
            this.out = new BufferedOutputStream(Files.newOutputStream(tempFilePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), BUFFER_SIZE);
            this.checkpoint = TransferCheckpoint.initial(metadata, ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE);
            this.recoveryManager.saveCheckpoint(checkpoint);

            transfer.setLocalPath(tempFilePath);
            transfer.setCheckpoint(checkpoint);
        }
    }

    /**
     * Backwards-compatible constructor for Phase 0 legacy interface.
     */
    public FileReceiver(StorageManager storageManager) {
        this.metadata = null;
        this.downloadsDir = storageManager.getDownloadsDir();
        this.tempDir = storageManager.getTempDir();
        this.tempFilePath = null;
        this.transfer = null;
        this.recoveryManager = null;
        this.listener = null;
        this.digest = null;
    }

    /**
     * Backwards-compatible handleChunk for Phase 0 legacy interface.
     */
    public void handleChunk(Chunk chunk, byte[] data) throws IOException {
        if (out != null && data != null) {
            out.write(data);
        }
    }

    public TransferCheckpoint getCheckpoint() {
        return checkpoint;
    }

    public long getExpectedOffset() {
        return expectedOffset;
    }

    public int getExpectedChunkIndex() {
        return expectedChunkIndex;
    }

    public Path getTempFilePath() {
        return tempFilePath;
    }

    /**
     * Appends a chunk to the temporary file while updating SHA-256 digest, checkpoint, and progress.
     */
    public synchronized void receiveChunk(FileChunk chunk) throws IOException {
        if (closed) {
            throw new IOException("FileReceiver is already closed");
        }
        if (completed) {
            throw new IOException("Transfer is already completed");
        }

        if (!chunk.transferId().equals(metadata.transferId())) {
            throw new IOException("Chunk transferId mismatch: expected " + metadata.transferId() + ", got " + chunk.transferId());
        }

        // Duplicate chunk handling (idempotent ignore)
        if (chunk.chunkIndex() < expectedChunkIndex) {
            Logger.fine("[TRANSFER] Ignoring duplicate chunk " + chunk.chunkIndex() + " (expected " + expectedChunkIndex + ")");
            return;
        }

        if (chunk.chunkIndex() != expectedChunkIndex) {
            throw new IOException("Out-of-order chunk index: expected " + expectedChunkIndex + ", got " + chunk.chunkIndex());
        }

        if (chunk.offset() != expectedOffset) {
            throw new IOException("Unexpected chunk offset: expected " + expectedOffset + ", got " + chunk.offset());
        }

        out.write(chunk.data());
        digest.update(chunk.data());

        expectedOffset += chunk.length();
        expectedChunkIndex++;

        // Persist updated checkpoint periodically (on chunk 1, every 32 chunks, or every 1000ms)
        boolean shouldCheckpoint = (expectedChunkIndex == 1) ||
                (expectedChunkIndex % CHECKPOINT_CHUNK_INTERVAL == 0) ||
                (System.currentTimeMillis() - lastCheckpointTime >= CHECKPOINT_MS_INTERVAL);

        if (recoveryManager != null && shouldCheckpoint) {
            out.flush();
            checkpoint = checkpoint.withProgress(expectedChunkIndex, expectedOffset, expectedOffset);
            transfer.setCheckpoint(checkpoint);
            recoveryManager.saveCheckpoint(checkpoint);
            lastCheckpointTime = System.currentTimeMillis();
        }

        if (transfer != null && (transfer.getState() == TransferState.WAITING_FOR_ACCEPT ||
                transfer.getState() == TransferState.ACCEPTED ||
                transfer.getState() == TransferState.RESUMING ||
                transfer.getState() == TransferState.RESUMABLE ||
                transfer.getState() == TransferState.INTERRUPTED)) {
            transfer.transitionTo(TransferState.TRANSFERRING);
        }

        transfer.addBytesTransferred(chunk.length());
        transfer.incrementChunksTransferred();

        if (listener != null) {
            listener.onTransferProgress(transfer);
        }
    }

    /**
     * Verifies total chunks, total bytes, and SHA-256 integrity, then moves the temporary
     * file to its final collision-safe destination.
     */
    public synchronized Path completeTransfer(int totalChunks, long totalBytes, String expectedSha256) throws IOException {
        if (closed) {
            throw new IOException("FileReceiver is already closed");
        }

        transfer.transitionTo(TransferState.VERIFYING);

        // 1. Close output stream to flush file
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }

        // 2. Validate chunk counts and byte sizes
        if (expectedChunkIndex != totalChunks) {
            abort("Received chunk count (" + expectedChunkIndex + ") does not match expected total (" + totalChunks + ")");
            throw new IOException("Chunk count mismatch: expected " + totalChunks + ", got " + expectedChunkIndex);
        }

        if (expectedOffset != totalBytes || totalBytes != metadata.fileSize()) {
            abort("Received bytes (" + expectedOffset + ") does not match expected file size (" + metadata.fileSize() + ")");
            throw new IOException("File size mismatch: expected " + metadata.fileSize() + ", received " + expectedOffset);
        }

        // 3. Finalize and verify SHA-256
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (!actualSha256.equalsIgnoreCase(expectedSha256) || !actualSha256.equalsIgnoreCase(metadata.sha256())) {
            abort("SHA-256 integrity check failed! Expected " + metadata.sha256() + ", computed " + actualSha256);
            throw new IOException("SHA-256 mismatch: expected " + metadata.sha256() + ", computed " + actualSha256);
        }

        // 4. Promote temporary file to collision-safe final destination
        Path finalPath = resolveCollisionSafePath(downloadsDir, metadata.fileName());
        try {
            Files.move(tempFilePath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fallback for cross-filesystem move
            Files.move(tempFilePath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Clean up recovery checkpoint on successful completion
        if (recoveryManager != null) {
            recoveryManager.deleteCheckpoint(metadata.transferId());
        }

        completed = true;
        transfer.setLocalPath(finalPath);
        transfer.transitionTo(TransferState.COMPLETED);

        if (listener != null) {
            listener.onTransferCompleted(transfer);
        }

        Logger.info("[TRANSFER] File verified and saved to: " + finalPath.toAbsolutePath());
        return finalPath;
    }

    /**
     * Resolves a destination path in the download directory without overwriting existing files.
     */
    public static Path resolveCollisionSafePath(Path directory, String fileName) {
        Path target = directory.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }

        String baseName;
        String extension;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        } else {
            baseName = fileName;
            extension = "";
        }

        int count = 1;
        while (Files.exists(target)) {
            String newName = baseName + " (" + count + ")" + extension;
            target = directory.resolve(newName);
            count++;
        }
        return target;
    }

    /**
     * Pauses receiver activity upon connection interruption, preserving partial file and metadata.
     */
    public synchronized void pauseForInterruption(String reason) {
        if (closed || completed) return;
        closed = true;

        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (IOException ignored) {}
            out = null;
        }

        if (recoveryManager != null && checkpoint != null) {
            try {
                checkpoint = checkpoint.withProgress(expectedChunkIndex, expectedOffset, expectedOffset);
                if (transfer != null) {
                    transfer.setCheckpoint(checkpoint);
                }
                recoveryManager.saveCheckpoint(checkpoint);
            } catch (IOException ignored) {}
        }

        if (transfer != null) {
            transfer.setErrorMessage(reason);
            if (transfer.getState().canTransitionTo(TransferState.INTERRUPTED)) {
                transfer.transitionTo(TransferState.INTERRUPTED);
                if (transfer.getState().canTransitionTo(TransferState.RESUMABLE)) {
                    transfer.transitionTo(TransferState.RESUMABLE);
                }
            }
            if (listener != null) {
                listener.onTransferInterrupted(transfer);
            }
        }
    }

    /**
     * Permanently aborts the transfer, deletes all temporary artifacts, and marks FAILED.
     */
    public synchronized void abort(String reason) {
        if (closed) return;
        closed = true;

        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {}
            out = null;
        }

        if (recoveryManager != null) {
            recoveryManager.deleteTransferArtifacts(metadata.transferId());
        } else if (tempFilePath != null) {
            try {
                Files.deleteIfExists(tempFilePath);
            } catch (IOException ignored) {}
        }

        if (transfer != null) {
            transfer.setErrorMessage(reason);
            if (!transfer.getState().isTerminal()) {
                transfer.transitionTo(TransferState.FAILED);
            }
            if (listener != null) {
                if (transfer.getState() == TransferState.CANCELLED) {
                    listener.onTransferCancelled(transfer);
                } else {
                    listener.onTransferFailed(transfer, reason);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            if (!completed) {
                pauseForInterruption("Receiver connection closed before completion");
            } else {
                closed = true;
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {}
                    out = null;
                }
            }
        }
    }
}
