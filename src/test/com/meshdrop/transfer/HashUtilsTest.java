package com.meshdrop.transfer;

import com.meshdrop.security.HashUtils;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for SHA-256 HashUtils.
 */
public class HashUtilsTest {

    public void runAll() throws Exception {
        testSha256();
    }

    private void testSha256() {
        byte[] input = "hello world".getBytes(StandardCharsets.UTF_8);
        String hash = HashUtils.sha256(input);
        // Known SHA-256 for "hello world"
        String expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        assert hash.equalsIgnoreCase(expected) : "SHA-256 hash mismatch! Got: " + hash;
    }
}
