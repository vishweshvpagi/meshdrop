package com.meshdrop.security;

import java.security.KeyPair;

public class IdentityFingerprintTest {

    public void runAll() {
        testDeriveFromPublicKey();
        testDeterministicFingerprint();
        testParseFormattedFingerprint();
        testInvalidFormatRejection();
        testEqualityAndHashCode();
    }

    private void testDeriveFromPublicKey() {
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp = IdentityFingerprint.fromPublicKey(kp.getPublic());
        assert fp != null : "Fingerprint must not be null";
        assert fp.formatted().matches("([0-9A-F]{4}-){7}[0-9A-F]{4}") : "Format must match 4-char groups: " + fp.formatted();
        assert fp.rawBytes().length == 16 : "Raw hash must have 16 bytes";
    }

    private void testDeterministicFingerprint() {
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        IdentityFingerprint fp1 = IdentityFingerprint.fromPublicKey(kp.getPublic());
        IdentityFingerprint fp2 = IdentityFingerprint.fromPublicKey(kp.getPublic());
        assert fp1.equals(fp2) : "Fingerprints from same key must equal";
        assert fp1.formatted().equals(fp2.formatted()) : "Formatted strings must match";
    }

    private void testParseFormattedFingerprint() {
        String original = "AB12-CD34-EF56-7890-1234-5678-90AB-CDEF";
        IdentityFingerprint parsed = IdentityFingerprint.parse(original);
        assert parsed.formatted().equals(original) : "Parsed fingerprint must preserve formatting";
    }

    private void testInvalidFormatRejection() {
        try {
            IdentityFingerprint.parse("INVALID-FORMAT");
            assert false : "Short fingerprint must be rejected";
        } catch (IllegalArgumentException expected) {}

        try {
            IdentityFingerprint.parse("ZZZZ-CD34-EF56-7890-1234-5678-90AB-CDEF");
            assert false : "Non-hex characters must be rejected";
        } catch (IllegalArgumentException expected) {}
    }

    private void testEqualityAndHashCode() {
        String str1 = "AB12-CD34-EF56-7890-1234-5678-90AB-CDEF";
        String str2 = "ab12-cd34-ef56-7890-1234-5678-90ab-cdef";
        IdentityFingerprint fp1 = IdentityFingerprint.parse(str1);
        IdentityFingerprint fp2 = IdentityFingerprint.parse(str2);
        assert fp1.equals(fp2) : "Case-insensitive equality must hold";
        assert fp1.hashCode() == fp2.hashCode() : "Hash codes must match for equal fingerprints";
    }
}
