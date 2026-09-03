package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit test verifying that incoming MESSAGE packets claiming a sender ID different
 * from the connection's authenticated remote identity are rejected.
 */
public class SenderIdentityValidationTest {

    public void runAll() throws Exception {
        testSpoofedSenderIdentityRejected();
    }

    private void testSpoofedSenderIdentityRejected() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity alice = NodeIdentity.createRandom("Alice-PC");
        NodeIdentity bob = NodeIdentity.createRandom("Bob-PC");

        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        service.addListener(msg -> listenerInvoked.set(true));

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                // The connection was authenticated as Alice!
                conn.setRemoteIdentity(alice);
                pm.registerConnected(alice, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                // Construct a spoofed message where senderId = Bob's ID!
                Message spoofed = Message.create(bob.nodeId(), local.nodeId(), "I claim to be Bob but I am on Alice's socket");
                Packet packet = Packet.createMessage(spoofed);

                service.handleIncomingPacket(conn, packet);

                assert !listenerInvoked.get() : "Spoofed sender message must be rejected and not delivered to listener";

                // Now verify a legitimate message from Alice succeeds
                Message legitimate = Message.create(alice.nodeId(), local.nodeId(), "Real message from Alice");
                Packet legitPacket = Packet.createMessage(legitimate);

                service.handleIncomingPacket(conn, legitPacket);
                assert listenerInvoked.get() : "Legitimate message from connection identity must be delivered";

                conn.close();
            }
        } finally {
            service.stop();
        }
    }
}
