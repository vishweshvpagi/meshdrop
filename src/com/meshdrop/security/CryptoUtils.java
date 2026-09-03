package com.meshdrop.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

/**
 * Standard Java Ed25519 cryptographic operations for node authentication and signatures.
 *
 * Implemented with zero external libraries using Java's built-in SunEC provider.
 */
public final class CryptoUtils {

    public static final String ALGORITHM = "Ed25519";

    private CryptoUtils() {}

    /**
     * Generates a new cryptographically secure Ed25519 keypair.
     */
    public static KeyPair generateEd25519KeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 algorithm not available in current JVM", e);
        }
    }

    /**
     * Decodes an X.509 byte array into an Ed25519 PublicKey.
     */
    public static PublicKey decodePublicKey(byte[] encodedX509) throws Exception {
        Objects.requireNonNull(encodedX509, "encodedX509 bytes must not be null");
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePublic(new X509EncodedKeySpec(encodedX509));
    }

    /**
     * Decodes a PKCS#8 byte array into an Ed25519 PrivateKey.
     */
    public static PrivateKey decodePrivateKey(byte[] encodedPKCS8) throws Exception {
        Objects.requireNonNull(encodedPKCS8, "encodedPKCS8 bytes must not be null");
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePrivate(new PKCS8EncodedKeySpec(encodedPKCS8));
    }

    /**
     * Signs data using the Ed25519 private key.
     */
    public static byte[] sign(PrivateKey privateKey, byte[] data) {
        Objects.requireNonNull(privateKey, "privateKey must not be null");
        Objects.requireNonNull(data, "data must not be null");
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 signature", e);
        }
    }

    /**
     * Verifies an Ed25519 signature against data using the public key.
     */
    public static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) {
        if (publicKey == null || data == null || signature == null) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
