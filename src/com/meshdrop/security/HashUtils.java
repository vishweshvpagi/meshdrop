package com.meshdrop.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cryptographic hashing utilities using SHA-256 for file integrity verification.
 */
public final class HashUtils {
    private HashUtils() {}

    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static String sha256(File file) throws IOException {
        return sha256(file.toPath());
    }

    public static String sha256(java.nio.file.Path path) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path), 256 * 1024)) {
            return sha256(in);
        }
    }

    public static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[256 * 1024]; // Bounded 256 KiB buffer for streaming O(1) memory hashing
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
