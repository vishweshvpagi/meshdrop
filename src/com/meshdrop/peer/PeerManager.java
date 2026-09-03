package com.meshdrop.peer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the lifecycle, state, and active transport connections for all known remote peers in the mesh.
 *
 * Distinct from transport layer:
 *   - TcpConnection owns the raw socket stream.
 *   - PeerManager maintains peer records, addresses, and deterministic connection deduplication.
 */
public class PeerManager {

    private final UUID localNodeId;
    private final ConcurrentHashMap<UUID, Peer> peers = new ConcurrentHashMap<>();
    private final List<PeerListener> listeners = new CopyOnWriteArrayList<>();
    private volatile com.meshdrop.security.TrustStore trustStore;

    public PeerManager(UUID localNodeId) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId must not be null");
    }

    public PeerManager() {
        this(UUID.randomUUID());
    }

    public com.meshdrop.security.TrustStore getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(com.meshdrop.security.TrustStore trustStore) {
        this.trustStore = trustStore;
    }

    public UUID getLocalNodeId() {
        return localNodeId;
    }

    public void addListener(PeerListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(PeerListener listener) {
        listeners.remove(listener);
    }

    /**
     * Compares two UUIDs using unsigned lexicographical 128-bit ordering (matching RFC 4122).
     */
    public static int compareUuids(UUID u1, UUID u2) {
        int cmp = Long.compareUnsigned(u1.getMostSignificantBits(), u2.getMostSignificantBits());
        if (cmp != 0) {
            return cmp;
        }
        return Long.compareUnsigned(u1.getLeastSignificantBits(), u2.getLeastSignificantBits());
    }

    /**
     * Registers a peer discovered on the network (e.g. via UDP discovery) before establishing a TCP connection.
     *
     * @param identity remote peer identity
     * @param address network address where peer is reachable
     * @return the Peer instance
     */
    public Peer registerDiscovered(NodeIdentity identity, PeerAddress address) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(address, "address must not be null");

        if (identity.nodeId().equals(localNodeId)) {
            return null; // Ignore self
        }

        return peers.compute(identity.nodeId(), (id, existing) -> {
            if (existing == null) {
                Peer peer = Peer.discovered(identity, address);
                if (trustStore != null) {
                    peer.setTrustDecision(trustStore.getTrustDecision(identity.nodeId(), identity.fingerprint()));
                }
                Logger.info("[PEER] Discovered new peer: " + identity.displayName() + " (" + id + ") at " + address +
                        (peer.getTrustDecision() != null ? " [" + peer.getTrustDecision() + "]" : ""));
                notifyDiscovered(peer);
                return peer;
            } else {
                existing.setAddress(address);
                existing.updateLastSeen();
                if (trustStore != null) {
                    existing.setTrustDecision(trustStore.getTrustDecision(identity.nodeId(), identity.fingerprint()));
                }
                if (existing.getState() == PeerState.DISCONNECTED) {
                    existing.setState(PeerState.DISCOVERED);
                    notifyDiscovered(existing);
                }
                return existing;
            }
        });
    }

    /**
     * Registers or updates a peer upon successful completion of the application handshake.
     * Applies deterministic duplicate connection resolution if multiple connections exist.
     *
     * @param identity remote peer identity
     * @param address network address of the connection
     * @param connection active, verified TcpConnection (in READY state)
     * @return the connected Peer instance
     */
    public synchronized Peer registerConnected(NodeIdentity identity, PeerAddress address, TcpConnection connection) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(connection, "connection must not be null");

        if (identity.nodeId().equals(localNodeId)) {
            throw new IllegalArgumentException("Self-connection cannot be registered as a peer");
        }

        Peer peer = peers.computeIfAbsent(identity.nodeId(), id -> {
            return new Peer(identity, address, PeerState.CONNECTING);
        });

        TcpConnection existingConn = peer.getConnection();

        if (existingConn != null && existingConn.isOpen() && existingConn != connection) {
            // Duplicate connection detected! Resolve deterministically:
            // Compare local UUID vs remote UUID using unsigned lexicographical comparison.
            int cmp = compareUuids(localNodeId, identity.nodeId());
            TcpConnection keptConn;
            TcpConnection rejectedConn;

            if (cmp < 0) {
                // Local UUID is smaller: keep OUTBOUND (connection initiated by Local)
                if (connection.getDirection() == ConnectionDirection.OUTBOUND) {
                    keptConn = connection;
                    rejectedConn = existingConn;
                } else {
                    keptConn = existingConn;
                    rejectedConn = connection;
                }
            } else {
                // Remote UUID is smaller: keep INBOUND (connection initiated by Remote)
                if (connection.getDirection() == ConnectionDirection.INBOUND) {
                    keptConn = connection;
                    rejectedConn = existingConn;
                } else {
                    keptConn = existingConn;
                    rejectedConn = connection;
                }
            }

            Logger.info("[PEER] Duplicate connection for " + identity.displayName() + " (" + identity.nodeId() +
                    "): keeping " + keptConn.getDirection() + " (id=" + keptConn.getConnectionId() +
                    "), closing " + rejectedConn.getDirection() + " (id=" + rejectedConn.getConnectionId() + ")");

            try {
                rejectedConn.close();
            } catch (IOException ignored) {}

            peer.setConnection(keptConn);
        } else {
            peer.setConnection(connection);
        }

        if (connection.getDirection() == ConnectionDirection.OUTBOUND || peer.getAddress() == null) {
            peer.setAddress(address);
        }
        if (trustStore != null) {
            peer.setTrustDecision(trustStore.getTrustDecision(identity.nodeId(), identity.fingerprint()));
        }
        peer.setState(PeerState.CONNECTED);
        peer.updateLastSeen();

        Logger.info("[PEER] Peer CONNECTED: " + identity.displayName() + " (" + identity.nodeId() +
                ") at " + address + " via connection " + peer.getConnection().getConnectionId() +
                (peer.getTrustDecision() != null ? " [" + peer.getTrustDecision() + "]" : "") +
                (peer.getFingerprint() != null ? " fp=" + peer.getFingerprint() : ""));

        notifyConnected(peer);
        return peer;
    }

    /**
     * Updates peer state to DISCONNECTED when its active transport connection closes.
     * The Peer object is preserved so discovery, address, and identity history remain available.
     *
     * @param nodeId ID of the disconnected peer
     * @param closedConnection the specific TcpConnection that closed
     */
    public synchronized void markDisconnected(UUID nodeId, TcpConnection closedConnection) {
        if (nodeId == null) return;

        Peer peer = peers.get(nodeId);
        if (peer != null) {
            if (closedConnection == null || peer.getConnection() == closedConnection) {
                peer.setState(PeerState.DISCONNECTED);
                peer.setConnection(null);
                peer.updateLastSeen();

                Logger.info("[PEER] Peer DISCONNECTED: " + peer.getDisplayName() + " (" + nodeId + ")");
                notifyDisconnected(peer);
            }
        }
    }

    public Optional<Peer> findPeer(UUID nodeId) {
        if (nodeId == null) return Optional.empty();
        return Optional.ofNullable(peers.get(nodeId));
    }

    /**
     * Searches for peers matching a string identifier.
     * Supports:
     *   1. Exact UUID string (e.g. "b6ba6915-1234-5678-9abc-def012345678")
     *   2. UUID prefix (e.g. "b6ba" matches if unambiguous)
     *   3. Exact display name (case-insensitive, e.g. "Laptop")
     *
     * @param identifier the search string
     * @return list of matching peers (empty = not found, 1 = exact match, >1 = ambiguous)
     */
    public List<Peer> findPeersByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return List.of();
        }

        String trimmed = identifier.trim();

        // 1. Try exact UUID match
        try {
            UUID exactId = UUID.fromString(trimmed);
            Peer exact = peers.get(exactId);
            if (exact != null) {
                return List.of(exact);
            }
        } catch (IllegalArgumentException ignored) {
            // Not a valid full UUID, continue to prefix/name matching
        }

        // 2. Try UUID prefix match
        String lowerTrimmed = trimmed.toLowerCase();
        List<Peer> prefixMatches = peers.values().stream()
                .filter(p -> p.getNodeId().toString().toLowerCase().startsWith(lowerTrimmed))
                .toList();

        if (prefixMatches.size() == 1) {
            return prefixMatches;
        }
        if (prefixMatches.size() > 1) {
            return prefixMatches; // Ambiguous
        }

        // 3. Try exact display name match (case-insensitive)
        List<Peer> nameMatches = peers.values().stream()
                .filter(p -> p.getDisplayName().equalsIgnoreCase(trimmed))
                .toList();

        return nameMatches;
    }

    /**
     * Returns an immutable snapshot of all known peers.
     */
    public List<Peer> getPeers() {
        return List.copyOf(peers.values());
    }

    /**
     * Returns an immutable snapshot of all currently CONNECTED peers.
     */
    public List<Peer> getConnectedPeers() {
        return peers.values().stream()
                .filter(Peer::isConnected)
                .toList();
    }

    public void removePeer(UUID nodeId) {
        if (nodeId != null) {
            Peer removed = peers.remove(nodeId);
            if (removed != null && removed.getConnection() != null) {
                try {
                    removed.getConnection().close();
                } catch (IOException ignored) {}
            }
        }
    }

    public int getPeerCount() {
        return peers.size();
    }

    public int getConnectedPeerCount() {
        return (int) peers.values().stream().filter(Peer::isConnected).count();
    }

    private void notifyDiscovered(Peer peer) {
        for (PeerListener listener : listeners) {
            try { listener.onPeerDiscovered(peer); } catch (Exception ignored) {}
        }
    }

    private void notifyConnected(Peer peer) {
        for (PeerListener listener : listeners) {
            try { listener.onPeerConnected(peer); } catch (Exception ignored) {}
        }
    }

    private void notifyDisconnected(Peer peer) {
        for (PeerListener listener : listeners) {
            try { listener.onPeerDisconnected(peer); } catch (Exception ignored) {}
        }
    }
}
