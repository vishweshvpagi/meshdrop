package com.meshdrop.peer;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionDirection;
import com.meshdrop.network.TcpConnection;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive unit and integration tests for PeerManager.
 */
public class PeerManagerTest {

    public void runAll() throws Exception {
        // --- Unit Tests ---
        testRegisterDiscoveredPeer();
        testFindPeerByUuid();
        testListPeers();
        testRegisterConnectedPeer();
        testMarkDisconnected();
        testGetConnectedPeers();
        testUnknownPeerLookup();
        testDuplicatePeerRegistration();
        testThreadSafeRegistration();
        testSnapshotImmutability();
        testDuplicateConnectionResolution();
        testSelfConnectionRejection();

        // --- Live Network Integration Tests ---
        testRealTcpPeerIntegration();
        testDisconnectIntegration();
        testReconnectPreparation();
        testRawClientDoesNotCreatePeer();
    }

    // ========================================================================
    // 1. Unit Tests
    // ========================================================================

    private void testRegisterDiscoveredPeer() {
        UUID localId = UUID.randomUUID();
        PeerManager manager = new PeerManager(localId);

        NodeIdentity remoteId = NodeIdentity.createRandom("DiscoveredPeer");
        PeerAddress address = new PeerAddress("192.168.1.50", 5000);

        Peer peer = manager.registerDiscovered(remoteId, address);
        assert peer != null : "Discovered peer should be returned";
        assert peer.getState() == PeerState.DISCOVERED : "State should be DISCOVERED";
        assert manager.getPeerCount() == 1 : "Peer count should be 1";

        Optional<Peer> found = manager.findPeer(remoteId.nodeId());
        assert found.isPresent() && found.get().getDisplayName().equals("DiscoveredPeer") : "Peer lookup mismatch";
    }

    private void testFindPeerByUuid() {
        PeerManager manager = new PeerManager();
        NodeIdentity id1 = NodeIdentity.createRandom("Peer1");
        NodeIdentity id2 = NodeIdentity.createRandom("Peer2");

        manager.registerDiscovered(id1, new PeerAddress("1.1.1.1", 5000));
        manager.registerDiscovered(id2, new PeerAddress("2.2.2.2", 5000));

        assert manager.findPeer(id1.nodeId()).isPresent() : "Peer 1 should be found";
        assert manager.findPeer(id2.nodeId()).isPresent() : "Peer 2 should be found";
        assert manager.findPeer(UUID.randomUUID()).isEmpty() : "Unknown UUID should return empty";
    }

    private void testListPeers() {
        PeerManager manager = new PeerManager();
        for (int i = 0; i < 5; i++) {
            manager.registerDiscovered(NodeIdentity.createRandom("P" + i), new PeerAddress("10.0.0." + i, 5000));
        }

        List<Peer> peers = manager.getPeers();
        assert peers.size() == 5 : "Expected 5 peers, got: " + peers.size();
    }

    private void testRegisterConnectedPeer() throws Exception {
        PeerManager manager = new PeerManager();
        NodeIdentity remoteId = NodeIdentity.createRandom("ConnectedPeer");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);

        try (ServerSocket ss = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            Peer peer = manager.registerConnected(remoteId, address, conn);
            assert peer.getState() == PeerState.CONNECTED : "Should be CONNECTED";
            assert peer.isConnected() : "isConnected() should be true";
            assert peer.getConnection() == conn : "Connection should be associated";
            assert manager.getConnectedPeerCount() == 1 : "Connected count should be 1";

            conn.close();
            client.close();
        }
    }

    private void testMarkDisconnected() throws Exception {
        PeerManager manager = new PeerManager();
        NodeIdentity remoteId = NodeIdentity.createRandom("DisconnectTest");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);

        try (ServerSocket ss = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            Peer peer = manager.registerConnected(remoteId, address, conn);
            assert peer.isConnected() : "Peer should be connected";

            manager.markDisconnected(remoteId.nodeId(), conn);
            assert peer.getState() == PeerState.DISCONNECTED : "Peer should be DISCONNECTED";
            assert peer.getConnection() == null : "Connection should be cleared";
            assert !peer.isConnected() : "isConnected() should be false";
            assert manager.getConnectedPeerCount() == 0 : "Connected count should be 0";
            assert manager.getPeerCount() == 1 : "Peer object must survive disconnection";

            conn.close();
            client.close();
        }
    }

    private void testGetConnectedPeers() throws Exception {
        PeerManager manager = new PeerManager();
        NodeIdentity id1 = NodeIdentity.createRandom("P1");
        NodeIdentity id2 = NodeIdentity.createRandom("P2");

        manager.registerDiscovered(id1, new PeerAddress("1.1.1.1", 5000));

        try (ServerSocket ss = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            manager.registerConnected(id2, new PeerAddress("2.2.2.2", 5000), conn);

            List<Peer> connected = manager.getConnectedPeers();
            assert connected.size() == 1 : "Expected 1 connected peer, got: " + connected.size();
            assert connected.get(0).getNodeId().equals(id2.nodeId()) : "Connected peer should be P2";

            conn.close();
            client.close();
        }
    }

    private void testUnknownPeerLookup() {
        PeerManager manager = new PeerManager();
        assert manager.findPeer(UUID.randomUUID()).isEmpty() : "Unknown lookup should return empty";
    }

    private void testDuplicatePeerRegistration() {
        PeerManager manager = new PeerManager();
        UUID id = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.of(id, "OriginalName");

        manager.registerDiscovered(identity, new PeerAddress("1.1.1.1", 5000));
        manager.registerDiscovered(identity, new PeerAddress("1.1.1.2", 6000)); // Same UUID, updated address

        assert manager.getPeerCount() == 1 : "Duplicate registration must not create second peer entry";
        Peer peer = manager.findPeer(id).orElseThrow();
        assert peer.getAddress().host().equals("1.1.1.2") : "Address should be updated";
    }

    private void testThreadSafeRegistration() throws Exception {
        PeerManager manager = new PeerManager();
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    NodeIdentity id = NodeIdentity.createRandom("ThreadPeer-" + index);
                    manager.registerDiscovered(id, new PeerAddress("10.0.0." + index, 5000));
                } finally {
                    latch.countDown();
                }
            });
        }

        assert latch.await(5, TimeUnit.SECONDS) : "Concurrent registration should complete within 5s";
        assert manager.getPeerCount() == threadCount : "All peers should be registered safely";
        executor.shutdown();
    }

    private void testSnapshotImmutability() {
        PeerManager manager = new PeerManager();
        manager.registerDiscovered(NodeIdentity.createRandom("SnapPeer"), new PeerAddress("1.1.1.1", 5000));

        List<Peer> snapshot = manager.getPeers();
        boolean threw = false;
        try {
            snapshot.add(new Peer(NodeIdentity.createRandom("Illegal"), new PeerAddress("2.2.2.2", 5000)));
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        assert threw : "getPeers() must return an immutable snapshot";
    }

    private void testDuplicateConnectionResolution() throws Exception {
        UUID localId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID remoteId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        // localId < remoteId -> smaller UUID (local) keeps OUTBOUND, closes INBOUND

        PeerManager manager = new PeerManager(localId);
        NodeIdentity remoteIdentity = NodeIdentity.of(remoteId, "RemotePeer");

        try (ServerSocket ss = new ServerSocket(0)) {
            // Connection 1: INBOUND
            Socket client1 = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server1 = ss.accept();
            TcpConnection inboundConn = new TcpConnection(server1, ConnectionDirection.INBOUND);

            // Register INBOUND connection
            manager.registerConnected(remoteIdentity, new PeerAddress("127.0.0.1", 5000), inboundConn);
            Peer peer = manager.findPeer(remoteId).orElseThrow();
            assert peer.getConnection() == inboundConn : "Initial connection should be inboundConn";

            // Connection 2: OUTBOUND to the same remote peer
            Socket client2 = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server2 = ss.accept();
            TcpConnection outboundConn = new TcpConnection(client2, ConnectionDirection.OUTBOUND);

            // Register second connection: duplicate resolution should trigger
            manager.registerConnected(remoteIdentity, new PeerAddress("127.0.0.1", 5000), outboundConn);

            // Since localId < remoteId, OUTBOUND must be kept and INBOUND must be closed
            assert peer.getConnection() == outboundConn : "Deterministic policy should keep OUTBOUND";
            assert !inboundConn.isOpen() : "Rejected INBOUND connection must be closed";
            assert outboundConn.isOpen() : "Kept OUTBOUND connection must remain open";

            outboundConn.close();
            client1.close();
            server2.close();
        }
    }

    private void testSelfConnectionRejection() {
        UUID localId = UUID.randomUUID();
        PeerManager manager = new PeerManager(localId);
        NodeIdentity selfIdentity = NodeIdentity.of(localId, "Self");

        // Discovered self-registration should be ignored
        Peer disc = manager.registerDiscovered(selfIdentity, new PeerAddress("127.0.0.1", 5000));
        assert disc == null : "Self discovery should return null";
        assert manager.getPeerCount() == 0 : "Self peer must not be added to PeerManager";
    }

    // ========================================================================
    // 2. Live Network Integration Tests
    // ========================================================================

    private void testRealTcpPeerIntegration() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("NodeA");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB");

        Node nodeA = new Node(NodeConfig.withPortAndTimeout(0, 5000), idA);
        Node nodeB = new Node(NodeConfig.withPortAndTimeout(0, 5000), idB);

        nodeA.start();
        nodeB.start();

        int portB = nodeB.getTcpServer().getLocalPort();

        try {
            TcpConnection connAtoB = nodeA.connectTo("127.0.0.1", portB);

            // Wait for handshake and peer registration on both sides
            long deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() == 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert nodeA.getPeerManager().getConnectedPeerCount() == 1 : "Node A should have 1 connected peer";
            assert nodeB.getPeerManager().getConnectedPeerCount() == 1 : "Node B should have 1 connected peer";

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peerBonA.getState() == PeerState.CONNECTED : "Peer B on Node A should be CONNECTED";
            assert peerBonA.getDisplayName().equals("NodeB") : "Display name mismatch";
            assert peerBonA.getConnection() != null && peerBonA.getConnection().isOpen() : "Active connection should be present";

            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow();
            assert peerAonB.getState() == PeerState.CONNECTED : "Peer A on Node B should be CONNECTED";
            assert peerAonB.getDisplayName().equals("NodeA") : "Display name mismatch";
            assert peerAonB.getConnection() != null && peerAonB.getConnection().isOpen() : "Active connection should be present";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    private void testDisconnectIntegration() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("DisconnNodeA");
        NodeIdentity idB = NodeIdentity.createRandom("DisconnNodeB");

        Node nodeA = new Node(NodeConfig.withPortAndTimeout(0, 5000), idA);
        Node nodeB = new Node(NodeConfig.withPortAndTimeout(0, 5000), idB);

        nodeA.start();
        nodeB.start();

        int portB = nodeB.getTcpServer().getLocalPort();

        try {
            TcpConnection conn = nodeA.connectTo("127.0.0.1", portB);

            long deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() == 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert nodeA.getPeerManager().getConnectedPeerCount() == 1;

            // Close connection from Node A
            conn.close();

            // Wait for both sides to mark peer DISCONNECTED
            deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() > 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() > 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peerBonA.getState() == PeerState.DISCONNECTED : "Peer should become DISCONNECTED";
            assert peerBonA.getConnection() == null : "Connection should be cleared";

            assert nodeA.getState() == com.meshdrop.core.NodeState.RUNNING : "Node A must remain RUNNING";
            assert nodeB.getState() == com.meshdrop.core.NodeState.RUNNING : "Node B must remain RUNNING";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    private void testReconnectPreparation() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("PrepA");
        NodeIdentity idB = NodeIdentity.createRandom("PrepB");

        Node nodeA = new Node(NodeConfig.withPortAndTimeout(0, 5000), idA);
        Node nodeB = new Node(NodeConfig.withPortAndTimeout(0, 5000), idB);

        nodeA.start();
        nodeB.start();

        try {
            TcpConnection conn = nodeA.connectTo("127.0.0.1", nodeB.getTcpServer().getLocalPort());
            Thread.sleep(200);

            conn.close();
            Thread.sleep(200);

            Peer peer = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peer.getState() == PeerState.DISCONNECTED : "Should be DISCONNECTED";
            assert peer.getAddress() != null : "PeerAddress must be retained for future reconnect";
            assert peer.getAddress().tcpPort() > 0 : "Peer port must be preserved";
            assert peer.getIdentity().equals(idB) : "NodeIdentity must be preserved";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    private void testRawClientDoesNotCreatePeer() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("NoFakePeerServer");
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), id);
        node.start();
        int port = node.getTcpServer().getLocalPort();

        try {
            // Raw socket connects and sends garbage, never completing MeshDrop handshake
            try (Socket rawClient = new Socket("127.0.0.1", port)) {
                rawClient.getOutputStream().write("GARBAGE_PAYLOAD\n".getBytes());
                rawClient.getOutputStream().flush();
                Thread.sleep(200);
            }

            assert node.getPeerManager().getPeerCount() == 0 : "No peer should be created for unauthenticated raw client";

        } finally {
            node.stop();
        }
    }
}
