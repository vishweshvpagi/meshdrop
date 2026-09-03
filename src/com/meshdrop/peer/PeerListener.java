package com.meshdrop.peer;

/**
 * Listener interface for peer lifecycle events in PeerManager.
 */
public interface PeerListener {
    default void onPeerDiscovered(Peer peer) {}
    default void onPeerConnected(Peer peer) {}
    default void onPeerDisconnected(Peer peer) {}
}
