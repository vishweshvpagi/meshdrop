package com.meshdrop.security;

import java.security.PublicKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Verified cryptographic identity of a remote peer node.
 */
public record PeerIdentity(
        UUID nodeId,
        String displayName,
        PublicKey publicKey,
        IdentityFingerprint fingerprint
) {

    public PeerIdentity {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (fingerprint == null && publicKey != null) {
            fingerprint = IdentityFingerprint.fromPublicKey(publicKey);
        }
    }

    public static PeerIdentity of(UUID nodeId, String displayName, PublicKey publicKey) {
        IdentityFingerprint fp = publicKey != null ? IdentityFingerprint.fromPublicKey(publicKey) : null;
        return new PeerIdentity(nodeId, displayName, publicKey, fp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerIdentity that)) return false;
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
