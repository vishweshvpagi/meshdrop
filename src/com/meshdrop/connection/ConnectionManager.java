package com.meshdrop.connection;

import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.network.TcpConnectionHandler;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerListener;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.peer.PeerState;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages outgoing TCP connection establishment to discovered peers.
 *
 * Responsibilities:
 *   1. Subscribes to PeerManager discovery events and initiates outgoing TCP connections.
 *   2. Deduplicates connection attempts so multiple UDP beacons do not trigger parallel connections.
 *   3. Enforces connection timeout using NodeConfig.connectionTimeoutMillis().
 *   4. Spawns Java 26 virtual threads for non-blocking asynchronous connection attempts.
 *   5. Tracks expected NodeIdentity to detect and reject discovery identity mismatches upon handshake.
 *   6. Cleans up in-flight attempts on success, timeout, network failure, or shutdown.
 */
public class ConnectionManager implements PeerListener {

    private final UUID localNodeId;
    private final NodeConfig config;
    private final PeerManager peerManager;
    private final TcpConnectionHandler connectionHandler;
    private final Consumer<TcpConnection> connectionRegistrar;

    /** In-flight outgoing connection attempts keyed by remote peer Node ID. */
    private final ConcurrentHashMap<UUID, ConnectionAttempt> inFlightAttempts = new ConcurrentHashMap<>();

    /** Maps connectionId to expected remote Node ID for outbound identity validation. */
    private final ConcurrentHashMap<Long, UUID> outboundExpectedIdentities = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public ConnectionManager(
            UUID localNodeId,
            NodeConfig config,
            PeerManager peerManager,
            TcpConnectionHandler connectionHandler,
            Consumer<TcpConnection> connectionRegistrar
    ) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager must not be null");
        this.connectionHandler = Objects.requireNonNull(connectionHandler, "connectionHandler must not be null");
        this.connectionRegistrar = Objects.requireNonNull(connectionRegistrar, "connectionRegistrar must not be null");
    }

    /**
     * Starts the connection manager and attaches to PeerManager events.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        this.running = true;
        this.peerManager.addListener(this);
        Logger.info("[CONNECTION] Connection manager started.");
    }

    /**
     * Gracefully stops the connection manager, canceling active attempts and rejecting future ones.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        this.running = false;
        this.peerManager.removeListener(this);

        // Clean up in-flight attempts
        for (ConnectionAttempt attempt : inFlightAttempts.values()) {
            TcpConnection conn = attempt.getConnection();
            if (conn != null && conn.isOpen()) {
                try {
                    conn.close();
                } catch (IOException ignored) {}
            }
        }
        inFlightAttempts.clear();
        outboundExpectedIdentities.clear();

        Logger.info("[CONNECTION] Connection manager stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Evaluates a discovered or rediscovered peer and initiates an outgoing TCP connection if appropriate.
     *
     * @param peer the discovered Peer
     */
    public void tryConnect(Peer peer) {
        if (!running || peer == null) {
            return;
        }

        UUID targetNodeId = peer.getNodeId();

        // 1. Ignore self-connection attempts
        if (targetNodeId.equals(localNodeId)) {
            return;
        }

        // 2. Ignore if already connected or has active open connection
        if (peer.getState() == PeerState.CONNECTED || peer.isConnected()) {
            return;
        }

        // 3. Ignore if already in CONNECTING state or an attempt is already in flight
        if (peer.getState() == PeerState.CONNECTING) {
            return;
        }

        ConnectionAttempt attempt = new ConnectionAttempt(peer);
        if (inFlightAttempts.putIfAbsent(targetNodeId, attempt) != null) {
            // Another connection attempt is already in flight for this peer (storm deduplication)
            return;
        }

        // Update peer state to CONNECTING
        peer.setState(PeerState.CONNECTING);
        Logger.info("[CONNECTION] Connecting to peer " + peer.getDisplayName() + " (" + targetNodeId + ") at " + peer.getAddress() + "...");

        // Launch connection attempt on a dedicated virtual thread
        Thread.ofVirtual()
                .name("conn-attempt-" + targetNodeId)
                .start(() -> executeConnectionAttempt(peer, attempt));
    }

    /**
     * Executes the blocking TCP socket connection and initialises the protocol handshake.
     */
    private void executeConnectionAttempt(Peer peer, ConnectionAttempt attempt) {
        UUID targetNodeId = peer.getNodeId();
        PeerAddress address = peer.getAddress();
        TcpConnection connection = null;

        try {
            if (!running) {
                cleanupAttempt(targetNodeId, peer, null);
                return;
            }

            int timeoutMs = config.connectionTimeoutMillis();
            connection = TcpConnection.connectTo(address.host(), address.tcpPort(), timeoutMs);
            attempt.setConnection(connection);

            Logger.info("[CONNECTION] TCP connection established (id=" + connection.getConnectionId() + ") to " + address);

            if (!running) {
                connection.close();
                cleanupAttempt(targetNodeId, peer, connection);
                return;
            }

            // Track expected remote identity for outbound identity verification
            outboundExpectedIdentities.put(connection.getConnectionId(), targetNodeId);

            // Register connection with Node
            connectionRegistrar.accept(connection);

            // Attach close listener to clean up in-flight attempt if connection terminates prematurely
            connection.addCloseListener(conn -> {
                outboundExpectedIdentities.remove(conn.getConnectionId());
                if (inFlightAttempts.remove(targetNodeId) != null) {
                    if (peer.getState() == PeerState.CONNECTING && !peer.isConnected()) {
                        peer.setState(PeerState.DISCONNECTED);
                        Logger.info("[CONNECTION] Connection " + conn.getConnectionId() +
                                " closed before handshake completed. Peer " + peer.getDisplayName() + " marked DISCONNECTED.");
                    }
                }
            });

            Logger.info("[CONNECTION] Starting handshake with " + address + " (expected peer: " + targetNodeId + ")...");

            // Start packet receiver loop and send HELLO
            connectionHandler.handle(connection);

        } catch (SocketTimeoutException e) {
            Logger.warn("[CONNECTION] Connection timeout connecting to " + address + " after " +
                    config.connectionTimeoutMillis() + "ms: " + e.getMessage());
            cleanupAttempt(targetNodeId, peer, connection);
        } catch (ConnectException e) {
            Logger.warn("[CONNECTION] Connection refused connecting to " + address + ": " + e.getMessage());
            cleanupAttempt(targetNodeId, peer, connection);
        } catch (IOException e) {
            Logger.warn("[CONNECTION] Connection failed connecting to " + address + ": " + e.getMessage());
            cleanupAttempt(targetNodeId, peer, connection);
        } catch (Exception e) {
            Logger.severe("[CONNECTION] Unexpected error during connection attempt to " + address + ": " + e.getMessage(), e);
            cleanupAttempt(targetNodeId, peer, connection);
        }
    }

    /**
     * Validates that the remote node's authenticated handshake identity matches the expected
     * NodeIdentity from discovery for outbound connections.
     *
     * Discovery beacons are untrusted hints; the handshake identity is authoritative.
     *
     * @param connection the active TcpConnection
     * @param remoteIdentity the authenticated remote identity from HELLO/HELLO_RESPONSE
     * @return true if identity matches (or connection was INBOUND), false if mismatch
     */
    public boolean verifyOutboundIdentity(TcpConnection connection, NodeIdentity remoteIdentity) {
        if (connection.getDirection() != ConnectionDirection.OUTBOUND) {
            return true;
        }

        UUID expectedNodeId = outboundExpectedIdentities.remove(connection.getConnectionId());
        if (expectedNodeId == null) {
            // Connection was not tracked as a discovery-triggered outbound attempt
            return true;
        }

        UUID actualNodeId = remoteIdentity.nodeId();
        if (!expectedNodeId.equals(actualNodeId)) {
            Logger.warn("[CONNECTION] Identity mismatch on connection " + connection.getConnectionId() +
                    ": expected Node ID " + expectedNodeId + " but remote handshake identified as " +
                    remoteIdentity.displayName() + " (" + actualNodeId + "). Closing rejected connection...");

            try {
                connection.close();
            } catch (IOException ignored) {}

            inFlightAttempts.remove(expectedNodeId);
            Peer expectedPeer = peerManager.findPeer(expectedNodeId).orElse(null);
            if (expectedPeer != null && expectedPeer.getState() == PeerState.CONNECTING) {
                expectedPeer.setState(PeerState.DISCONNECTED);
            }
            return false;
        }

        // Expected identity verified
        inFlightAttempts.remove(expectedNodeId);
        Logger.info("[CONNECTION] Handshake READY and identity verified for peer: " +
                remoteIdentity.displayName() + " (" + actualNodeId + ")");
        return true;
    }

    /**
     * Programmatic outgoing connection establishment to a specific host and port.
     */
    public TcpConnection connectTo(String host, int port, UUID expectedNodeId) throws IOException {
        if (!running) {
            throw new IOException("ConnectionManager is not running");
        }

        Logger.info("[CONNECTION] Connecting to " + host + ":" + port + "...");
        TcpConnection connection = TcpConnection.connectTo(host, port, config.connectionTimeoutMillis());
        Logger.info("[CONNECTION] TCP connection established (id=" + connection.getConnectionId() + ") to " + host + ":" + port);

        if (expectedNodeId != null) {
            outboundExpectedIdentities.put(connection.getConnectionId(), expectedNodeId);
        }

        connectionRegistrar.accept(connection);
        connectionHandler.handle(connection);
        return connection;
    }

    public TcpConnection connectTo(String host, int port) throws IOException {
        return connectTo(host, port, null);
    }

    private void cleanupAttempt(UUID targetNodeId, Peer peer, TcpConnection connection) {
        inFlightAttempts.remove(targetNodeId);
        if (connection != null) {
            outboundExpectedIdentities.remove(connection.getConnectionId());
            try {
                connection.close();
            } catch (IOException ignored) {}
        }
        if (peer != null && peer.getState() == PeerState.CONNECTING) {
            peer.setState(PeerState.DISCONNECTED);
        }
    }

    @Override
    public void onPeerDiscovered(Peer peer) {
        tryConnect(peer);
    }

    @Override
    public void onPeerConnected(Peer peer) {
        if (peer != null) {
            inFlightAttempts.remove(peer.getNodeId());
            Logger.info("[CONNECTION] Peer CONNECTED: " + peer.getDisplayName() + " (" + peer.getNodeId() + ")");
        }
    }

    @Override
    public void onPeerDisconnected(Peer peer) {
        if (peer != null) {
            inFlightAttempts.remove(peer.getNodeId());
        }
    }

    public boolean isAttemptInFlight(UUID nodeId) {
        return inFlightAttempts.containsKey(nodeId);
    }

    public int getInFlightAttemptCount() {
        return inFlightAttempts.size();
    }

    public Map<UUID, ConnectionAttempt> getInFlightAttempts() {
        return Collections.unmodifiableMap(inFlightAttempts);
    }
}
