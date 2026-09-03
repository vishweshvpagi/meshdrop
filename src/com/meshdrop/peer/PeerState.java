package com.meshdrop.peer;

/**
 * High-level lifecycle states of a remote MeshDrop peer relationship.
 *
 * States:
 *   DISCOVERED   -> Peer announced on the local network (e.g. via UDP), but no active TCP connection exists.
 *   CONNECTING   -> An outgoing TCP connection or handshake is currently in progress.
 *   CONNECTED    -> Peer has completed a mutual application handshake and has an active TCP connection.
 *   DISCONNECTED -> Peer was previously known/connected but currently has no active TCP connection.
 */
public enum PeerState {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}
