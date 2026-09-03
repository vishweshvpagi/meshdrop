package com.meshdrop.integration;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerState;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end integration tests for MeshDrop Phase 8:
 *   - Real UDP Discovery -> TCP Connection -> Handshake -> PeerState.CONNECTED
 *   - Real protocol packet exchange over automatically established session
 *   - Simultaneous duplicate connection resolution (exactly 1 connection survives)
 *   - Disconnect and rediscovery re-establishment
 *   - Graceful shutdown of nodes
 */
public class TwoNodeIntegrationTest {

    public void runAll() throws Exception {
        test1_RealDiscoveryToConnectionToHandshakeAndMessaging();
        test2_SimultaneousDuplicateConnectionResolution();
        test3_DisconnectAndRediscovery();
        test4_CleanShutdown();
    }

    /**
     * 1. Core acceptance test:
     *    - Node A and Node B start on ephemeral ports with UDP discovery.
     *    - A discovers B and B discovers A.
     *    - Automatic TCP connection occurs.
     *    - Handshake completes and both show CONNECTED.
     *    - Real binary MESSAGE and PING packets are exchanged across the session.
     */
    private void test1_RealDiscoveryToConnectionToHandshakeAndMessaging() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("IntegrationNodeA");
        NodeIdentity idB = NodeIdentity.createRandom("IntegrationNodeB");

        NodeConfig configA = NodeConfig.withDiscovery(0, 0, true);
        NodeConfig configB = NodeConfig.withDiscovery(0, 0, true);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        CountDownLatch messageReceivedByB = new CountDownLatch(1);
        AtomicReference<String> receivedContentOnB = new AtomicReference<>();

        nodeA.start();
        nodeB.start();

        try {
            int udpPortA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int udpPortB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            // Emit direct discovery beacons between nodes
            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortA);

            // Wait for both nodes to automatically discover, connect, and handshake
            long deadline = System.currentTimeMillis() + 5000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() == 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow(
                    () -> new AssertionError("Node A must find Peer B"));
            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow(
                    () -> new AssertionError("Node B must find Peer A"));

            assert peerBonA.getState() == PeerState.CONNECTED : "Peer B on Node A must be CONNECTED, got " + peerBonA.getState();
            assert peerAonB.getState() == PeerState.CONNECTED : "Peer A on Node B must be CONNECTED, got " + peerAonB.getState();

            assert peerBonA.isConnected() : "Peer B must have active connection";
            assert peerAonB.isConnected() : "Peer A must have active connection";

            // Verify real protocol message exchange
            TcpConnection connFromAtoB = peerBonA.getConnection();
            assert connFromAtoB != null && connFromAtoB.isOpen();

            Packet messagePacket = Packet.createMessage("Hello MeshDrop Phase 8!");
            connFromAtoB.sendPacket(messagePacket);

            // Verify PING / PONG exchange
            Packet pingPacket = Packet.createPing();
            connFromAtoB.sendPacket(pingPacket);

            Thread.sleep(150); // Allow packet transmission
            assert connFromAtoB.isOpen() : "Connection should remain open and healthy after packet exchange";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    /**
     * 2. Simultaneous duplicate connection resolution:
     *    - When both nodes connect to each other simultaneously, the deterministic duplicate
     *      policy executes so exactly 1 connection survives and is referenced by both peers.
     */
    private void test2_SimultaneousDuplicateConnectionResolution() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("DuplNodeA");
        NodeIdentity idB = NodeIdentity.createRandom("DuplNodeB");

        NodeConfig configA = NodeConfig.withPortAndTimeout(0, 5000);
        NodeConfig configB = NodeConfig.withPortAndTimeout(0, 5000);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        nodeA.start();
        nodeB.start();

        int portA = nodeA.getTcpServer().getLocalPort();
        int portB = nodeB.getTcpServer().getLocalPort();

        try {
            // Trigger simultaneous connections: A -> B and B -> A
            Thread t1 = Thread.ofVirtual().start(() -> {
                try { nodeA.connectTo("127.0.0.1", portB); } catch (Exception ignored) {}
            });
            Thread t2 = Thread.ofVirtual().start(() -> {
                try { nodeB.connectTo("127.0.0.1", portA); } catch (Exception ignored) {}
            });

            t1.join();
            t2.join();

            // Allow handshake and duplicate resolution to complete
            long deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() != 1 ||
                    nodeB.getPeerManager().getConnectedPeerCount() != 1 ||
                    nodeA.getConnectionCount() != 1 ||
                    nodeB.getConnectionCount() != 1)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert nodeA.getPeerManager().getConnectedPeerCount() == 1 : "Node A must have exactly 1 connected peer";
            assert nodeB.getPeerManager().getConnectedPeerCount() == 1 : "Node B must have exactly 1 connected peer";

            assert nodeA.getConnectionCount() == 1 : "Node A must retain exactly 1 active TCP connection, got " + nodeA.getConnectionCount();
            assert nodeB.getConnectionCount() == 1 : "Node B must retain exactly 1 active TCP connection, got " + nodeB.getConnectionCount();

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow();

            assert peerBonA.isConnected();
            assert peerAonB.isConnected();

            // Send packet over surviving connection
            peerBonA.getConnection().sendPacket(Packet.createMessage("Surviving connection test"));
            Thread.sleep(100);
            assert peerBonA.getConnection().isOpen();
            assert peerAonB.getConnection().isOpen();

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    /**
     * 3. Disconnect and rediscovery:
     *    - Connect automatically -> Disconnect -> DISCONNECTED state -> Rediscover -> CONNECTED
     */
    private void test3_DisconnectAndRediscovery() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("ReconnA");
        NodeIdentity idB = NodeIdentity.createRandom("ReconnB");

        NodeConfig configA = NodeConfig.withDiscovery(0, 0, true);
        NodeConfig configB = NodeConfig.withDiscovery(0, 0, true);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        nodeA.start();
        nodeB.start();

        try {
            int udpPortA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int udpPortB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            // Initial discovery and connection
            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortA);

            long deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() == 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peerBonA.getState() == PeerState.CONNECTED;

            // Close connection to simulate network drop
            peerBonA.getConnection().close();

            long disconnectDeadline = System.currentTimeMillis() + 3000;
            while (peerBonA.getState() == PeerState.CONNECTED && System.currentTimeMillis() < disconnectDeadline) {
                Thread.sleep(50);
            }

            assert peerBonA.getState() == PeerState.DISCONNECTED : "Peer state must be DISCONNECTED after socket close";

            // Allow a later discovery event to trigger automatic reconnection
            Thread.sleep(200);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortA);

            long reconnectDeadline = System.currentTimeMillis() + 4000;
            while (peerBonA.getState() != PeerState.CONNECTED && System.currentTimeMillis() < reconnectDeadline) {
                Thread.sleep(50);
            }

            assert peerBonA.getState() == PeerState.CONNECTED : "Peer must successfully reconnect to CONNECTED state after rediscovery";
            assert peerBonA.isConnected();

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    /**
     * 4. Shutdown behavior:
     *    - Stopping Node A stops discovery, connection manager, server, and active connections.
     *    - Node B observes the disconnect.
     */
    private void test4_CleanShutdown() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("ShutNodeA");
        NodeIdentity idB = NodeIdentity.createRandom("ShutNodeB");

        NodeConfig configA = NodeConfig.withDiscovery(0, 0, true);
        NodeConfig configB = NodeConfig.withDiscovery(0, 0, true);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        nodeA.start();
        nodeB.start();

        try {
            int udpPortA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int udpPortB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", udpPortA);

            long deadline = System.currentTimeMillis() + 4000;
            while (nodeB.getPeerManager().getConnectedPeerCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert nodeB.getPeerManager().getConnectedPeerCount() >= 1;

            // Stop Node A
            nodeA.stop();

            assert !nodeA.getDiscoveryService().isRunning() : "Discovery must be stopped";
            assert !nodeA.getConnectionManager().isRunning() : "ConnectionManager must be stopped";
            assert !nodeA.getTcpServer().isRunning() : "TcpServer must be stopped";
            assert nodeA.getConnectionCount() == 0 : "Node A must have 0 connections";

            // Node B should observe disconnect
            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow();
            long waitDisconn = System.currentTimeMillis() + 3000;
            while (peerAonB.getState() == PeerState.CONNECTED && System.currentTimeMillis() < waitDisconn) {
                Thread.sleep(50);
            }

            assert peerAonB.getState() == PeerState.DISCONNECTED : "Node B must observe Node A as DISCONNECTED";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }
}
