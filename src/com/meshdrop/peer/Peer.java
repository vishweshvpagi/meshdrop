package com.meshdrop.peer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulates the identity, address, state, and active connection for a remote MeshDrop node.
 *
 * Distinct from TcpConnection:
 *   - TcpConnection is a transport stream object.
 *   - Peer represents a known remote node in the mesh network.
 */
public class Peer {
    private final NodeIdentity identity;
    private volatile PeerAddress address;
    private volatile PeerState state;
    private volatile TcpConnection connection;
    private volatile Instant lastSeen;
    private volatile Instant connectedAt;
    private volatile com.meshdrop.security.TrustDecision trustDecision = com.meshdrop.security.TrustDecision.UNTRUSTED;

    public Peer(NodeIdentity identity, PeerAddress address, PeerState state) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.address = Objects.requireNonNull(address, "address must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.lastSeen = Instant.now();
        this.connectedAt = (state == PeerState.CONNECTED) ? Instant.now() : null;
    }

    public Peer(NodeIdentity identity, PeerAddress address) {
        this(identity, address, PeerState.DISCOVERED);
    }

    public static Peer discovered(NodeIdentity identity, PeerAddress address) {
        return new Peer(identity, address, PeerState.DISCOVERED);
    }

    public static Peer connected(NodeIdentity identity, PeerAddress address, TcpConnection connection) {
        Peer peer = new Peer(identity, address, PeerState.CONNECTED);
        peer.setConnection(connection);
        return peer;
    }

    public NodeIdentity getIdentity() {
        return identity;
    }

    public UUID getNodeId() {
        return identity.nodeId();
    }

    public String getDisplayName() {
        return identity.displayName();
    }

    public PeerAddress getAddress() {
        return address;
    }

    public PeerState getState() {
        return state;
    }

    public TcpConnection getConnection() {
        return connection;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public boolean isConnected() {
        return state == PeerState.CONNECTED && connection != null && connection.isOpen();
    }

    public void setAddress(PeerAddress address) {
        this.address = Objects.requireNonNull(address, "address must not be null");
    }

    public void setState(PeerState state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        if (state == PeerState.CONNECTED && this.connectedAt == null) {
            this.connectedAt = Instant.now();
        } else if (state == PeerState.DISCONNECTED) {
            this.connectedAt = null;
        }
    }

    public void setConnection(TcpConnection connection) {
        this.connection = connection;
    }

    public void updateLastSeen() {
        this.lastSeen = Instant.now();
    }

    public com.meshdrop.security.TrustDecision getTrustDecision() {
        return trustDecision;
    }

    public void setTrustDecision(com.meshdrop.security.TrustDecision trustDecision) {
        this.trustDecision = Objects.requireNonNull(trustDecision, "trustDecision must not be null");
    }

    public com.meshdrop.security.IdentityFingerprint getFingerprint() {
        return identity.fingerprint();
    }

    public java.security.PublicKey getPublicKey() {
        return identity.publicKey();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Peer peer)) return false;
        // Peer identity is strictly determined by Node ID
        return identity.nodeId().equals(peer.identity.nodeId());
    }

    @Override
    public int hashCode() {
        return identity.nodeId().hashCode();
    }

    @Override
    public String toString() {
        return "Peer{" +
                "id=" + identity.nodeId() +
                ", name='" + identity.displayName() + '\'' +
                ", address=" + address +
                ", state=" + state +
                '}';
    }
}
