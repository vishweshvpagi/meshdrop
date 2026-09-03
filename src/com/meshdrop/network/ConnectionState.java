package com.meshdrop.network;

/**
 * Lifecycle states of a peer-to-peer TCP connection.
 *
 * Phase 2 states:
 *   CONNECTING → Socket created but TCP handshake not yet complete.
 *   CONNECTED  → TCP handshake complete, bidirectional I/O is possible.
 *   CLOSING    → Close has been requested, cleanup in progress.
 *   CLOSED     → Socket is closed, no further I/O possible.
 *
 * Reserved for future phases:
 *   HANDSHAKING → Application-level HELLO exchange (Phase 6).
 *   READY       → Handshake complete, full protocol available (Phase 6).
 */
public enum ConnectionState {
    CONNECTING,
    CONNECTED,
    HANDSHAKING,
    READY,
    CLOSING,
    CLOSED
}
