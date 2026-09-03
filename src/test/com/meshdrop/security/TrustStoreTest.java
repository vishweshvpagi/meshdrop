package com.meshdrop.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Comparator;
import java.util.UUID;

public class TrustStoreTest {

    public void runAll() throws Exception {
        Path tempDir = Files.createTempDirectory("trust-store-test");
        try {
            testDefaultUntrusted(tempDir);
            testTrustPeer(tempDir);
            testUntrustPeer(tempDir);
            testBlockPeer(tempDir);
            testFingerprintMismatchTriggersBlock(tempDir);
            testPersistenceAcrossReload(tempDir);
        } finally {
            cleanupDir(tempDir);
        }
    }

    private void testDefaultUntrusted(Path dir) {
        TrustStore store = new TrustStore(dir);
        UUID peerId = UUID.randomUUID();
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp = IdentityFingerprint.fromPublicKey(kp.getPublic());

        TrustDecision decision = store.getTrustDecision(peerId, fp);
        assert decision == TrustDecision.UNTRUSTED : "New peer must default to UNTRUSTED";
        assert !store.isTrusted(peerId, fp) : "New peer must not be trusted";
        assert !store.isBlocked(peerId) : "New peer must not be blocked";
    }

    private void testTrustPeer(Path dir) {
        TrustStore store = new TrustStore(dir);
        UUID peerId = UUID.randomUUID();
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp = IdentityFingerprint.fromPublicKey(kp.getPublic());

        store.trust(peerId, fp, "Alice");
        assert store.isTrusted(peerId, fp) : "Peer must be trusted after trust() call";
        assert store.getTrustDecision(peerId, fp) == TrustDecision.TRUSTED : "Decision must be TRUSTED";
    }

    private void testUntrustPeer(Path dir) {
        TrustStore store = new TrustStore(dir);
        UUID peerId = UUID.randomUUID();
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp = IdentityFingerprint.fromPublicKey(kp.getPublic());

        store.trust(peerId, fp, "Bob");
        assert store.isTrusted(peerId, fp);

        store.untrust(peerId);
        assert !store.isTrusted(peerId, fp) : "Peer must not be trusted after untrust()";
        assert store.getTrustDecision(peerId, fp) == TrustDecision.UNTRUSTED;
    }

    private void testBlockPeer(Path dir) {
        TrustStore store = new TrustStore(dir);
        UUID peerId = UUID.randomUUID();
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp = IdentityFingerprint.fromPublicKey(kp.getPublic());

        store.block(peerId, fp, "Malicious behavior");
        assert store.isBlocked(peerId) : "Peer must be marked blocked";
        assert store.getTrustDecision(peerId, fp) == TrustDecision.BLOCKED;
    }

    private void testFingerprintMismatchTriggersBlock(Path dir) {
        TrustStore store = new TrustStore(dir);
        UUID peerId = UUID.randomUUID();
        KeyPair kp1 = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp1 = IdentityFingerprint.fromPublicKey(kp1.getPublic());

        store.trust(peerId, fp1, "LegitimatePeer");

        KeyPair kp2 = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp2 = IdentityFingerprint.fromPublicKey(kp2.getPublic());

        TrustDecision decision = store.getTrustDecision(peerId, fp2);
        assert decision == TrustDecision.BLOCKED : "Fingerprint mismatch must trigger BLOCKED decision";
    }

    private void testPersistenceAcrossReload(Path dir) {
        TrustStore store1 = new TrustStore(dir);
        UUID peerId = UUID.randomUUID();
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp = IdentityFingerprint.fromPublicKey(kp.getPublic());

        store1.trust(peerId, fp, "PersistentPeer");

        // Reload fresh instance from disk
        TrustStore store2 = new TrustStore(dir);
        assert store2.isTrusted(peerId, fp) : "Trust store state must persist across instances";
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
