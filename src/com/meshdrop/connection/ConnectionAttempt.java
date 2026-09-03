package com.meshdrop.connection;

import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;

import java.time.Instant;
import java.util.Objects;

/**
 * Tracks the state and lifecycle of an in-flight outgoing TCP connection attempt.
 */
public class ConnectionAttempt {
    private final Peer peer;
    private final Instant startedAt;
    private volatile TcpConnection connection;

    public ConnectionAttempt(Peer peer) {
        this.peer = Objects.requireNonNull(peer, "peer must not be null");
        this.startedAt = Instant.now();
    }

    public Peer getPeer() {
        return peer;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public TcpConnection getConnection() {
        return connection;
    }

    public void setConnection(TcpConnection connection) {
        this.connection = connection;
    }

    @Override
    public String toString() {
        return "ConnectionAttempt{" +
                "peer=" + peer.getDisplayName() + " (" + peer.getNodeId() + ")" +
                ", startedAt=" + startedAt +
                ", hasConnection=" + (connection != null) +
                '}';
    }
}
