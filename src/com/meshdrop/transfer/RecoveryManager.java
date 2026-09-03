package com.meshdrop.transfer;

import com.meshdrop.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages crash-safe persistence, atomic updates, consistency verification,
 * and startup discovery of transfer checkpoints.
 */
public class RecoveryManager {

    public static final String PART_PREFIX = ".transfer-";
    public static final String PART_SUFFIX = ".part";
    public static final String META_SUFFIX = ".meta";
    public static final String TMP_SUFFIX = ".tmp";

    private final Path recoveryDir;

    public RecoveryManager(Path recoveryDir) {
        this.recoveryDir = Objects.requireNonNull(recoveryDir, "recoveryDir must not be null");
        try {
            Files.createDirectories(recoveryDir);
        } catch (IOException e) {
            Logger.severe("[RECOVERY] Failed to create recovery directory: " + recoveryDir, e);
        }
    }

    public Path getRecoveryDir() {
        return recoveryDir;
    }

    public Path getPartFilePath(UUID transferId) {
        return recoveryDir.resolve(PART_PREFIX + transferId + PART_SUFFIX);
    }

    public Path getMetaFilePath(UUID transferId) {
        return recoveryDir.resolve(PART_PREFIX + transferId + META_SUFFIX);
    }

    private Path getTempMetaFilePath(UUID transferId) {
        return recoveryDir.resolve(PART_PREFIX + transferId + META_SUFFIX + TMP_SUFFIX);
    }

    /**
     * Atomically saves a checkpoint to disk using a temporary file and replace.
     */
    public synchronized void saveCheckpoint(TransferCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");

        Path metaPath = getMetaFilePath(checkpoint.transferId());
        Path tmpPath = getTempMetaFilePath(checkpoint.transferId());

        String serialized = checkpoint.serialize();
        byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);

        // 1. Write to tmp file with sync
        Files.write(tmpPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        // 2. Atomic move to target
        try {
            Files.move(tmpPath, metaPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fallback for filesystems without atomic move support
            Files.move(tmpPath, metaPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Loads a checkpoint for a specific transfer ID.
     */
    public Optional<TransferCheckpoint> loadCheckpoint(UUID transferId) {
        if (transferId == null) return Optional.empty();
        Path metaPath = getMetaFilePath(transferId);
        if (!Files.isRegularFile(metaPath)) {
            return Optional.empty();
        }

        try {
            String content = Files.readString(metaPath, StandardCharsets.UTF_8);
            TransferCheckpoint cp = TransferCheckpoint.deserialize(content);
            return Optional.of(cp);
        } catch (Exception e) {
            Logger.warn("[RECOVERY] Corrupted checkpoint for transfer " + transferId + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Verifies that the partial file exists and strictly matches the checkpoint byte counts.
     */
    public boolean verifyConsistency(TransferCheckpoint checkpoint) {
        if (checkpoint == null) return false;
        Path partPath = getPartFilePath(checkpoint.transferId());
        if (!Files.isRegularFile(partPath)) {
            Logger.warn("[RECOVERY] Consistency check failed: part file missing for " + checkpoint.transferId());
            return false;
        }

        try {
            long actualSize = Files.size(partPath);
            if (actualSize != checkpoint.bytesReceived()) {
                Logger.warn("[RECOVERY] Consistency check failed for " + checkpoint.transferId() +
                        ": actual part size (" + actualSize + ") != checkpoint bytesReceived (" + checkpoint.bytesReceived() + ")");
                return false;
            }
            if (checkpoint.bytesReceived() > checkpoint.fileSize()) {
                Logger.warn("[RECOVERY] Consistency check failed for " + checkpoint.transferId() +
                        ": bytesReceived (" + checkpoint.bytesReceived() + ") > fileSize (" + checkpoint.fileSize() + ")");
                return false;
            }
            if (checkpoint.nextExpectedOffset() != checkpoint.bytesReceived()) {
                Logger.warn("[RECOVERY] Consistency check failed for " + checkpoint.transferId() +
                        ": nextExpectedOffset (" + checkpoint.nextExpectedOffset() + ") != bytesReceived (" + checkpoint.bytesReceived() + ")");
                return false;
            }
            return true;
        } catch (IOException e) {
            Logger.warn("[RECOVERY] Error checking part file size for " + checkpoint.transferId() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Scans recovery directory at startup and returns all consistent, recoverable checkpoints.
     */
    public List<TransferCheckpoint> scanRecoverableCheckpoints() {
        List<TransferCheckpoint> recoverable = new ArrayList<>();
        if (!Files.isDirectory(recoveryDir)) {
            return recoverable;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(recoveryDir, p -> p.getFileName().toString().endsWith(META_SUFFIX))) {
            for (Path metaPath : stream) {
                try {
                    String content = Files.readString(metaPath, StandardCharsets.UTF_8);
                    TransferCheckpoint cp = TransferCheckpoint.deserialize(content);
                    if (verifyConsistency(cp)) {
                        recoverable.add(cp);
                        Logger.info("[RECOVERY] Discovered recoverable transfer: " + cp.fileName() +
                                " (ID: " + cp.transferId() + ", Progress: " +
                                String.format("%.1f%%", cp.fileSize() > 0 ? (cp.bytesReceived() * 100.0 / cp.fileSize()) : 100.0) + ")");
                    } else {
                        Logger.warn("[RECOVERY] Ignoring inconsistent checkpoint: " + metaPath.getFileName());
                    }
                } catch (Exception e) {
                    Logger.warn("[RECOVERY] Skipping unparseable metadata: " + metaPath.getFileName() + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            Logger.warn("[RECOVERY] Error scanning recovery directory: " + e.getMessage());
        }

        return recoverable;
    }

    /**
     * Deletes checkpoint metadata.
     */
    public synchronized void deleteCheckpoint(UUID transferId) {
        if (transferId == null) return;
        try {
            Files.deleteIfExists(getMetaFilePath(transferId));
            Files.deleteIfExists(getTempMetaFilePath(transferId));
        } catch (IOException ignored) {}
    }

    /**
     * Deletes partial data file.
     */
    public synchronized void deletePartFile(UUID transferId) {
        if (transferId == null) return;
        try {
            Files.deleteIfExists(getPartFilePath(transferId));
        } catch (IOException ignored) {}
    }

    /**
     * Deletes all artifacts (.part, .meta, .tmp) associated with a transfer.
     */
    public synchronized void deleteTransferArtifacts(UUID transferId) {
        deleteCheckpoint(transferId);
        deletePartFile(transferId);
    }
}
