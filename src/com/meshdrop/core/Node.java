package com.meshdrop.core;

import com.meshdrop.connection.ConnectionManager;
import com.meshdrop.discovery.DiscoveryService;
import com.meshdrop.message.MessageService;
import com.meshdrop.message.PingService;
import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.network.TcpConnectionHandler;
import com.meshdrop.network.TcpServer;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.HandshakeService;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level orchestrator for a MeshDrop peer node.
 *
 * The Node coordinates:
 *   - NodeIdentity & NodeConfig
 *   - TcpServer (Inbound transport connections)
 *   - ConnectionManager (Outbound transport connections & discovery-triggered connection attempts)
 *   - DiscoveryService (UDP multicast discovery announcements & reception)
 *   - HandshakeService (Application identity negotiation)
 *   - PeerManager (Peer table, lifecycle states, duplicate connection deduplication)
 *   - MessageService (Sending/receiving text messages)
 *   - PingService (Protocol-level latency measurement)
 */
public class Node {
    private final NodeConfig config;
    private final NodeIdentity identity;
    private final PeerManager peerManager;
    private final com.meshdrop.storage.StorageManager storageManager;
    private final com.meshdrop.security.TrustStore trustStore;
    private final java.time.Instant startTime = java.time.Instant.now();
    private volatile NodeState state;

    /** All active raw connections, keyed by TcpConnection.getConnectionId(). */
    private final ConcurrentHashMap<Long, TcpConnection> connections = new ConcurrentHashMap<>();

    private TcpServer tcpServer;
    private ConnectionManager connectionManager;
    private DiscoveryService discoveryService;
    private HandshakeService handshakeService;
    private TcpConnectionHandler connectionHandler;
    private MessageService messageService;
    private PingService pingService;
    private com.meshdrop.transfer.FileTransferService fileTransferService;

    /** Guards stop() to ensure idempotent shutdown. */
    private volatile boolean shutdownStarted = false;

    public Node(NodeConfig config, NodeIdentity identity) {
        this.config = config;
        this.identity = identity;
        this.peerManager = new PeerManager(identity.nodeId());
        this.storageManager = new com.meshdrop.storage.StorageManager(
                config.storageDir(), config.downloadsDir(), config.tempDir());
        this.trustStore = new com.meshdrop.security.TrustStore(this.storageManager.getTrustDir());
        this.peerManager.setTrustStore(this.trustStore);
        this.state = NodeState.INITIALIZING;
    }

    /**
     * Starts all node subsystems.
     */
    public void start() throws IOException {
        printBanner();

        // 1. Initialize local filesystem storage
        this.storageManager.init();

        // 2. Initialize application services
        this.messageService = new MessageService(identity, peerManager);
        this.pingService = new PingService();
        java.nio.file.Path dlDir = storageManager.getDownloadsDir();
        java.nio.file.Path tmpDir = storageManager.getTempDir();
        this.fileTransferService = new com.meshdrop.transfer.FileTransferService(identity, peerManager, dlDir, tmpDir);
        this.fileTransferService.scanAndRegisterRecoverableTransfers();
        this.peerManager.setConnectionMigrationListener(this.fileTransferService::migrateConnection);

        // 3. Initialize application handshake and packet dispatcher
        this.handshakeService = new HandshakeService(
                identity,
                config.handshakeTimeoutMillis(),
                this::onPeerHandshakeCompleted
        );
        this.connectionHandler = new TcpConnectionHandler(handshakeService, this::onPacketReceived);

        // 3. Start TCP server (listens for inbound peer connections)
        this.tcpServer = new TcpServer(
                config.tcpPort(),
                connectionHandler,
                this::registerConnection
        );
        this.tcpServer.start();

        // 4. Initialize & start ConnectionManager (initiates outbound connections to discovered peers)
        this.connectionManager = new ConnectionManager(
                identity.nodeId(),
                config,
                peerManager,
                connectionHandler,
                this::registerConnection
        );
        this.peerManager.addListener(connectionManager);
        this.connectionManager.start();

        // 5. Start UDP Peer Discovery (if enabled)
        if (config.discoveryEnabled()) {
            try {
                this.discoveryService = new DiscoveryService(
                        identity,
                        tcpServer.getLocalPort(),
                        config.udpDiscoveryPort(),
                        config.discoveryMulticastGroup(),
                        config.discoveryIntervalMillis(),
                        this::onPeerDiscovered
                );
                this.discoveryService.start();
            } catch (IOException e) {
                Logger.warn("[DISCOVERY] UDP discovery initialization failed (running in TCP-only mode): " + e.getMessage());
            }
        }

        this.state = NodeState.RUNNING;

        Logger.info("Listening for connections on TCP port " + tcpServer.getLocalPort() + "...");
    }

    /**
     * Creates an outgoing TCP connection to a remote MeshDrop node and initiates handshake.
     *
     * @param host the remote IP address or hostname
     * @param port the remote TCP port
     * @return the established TcpConnection
     * @throws IOException if the connection cannot be established
     */
    public TcpConnection connectTo(String host, int port) throws IOException {
        if (state != NodeState.RUNNING) {
            throw new IOException("Node is not running (state=" + state + ")");
        }

        if (connectionManager != null && connectionManager.isRunning()) {
            return connectionManager.connectTo(host, port);
        }

        Logger.info("Connecting to " + host + ":" + port + "...");
        TcpConnection connection = TcpConnection.connectTo(host, port, config.connectionTimeoutMillis());
        Logger.info("Connected to " + connection.getRemoteAddress());

        // Initialise connection (starts receiver loop and sends HELLO packet with identity)
        registerConnection(connection);
        connectionHandler.handle(connection);

        return connection;
    }

    /**
     * Connects to a known discovered or disconnected peer by its UUID.
     *
     * @param peerId the remote peer's Node ID
     * @return the established or existing TcpConnection
     * @throws IOException if the peer is unknown, has no known address, or the connection fails
     */
    public TcpConnection connectToPeer(UUID peerId) throws IOException {
        if (state != NodeState.RUNNING) {
            throw new IOException("Node is not running (state=" + state + ")");
        }
        Peer peer = peerManager.findPeer(peerId)
                .orElseThrow(() -> new IllegalArgumentException("Peer not found: " + peerId));
        if (peer.isConnected() && peer.getConnection() != null && peer.getConnection().isOpen()) {
            return peer.getConnection();
        }
        if (peer.getAddress() == null) {
            throw new IOException("Peer " + peer.getDisplayName() + " has no known network address");
        }
        return connectTo(peer.getAddress().host(), peer.getAddress().tcpPort());
    }

    /**
     * Disconnects a peer by closing its active transport TCP connection.
     * The peer remains registered in PeerManager with its known address and identity.
     *
     * @param peerId the remote peer's Node ID
     * @return true if an active connection was found and closed, false otherwise
     * @throws IOException if an error occurs while closing the socket
     */
    public boolean disconnectPeer(UUID peerId) throws IOException {
        if (peerManager == null) return false;
        Peer peer = peerManager.findPeer(peerId)
                .orElseThrow(() -> new IllegalArgumentException("Peer not found: " + peerId));
        TcpConnection conn = peer.getConnection();
        if (conn != null && conn.isOpen()) {
            conn.close();
            return true;
        }
        return false;
    }

    // ========================================================================
    // Application-level convenience methods (used by CLI)
    // ========================================================================

    /**
     * Sends a text message to a connected peer.
     *
     * @param peerId the target peer's node ID
     * @param text the message text
     * @return CompletableFuture resolving to MessageDeliveryResult upon ACK or timeout
     */
    public CompletableFuture<com.meshdrop.message.MessageDeliveryResult> sendMessage(UUID peerId, String text) {
        if (messageService == null) {
            return CompletableFuture.completedFuture(
                    com.meshdrop.message.MessageDeliveryResult.error(
                            com.meshdrop.message.MessageDeliveryResult.Status.NODE_SHUTTING_DOWN, "Node is not running"));
        }
        return messageService.sendMessage(peerId, text);
    }

    /**
     * Sends a text message to a connected peer.
     *
     * @param peer the target peer
     * @param text the message text
     * @return CompletableFuture resolving to MessageDeliveryResult upon ACK or timeout
     */
    public CompletableFuture<com.meshdrop.message.MessageDeliveryResult> sendMessage(Peer peer, String text) {
        if (messageService == null) {
            return CompletableFuture.completedFuture(
                    com.meshdrop.message.MessageDeliveryResult.error(
                            com.meshdrop.message.MessageDeliveryResult.Status.NODE_SHUTTING_DOWN, "Node is not running"));
        }
        return messageService.sendMessage(peer, text);
    }

    /**
     * Sends a PING to a connected peer and returns a future with latency in ms.
     *
     * @param peerId the target peer's node ID
     * @return CompletableFuture resolving to latency in milliseconds
     * @throws IOException if the peer is not connected or sending fails
     */
    public CompletableFuture<Long> pingPeer(UUID peerId) throws IOException {
        Peer peer = peerManager.findPeer(peerId)
                .orElseThrow(() -> new IOException("Peer not found: " + peerId));
        return pingService.ping(peer);
    }

    // ========================================================================
    // Internal callbacks
    // ========================================================================

    /**
     * Callback invoked when a peer is discovered on the local network via UDP discovery.
     */
    private void onPeerDiscovered(NodeIdentity remoteIdentity, PeerAddress remoteAddress) {
        peerManager.registerDiscovered(remoteIdentity, remoteAddress);
    }

    /**
     * Callback invoked when a peer handshake completes successfully over TCP.
     * Promotes the peer to CONNECTED in PeerManager after verifying identity.
     */
    private void onPeerHandshakeCompleted(TcpConnection connection, NodeIdentity remoteIdentity) {
        // Enforce identity verification for outbound connections (rejects discovery identity mismatches)
        if (connectionManager != null && !connectionManager.verifyOutboundIdentity(connection, remoteIdentity)) {
            return;
        }

        connection.setRemoteIdentity(remoteIdentity);
        PeerAddress address;
        if (connection.getDirection() == ConnectionDirection.OUTBOUND) {
            address = PeerAddress.fromSocketAddress(connection.getRemoteAddress());
        } else {
            address = peerManager.findPeer(remoteIdentity.nodeId())
                    .map(Peer::getAddress)
                    .orElseGet(() -> PeerAddress.fromSocketAddress(connection.getRemoteAddress()));
        }
        peerManager.registerConnected(remoteIdentity, address, connection);
        Logger.info("[NODE] Established peer session with " + remoteIdentity.displayName() +
                " (" + remoteIdentity.nodeId() + ") on connection " + connection.getConnectionId());
    }

    /**
     * Callback invoked when a post-handshake Packet arrives on any tracked connection.
     * Routes packets to the appropriate application service by type.
     */
    private void onPacketReceived(TcpConnection connection, Packet packet) {
        Logger.fine("[" + connection.getConnectionId() + "] Packet " +
                packet.getType() + " (req=" + packet.getRequestId() + ", len=" + packet.getLength() + ")");

        switch (packet.getType()) {
            case MESSAGE, MESSAGE_ACK -> {
                if (messageService != null) {
                    messageService.handleIncomingPacket(connection, packet);
                }
            }
            case PONG -> {
                if (pingService != null) {
                    pingService.handlePong(packet);
                }
            }
            case FILE_OFFER, FILE_ACCEPT, FILE_REJECT, FILE_CHUNK, FILE_COMPLETE, FILE_ACK, FILE_ERROR, FILE_RESUME_REQUEST, FILE_RESUME_RESPONSE -> {
                if (fileTransferService != null) {
                    fileTransferService.handleIncomingPacket(connection, packet);
                }
            }
            default -> {
                // Future packet types
            }
        }
    }

    /**
     * Registers a connection in the tracking map and attaches close listener.
     */
    public void registerConnection(TcpConnection connection) {
        connections.put(connection.getConnectionId(), connection);
        connection.addCloseListener(this::onConnectionClosed);
        Logger.info("Connection " + connection.getConnectionId() +
                " registered (" + connections.size() + " active)");
    }

    private void onConnectionClosed(TcpConnection connection) {
        unregisterConnection(connection.getConnectionId());
    }

    /**
     * Removes a closed connection from the tracking map and updates PeerManager.
     */
    public void unregisterConnection(long connectionId) {
        TcpConnection removed = connections.remove(connectionId);
        if (removed != null) {
            Logger.info("Connection " + connectionId +
                    " unregistered (" + connections.size() + " active)");
            if (removed.getRemoteIdentity() != null) {
                peerManager.markDisconnected(removed.getRemoteIdentity().nodeId(), removed);
            }
        }
    }

    /**
     * Graceful shutdown. Stops subsystems in reverse startup order. Idempotent.
     */
    public void stop() {
        if (shutdownStarted) {
            return;
        }
        shutdownStarted = true;

        Logger.info("Shutting down node...");
        this.state = NodeState.SHUTTING_DOWN;

        // 1. Stop message service (cancels pending ACKs)
        if (messageService != null) {
            messageService.stop();
        }

        // 2. Stop file transfer service (aborts receivers and cancel transfers)
        if (fileTransferService != null) {
            fileTransferService.stop();
        }

        // 3. Stop discovery announcements and receiver
        if (discoveryService != null) {
            discoveryService.stop();
        }

        // 4. Stop connection manager (cancels active in-flight attempts, rejects new ones)
        if (connectionManager != null) {
            connectionManager.stop();
        }

        // 3. Stop accepting new connections
        if (tcpServer != null) {
            try {
                tcpServer.close();
            } catch (IOException e) {
                Logger.severe("Error closing TCP server", e);
            }
        }

        // 4. Close all active connections
        for (TcpConnection conn : connections.values()) {
            if (conn.getRemoteIdentity() != null) {
                peerManager.markDisconnected(conn.getRemoteIdentity().nodeId(), conn);
            }
            try {
                conn.close();
            } catch (IOException e) {
                Logger.severe("Error closing connection " + conn.getConnectionId(), e);
            }
        }
        connections.clear();

        this.state = NodeState.STOPPED;
        Logger.info("Node stopped.");
    }

    private void printBanner() {
        System.out.println();
        System.out.println("------------------------------------");
        System.out.println("        MeshDrop");
        System.out.println("------------------------------------");
        System.out.println();
        System.out.println("Node: " + identity.displayName());
        System.out.println("ID:   " + identity.nodeId());
        System.out.println("TCP:  " + config.tcpPort());
        System.out.println("UDP:  " + (config.discoveryEnabled() ? config.udpDiscoveryPort() : "DISABLED"));
        System.out.println();
        System.out.println("Starting services...");
        System.out.println();
        System.out.println("Discovery:  " + (config.discoveryEnabled() ? "RUNNING" : "DISABLED"));
        System.out.println("TCP server: RUNNING");
        System.out.println();
        System.out.println("Type 'help' for available commands.");
        System.out.println();
    }

    // ========================================================================
    // Accessors (read-only service access for CLI layer)
    // ========================================================================

    public NodeConfig getConfig() {
        return config;
    }

    public NodeIdentity getIdentity() {
        return identity;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public DiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public PingService getPingService() {
        return pingService;
    }

    public NodeState getState() {
        return state;
    }

    public TcpServer getTcpServer() {
        return tcpServer;
    }

    public HandshakeService getHandshakeService() {
        return handshakeService;
    }

    public Collection<TcpConnection> getActiveConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public com.meshdrop.transfer.FileTransferService getFileTransferService() {
        return fileTransferService;
    }

    public com.meshdrop.transfer.Transfer startFileTransfer(UUID peerId, java.nio.file.Path filePath) throws IOException {
        if (fileTransferService == null) {
            throw new IOException("Node is not running");
        }
        Peer peer = peerManager.findPeer(peerId)
                .orElseThrow(() -> new IllegalArgumentException("Peer not found: " + peerId));
        return fileTransferService.startFileTransfer(peer, filePath);
    }

    public CompletableFuture<com.meshdrop.transfer.Transfer> sendFile(UUID peerId, java.nio.file.Path filePath) {
        if (fileTransferService == null) {
            return CompletableFuture.failedFuture(new IOException("Node is not running"));
        }
        Peer peer = peerManager.findPeer(peerId)
                .orElseThrow(() -> new IllegalArgumentException("Peer not found: " + peerId));
        return fileTransferService.sendFile(peer, filePath);
    }

    public CompletableFuture<com.meshdrop.transfer.Transfer> resumeTransfer(UUID transferId) {
        if (fileTransferService == null) {
            return CompletableFuture.failedFuture(new IOException("Node is not running"));
        }
        var transfer = fileTransferService.getTransferManager().getTransfer(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
        UUID targetPeerId = transfer.getDirection() == com.meshdrop.transfer.TransferDirection.UPLOAD
                ? transfer.getFileMetadata().recipientId()
                : transfer.getFileMetadata().senderId();
        Peer peer = peerManager.findPeer(targetPeerId)
                .orElseThrow(() -> new IllegalStateException("Peer " + targetPeerId + " is not registered"));
        if (!peer.isConnected()) {
            if (connectionManager != null && peer.getAddress() != null) {
                try {
                    connectionManager.tryConnect(peer);
                    long deadline = System.currentTimeMillis() + config.connectionTimeoutMillis();
                    while (!peer.isConnected() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(new IOException("Failed to reconnect to peer: " + e.getMessage(), e));
                }
            }
            if (!peer.isConnected()) {
                return CompletableFuture.failedFuture(new IOException("Peer " + peer.getDisplayName() + " is not connected"));
            }
        }
        return fileTransferService.resumeTransfer(transferId, peer);
    }

    public void cancelTransfer(UUID transferId) {
        if (fileTransferService != null) {
            fileTransferService.cancelTransfer(transferId);
        }
    }

    public CompletableFuture<com.meshdrop.transfer.Transfer> retryTransfer(UUID transferId) {
        if (fileTransferService == null) {
            return CompletableFuture.failedFuture(new IOException("Node is not running"));
        }
        var transfer = fileTransferService.getTransferManager().getTransfer(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
        if (transfer.getDirection() != com.meshdrop.transfer.TransferDirection.UPLOAD) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Retry is only supported for outbound transfers"));
        }
        if (transfer.getLocalPath() == null || !java.nio.file.Files.isRegularFile(transfer.getLocalPath())) {
            return CompletableFuture.failedFuture(new IOException("Local source file is missing: " + transfer.getLocalPath()));
        }
        transfer.setErrorMessage(null);
        return resumeTransfer(transferId);
    }

    public boolean removeTransfer(UUID transferId) {
        if (fileTransferService == null) return false;
        var transferOpt = fileTransferService.getTransferManager().getTransfer(transferId);
        if (transferOpt.isEmpty()) return false;
        var transfer = transferOpt.get();
        if (!transfer.getState().isTerminal() && transfer.getState() != com.meshdrop.transfer.TransferState.CANCELLED) {
            throw new IllegalStateException("Cannot remove an active transfer from history; cancel it first.");
        }
        return fileTransferService.getTransferManager().removeTransfer(transferId);
    }

    public void interruptTransfer(UUID transferId) {
        if (fileTransferService != null) {
            fileTransferService.interruptTransfer(transferId);
        }
    }

    public com.meshdrop.storage.StorageManager getStorageManager() {
        return storageManager;
    }

    public com.meshdrop.security.TrustStore getTrustStore() {
        return trustStore;
    }

    public java.time.Instant getStartTime() {
        return startTime;
    }

    public long getUptimeMillis() {
        return java.time.Duration.between(startTime, java.time.Instant.now()).toMillis();
    }

    public void trustPeer(UUID peerId, String alias) {
        Peer peer = peerManager.findPeer(peerId).orElse(null);
        com.meshdrop.security.IdentityFingerprint fp = peer != null ? peer.getFingerprint() : null;
        trustStore.trust(peerId, fp, alias != null ? alias : (peer != null ? peer.getDisplayName() : "peer"));
        if (peer != null) {
            peer.setTrustDecision(com.meshdrop.security.TrustDecision.TRUSTED);
        }
    }

    public void untrustPeer(UUID peerId) {
        trustStore.untrust(peerId);
        peerManager.findPeer(peerId).ifPresent(p -> p.setTrustDecision(com.meshdrop.security.TrustDecision.UNTRUSTED));
    }

    public void blockPeer(UUID peerId, String reason) {
        Peer peer = peerManager.findPeer(peerId).orElse(null);
        com.meshdrop.security.IdentityFingerprint fp = peer != null ? peer.getFingerprint() : null;
        trustStore.block(peerId, fp, reason != null ? reason : "blocked by operator");
        if (peer != null) {
            peer.setTrustDecision(com.meshdrop.security.TrustDecision.BLOCKED);
            if (peer.getConnection() != null) {
                try {
                    peer.getConnection().close();
                } catch (IOException ignored) {}
            }
        }
    }

    public com.meshdrop.transfer.RecoveryManager getRecoveryManager() {
        return fileTransferService != null ? fileTransferService.getRecoveryManager() : null;
    }
}
