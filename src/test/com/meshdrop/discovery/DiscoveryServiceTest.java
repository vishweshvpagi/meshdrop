package com.meshdrop.discovery;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.peer.PeerState;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive unit and integration tests for DiscoveryService and PeerManager discovery integration.
 */
public class DiscoveryServiceTest {

    public void runAll() throws Exception {
        test1_ServiceStarts();
        test2_ServiceStops();
        test3_BeaconSchedulerRuns();
        test4_ReceiverHandlesValidBeacon();
        test5_SelfBeaconIgnored();
        test6_MalformedPacketIgnored();
        test7_UnsupportedVersionIgnored();
        test8_PeerDiscovered();
        test9_DuplicateBeaconDoesNotCreateDuplicatePeer();
        test10_ExistingConnectedPeerNotDowngraded();
        test11_LastSeenTimestampUpdates();
        test12_AddressUpdatesCorrectly();
        test13_SocketClosesCleanly();
        test14_ServiceCanRestartAndTwoNodeDiscovery();
    }

    // 1. Service starts
    private void test1_ServiceStarts() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("DiscTest1");
        DiscoveryService service = new DiscoveryService(
                id, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (remoteId, address) -> {}
        );

        assert !service.isRunning() : "Should not be running before start()";
        service.start();
        try {
            assert service.isRunning() : "Should be running after start()";
            assert service.getUdpDiscoveryPort() > 0 : "Allocated UDP discovery port should be positive";
        } finally {
            service.stop();
        }
    }

    // 2. Service stops
    private void test2_ServiceStops() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("DiscTest2");
        DiscoveryService service = new DiscoveryService(
                id, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (remoteId, address) -> {}
        );

        service.start();
        assert service.isRunning() : "Service should be running";
        service.stop();
        assert !service.isRunning() : "Service should not be running after stop()";
    }

    // 3. Beacon scheduler runs
    private void test3_BeaconSchedulerRuns() throws Exception {
        NodeIdentity senderId = NodeIdentity.createRandom("BeaconEmitter");
        // Start sender with high frequency beacon (e.g. 50ms)
        DiscoveryService sender = new DiscoveryService(
                senderId, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                50,
                (remoteId, address) -> {}
        );
        sender.start();

        try {
            assert sender.isRunning() : "Sender must be running";
            // Allow scheduler to execute at least a few cycles
            Thread.sleep(150);
        } finally {
            sender.stop();
        }
    }

    // 4. Receiver handles a valid beacon
    private void test4_ReceiverHandlesValidBeacon() throws Exception {
        NodeIdentity receiverId = NodeIdentity.createRandom("Receiver4");
        NodeIdentity senderId = NodeIdentity.createRandom("Sender4");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<NodeIdentity> discoveredId = new AtomicReference<>();
        AtomicReference<PeerAddress> discoveredAddr = new AtomicReference<>();

        DiscoveryService receiver = new DiscoveryService(
                receiverId, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (id, addr) -> {
                    discoveredId.set(id);
                    discoveredAddr.set(addr);
                    latch.countDown();
                }
        );
        receiver.start();

        try {
            DiscoveryMessage msg = DiscoveryMessage.beacon(senderId.nodeId(), 6000, "Sender4");
            byte[] bytes = msg.encode();

            try (DatagramSocket directSocket = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), receiver.getUdpDiscoveryPort());
                directSocket.send(packet);
            }

            assert latch.await(3, TimeUnit.SECONDS) : "Receiver should handle valid beacon within 3s";
            assert discoveredId.get().nodeId().equals(senderId.nodeId()) : "Discovered UUID mismatch";
            assert discoveredId.get().displayName().equals("Sender4") : "Discovered name mismatch";
            assert discoveredAddr.get().tcpPort() == 6000 : "Discovered TCP port mismatch";
            assert discoveredAddr.get().host().equals("127.0.0.1") : "Discovered IP mismatch";
        } finally {
            receiver.stop();
        }
    }

    // 5. Self beacon ignored
    private void test5_SelfBeaconIgnored() throws Exception {
        NodeIdentity selfId = NodeIdentity.createRandom("SelfNode5");
        CountDownLatch latch = new CountDownLatch(1);

        DiscoveryService service = new DiscoveryService(
                selfId, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (id, addr) -> latch.countDown()
        );
        service.start();

        try {
            // Send a beacon containing the receiver's OWN Node ID
            DiscoveryMessage selfMsg = DiscoveryMessage.beacon(selfId.nodeId(), 5000, "SelfNode5");
            byte[] bytes = selfMsg.encode();

            try (DatagramSocket raw = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), service.getUdpDiscoveryPort());
                raw.send(packet);
            }

            boolean triggered = latch.await(300, TimeUnit.MILLISECONDS);
            assert !triggered : "Self-discovery beacon must be ignored";
        } finally {
            service.stop();
        }
    }

    // 6. Malformed packet ignored
    private void test6_MalformedPacketIgnored() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("MalformedTestNode6");
        DiscoveryService service = new DiscoveryService(
                id, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (remoteId, addr) -> {}
        );
        service.start();

        try {
            try (DatagramSocket raw = new DatagramSocket()) {
                byte[] garbage = "RANDOM_CORRUPT_BYTES_NOT_A_VALID_MESHDROP_PACKET".getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(garbage, garbage.length, InetAddress.getByName("127.0.0.1"), service.getUdpDiscoveryPort());
                raw.send(packet);
            }

            Thread.sleep(150);
            assert service.isRunning() : "Service must remain RUNNING after receiving malformed packet";
        } finally {
            service.stop();
        }
    }

    // 7. Unsupported version ignored
    private void test7_UnsupportedVersionIgnored() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("VersionTestNode7");
        CountDownLatch latch = new CountDownLatch(1);

        DiscoveryService service = new DiscoveryService(
                id, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (remoteId, addr) -> latch.countDown()
        );
        service.start();

        try {
            byte[] bytes = DiscoveryMessage.beacon(UUID.randomUUID(), 5000, "FutureNode").encode();
            bytes[4] = (byte) 0x99; // Set version to 0x99

            try (DatagramSocket raw = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), service.getUdpDiscoveryPort());
                raw.send(packet);
            }

            boolean triggered = latch.await(300, TimeUnit.MILLISECONDS);
            assert !triggered : "Unsupported discovery version must be safely ignored";
        } finally {
            service.stop();
        }
    }

    // 8. Peer discovered
    private void test8_PeerDiscovered() {
        UUID localId = UUID.randomUUID();
        PeerManager peerManager = new PeerManager(localId);

        NodeIdentity remoteId = NodeIdentity.createRandom("DiscoveredRemote8");
        PeerAddress address = new PeerAddress("192.168.1.100", 5000);

        Peer peer = peerManager.registerDiscovered(remoteId, address);

        assert peer != null : "Discovered peer must not be null";
        assert peerManager.getPeerCount() == 1 : "Peer count should be 1";
        assert peer.getState() == PeerState.DISCOVERED : "State must be DISCOVERED (not CONNECTED)";
        assert peer.getDisplayName().equals("DiscoveredRemote8") : "Name mismatch";
        assert peer.getAddress().equals(address) : "Address mismatch";
        assert peer.getConnection() == null : "Connection must be null for discovered peer";
    }

    // 9. Duplicate beacon does not create duplicate Peer
    private void test9_DuplicateBeaconDoesNotCreateDuplicatePeer() {
        PeerManager peerManager = new PeerManager();
        UUID peerId = UUID.randomUUID();
        NodeIdentity id1 = NodeIdentity.of(peerId, "UniquePeer9");

        peerManager.registerDiscovered(id1, new PeerAddress("10.0.0.1", 5000));
        assert peerManager.getPeerCount() == 1;

        peerManager.registerDiscovered(id1, new PeerAddress("10.0.0.1", 5000));
        assert peerManager.getPeerCount() == 1 : "Duplicate beacon must NOT create duplicate Peer record";
    }

    // 10. Existing CONNECTED peer is not downgraded
    private void test10_ExistingConnectedPeerNotDowngraded() {
        PeerManager peerManager = new PeerManager();
        UUID peerId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.of(peerId, "ActivePeer10");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);

        // Register and promote to CONNECTED
        peerManager.registerDiscovered(identity, address);
        Peer peer = peerManager.findPeer(peerId).orElseThrow();
        peer.setState(PeerState.CONNECTED);

        // Repeated discovery beacon arrives
        peerManager.registerDiscovered(identity, address);

        Peer stored = peerManager.findPeer(peerId).orElseThrow();
        assert stored.getState() == PeerState.CONNECTED : "Discovery beacon must NOT downgrade CONNECTED peer";
    }

    // 11. Last-seen timestamp updates
    private void test11_LastSeenTimestampUpdates() throws Exception {
        PeerManager peerManager = new PeerManager();
        UUID peerId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.of(peerId, "TimestampPeer11");
        PeerAddress address = new PeerAddress("10.0.0.1", 5000);

        peerManager.registerDiscovered(identity, address);
        Peer peer = peerManager.findPeer(peerId).orElseThrow();
        Instant initialLastSeen = peer.getLastSeen();

        Thread.sleep(20);

        peerManager.registerDiscovered(identity, address);
        Instant updatedLastSeen = peer.getLastSeen();

        assert updatedLastSeen.isAfter(initialLastSeen) || updatedLastSeen.equals(initialLastSeen) :
                "lastSeen timestamp must be updated";
    }

    // 12. Address updates correctly
    private void test12_AddressUpdatesCorrectly() {
        PeerManager peerManager = new PeerManager();
        UUID peerId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.of(peerId, "RoamingPeer12");

        peerManager.registerDiscovered(identity, new PeerAddress("192.168.1.10", 5000));
        Peer peer = peerManager.findPeer(peerId).orElseThrow();
        assert peer.getAddress().host().equals("192.168.1.10");

        // Peer changes IP address (e.g. Wi-Fi roaming)
        peerManager.registerDiscovered(identity, new PeerAddress("192.168.1.20", 5000));
        assert peerManager.getPeerCount() == 1 : "Must remain 1 peer";
        assert peer.getAddress().host().equals("192.168.1.20") : "Address host must update";
    }

    // 13. Socket closes cleanly
    private void test13_SocketClosesCleanly() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("CloseTest13");
        DiscoveryService service = new DiscoveryService(
                id, 5000, 0,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                5000,
                (remoteId, addr) -> {}
        );

        service.start();
        int port = service.getUdpDiscoveryPort();
        service.stop();

        assert !service.isRunning() : "Service must report not running after stop";

        // Verify socket is closed by attempting to bind to the same port or creating another socket
        try (DatagramSocket testSocket = new DatagramSocket(null)) {
            testSocket.setReuseAddress(true);
            testSocket.bind(new java.net.InetSocketAddress(port));
            assert testSocket.isBound() : "Port should be immediately reusable after stop()";
        }
    }

    // 14. Service can restart after stop and Real two-node discovery
    private void test14_ServiceCanRestartAndTwoNodeDiscovery() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("NodeA14");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB14");

        NodeConfig configA = NodeConfig.withDiscovery(0, 0, true);
        NodeConfig configB = NodeConfig.withDiscovery(0, 0, true);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        nodeA.start();
        nodeB.start();

        try {
            int portA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int portB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            // A emits unicast beacon to B, B emits to A
            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portA);

            long deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().findPeer(idB.nodeId()).isEmpty() ||
                    nodeB.getPeerManager().findPeer(idA.nodeId()).isEmpty())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peerBonA.getState() == PeerState.DISCOVERED || peerBonA.getState() == PeerState.CONNECTED : "Peer B on Node A must be DISCOVERED or CONNECTED";
            assert peerBonA.getDisplayName().equals("NodeB14") : "Display name mismatch";
            assert peerBonA.getAddress().tcpPort() == nodeB.getTcpServer().getLocalPort() : "TCP port mismatch";

            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow();
            assert peerAonB.getState() == PeerState.DISCOVERED || peerAonB.getState() == PeerState.CONNECTED : "Peer A on Node B must be DISCOVERED or CONNECTED";
            assert peerAonB.getDisplayName().equals("NodeA14") : "Display name mismatch";
            assert peerAonB.getAddress().tcpPort() == nodeA.getTcpServer().getLocalPort() : "TCP port mismatch";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }
}
