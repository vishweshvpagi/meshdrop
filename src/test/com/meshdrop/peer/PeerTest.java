package com.meshdrop.peer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

/**
 * Unit tests for Peer model.
 */
public class PeerTest {

    public void runAll() throws Exception {
        testPeerCreation();
        testIdentityPreservation();
        testAddressPreservation();
        testInitialState();
        testStateTransitions();
        testConnectionAssociation();
        testDisconnectBehavior();
        testSameUuidEquality();
        testDisplayNameDoesNotDetermineIdentity();
    }

    private void testPeerCreation() {
        NodeIdentity identity = NodeIdentity.createRandom("Alice");
        PeerAddress address = new PeerAddress("192.168.1.10", 5000);
        Peer peer = new Peer(identity, address);

        assert peer.getNodeId().equals(identity.nodeId()) : "Node ID mismatch";
        assert peer.getDisplayName().equals("Alice") : "Display name mismatch";
        assert peer.getAddress().equals(address) : "Address mismatch";
        assert peer.getState() == PeerState.DISCOVERED : "Default state should be DISCOVERED";
        assert peer.getConnection() == null : "Connection should be null initially";
    }

    private void testIdentityPreservation() {
        UUID id = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.of(id, "Bob");
        PeerAddress address = new PeerAddress("10.0.0.5", 5000);
        Peer peer = new Peer(identity, address, PeerState.CONNECTED);

        assert peer.getIdentity().equals(identity) : "Identity mismatch";
        assert peer.getNodeId().equals(id) : "UUID mismatch";
        assert peer.getDisplayName().equals("Bob") : "Name mismatch";
    }

    private void testAddressPreservation() {
        NodeIdentity identity = NodeIdentity.createRandom("Charlie");
        PeerAddress address1 = new PeerAddress("127.0.0.1", 5000);
        Peer peer = new Peer(identity, address1);

        assert peer.getAddress().host().equals("127.0.0.1") : "Host mismatch";
        assert peer.getAddress().tcpPort() == 5000 : "Port mismatch";

        PeerAddress address2 = new PeerAddress("192.168.0.20", 5002);
        peer.setAddress(address2);
        assert peer.getAddress().equals(address2) : "Updated address mismatch";
    }

    private void testInitialState() {
        NodeIdentity identity = NodeIdentity.createRandom("Dave");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);

        Peer discovered = Peer.discovered(identity, address);
        assert discovered.getState() == PeerState.DISCOVERED : "Should be DISCOVERED";

        Peer connecting = new Peer(identity, address, PeerState.CONNECTING);
        assert connecting.getState() == PeerState.CONNECTING : "Should be CONNECTING";
    }

    private void testStateTransitions() {
        NodeIdentity identity = NodeIdentity.createRandom("Eve");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);
        Peer peer = new Peer(identity, address, PeerState.DISCOVERED);

        peer.setState(PeerState.CONNECTING);
        assert peer.getState() == PeerState.CONNECTING : "State should be CONNECTING";

        peer.setState(PeerState.CONNECTED);
        assert peer.getState() == PeerState.CONNECTED : "State should be CONNECTED";
        assert peer.getConnectedAt() != null : "ConnectedAt timestamp should be set";

        peer.setState(PeerState.DISCONNECTED);
        assert peer.getState() == PeerState.DISCONNECTED : "State should be DISCONNECTED";
    }

    private void testConnectionAssociation() throws Exception {
        NodeIdentity identity = NodeIdentity.createRandom("Frank");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);
        Peer peer = new Peer(identity, address);

        try (ServerSocket ss = new ServerSocket(0)) {
            Socket client = new Socket("127.0.0.1", ss.getLocalPort());
            Socket server = ss.accept();
            TcpConnection conn = new TcpConnection(server);

            peer.setConnection(conn);
            peer.setState(PeerState.CONNECTED);

            assert peer.getConnection() == conn : "Connection should be associated";
            assert peer.isConnected() : "peer.isConnected() should be true";

            conn.close();
            client.close();
        }
    }

    private void testDisconnectBehavior() {
        NodeIdentity identity = NodeIdentity.createRandom("Grace");
        PeerAddress address = new PeerAddress("127.0.0.1", 5000);
        Peer peer = new Peer(identity, address, PeerState.CONNECTED);

        peer.setState(PeerState.DISCONNECTED);
        peer.setConnection(null);

        assert peer.getState() == PeerState.DISCONNECTED : "State should be DISCONNECTED";
        assert peer.getConnection() == null : "Connection should be cleared";
        assert !peer.isConnected() : "isConnected() should be false";

        // Identity and address must remain intact
        assert peer.getIdentity().equals(identity) : "Identity must be preserved across disconnect";
        assert peer.getAddress().equals(address) : "Address must be preserved across disconnect";
    }

    private void testSameUuidEquality() {
        UUID id = UUID.randomUUID();
        NodeIdentity id1 = NodeIdentity.of(id, "Name1");
        NodeIdentity id2 = NodeIdentity.of(id, "Name2"); // Same UUID, different display name

        Peer p1 = new Peer(id1, new PeerAddress("1.1.1.1", 5000));
        Peer p2 = new Peer(id2, new PeerAddress("2.2.2.2", 6000));

        assert p1.equals(p2) : "Peers with the same UUID must be equal";
        assert p1.hashCode() == p2.hashCode() : "Hash codes must match for same UUID";
    }

    private void testDisplayNameDoesNotDetermineIdentity() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        NodeIdentity id1 = NodeIdentity.of(u1, "DuplicateName");
        NodeIdentity id2 = NodeIdentity.of(u2, "DuplicateName");

        Peer p1 = new Peer(id1, new PeerAddress("1.1.1.1", 5000));
        Peer p2 = new Peer(id2, new PeerAddress("1.1.1.1", 5000));

        assert !p1.equals(p2) : "Peers with different UUIDs must NOT be equal even if names match";
    }
}
