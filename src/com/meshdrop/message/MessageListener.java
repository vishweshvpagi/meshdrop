package com.meshdrop.message;

/**
 * Event listener callback invoked when a validated Message arrives from a verified remote peer.
 */
@FunctionalInterface
public interface MessageListener {
    /**
     * Called when an authenticated, non-duplicate Message addressed to this node is received.
     *
     * @param message the received Message domain object
     */
    void onMessageReceived(Message message);
}
