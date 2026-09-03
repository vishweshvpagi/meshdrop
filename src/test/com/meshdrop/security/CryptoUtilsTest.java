package com.meshdrop.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public class CryptoUtilsTest {

    public void runAll() throws Exception {
        testKeyPairGeneration();
        testKeyEncodingAndDecoding();
        testSignAndVerify();
        testTamperedDataRejected();
        testWrongKeyVerificationFails();
    }

    private void testKeyPairGeneration() {
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        assert kp != null : "Generated keypair must not be null";
        assert kp.getPublic() != null : "Public key must not be null";
        assert kp.getPrivate() != null : "Private key must not be null";
        assert kp.getPublic().getAlgorithm().equalsIgnoreCase("Ed25519") ||
               kp.getPublic().getAlgorithm().equalsIgnoreCase("EdDSA") : "Algorithm must be Ed25519 or EdDSA";
    }

    private void testKeyEncodingAndDecoding() throws Exception {
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        byte[] pubBytes = kp.getPublic().getEncoded();
        byte[] privBytes = kp.getPrivate().getEncoded();

        PublicKey decodedPub = CryptoUtils.decodePublicKey(pubBytes);
        PrivateKey decodedPriv = CryptoUtils.decodePrivateKey(privBytes);

        assert decodedPub.equals(kp.getPublic()) : "Decoded public key must equal original";
        assert decodedPriv.equals(kp.getPrivate()) : "Decoded private key must equal original";
    }

    private void testSignAndVerify() {
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        byte[] payload = "MeshDrop Authenticated Message".getBytes(StandardCharsets.UTF_8);

        byte[] sig = CryptoUtils.sign(kp.getPrivate(), payload);
        assert sig != null && sig.length == 64 : "Ed25519 signature must be 64 bytes";

        boolean valid = CryptoUtils.verify(kp.getPublic(), payload, sig);
        assert valid : "Signature must verify successfully against original payload and public key";
    }

    private void testTamperedDataRejected() {
        KeyPair kp = CryptoUtils.generateEd25519KeyPair();
        byte[] original = "Integrity Check".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "Integrity Checj".getBytes(StandardCharsets.UTF_8);

        byte[] sig = CryptoUtils.sign(kp.getPrivate(), original);
        boolean valid = CryptoUtils.verify(kp.getPublic(), tampered, sig);
        assert !valid : "Signature verification must fail when payload is tampered";
    }

    private void testWrongKeyVerificationFails() {
        KeyPair kp1 = CryptoUtils.generateEd25519KeyPair();
        KeyPair kp2 = CryptoUtils.generateEd25519KeyPair();
        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);

        byte[] sig = CryptoUtils.sign(kp1.getPrivate(), payload);
        boolean valid = CryptoUtils.verify(kp2.getPublic(), payload, sig);
        assert !valid : "Signature verification must fail when verified against different public key";
    }
}
