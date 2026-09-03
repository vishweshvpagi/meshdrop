package com.meshdrop.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Deterministic, human-readable fingerprint representing a cryptographic public key.
 *
 * Fingerprints are computed as the SHA-256 digest of the X.509 encoded public key bytes.
 * The first 16 bytes (32 hex characters) are partitioned into uppercase 4-character
 * hyphen-delimited segments (e.g. "AB12-CD34-EF56-7890-1234-5678-90AB-CDEF").
 */
public final class IdentityFingerprint {

    private final String formatted;
    private final byte[] rawHash;

    private IdentityFingerprint(String formatted, byte[] rawHash) {
        this.formatted = Objects.requireNonNull(formatted, "formatted fingerprint must not be null");
        this.rawHash = rawHash != null ? rawHash.clone() : new byte[0];
    }

    /**
     * Derives an IdentityFingerprint from an Ed25519 (or any X.509) public key.
     */
    public static IdentityFingerprint fromPublicKey(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        byte[] encoded = publicKey.getEncoded();
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("PublicKey encoding must not be null or empty");
        }
        return fromBytes(encoded);
    }

    /**
     * Derives an IdentityFingerprint directly from encoded public key bytes.
     */
    public static IdentityFingerprint fromBytes(byte[] encodedKeyBytes) {
        Objects.requireNonNull(encodedKeyBytes, "encodedKeyBytes must not be null");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encodedKeyBytes);
            return fromDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing in standard runtime", e);
        }
    }

    /**
     * Creates an IdentityFingerprint from a full 32-byte SHA-256 digest.
     */
    public static IdentityFingerprint fromDigest(byte[] digest) {
        Objects.requireNonNull(digest, "digest must not be null");
        if (digest.length < 16) {
            throw new IllegalArgumentException("Digest length must be at least 16 bytes");
        }

        byte[] first16 = Arrays.copyOf(digest, 16);
        String hex = HexFormat.of().formatHex(first16).toUpperCase();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (sb.length() > 0) {
                sb.append('-');
            }
            sb.append(hex, i, Math.min(i + 4, hex.length()));
        }

        return new IdentityFingerprint(sb.toString(), first16);
    }

    /**
     * Parses an existing human-readable fingerprint string (e.g. "AB12-CD34-...").
     */
    public static IdentityFingerprint parse(String fingerprintStr) {
        Objects.requireNonNull(fingerprintStr, "fingerprint string must not be null");
        String clean = fingerprintStr.trim().toUpperCase();
        String unhyphenated = clean.replace("-", "");
        if (unhyphenated.length() != 32 || !unhyphenated.matches("[0-9A-F]{32}")) {
            throw new IllegalArgumentException("Invalid fingerprint format: expected 32 hex characters, got " + clean);
        }

        byte[] raw = HexFormat.of().parseHex(unhyphenated);
        return new IdentityFingerprint(clean, raw);
    }

    public String formatted() {
        return formatted;
    }

    public byte[] rawBytes() {
        return rawHash.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdentityFingerprint that)) return false;
        return formatted.equalsIgnoreCase(that.formatted);
    }

    @Override
    public int hashCode() {
        return formatted.toUpperCase().hashCode();
    }

    @Override
    public String toString() {
        return formatted;
    }
}
