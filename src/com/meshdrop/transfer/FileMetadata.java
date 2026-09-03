package com.meshdrop.transfer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Domain model representing metadata for an offered or active file transfer.
 *
 * Enforces strict security validation:
 *   - Path traversal prevention (no separators, no '..', strictly basename).
 *   - Maximum filename length (255 characters).
 *   - Non-negative file sizes.
 *   - 64-character SHA-256 hex string validation.
 */
public record FileMetadata(
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        String fileName,
        long fileSize,
        long createdAt,
        String sha256
) {

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    public FileMetadata {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(senderId, "senderId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");

        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be empty or blank");
        }

        if (fileName.length() > 255) {
            throw new IllegalArgumentException("fileName exceeds maximum length of 255 characters");
        }

        // Path traversal and absolute path protection
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || fileName.contains(":")) {
            throw new IllegalArgumentException("fileName must not contain path traversal characters or drive specifiers: " + fileName);
        }

        Path path = Path.of(fileName);
        if (path.isAbsolute() || path.getNameCount() != 1) {
            throw new IllegalArgumentException("fileName must be a simple file name without path components: " + fileName);
        }

        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must not be negative: " + fileSize);
        }

        if (createdAt <= 0) {
            throw new IllegalArgumentException("createdAt must be a positive timestamp: " + createdAt);
        }

        if (!SHA256_HEX_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256 format (expected 64 hex chars): " + sha256);
        }
    }

    /**
     * Factory method creating a new FileMetadata with a fresh random transfer UUID and current timestamp.
     */
    public static FileMetadata create(UUID senderId, UUID recipientId, String fileName, long fileSize, String sha256) {
        String safeName = sanitizeFileName(fileName);
        return new FileMetadata(UUID.randomUUID(), senderId, recipientId, safeName, fileSize, System.currentTimeMillis(), sha256);
    }

    /**
     * Sanitizes a file path or raw name to extract only its safe base filename.
     */
    public static String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Raw file name must not be empty");
        }
        Path path = Path.of(rawName.trim());
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            throw new IllegalArgumentException("Cannot determine filename from: " + rawName);
        }
        String name = fileNamePath.toString();
        // Strip any residual separator or path traversal chars
        name = name.replace("..", "").replace("/", "").replace("\\", "").replace(":", "");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Sanitized filename is empty for: " + rawName);
        }
        return name;
    }
}
