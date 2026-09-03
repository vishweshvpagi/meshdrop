package com.meshdrop.transfer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable checkpoint representing verified receiver state for an in-flight or interrupted file transfer.
 */
public record TransferCheckpoint(
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        String fileName,
        long fileSize,
        int chunkSize,
        int nextExpectedChunk,
        long nextExpectedOffset,
        long bytesReceived,
        String expectedSha256,
        long lastUpdated
) {
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    public TransferCheckpoint {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(senderId, "senderId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(expectedSha256, "expectedSha256 must not be null");

        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || fileName.contains(":")) {
            throw new IllegalArgumentException("fileName must not contain path traversal characters: " + fileName);
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must not be negative: " + fileSize);
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        if (nextExpectedChunk < 0) {
            throw new IllegalArgumentException("nextExpectedChunk must not be negative: " + nextExpectedChunk);
        }
        if (nextExpectedOffset < 0 || nextExpectedOffset > fileSize) {
            throw new IllegalArgumentException("nextExpectedOffset out of range [0, " + fileSize + "]: " + nextExpectedOffset);
        }
        if (bytesReceived < 0 || bytesReceived > fileSize) {
            throw new IllegalArgumentException("bytesReceived out of range [0, " + fileSize + "]: " + bytesReceived);
        }
        if (bytesReceived != nextExpectedOffset) {
            throw new IllegalArgumentException("bytesReceived (" + bytesReceived + ") must equal nextExpectedOffset (" + nextExpectedOffset + ")");
        }
        if (!SHA256_PATTERN.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException("expectedSha256 must be a 64-character hex string: " + expectedSha256);
        }
        if (lastUpdated <= 0) {
            throw new IllegalArgumentException("lastUpdated must be positive: " + lastUpdated);
        }
    }

    public static TransferCheckpoint initial(FileMetadata metadata, int chunkSize) {
        return new TransferCheckpoint(
                metadata.transferId(),
                metadata.senderId(),
                metadata.recipientId(),
                metadata.fileName(),
                metadata.fileSize(),
                chunkSize,
                0,
                0L,
                0L,
                metadata.sha256(),
                System.currentTimeMillis()
        );
    }

    public TransferCheckpoint withProgress(int nextChunk, long nextOffset, long bytes) {
        return new TransferCheckpoint(
                transferId,
                senderId,
                recipientId,
                fileName,
                fileSize,
                chunkSize,
                nextChunk,
                nextOffset,
                bytes,
                expectedSha256,
                System.currentTimeMillis()
        );
    }

    /**
     * Serializes this checkpoint to an explicit UTF-8 key-value string.
     */
    public String serialize() {
        return "transferId=" + transferId + "\n" +
                "senderId=" + senderId + "\n" +
                "recipientId=" + recipientId + "\n" +
                "fileName=" + fileName + "\n" +
                "fileSize=" + fileSize + "\n" +
                "chunkSize=" + chunkSize + "\n" +
                "nextExpectedChunk=" + nextExpectedChunk + "\n" +
                "nextExpectedOffset=" + nextExpectedOffset + "\n" +
                "bytesReceived=" + bytesReceived + "\n" +
                "expectedSha256=" + expectedSha256 + "\n" +
                "lastUpdated=" + lastUpdated + "\n";
    }

    /**
     * Parses and strictly validates a checkpoint from key-value text lines.
     */
    public static TransferCheckpoint deserialize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Checkpoint text must not be null or blank");
        }

        Map<String, String> map = new HashMap<>();
        for (String line : text.lines().toList()) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Malformed line in checkpoint: " + line);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            map.put(key, value);
        }

        String[] required = {
                "transferId", "senderId", "recipientId", "fileName",
                "fileSize", "chunkSize", "nextExpectedChunk", "nextExpectedOffset",
                "bytesReceived", "expectedSha256", "lastUpdated"
        };
        for (String req : required) {
            if (!map.containsKey(req)) {
                throw new IllegalArgumentException("Missing required checkpoint field: " + req);
            }
        }

        try {
            UUID tid = UUID.fromString(map.get("transferId"));
            UUID sid = UUID.fromString(map.get("senderId"));
            UUID rid = UUID.fromString(map.get("recipientId"));
            String name = map.get("fileName");
            long size = Long.parseLong(map.get("fileSize"));
            int cSize = Integer.parseInt(map.get("chunkSize"));
            int nChunk = Integer.parseInt(map.get("nextExpectedChunk"));
            long nOffset = Long.parseLong(map.get("nextExpectedOffset"));
            long bRecv = Long.parseLong(map.get("bytesReceived"));
            String sha = map.get("expectedSha256");
            long updated = Long.parseLong(map.get("lastUpdated"));

            return new TransferCheckpoint(tid, sid, rid, name, size, cSize, nChunk, nOffset, bRecv, sha, updated);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid field format in checkpoint: " + e.getMessage(), e);
        }
    }
}
