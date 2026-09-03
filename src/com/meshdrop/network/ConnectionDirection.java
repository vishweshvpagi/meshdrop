package com.meshdrop.network;

/**
 * Represents the establishment direction of a TCP connection from the local node's perspective.
 */
public enum ConnectionDirection {
    /** Connection was accepted from a remote peer via ServerSocket. */
    INBOUND,

    /** Connection was initiated to a remote peer via Socket.connect(). */
    OUTBOUND
}
