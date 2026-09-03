package com.meshdrop.transfer;

import java.util.UUID;

/**
 * Unit tests for FileMetadata model and path traversal validations.
 */
public class FileMetadataTest {

    private static final String VALID_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public void runAll() throws Exception {
        testValidMetadata();
        testPathTraversalRejection();
        testNegativeFileSize();
        testInvalidHash();
        testNullChecks();
        testSanitizeFileName();
    }

    private void testValidMetadata() {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        FileMetadata meta = new FileMetadata(tid, sid, rid, "photo.jpg", 1024L, System.currentTimeMillis(), VALID_HASH);

        assert meta.transferId().equals(tid);
        assert meta.fileName().equals("photo.jpg");
        assert meta.fileSize() == 1024L;
        assert meta.sha256().equals(VALID_HASH);
    }

    private void testPathTraversalRejection() {
        UUID tid = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        UUID rid = UUID.randomUUID();

        String[] badNames = {
                "../secret.txt",
                "..\\secret.txt",
                "/etc/passwd",
                "C:\\Windows\\System32\\cmd.exe",
                "folder/sub/file.txt",
                "folder\\file.txt",
                ".."
        };

        for (String bad : badNames) {
            boolean thrown = false;
            try {
                new FileMetadata(tid, sid, rid, bad, 100L, 1000L, VALID_HASH);
            } catch (IllegalArgumentException e) {
                thrown = true;
            }
            assert thrown : "Expected path traversal to be rejected for: " + bad;
        }
    }

    private void testNegativeFileSize() {
        boolean thrown = false;
        try {
            new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test.txt", -5L, 1000L, VALID_HASH);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Negative file size must be rejected";
    }

    private void testInvalidHash() {
        boolean thrown = false;
        try {
            new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test.txt", 100L, 1000L, "short_hash");
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Short hash must be rejected";

        thrown = false;
        try {
            new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test.txt", 100L, 1000L, "z".repeat(64));
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assert thrown : "Non-hex hash must be rejected";
    }

    private void testNullChecks() {
        boolean thrown = false;
        try {
            new FileMetadata(null, UUID.randomUUID(), UUID.randomUUID(), "test.txt", 100L, 1000L, VALID_HASH);
        } catch (NullPointerException e) {
            thrown = true;
        }
        assert thrown : "Null transferId must be rejected";
    }

    private void testSanitizeFileName() {
        assert FileMetadata.sanitizeFileName("C:\\Users\\Bob\\photo.png").equals("photo.png");
        assert FileMetadata.sanitizeFileName("/home/user/docs/notes.txt").equals("notes.txt");
        assert FileMetadata.sanitizeFileName("../../../evil.sh").equals("evil.sh");
    }
}
