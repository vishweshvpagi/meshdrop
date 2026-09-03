package com.meshdrop.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class StorageManagerTest {

    public void runAll() throws Exception {
        Path tempRoot = Files.createTempDirectory("storage-manager-test");
        try {
            testDirectoryInitialization(tempRoot);
            testSafePathResolution(tempRoot);
            testPathTraversalBlocked(tempRoot);
            testAbsoluteInjectionBlocked(tempRoot);
            testEmptyFilenameRejected(tempRoot);
        } finally {
            cleanupDir(tempRoot);
        }
    }

    private void testDirectoryInitialization(Path root) throws IOException {
        StorageManager sm = new StorageManager(root);
        sm.init();

        assert Files.isDirectory(sm.getStorageDir()) : "Storage dir must exist";
        assert Files.isDirectory(sm.getIdentityDir()) : "Identity dir must exist";
        assert Files.isDirectory(sm.getTrustDir()) : "Trust dir must exist";
        assert Files.isDirectory(sm.getDownloadsDir()) : "Downloads dir must exist";
        assert Files.isDirectory(sm.getTempDir()) : "Temp dir must exist";
        assert Files.isDirectory(sm.getLogsDir()) : "Logs dir must exist";
    }

    private void testSafePathResolution(Path root) throws IOException {
        StorageManager sm = new StorageManager(root);
        sm.init();

        Path safe = sm.resolveSafeDownloadPath("document.pdf");
        assert safe.getParent().equals(sm.getDownloadsDir().toAbsolutePath().normalize()) : "Must reside inside downloads";
        assert safe.getFileName().toString().equals("document.pdf") : "Filename must match";
    }

    private void testPathTraversalBlocked(Path root) throws IOException {
        StorageManager sm = new StorageManager(root);
        sm.init();

        // Test ../ traversal
        Path resolved = sm.resolveSafeDownloadPath("../../../etc/passwd");
        assert resolved.getParent().equals(sm.getDownloadsDir().toAbsolutePath().normalize()) : "Sanitized traversal must remain inside downloads";
        assert resolved.getFileName().toString().equals("passwd") : "Base filename must be preserved safely";
    }

    private void testAbsoluteInjectionBlocked(Path root) throws IOException {
        StorageManager sm = new StorageManager(root);
        sm.init();

        Path resolved = sm.resolveSafeDownloadPath("C:\\Windows\\System32\\cmd.exe");
        assert resolved.getParent().equals(sm.getDownloadsDir().toAbsolutePath().normalize()) : "Absolute Windows paths must not escape download root";
        assert resolved.getFileName().toString().equals("cmd.exe");
    }

    private void testEmptyFilenameRejected(Path root) {
        StorageManager sm = new StorageManager(root);
        try {
            sm.resolveSafeDownloadPath("");
            assert false : "Empty filename must throw SecurityException";
        } catch (SecurityException expected) {}

        try {
            sm.resolveSafeDownloadPath("   ");
            assert false : "Blank filename must throw SecurityException";
        } catch (SecurityException expected) {}
    }

    private void cleanupDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
