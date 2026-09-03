package com.meshdrop.security;

/**
 * Trust status assigned to a peer node identity and public key fingerprint.
 */
public enum TrustDecision {
    /**
     * Peer public key has been explicitly trusted by the local operator.
     */
    TRUSTED("TRUSTED"),

    /**
     * Peer is freshly discovered or not yet explicitly verified.
     */
    UNTRUSTED("UNTRUSTED"),

    /**
     * Peer has been explicitly blocked; all inbound/outbound interaction is dropped.
     */
    BLOCKED("BLOCKED");

    private final String label;

    TrustDecision(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTrusted() {
        return this == TRUSTED;
    }

    public boolean isBlocked() {
        return this == BLOCKED;
    }

    public static TrustDecision fromString(String str) {
        if (str == null || str.isBlank()) {
            return UNTRUSTED;
        }
        try {
            return valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNTRUSTED;
        }
    }
}
