package com.meshdrop.discovery;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.PeerAddress;

/**
 * Callback listener invoked when a remote peer is discovered on the local network via UDP discovery.
 */
@FunctionalInterface
public interface DiscoveryListener {
    /**
     * Invoked upon receiving and validating a discovery beacon from a remote peer.
     *
     * @param identity validated identity of the discovered remote node
     * @param address network address where the remote node's TCP server is reachable
     */
    void onPeerDiscovered(NodeIdentity identity, PeerAddress address);
}
