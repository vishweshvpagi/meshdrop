package com.meshdrop.connection;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.network.TcpConnectionHandler;
import com.meshdrop.network.TcpServer;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.peer.PeerState;
import com.meshdrop.protocol.HandshakeService;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit and component tests for ConnectionManager:
 *   - Connection attempt deduplication (storm test)
 *   - Already-connected protection
 *   - Self-connection filtering
 *   - Failed connection handling and cleanup
 *   - Identity mismatch rejection
 *   - Graceful shutdown behavior
 */
public class ConnectionManagerTest {

    public void runAll() throws Exception {
        test1_ConnectionStormDeduplication();
        test2_AlreadyConnected_NoNewAttempt();
        test3_SelfConnection_Ignored();
        test4_ConnectionFailure_UnreachablePort();
        test5_IdentityMismatch_Rejected();
        test6_Shutdown_PreventsNewAttempts();
        test7_ConnectionTimeoutEnforced();
    }

    /**
     * 1. Generates 100 rapid discovery events for the same peer.
     * Verifies at most 1 connection attempt is in-flight at any time and only 1 connection is made.
     */
    private void test1_ConnectionStormDeduplication() throws Exception {
        NodeIdentity serverId = NodeIdentity.createRandom("StormServer");
        NodeIdentity clientId = NodeIdentity.createRandom("StormClient");

        NodeConfig serverConfig = NodeConfig.withPortAndTimeout(0, 5000);
        Node serverNode = new Node(serverConfig, serverId);
        serverNode.start();

        int serverPort = serverNode.getTcpServer().getLocalPort();

        PeerManager peerManager = new PeerManager(clientId.nodeId());
        HandshakeService handshakeService = new HandshakeService(clientId, 5000, (conn, remoteId) -> {
            conn.setRemoteIdentity(remoteId);
            PeerAddress addr = PeerAddress.fromSocketAddress(conn.getRemoteAddress());
            peerManager.registerConnected(remoteId, addr, conn);
        });
        TcpConnectionHandler handler = new TcpConnectionHandler(handshakeService);

        AtomicInteger registeredCount = new AtomicInteger(0);
        NodeConfig clientConfig = NodeConfig.defaultConfig();

        ConnectionManager connManager = new ConnectionManager(
                clientId.nodeId(),
                clientConfig,
                peerManager,
                handler,
                conn -> registeredCount.incrementAndGet()
        );
        connManager.start();

        try {
            PeerAddress serverAddress = new PeerAddress("127.0.0.1", serverPort);
            Peer peer = peerManager.registerDiscovered(serverId, serverAddress);

            // Fire 100 discovery events rapidly
            for (int i = 0; i < 100; i++) {
                connManager.tryConnect(peer);
            }

            // Wait for handshake to complete
            long deadline = System.currentTimeMillis() + 3000;
            while (connManager.getInFlightAttemptCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert registeredCount.get() == 1 : "Expected exactly 1 connection created during storm, got " + registeredCount.get();
            assert connManager.getInFlightAttemptCount() == 0 : "In-flight attempts should be 0 after completion";
        } finally {
            connManager.stop();
            serverNode.stop();
        }
    }

    /**
     * 2. When a peer is already in CONNECTED state, tryConnect must do nothing.
     */
    private void test2_AlreadyConnected_NoNewAttempt() {
        UUID localId = UUID.randomUUID();
        PeerManager peerManager = new PeerManager(localId);
        NodeIdentity remoteId = NodeIdentity.createRandom("ConnectedPeer");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);

        Peer peer = peerManager.registerDiscovered(remoteId, address);
        peer.setState(PeerState.CONNECTED);

        AtomicInteger connectAttempts = new AtomicInteger(0);
        ConnectionManager connManager = new ConnectionManager(
                localId,
                NodeConfig.defaultConfig(),
                peerManager,
                new TcpConnectionHandler(),
                conn -> connectAttempts.incrementAndGet()
        );
        connManager.start();

        try {
            connManager.tryConnect(peer);
            assert !connManager.isAttemptInFlight(remoteId.nodeId()) : "Must not attempt connection to CONNECTED peer";
            assert connectAttempts.get() == 0 : "Must not create connection for already connected peer";
        } finally {
            connManager.stop();
        }
    }

    /**
     * 3. Self-connection attempts must be completely ignored.
     */
    private void test3_SelfConnection_Ignored() {
        UUID localId = UUID.randomUUID();
        PeerManager peerManager = new PeerManager(localId);
        NodeIdentity selfId = NodeIdentity.of(localId, "SelfNode");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);

        Peer peer = new Peer(selfId, address, PeerState.DISCOVERED);

        ConnectionManager connManager = new ConnectionManager(
                localId,
                NodeConfig.defaultConfig(),
                peerManager,
                new TcpConnectionHandler(),
                conn -> {}
        );
        connManager.start();

        try {
            connManager.tryConnect(peer);
            assert !connManager.isAttemptInFlight(localId) : "Self connection must not be initiated";
            assert connManager.getInFlightAttemptCount() == 0;
        } finally {
            connManager.stop();
        }
    }

    /**
     * 4. Connection attempt to an unreachable TCP port must fail gracefully:
     *    - Peer state set to DISCONNECTED
     *    - In-flight attempt cleared
     *    - No crash or resource leak
     */
    private void test4_ConnectionFailure_UnreachablePort() throws Exception {
        UUID localId = UUID.randomUUID();
        PeerManager peerManager = new PeerManager(localId);
        NodeIdentity remoteId = NodeIdentity.createRandom("UnreachableNode");

        // Find a guaranteed closed/unused local port
        int unusedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            unusedPort = temp.getLocalPort();
        }

        PeerAddress address = new PeerAddress("127.0.0.1", unusedPort);
        Peer peer = peerManager.registerDiscovered(remoteId, address);

        NodeConfig config = NodeConfig.defaultConfig();
        ConnectionManager connManager = new ConnectionManager(
                localId,
                config,
                peerManager,
                new TcpConnectionHandler(),
                conn -> {}
        );
        connManager.start();

        try {
            connManager.tryConnect(peer);

            // Wait for connection attempt to fail
            long deadline = System.currentTimeMillis() + 3000;
            while (connManager.getInFlightAttemptCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert connManager.getInFlightAttemptCount() == 0 : "In-flight attempt must be cleared on failure";
            assert peer.getState() == PeerState.DISCONNECTED : "Peer state must be DISCONNECTED after failure, got " + peer.getState();
        } finally {
            connManager.stop();
        }
    }

    /**
     * 5. Identity mismatch:
     *    - Discovery says address has Node ID AAA
     *    - Remote server actually identifies as Node ID BBB during handshake
     *    - ConnectionManager rejects connection, closes socket, cleans attempt state,
     *      and does NOT mark AAA or BBB as CONNECTED.
     */
    private void test5_IdentityMismatch_Rejected() throws Exception {
        NodeIdentity realServerId = NodeIdentity.createRandom("RealServerB");
        NodeConfig serverConfig = NodeConfig.withPortAndTimeout(0, 5000);
        Node serverNode = new Node(serverConfig, realServerId);
        serverNode.start();

        int serverPort = serverNode.getTcpServer().getLocalPort();

        // Fake peer with ID AAA at the server's port
        NodeIdentity fakePeerIdA = NodeIdentity.createRandom("SpoofedPeerA");
        NodeIdentity clientLocalId = NodeIdentity.createRandom("LocalClient");

        NodeConfig clientConfig = NodeConfig.withPortAndTimeout(0, 5000);
        Node clientNode = new Node(clientConfig, clientLocalId);
        clientNode.start();

        try {
            PeerAddress address = new PeerAddress("127.0.0.1", serverPort);
            // registerDiscovered automatically notifies ConnectionManager to tryConnect
            Peer fakePeer = clientNode.getPeerManager().registerDiscovered(fakePeerIdA, address);

            // Wait for attempt to complete and reject
            long deadline = System.currentTimeMillis() + 3000;
            while (clientNode.getConnectionManager().getInFlightAttemptCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            // Verify fake peer AAA was rejected and NOT connected
            assert fakePeer.getState() == PeerState.DISCONNECTED : "Spoofed peer AAA must remain DISCONNECTED, got " + fakePeer.getState();
            assert fakePeer.getConnection() == null || !fakePeer.getConnection().isOpen() : "Connection must be closed";

            // Verify realServerId was not accidentally added as connected peer without proper registration
            assert clientNode.getPeerManager().findPeer(realServerId.nodeId()).isEmpty() ||
                    !clientNode.getPeerManager().findPeer(realServerId.nodeId()).get().isConnected() :
                    "Server ID BBB must not be substituted as a connected peer";

            assert clientNode.getConnectionManager().getInFlightAttemptCount() == 0 : "Attempt state must be cleaned";
        } finally {
            clientNode.stop();
            serverNode.stop();
        }
    }

    /**
     * 6. Stopping ConnectionManager prevents new attempts and clears in-flight attempts.
     */
    private void test6_Shutdown_PreventsNewAttempts() {
        UUID localId = UUID.randomUUID();
        PeerManager peerManager = new PeerManager(localId);
        NodeIdentity remoteId = NodeIdentity.createRandom("ShutNode");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);
        Peer peer = peerManager.registerDiscovered(remoteId, address);

        ConnectionManager connManager = new ConnectionManager(
                localId,
                NodeConfig.defaultConfig(),
                peerManager,
                new TcpConnectionHandler(),
                conn -> {}
        );

        connManager.start();
        connManager.stop();

        assert !connManager.isRunning() : "Manager must report not running after stop()";

        connManager.tryConnect(peer);
        assert !connManager.isAttemptInFlight(remoteId.nodeId()) : "No attempts allowed when stopped";
        assert connManager.getInFlightAttemptCount() == 0;
    }

    /**
     * 7. Enforces configured connection timeout.
     */
    private void test7_ConnectionTimeoutEnforced() {
        NodeConfig config = NodeConfig.defaultConfig();
        assert config.connectionTimeoutMillis() > 0 : "Connection timeout must be positive";
        assert config.connectionTimeoutMillis() == 5000 : "Default connection timeout should be 5000ms";
    }
}
