package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolConstants;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Domain model representing metadata for an offered or active file transfer.
 *
 * Enforces strict security validation:
 *   - Path traversal prevention (no separators, no '..', strictly safe basename).
 *   - Windows reserved device name protection (CON, PRN, AUX, NUL, COM1-9, LPT1-9).
 *   - Forbidden character stripping (< > : " / \ | ? * and ASCII control characters).
 *   - Maximum filename length (255 characters).
 *   - Bounds validation on file sizes (0 to 100 GiB).
 *   - Cryptographic 64-character SHA-256 hex string validation.
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

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

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

        if (fileSize > ProtocolConstants.MAX_ACCEPTED_FILE_SIZE) {
            throw new IllegalArgumentException("fileSize exceeds maximum allowable limit of " +
                    ProtocolConstants.MAX_ACCEPTED_FILE_SIZE + " bytes: " + fileSize);
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
     *
     * Protections:
     *   - Path traversal components ('..', '/', '\', drive letters) stripped.
     *   - Windows reserved device names (CON, NUL, AUX, PRN, COM1-9, LPT1-9) prefixed with 'safe_'.
     *   - Prohibited filesystem characters (< > : " | ? *) replaced with '_'.
     *   - ASCII control characters (0x00 to 0x1F) stripped.
     *   - Trailing dots and spaces trimmed.
     *   - Length capped at 255 characters.
     */
    public static String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Raw file name must not be empty");
        }

        String name = rawName.trim();

        // 1. If it contains path separators, extract only the last segment
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < name.length() - 1) {
            name = name.substring(lastSlash + 1);
        }

        // 2. Remove drive letters if present (e.g. C:filename)
        int colon = name.lastIndexOf(':');
        if (colon >= 0 && colon < name.length() - 1) {
            name = name.substring(colon + 1);
        }

        // 3. Remove any residual traversal markers and separators
        name = name.replace("..", "").replace("/", "").replace("\\", "").replace(":", "");

        // 4. Replace illegal filesystem characters with underscores
        StringBuilder clean = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c < 32 || c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                clean.append('_');
            } else {
                clean.append(c);
            }
        }
        name = clean.toString();

        // 5. Trim trailing dots and spaces (problematic on Windows filesystems)
        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }

        if (name.isBlank()) {
            name = "downloaded_file";
        }

        // 6. Check for Windows reserved device names (e.g. CON, NUL, AUX, CON.txt)
        String baseName = name;
        int dot = name.indexOf('.');
        if (dot >= 0) {
            baseName = name.substring(0, dot);
        }
        if (WINDOWS_RESERVED_NAMES.contains(baseName.toUpperCase())) {
            name = "safe_" + name;
        }

        // 7. Enforce maximum filename length of 255 characters
        if (name.length() > 255) {
            int extDot = name.lastIndexOf('.');
            if (extDot > 0 && name.length() - extDot < 20) {
                String ext = name.substring(extDot);
                name = name.substring(0, 255 - ext.length()) + ext;
            } else {
                name = name.substring(0, 255);
            }
        }

        return name;
    }
}
