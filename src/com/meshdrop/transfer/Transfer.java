package com.meshdrop.transfer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages runtime state, progress tracking, and metrics for an active file transfer.
 */
public class Transfer {

    private final UUID transferId;
    private final FileMetadata metadata;
    private final TransferMetadata legacyMetadata;
    private final TransferDirection direction;
    private volatile TransferState state;
    private volatile TransferStatus status; // Backwards compatibility with Phase 0
    private volatile long bytesTransferred;
    private volatile int chunksTransferred;
    private final long totalBytes;
    private final long startTimeMs;
    private volatile long completedTimeMs;
    private volatile String errorMessage;
    private volatile Path localPath;
    private volatile TransferCheckpoint checkpoint;

    public Transfer(FileMetadata metadata, TransferDirection direction, Path localPath, TransferState initialState) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.transferId = metadata.transferId();
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.localPath = localPath;
        this.totalBytes = metadata.fileSize();
        this.state = Objects.requireNonNull(initialState, "initialState must not be null");
        this.status = TransferStatus.QUEUED;
        this.bytesTransferred = 0;
        this.chunksTransferred = 0;
        this.startTimeMs = System.currentTimeMillis();
        this.legacyMetadata = null;
    }

    public Transfer(FileMetadata metadata, TransferDirection direction, Path localPath) {
        this(metadata, direction, localPath, direction == TransferDirection.UPLOAD ? TransferState.OFFERING : TransferState.WAITING_FOR_ACCEPT);
    }

    /**
     * Backwards-compatible constructor for Phase 0 legacy tests.
     */
    public Transfer(TransferMetadata legacyMetadata) {
        this.legacyMetadata = Objects.requireNonNull(legacyMetadata, "legacyMetadata must not be null");
        this.transferId = legacyMetadata.transferId();
        this.direction = TransferDirection.UPLOAD;
        this.totalBytes = legacyMetadata.fileSize();
        this.state = TransferState.OFFERING;
        this.status = TransferStatus.QUEUED;
        this.bytesTransferred = 0;
        this.chunksTransferred = 0;
        this.startTimeMs = System.currentTimeMillis();
        this.metadata = null;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public FileMetadata getFileMetadata() {
        return metadata;
    }

    public TransferMetadata getMetadata() {
        return legacyMetadata != null ? legacyMetadata :
                new TransferMetadata(
                        metadata.transferId(),
                        metadata.fileName(),
                        metadata.fileSize(),
                        64 * 1024,
                        (int) Math.ceil((double) metadata.fileSize() / (64 * 1024)),
                        metadata.sha256(),
                        metadata.senderId(),
                        metadata.recipientId()
                );
    }

    public TransferDirection getDirection() {
        return direction;
    }

    public TransferState getState() {
        return state;
    }

    /**
     * Attempts to transition the transfer to a new state.
     *
     * @param nextState target state
     * @throws IllegalStateException if the state machine transition is invalid
     */
    public synchronized void transitionTo(TransferState nextState) {
        if (!state.canTransitionTo(nextState)) {
            throw new IllegalStateException("Invalid transfer state transition from " + state + " to " + nextState);
        }
        this.state = nextState;

        // Synchronize legacy status
        switch (nextState) {
            case OFFERING, WAITING_FOR_ACCEPT -> this.status = TransferStatus.CONNECTING;
            case ACCEPTED, TRANSFERRING -> this.status = TransferStatus.TRANSFERRING;
            case VERIFYING -> this.status = TransferStatus.VERIFYING;
            case COMPLETED -> {
                this.status = TransferStatus.COMPLETED;
                this.completedTimeMs = System.currentTimeMillis();
            }
            case FAILED, TIMED_OUT -> {
                this.status = TransferStatus.FAILED;
                this.completedTimeMs = System.currentTimeMillis();
            }
            case REJECTED, CANCELLED -> {
                this.status = TransferStatus.CANCELLED;
                this.completedTimeMs = System.currentTimeMillis();
            }
            case INTERRUPTED, RESUMABLE, RESUMING -> this.status = TransferStatus.PAUSED;
        }
    }

    /**
     * Returns a concise human-readable transfer identifier (e.g. "TX-8F32A1").
     */
    public String getShortId() {
        if (transferId == null) return "TX-000000";
        String raw = transferId.toString().replace("-", "").toUpperCase();
        return "TX-" + raw.substring(0, Math.min(6, raw.length()));
    }

    /**
     * Matches either the full UUID or the human-readable short ID.
     */
    public boolean matchesIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return false;
        String trimmed = identifier.trim().toUpperCase();
        if (trimmed.equalsIgnoreCase(transferId.toString())) return true;
        if (trimmed.equalsIgnoreCase(getShortId())) return true;
        if (trimmed.startsWith("TX-") && trimmed.substring(3).equalsIgnoreCase(getShortId().substring(3))) return true;
        return transferId.toString().toUpperCase().startsWith(trimmed);
    }

    public boolean isCancelled() {
        return state == TransferState.CANCELLED;
    }

    public synchronized void cancel(String reason) {
        this.errorMessage = reason != null ? reason : "Cancelled";
        if (state.canTransitionTo(TransferState.CANCELLED)) {
            transitionTo(TransferState.CANCELLED);
        }
    }

    public static Transfer fromCheckpoint(TransferCheckpoint checkpoint, Path localPartPath) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        FileMetadata meta = new FileMetadata(
                checkpoint.transferId(),
                checkpoint.senderId(),
                checkpoint.recipientId(),
                checkpoint.fileName(),
                checkpoint.fileSize(),
                checkpoint.lastUpdated(),
                checkpoint.expectedSha256()
        );
        Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, localPartPath, TransferState.RESUMABLE);
        transfer.setBytesTransferred(checkpoint.bytesReceived());
        transfer.setChunksTransferred(checkpoint.nextExpectedChunk());
        transfer.setCheckpoint(checkpoint);
        return transfer;
    }

    public TransferCheckpoint getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(TransferCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public synchronized void addBytesTransferred(long bytes) {
        this.bytesTransferred += bytes;
    }

    public synchronized void setBytesTransferred(long bytes) {
        this.bytesTransferred = bytes;
    }

    public int getChunksTransferred() {
        return chunksTransferred;
    }

    public synchronized void setChunksTransferred(int chunks) {
        this.chunksTransferred = chunks;
    }

    public synchronized void incrementChunksTransferred() {
        this.chunksTransferred++;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public double getProgressPercentage() {
        if (totalBytes <= 0) return 100.0;
        double pct = (bytesTransferred * 100.0) / totalBytes;
        return Math.min(100.0, Math.max(0.0, pct));
    }

    /**
     * Calculates transfer speed in bytes per second.
     */
    public double getTransferSpeedBps() {
        long elapsedMs = (completedTimeMs > 0 ? completedTimeMs : System.currentTimeMillis()) - startTimeMs;
        if (elapsedMs <= 0 || bytesTransferred <= 0) {
            return 0.0;
        }
        return (bytesTransferred * 1000.0) / elapsedMs;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getCompletedTimeMs() {
        return completedTimeMs;
    }

    public long getElapsedDurationMs() {
        long end = completedTimeMs > 0 ? completedTimeMs : System.currentTimeMillis();
        return Math.max(0, end - startTimeMs);
    }

    /**
     * Calculates estimated remaining seconds based on current transfer speed.
     * Returns 0 if transfer is completed or remaining bytes is 0.
     * Returns -1 if speed is unknown or 0.
     */
    public long getEstimatedRemainingSeconds() {
        if (state == TransferState.COMPLETED || bytesTransferred >= totalBytes) {
            return 0;
        }
        double speed = getTransferSpeedBps();
        if (speed <= 0) {
            return -1;
        }
        long remainingBytes = Math.max(0, totalBytes - bytesTransferred);
        return (long) Math.ceil(remainingBytes / speed);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Path getLocalPath() {
        return localPath;
    }

    public void setLocalPath(Path localPath) {
        this.localPath = localPath;
    }
}
