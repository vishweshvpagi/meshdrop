package com.meshdrop.protocol;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.network.TcpConnectionHandler;
import com.meshdrop.network.TcpServer;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive unit and integration test suite for NodeIdentity and HandshakeService.
 */
public class HandshakeTest {

    public void runAll() throws Exception {
        // --- Identity Unit Tests ---
        testIdentityUuidPreservation();
        testIdentityDisplayNamePreservation();
        testDistinctIdentitiesDifferentUuids();
        testSameDisplayNamesAllowed();
        testIdentitySerializationExactBytes();
        testUtf8DisplayNameMultiByte();
        testMaxDisplayNameAccepted();
        testOversizedDisplayNameRejected();

        // --- Handshake Unit Tests ---
        testCreateAndParseHelloPacket();
        testCreateAndParseHelloResponsePacket();
        testHandshakeStateTransitions();
        testIllegalStateTransitionsRejected();
        testSelfConnectionRejected();
        testDuplicateHelloHandledSafely();
        testNonHandshakePacketBeforeReadyRejected();
        testMalformedIdentityPayload();

        // --- Live Network Handshake Tests ---
        testRealTwoNodeHandshake();
        testLiveSelfConnectionRejection();
        testNoHelloTimeoutRejection();
        testMalformedHandshakePayloadLive();
        testSimultaneousHandshakesLive();
    }

    // ========================================================================
    // 1. Identity Unit Tests
    // ========================================================================

    private void testIdentityUuidPreservation() throws Exception {
        UUID id = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.of(id, "TestNode");
        byte[] encoded = identity.encode();

        NodeIdentity decoded = NodeIdentity.decode(encoded);
        assert decoded.nodeId().equals(id) : "UUID mismatch: expected " + id + ", got " + decoded.nodeId();
    }

    private void testIdentityDisplayNamePreservation() throws Exception {
        NodeIdentity identity = NodeIdentity.of(UUID.randomUUID(), "Alice-Workstation");
        byte[] encoded = identity.encode();

        NodeIdentity decoded = NodeIdentity.decode(encoded);
        assert decoded.displayName().equals("Alice-Workstation") : "Display name mismatch: " + decoded.displayName();
    }

    private void testDistinctIdentitiesDifferentUuids() {
        NodeIdentity id1 = NodeIdentity.createRandom("NodeA");
        NodeIdentity id2 = NodeIdentity.createRandom("NodeA"); // Same name, different UUID

        assert !id1.equals(id2) : "Identities with different UUIDs must not be equal";
        assert !id1.nodeId().equals(id2.nodeId()) : "UUIDs must be distinct";
    }

    private void testSameDisplayNamesAllowed() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        NodeIdentity id1 = NodeIdentity.of(u1, "SameName");
        NodeIdentity id2 = NodeIdentity.of(u2, "SameName");

        assert id1.displayName().equals(id2.displayName()) : "Display names should match";
        assert !id1.equals(id2) : "Identities must differ by UUID authority";
    }

    private void testIdentitySerializationExactBytes() throws Exception {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        String name = "MeshPeer";
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

        NodeIdentity identity = NodeIdentity.of(id, name);
        byte[] encoded = identity.encode();

        // 16 bytes (UUID) + 2 bytes (Name Length) + nameBytes.length
        int expectedSize = 16 + 2 + nameBytes.length;
        assert encoded.length == expectedSize : "Expected size " + expectedSize + ", got " + encoded.length;

        ByteBuffer buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        long mostSig = buf.getLong();
        long leastSig = buf.getLong();
        int nameLen = buf.getShort() & 0xFFFF;

        assert new UUID(mostSig, leastSig).equals(id) : "UUID wire mismatch";
        assert nameLen == nameBytes.length : "Name length wire mismatch";
    }

    private void testUtf8DisplayNameMultiByte() throws Exception {
        String utf8Name = "MeshDrop-🚀-Node-💻";
        NodeIdentity identity = NodeIdentity.createRandom(utf8Name);
        byte[] encoded = identity.encode();

        NodeIdentity decoded = NodeIdentity.decode(encoded);
        assert decoded.displayName().equals(utf8Name) : "UTF-8 multi-byte name mismatch: " + decoded.displayName();
    }

    private void testMaxDisplayNameAccepted() throws Exception {
        char[] chars = new char[ProtocolConstants.MAX_DISPLAY_NAME_BYTES];
        Arrays.fill(chars, 'X');
        String maxName = new String(chars);

        NodeIdentity identity = NodeIdentity.createRandom(maxName);
        byte[] encoded = identity.encode();
        NodeIdentity decoded = NodeIdentity.decode(encoded);

        assert decoded.displayName().equals(maxName) : "Max length display name mismatch";
    }

    private void testOversizedDisplayNameRejected() {
        char[] chars = new char[ProtocolConstants.MAX_DISPLAY_NAME_BYTES + 1];
        Arrays.fill(chars, 'Y');
        String oversizedName = new String(chars);

        boolean rejected = false;
        try {
            NodeIdentity.createRandom(oversizedName);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assert rejected : "Oversized display name (> 128 bytes) must be rejected";
    }

    // ========================================================================
    // 2. Handshake Unit Tests
    // ========================================================================

    private void testCreateAndParseHelloPacket() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("HelloTester");
        UUID reqId = UUID.randomUUID();
        Packet helloPacket = Packet.createHello(reqId, id);

        assert helloPacket.getType() == PacketType.HELLO : "Type mismatch";
        assert helloPacket.getRequestId().equals(reqId) : "Request ID mismatch";

        NodeIdentity decoded = helloPacket.decodeIdentity();
        assert decoded.equals(id) : "Decoded identity mismatch";
        assert decoded.displayName().equals("HelloTester") : "Display name mismatch";
    }

    private void testCreateAndParseHelloResponsePacket() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("ResponseTester");
        UUID reqId = UUID.randomUUID();
        Packet respPacket = Packet.createHelloResponse(reqId, id);

        assert respPacket.getType() == PacketType.HELLO_RESPONSE : "Type mismatch";
        assert respPacket.getRequestId().equals(reqId) : "Request ID mismatch";

        NodeIdentity decoded = respPacket.decodeIdentity();
        assert decoded.equals(id) : "Decoded identity mismatch";
    }

    private void testHandshakeStateTransitions() {
        ConnectionState state = ConnectionState.CONNECTING;
        assert state == ConnectionState.CONNECTING;

        state = ConnectionState.CONNECTED;
        assert state == ConnectionState.CONNECTED;

        state = ConnectionState.HANDSHAKING;
        assert state == ConnectionState.HANDSHAKING;

        state = ConnectionState.READY;
        assert state == ConnectionState.READY;

        state = ConnectionState.CLOSING;
        assert state == ConnectionState.CLOSING;

        state = ConnectionState.CLOSED;
        assert state == ConnectionState.CLOSED;
    }

    private void testIllegalStateTransitionsRejected() throws Exception {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();

            TcpConnection conn = new TcpConnection(server);
            conn.close(); // Transitions to CLOSED

            boolean threw = false;
            try {
                conn.setState(ConnectionState.READY); // CLOSED -> READY must fail
            } catch (IllegalStateException e) {
                threw = true;
            }
            assert threw : "Cannot transition from CLOSED to READY";

            client.close();
        }
    }

    private void testSelfConnectionRejected() throws Exception {
        NodeIdentity selfId = NodeIdentity.createRandom("SelfNode");
        HandshakeService handshake = new HandshakeService(selfId);

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            Packet selfHello = Packet.createHello(selfId);

            boolean rejected = false;
            try {
                handshake.handlePacket(conn, selfHello);
            } catch (ProtocolException e) {
                rejected = true;
                assert e.getMessage().contains("Self-connection rejected") : "Expected self-connection error message";
            }
            assert rejected : "Self-connection must be rejected with ProtocolException";
            assert conn.getState() == ConnectionState.CLOSED : "Connection must be closed on self-connection";

            client.close();
        }
    }

    private void testDuplicateHelloHandledSafely() throws Exception {
        NodeIdentity localId = NodeIdentity.createRandom("Local");
        NodeIdentity remoteId = NodeIdentity.createRandom("Remote");
        HandshakeService handshake = new HandshakeService(localId);

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            // 1st HELLO completes handshake
            Packet hello1 = Packet.createHello(remoteId);
            boolean handled1 = handshake.handlePacket(conn, hello1);
            assert handled1 : "First HELLO must be handled";
            assert conn.isReady() : "Connection must be READY";

            // 2nd duplicate HELLO should be safely ignored
            Packet hello2 = Packet.createHello(remoteId);
            boolean handled2 = handshake.handlePacket(conn, hello2);
            assert handled2 : "Duplicate HELLO must be safely consumed without throwing";
            assert conn.isReady() : "Connection must remain READY";

            conn.close();
            client.close();
        }
    }

    private void testNonHandshakePacketBeforeReadyRejected() throws Exception {
        NodeIdentity localId = NodeIdentity.createRandom("LocalNode");
        HandshakeService handshake = new HandshakeService(localId);

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            Packet prematureMessage = Packet.createMessage("Premature message");

            boolean threw = false;
            try {
                handshake.handlePacket(conn, prematureMessage);
            } catch (ProtocolException e) {
                threw = true;
                assert e.getMessage().contains("Protocol violation: received MESSAGE before handshake reached READY") :
                        "Got: " + e.getMessage();
            }
            assert threw : "Non-handshake packet before READY must throw ProtocolException";
            assert conn.getState() == ConnectionState.CLOSED : "Connection must be closed";

            client.close();
        }
    }

    private void testMalformedIdentityPayload() {
        boolean threw = false;
        try {
            byte[] corrupt = new byte[]{0x01, 0x02, 0x03}; // Truncated (< 18 bytes)
            NodeIdentity.decode(corrupt);
        } catch (ProtocolException e) {
            threw = true;
        }
        assert threw : "Malformed identity payload must throw ProtocolException";
    }

    // ========================================================================
    // 3. Live Network Integration Tests
    // ========================================================================

    private void testRealTwoNodeHandshake() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("NodeA");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB");

        NodeConfig configA = NodeConfig.withPortAndTimeout(0, 5000);
        NodeConfig configB = NodeConfig.withPortAndTimeout(0, 5000);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        nodeA.start();
        nodeB.start();

        int portB = nodeB.getTcpServer().getLocalPort();

        try {
            // Node A initiates connection to Node B
            TcpConnection connAtoB = nodeA.connectTo("127.0.0.1", portB);

            // Wait for both sides to reach READY
            long deadline = System.currentTimeMillis() + 4000;
            while ((!connAtoB.isReady() || nodeB.getActiveConnections().isEmpty()
                    || !nodeB.getActiveConnections().iterator().next().isReady())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert connAtoB.isReady() : "Node A connection must be READY";
            assert !nodeB.getActiveConnections().isEmpty() : "Node B must have an active connection";
            TcpConnection connBfromA = nodeB.getActiveConnections().iterator().next();
            assert connBfromA.isReady() : "Node B connection must be READY";

            // Verify mutual identity exchange
            assert connAtoB.getRemoteIdentity() != null : "Node A must see Node B's identity";
            assert connAtoB.getRemoteIdentity().nodeId().equals(idB.nodeId()) : "Node A remote ID mismatch";
            assert connAtoB.getRemoteIdentity().displayName().equals("NodeB") : "Node A remote display name mismatch";

            assert connBfromA.getRemoteIdentity() != null : "Node B must see Node A's identity";
            assert connBfromA.getRemoteIdentity().nodeId().equals(idA.nodeId()) : "Node B remote ID mismatch";
            assert connBfromA.getRemoteIdentity().displayName().equals("NodeA") : "Node B remote display name mismatch";

            // Test post-handshake packet (PING)
            connAtoB.sendPacket(Packet.createPing());
            Thread.sleep(200);

            assert connAtoB.isOpen() : "Connection must remain open";
            assert connBfromA.isOpen() : "Connection must remain open";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }

    private void testLiveSelfConnectionRejection() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("SelfTestNode");
        NodeConfig config = NodeConfig.withPortAndTimeout(0, 5000);
        Node node = new Node(config, id);
        node.start();
        int port = node.getTcpServer().getLocalPort();

        try {
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(3000);
                PacketEncoder encoder = new PacketEncoder();
                PacketDecoder decoder = new PacketDecoder();

                // Read server's HELLO
                Packet serverHello = decoder.decode(client.getInputStream());
                assert serverHello != null && serverHello.getType() == PacketType.HELLO;

                // Send HELLO with the SAME node ID
                Packet selfHello = Packet.createHello(id);
                encoder.encode(selfHello, client.getOutputStream());

                // Server should reject and close the socket
                boolean closed = false;
                try {
                    Packet nextPacket = decoder.decode(client.getInputStream());
                    if (nextPacket == null) {
                        closed = true;
                    }
                } catch (IOException e) {
                    closed = true;
                }
                assert closed : "Server should close connection on self-connection";
            }

            assert node.getState() == com.meshdrop.core.NodeState.RUNNING : "Node must remain RUNNING";
        } finally {
            node.stop();
        }
    }

    private void testNoHelloTimeoutRejection() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("TimeoutTestNode");
        // Short 400ms handshake timeout for fast testing
        NodeConfig config = NodeConfig.withPortAndTimeout(0, 400);
        Node node = new Node(config, id);
        node.start();
        int port = node.getTcpServer().getLocalPort();

        try {
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(3000);
                PacketDecoder decoder = new PacketDecoder();

                // Read server's HELLO
                Packet serverHello = decoder.decode(client.getInputStream());
                assert serverHello != null && serverHello.getType() == PacketType.HELLO;

                // Client sends NOTHING. Server closes after 400ms timeout
                boolean closed = false;
                try {
                    Packet afterTimeout = decoder.decode(client.getInputStream());
                    if (afterTimeout == null) {
                        closed = true;
                    }
                } catch (IOException e) {
                    closed = true;
                }
                assert closed : "Connection should be closed after handshake timeout";
            }

            assert node.getState() == com.meshdrop.core.NodeState.RUNNING : "Node must remain RUNNING";
        } finally {
            node.stop();
        }
    }

    private void testMalformedHandshakePayloadLive() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("MalformedTester");
        NodeConfig config = NodeConfig.withPortAndTimeout(0, 5000);
        Node node = new Node(config, id);
        node.start();
        int port = node.getTcpServer().getLocalPort();

        try {
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(3000);
                PacketEncoder encoder = new PacketEncoder();
                PacketDecoder decoder = new PacketDecoder();

                // Read server's HELLO
                Packet serverHello = decoder.decode(client.getInputStream());
                assert serverHello != null;

                // Send corrupt HELLO payload (5 bytes instead of >= 18 bytes)
                byte[] corruptPayload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
                Packet corruptHello = Packet.of(PacketType.HELLO, UUID.randomUUID(), corruptPayload);
                encoder.encode(corruptHello, client.getOutputStream());

                // Server should reject and close the connection
                boolean closed = false;
                try {
                    Packet afterCorrupt = decoder.decode(client.getInputStream());
                    if (afterCorrupt == null) {
                        closed = true;
                    }
                } catch (IOException e) {
                    closed = true;
                }
                assert closed : "Server must close connection on malformed handshake payload";
            }

            assert node.getState() == com.meshdrop.core.NodeState.RUNNING : "Node must remain RUNNING";
        } finally {
            node.stop();
        }
    }

    private void testSimultaneousHandshakesLive() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("SimulNodeA");
        NodeIdentity idB = NodeIdentity.createRandom("SimulNodeB");

        NodeConfig configA = NodeConfig.withPortAndTimeout(0, 5000);
        NodeConfig configB = NodeConfig.withPortAndTimeout(0, 5000);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        nodeA.start();
        nodeB.start();

        int portA = nodeA.getTcpServer().getLocalPort();
        int portB = nodeB.getTcpServer().getLocalPort();

        try {
            CountDownLatch startLatch = new CountDownLatch(1);

            Thread t1 = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    nodeA.connectTo("127.0.0.1", portB);
                } catch (Exception ignored) {}
            });

            Thread t2 = Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    nodeB.connectTo("127.0.0.1", portA);
                } catch (Exception ignored) {}
            });

            startLatch.countDown();
            t1.join(3000);
            t2.join(3000);

            // Wait for both sides to complete handshake and register active peer sessions
            long deadline = System.currentTimeMillis() + 4000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() == 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert nodeA.getPeerManager().getConnectedPeerCount() == 1 : "Node A must have 1 connected peer";
            assert nodeB.getPeerManager().getConnectedPeerCount() == 1 : "Node B must have 1 connected peer";

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peerBonA.getState() == PeerState.CONNECTED : "Peer B must be CONNECTED on Node A";
            assert peerBonA.getConnection() != null && peerBonA.getConnection().isReady() : "Active connection on Node A must be READY";

            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow();
            assert peerAonB.getState() == PeerState.CONNECTED : "Peer A must be CONNECTED on Node B";
            assert peerAonB.getConnection() != null && peerAonB.getConnection().isReady() : "Active connection on Node B must be READY";

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }
}
