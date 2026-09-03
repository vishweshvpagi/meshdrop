package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Verifies SHA-256 file hashing on various file types and sizes.
 */
public class FileHashTest {

    public void runAll() throws Exception {
        testEmptyFileHash();
        testSmallFileHash();
        testBinaryFileHash();
        testLargerFileStreamed();
    }

    private void testEmptyFileHash() throws Exception {
        Path empty = Files.createTempFile("empty-test", ".tmp");
        try {
            String hash = HashUtils.sha256(empty.toFile());
            String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            assert hash.equalsIgnoreCase(expected) : "Empty file hash mismatch: " + hash;
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    private void testSmallFileHash() throws Exception {
        Path small = Files.createTempFile("small-test", ".tmp");
        try {
            Files.writeString(small, "MeshDrop P2P File Sharing", StandardCharsets.UTF_8);
            String hashFile = HashUtils.sha256(small.toFile());
            String hashBytes = HashUtils.sha256("MeshDrop P2P File Sharing".getBytes(StandardCharsets.UTF_8));
            assert hashFile.equalsIgnoreCase(hashBytes) : "File and byte hash mismatch";
        } finally {
            Files.deleteIfExists(small);
        }
    }

    private void testBinaryFileHash() throws Exception {
        Path bin = Files.createTempFile("bin-test", ".tmp");
        try {
            byte[] data = new byte[4096];
            new Random(42).nextBytes(data);
            Files.write(bin, data);

            String hash1 = HashUtils.sha256(bin.toFile());
            String hash2 = HashUtils.sha256(data);
            assert hash1.equalsIgnoreCase(hash2) : "Binary file hash mismatch";
        } finally {
            Files.deleteIfExists(bin);
        }
    }

    private void testLargerFileStreamed() throws Exception {
        Path large = Files.createTempFile("large-test", ".tmp");
        try {
            byte[] data = new byte[128 * 1024]; // 128 KiB
            new Random(123).nextBytes(data);
            Files.write(large, data);

            String fileHash = HashUtils.sha256(large.toFile());
            try (InputStream in = Files.newInputStream(large)) {
                String streamHash = HashUtils.sha256(in);
                assert fileHash.equalsIgnoreCase(streamHash) : "Stream and file hash mismatch";
            }
        } finally {
            Files.deleteIfExists(large);
        }
    }
}
