package com.meshdrop.core;

import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;
import com.meshdrop.security.CryptoUtils;
import com.meshdrop.security.IdentityFingerprint;
import com.meshdrop.util.Logger;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the persistent unique identity of a MeshDrop node.
 *
 * Wire format for HELLO / HELLO_RESPONSE payloads:
 *   - NODE ID (16 bytes): UUID most-significant bits (8 bytes) + least-significant bits (8 bytes)
 *   - NAME LENGTH (2 bytes): Big-endian short representing UTF-8 byte length (0 to 128 bytes)
 *   - DISPLAY NAME (N bytes): UTF-8 encoded human-readable display name string
 *   - KEY LENGTH (2 bytes): Big-endian short representing X.509 public key byte length (optional, 0 to 512 bytes)
 *   - PUBLIC KEY (K bytes): Raw X.509 encoded Ed25519 public key bytes
 */
public record NodeIdentity(
        UUID nodeId,
        String displayName,
        PublicKey publicKey,
        IdentityFingerprint fingerprint,
        Instant createdAt
) implements Serializable {

    public NodeIdentity {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > ProtocolConstants.MAX_DISPLAY_NAME_BYTES) {
            throw new IllegalArgumentException("Display name UTF-8 byte length (" + nameBytes.length +
                    ") exceeds maximum allowed " + ProtocolConstants.MAX_DISPLAY_NAME_BYTES + " bytes");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (fingerprint == null && publicKey != null) {
            fingerprint = IdentityFingerprint.fromPublicKey(publicKey);
        }
    }

    public NodeIdentity(UUID nodeId, String displayName, Instant createdAt) {
        this(nodeId, displayName, null, null, createdAt);
    }

    public NodeIdentity(UUID nodeId, String displayName, PublicKey publicKey, Instant createdAt) {
        this(nodeId, displayName, publicKey, publicKey != null ? IdentityFingerprint.fromPublicKey(publicKey) : null, createdAt);
    }

    public static NodeIdentity of(UUID nodeId, String displayName) {
        return new NodeIdentity(nodeId, displayName, null, null, Instant.now());
    }

    public static NodeIdentity of(UUID nodeId, String displayName, PublicKey publicKey) {
        return new NodeIdentity(nodeId, displayName, publicKey, Instant.now());
    }

    public static NodeIdentity createRandom() {
        UUID id = UUID.randomUUID();
        String shortId = id.toString().substring(0, 4).toUpperCase();
        var kp = CryptoUtils.generateEd25519KeyPair();
        return new NodeIdentity(id, "MeshDrop-" + shortId, kp.getPublic(), Instant.now());
    }

    public static NodeIdentity createRandom(String displayName) {
        var kp = CryptoUtils.generateEd25519KeyPair();
        return new NodeIdentity(UUID.randomUUID(), displayName, kp.getPublic(), Instant.now());
    }

    /**
     * Serializes this NodeIdentity into binary wire format for HELLO and HELLO_RESPONSE packets.
     */
    public byte[] encode() {
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = publicKey != null ? publicKey.getEncoded() : null;
        int keySectionLen = keyBytes != null ? 2 + keyBytes.length : 0;
        byte[] payload = new byte[16 + 2 + nameBytes.length + keySectionLen];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        buf.putLong(nodeId.getMostSignificantBits());
        buf.putLong(nodeId.getLeastSignificantBits());
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);
        if (keyBytes != null) {
            buf.putShort((short) keyBytes.length);
            buf.put(keyBytes);
        }

        return payload;
    }

    /**
     * Deserializes and validates a NodeIdentity from a binary payload.
     */
    public static NodeIdentity decode(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 18) {
            throw new ProtocolException("Malformed identity payload: expected at least 18 bytes, got " +
                    (payload != null ? payload.length : 0));
        }

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long mostSig = buf.getLong();
        long leastSig = buf.getLong();
        UUID id = new UUID(mostSig, leastSig);

        int nameLen = buf.getShort() & 0xFFFF; // unsigned short
        if (nameLen > ProtocolConstants.MAX_DISPLAY_NAME_BYTES) {
            throw new ProtocolException("Identity display name length " + nameLen +
                    " exceeds maximum allowable " + ProtocolConstants.MAX_DISPLAY_NAME_BYTES + " bytes");
        }

        if (buf.remaining() < nameLen) {
            throw new ProtocolException("Identity payload length mismatch: expected at least " + nameLen +
                    " name bytes, but buffer has " + buf.remaining() + " remaining");
        }

        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);

        PublicKey pubKey = null;
        if (buf.remaining() >= 2) {
            int keyLen = buf.getShort() & 0xFFFF;
            if (keyLen > 0 && buf.remaining() >= keyLen) {
                byte[] keyBytes = new byte[keyLen];
                buf.get(keyBytes);
                try {
                    pubKey = CryptoUtils.decodePublicKey(keyBytes);
                } catch (Exception e) {
                    Logger.warn("[SECURITY] Could not decode Ed25519 public key for node " + id + ": " + e.getMessage());
                }
            }
        }

        return new NodeIdentity(id, name, pubKey, Instant.now());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeIdentity that)) return false;
        // Node ID is the authoritative unique identifier
        return nodeId.equals(that.nodeId);
    }

    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }

    @Override
    public String toString() {
        return displayName + " (" + nodeId + ")" + (fingerprint != null ? " [" + fingerprint + "]" : "");
    }
}
