package com.meshdrop.storage;

import com.meshdrop.transfer.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Manages local filesystem storage layout, directory isolation, and path traversal protection.
 *
 * Layout:
 *   <storageDir>/
 *     ├── identity/    (Persistent cryptographic keypairs and node credentials)
 *     ├── trust/       (TrustStore peer authorization records)
 *     ├── downloads/   (Verified, completed incoming file downloads)
 *     ├── transfers/   (In-flight chunk streaming data and .meta checkpoints)
 *     └── logs/        (Runtime diagnostic logs)
 */
public class StorageManager {
    private final Path storageDir;
    private final Path identityDir;
    private final Path trustDir;
    private final Path downloadsDir;
    private final Path tempDir;
    private final Path logsDir;

    public StorageManager(Path storageDir, Path downloadsDir, Path tempDir) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir must not be null");
        this.identityDir = storageDir.resolve("identity");
        this.trustDir = storageDir.resolve("trust");
        this.downloadsDir = downloadsDir != null ? downloadsDir : storageDir.resolve("downloads");
        this.tempDir = tempDir != null ? tempDir : storageDir.resolve("transfers");
        this.logsDir = storageDir.resolve("logs");
    }

    public StorageManager(Path storageDir) {
        this(storageDir, storageDir.resolve("downloads"), storageDir.resolve("transfers"));
    }

    /**
     * Initializes and verifies all required application storage directories.
     */
    public void init() throws IOException {
        Files.createDirectories(storageDir);
        Files.createDirectories(identityDir);
        Files.createDirectories(trustDir);
        Files.createDirectories(downloadsDir);
        Files.createDirectories(tempDir);
        Files.createDirectories(logsDir);
    }

    /**
     * Resolves a sanitized download destination path and verifies it strictly resides
     * within the configured downloads root directory.
     *
     * @param rawFilename incoming filename offered by remote peer
     * @return safe, fully qualified destination path
     * @throws SecurityException if path traversal or illegal characters are detected
     */
    public Path resolveSafeDownloadPath(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw new SecurityException("Filename must not be empty or blank");
        }

        String safeName = FileMetadata.sanitizeFileName(rawFilename);
        Path target = downloadsDir.resolve(safeName).normalize().toAbsolutePath();
        Path root = downloadsDir.normalize().toAbsolutePath();

        if (!target.startsWith(root) || target.equals(root)) {
            throw new SecurityException("Path traversal attempt detected outside download root: " + rawFilename);
        }

        return target;
    }

    public Path getStorageDir() {
        return storageDir;
    }

    public Path getIdentityDir() {
        return identityDir;
    }

    public Path getTrustDir() {
        return trustDir;
    }

    public Path getDownloadsDir() {
        return downloadsDir;
    }

    public Path getTempDir() {
        return tempDir;
    }

    public Path getLogsDir() {
        return logsDir;
    }
}
