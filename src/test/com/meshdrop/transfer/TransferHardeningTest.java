package com.meshdrop.transfer;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Hardening and reliability test suite verifying transfer state machine transitions,
 * path sanitization, Windows reserved device names, DoS bounds, cancellation mechanics,
 * collision resolution, and progress metrics.
 */
public class TransferHardeningTest {

    public void runAll() throws Exception {
        testFilenameSanitizationWindowsReserved();
        testPathTraversalSanitization();
        testForbiddenCharactersSanitization();
        testTransferStateTransitionsAndTimedOut();
        testIllegalStateTransitionRejection();
        testShortTransferIdAndMatching();
        testEtaAndProgressCalculations();
        testCollisionSafePathResolution();
        testFileReceiverCancellationCleanup();
        testZeroByteAndHugeFileBounds();
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("ASSERTION FAILED: " + message);
        }
    }

    private void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("ASSERTION FAILED: " + message + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    public void testFilenameSanitizationWindowsReserved() {
        assertEquals("safe_CON.txt", FileMetadata.sanitizeFileName("CON.txt"), "CON.txt should be prefixed with safe_");
        assertEquals("safe_nul.dat", FileMetadata.sanitizeFileName("nul.dat"), "nul.dat should be prefixed with safe_");
        assertEquals("safe_AUX", FileMetadata.sanitizeFileName("AUX"), "AUX should be prefixed with safe_");
        assertEquals("safe_com1.zip", FileMetadata.sanitizeFileName("com1.zip"), "com1.zip should be prefixed with safe_");
        assertEquals("safe_LPT9.tar", FileMetadata.sanitizeFileName("LPT9.tar"), "LPT9.tar should be prefixed with safe_");
    }

    public void testPathTraversalSanitization() {
        assertEquals("evil.exe", FileMetadata.sanitizeFileName("../../evil.exe"), "Unix traversal stripped");
        assertEquals("evil.exe", FileMetadata.sanitizeFileName("..\\..\\evil.exe"), "Windows traversal stripped");
        assertEquals("passwd", FileMetadata.sanitizeFileName("/etc/passwd"), "Absolute unix path stripped");
        assertEquals("cmd.exe", FileMetadata.sanitizeFileName("C:\\Windows\\System32\\cmd.exe"), "Windows drive and path stripped");
        assertEquals("script.bat", FileMetadata.sanitizeFileName("D:script.bat"), "Drive prefix stripped");
    }

    public void testForbiddenCharactersSanitization() {
        String input = "my<file>:name\"is|cool?.txt";
        String sanitized = FileMetadata.sanitizeFileName(input);
        assertTrue(!sanitized.contains("<") && !sanitized.contains(">") && !sanitized.contains(":") &&
                   !sanitized.contains("\"") && !sanitized.contains("|") && !sanitized.contains("?"),
                   "Sanitized filename must not contain forbidden characters: " + sanitized);

        assertEquals("movie.mp4", FileMetadata.sanitizeFileName("movie.mp4...   "), "Trailing dots and whitespace trimmed");
    }

    public void testTransferStateTransitionsAndTimedOut() {
        assertTrue(TransferState.WAITING_FOR_ACCEPT.canTransitionTo(TransferState.TIMED_OUT), "WAITING_FOR_ACCEPT can time out");
        assertTrue(TransferState.TRANSFERRING.canTransitionTo(TransferState.TIMED_OUT), "TRANSFERRING can time out on idle");
        assertTrue(TransferState.TRANSFERRING.canTransitionTo(TransferState.CANCELLED), "TRANSFERRING can be cancelled");
        assertTrue(TransferState.TIMED_OUT.isTerminal(), "TIMED_OUT is terminal");
        assertTrue(TransferState.TIMED_OUT.isResumable(), "TIMED_OUT is resumable");

        FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), "test.txt", 1024, "a".repeat(64));
        Transfer t = new Transfer(meta, TransferDirection.UPLOAD, Path.of("test.txt"));
        assertEquals(TransferState.OFFERING, t.getState(), "Initial state OFFERING");

        t.transitionTo(TransferState.WAITING_FOR_ACCEPT);
        assertEquals(TransferState.WAITING_FOR_ACCEPT, t.getState(), "Transition to WAITING_FOR_ACCEPT");

        t.transitionTo(TransferState.TIMED_OUT);
        assertEquals(TransferState.TIMED_OUT, t.getState(), "Transition to TIMED_OUT");
    }

    public void testIllegalStateTransitionRejection() {
        FileMetadata meta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), "test.txt", 1024, "a".repeat(64));
        Transfer t = new Transfer(meta, TransferDirection.DOWNLOAD, Path.of("test.txt"), TransferState.COMPLETED);

        boolean exceptionThrown = false;
        try {
            t.transitionTo(TransferState.TRANSFERRING);
        } catch (IllegalStateException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown, "Transition from COMPLETED to TRANSFERRING must be rejected with IllegalStateException");
    }

    public void testShortTransferIdAndMatching() {
        UUID id = UUID.fromString("b923e01c-b932-4cdb-965c-f4fe37591f94");
        FileMetadata meta = new FileMetadata(id, UUID.randomUUID(), UUID.randomUUID(), "movie.mp4", 1000, 123456L, "b".repeat(64));
        Transfer t = new Transfer(meta, TransferDirection.UPLOAD, Path.of("movie.mp4"));

        assertEquals("TX-B923E0", t.getShortId(), "Short ID matches first 6 hex uppercase chars with TX- prefix");
        assertTrue(t.matchesIdentifier("TX-B923E0"), "Matches exact short ID");
        assertTrue(t.matchesIdentifier("tx-b923e0"), "Matches lowercase short ID");
        assertTrue(t.matchesIdentifier("b923e01c-b932-4cdb-965c-f4fe37591f94"), "Matches full UUID");
        assertTrue(t.matchesIdentifier("B923E0"), "Matches prefix without TX-");
        assertTrue(!t.matchesIdentifier("TX-FFFFFF"), "Does not match mismatched short ID");
    }

    public void testEtaAndProgressCalculations() throws Exception {
        // Zero-byte file
        FileMetadata zeroMeta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), "zero.txt", 0, "c".repeat(64));
        Transfer zeroTransfer = new Transfer(zeroMeta, TransferDirection.UPLOAD, Path.of("zero.txt"));
        assertEquals(100.0, zeroTransfer.getProgressPercentage(), "Zero-byte file progress is 100%");
        assertEquals(0L, zeroTransfer.getEstimatedRemainingSeconds(), "Zero-byte file ETA is 0");

        // 1 GB file
        long oneGB = 1024L * 1024L * 1024L;
        FileMetadata largeMeta = FileMetadata.create(UUID.randomUUID(), UUID.randomUUID(), "large.iso", oneGB, "d".repeat(64));
        Transfer largeTransfer = new Transfer(largeMeta, TransferDirection.UPLOAD, Path.of("large.iso"));

        largeTransfer.setBytesTransferred(oneGB / 2);
        assertTrue(Math.abs(largeTransfer.getProgressPercentage() - 50.0) < 0.1, "Halfway transfer progress is ~50%");
        assertTrue(largeTransfer.getElapsedDurationMs() >= 0, "Elapsed duration is non-negative");
    }

    public void testCollisionSafePathResolution() throws IOException {
        Path tempDir = Files.createTempDirectory("collision-test");
        try {
            Path file1 = tempDir.resolve("sample.pdf");
            Files.writeString(file1, "first file");

            Path resolved1 = FileReceiver.resolveCollisionSafePath(tempDir, "sample.pdf");
            assertEquals("sample (1).pdf", resolved1.getFileName().toString(), "Collision resolves to (1)");

            Files.writeString(resolved1, "second file");
            Path resolved2 = FileReceiver.resolveCollisionSafePath(tempDir, "sample.pdf");
            assertEquals("sample (2).pdf", resolved2.getFileName().toString(), "Subsequent collision resolves to (2)");
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    public void testFileReceiverCancellationCleanup() throws Exception {
        Path tempDir = Files.createTempDirectory("rx-cancel-test");
        Path downloadsDir = Files.createTempDirectory("rx-dl-test");
        try {
            UUID tid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, UUID.randomUUID(), UUID.randomUUID(), "cancelled.bin", 1024, System.currentTimeMillis(), "e".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null, TransferState.TRANSFERRING);

            FileReceiver receiver = new FileReceiver(meta, downloadsDir, tempDir, transfer, (TransferListener) null);
            Path partFile = receiver.getTempFilePath();
            assertTrue(Files.exists(partFile), "Part staging file exists initially: " + partFile);

            transfer.cancel("Transfer cancelled by user");
            receiver.abort("Transfer cancelled by user");

            assertEquals(TransferState.CANCELLED, transfer.getState(), "State transitioned to CANCELLED");
            assertTrue(transfer.isCancelled(), "transfer.isCancelled() is true");
            assertTrue(!Files.exists(partFile), "Part staging file deleted upon cancellation");
        } finally {
            deleteRecursively(tempDir.toFile());
            deleteRecursively(downloadsDir.toFile());
        }
    }

    public void testZeroByteAndHugeFileBounds() {
        // Upper bound validation
        boolean rejectedAbsurdSize = false;
        try {
            new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "absurd.bin",
                    ProtocolConstants.MAX_ACCEPTED_FILE_SIZE + 1, System.currentTimeMillis(), "f".repeat(64));
        } catch (IllegalArgumentException e) {
            rejectedAbsurdSize = true;
        }
        assertTrue(rejectedAbsurdSize, "File size exceeding MAX_ACCEPTED_FILE_SIZE (100 GiB) must be rejected");

        // Negative size validation
        boolean rejectedNegativeSize = false;
        try {
            new FileMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "neg.bin",
                    -1L, System.currentTimeMillis(), "f".repeat(64));
        } catch (IllegalArgumentException e) {
            rejectedNegativeSize = true;
        }
        assertTrue(rejectedNegativeSize, "Negative file size must be rejected");
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
