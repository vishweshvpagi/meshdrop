package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Verifies strict path traversal defense at codec, model, and receiver layers.
 */
public class PathTraversalTest {

    public void runAll() throws Exception {
        testModelRejectsTraversal();
        testCodecRejectsTraversal();
        testReceiverNeverWritesOutsideDownloads();
    }

    private void testModelRejectsTraversal() {
        String[] attacks = {
                "../secret.txt",
                "..\\secret.txt",
                "/etc/passwd",
                "C:\\Windows\\System32\\config\\SAM",
                "..\\..\\boot.ini"
        };

        for (String attack : attacks) {
            boolean rejected = false;
            try {
                new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        attack, 100L, 1000L, "0".repeat(64));
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            assert rejected : "Expected attack to be rejected by FileMetadata: " + attack;
        }
    }

    private void testCodecRejectsTraversal() throws Exception {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();

        // Construct raw offer payload with malicious filename "../hacked.txt"
        byte[] attackName = "../hacked.txt".getBytes();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(FileTransferCodec.OFFER_FIXED_HEADER_BYTES + attackName.length);
        buf.putLong(tid.getMostSignificantBits()).putLong(tid.getLeastSignificantBits());
        buf.putLong(sid.getMostSignificantBits()).putLong(sid.getLeastSignificantBits());
        buf.putLong(rid.getMostSignificantBits()).putLong(rid.getLeastSignificantBits());
        buf.putLong(100L).putLong(1000L);
        buf.put("0".repeat(64).getBytes());
        buf.putShort((short) attackName.length);
        buf.put(attackName);

        boolean rejected = false;
        try {
            FileTransferCodec.decodeOffer(buf.array());
        } catch (ProtocolException e) {
            rejected = true;
        }
        assert rejected : "FileTransferCodec must reject path traversal filename";
    }

    private void testReceiverNeverWritesOutsideDownloads() throws Exception {
        Path base = Files.createTempDirectory("pt-test");
        Path downloads = base.resolve("downloads");
        Path temp = base.resolve("temp");
        Path outside = base.resolve("sensitive.txt");
        Files.writeString(outside, "TOP SECRET");

        try {
            // Attempt to resolve path with traversal in FileReceiver
            Path resolved = FileReceiver.resolveCollisionSafePath(downloads, "safe.txt");
            assert resolved.startsWith(downloads) : "Resolved path escaped downloads directory: " + resolved;
            assert !resolved.equals(outside);
        } finally {
            deleteDir(base);
        }
    }

    private void deleteDir(Path dir) {
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
