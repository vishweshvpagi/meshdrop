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
 * Unit test verifying that incoming MESSAGE packets addressed to a third-party recipient
 * (not the local node ID) are dropped and not delivered to local listeners.
 */
public class RecipientValidationTest {

    public void runAll() throws Exception {
        testWrongRecipientDropped();
    }

    private void testWrongRecipientDropped() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity sender = NodeIdentity.createRandom("SenderNode");
        UUID thirdPartyId = UUID.randomUUID();

        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        service.addListener(msg -> listenerInvoked.set(true));

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(sender);
                pm.registerConnected(sender, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                // Message addressed to thirdPartyId, NOT local.nodeId()!
                Message wrongRecipient = Message.create(sender.nodeId(), thirdPartyId, "Not for you");
                Packet packet = Packet.createMessage(wrongRecipient);

                service.handleIncomingPacket(conn, packet);

                assert !listenerInvoked.get() : "Message addressed to another node must not be delivered";

                // Message addressed to local node ID -> must be delivered
                Message correctRecipient = Message.create(sender.nodeId(), local.nodeId(), "For you");
                Packet legitPacket = Packet.createMessage(correctRecipient);

                service.handleIncomingPacket(conn, legitPacket);
                assert listenerInvoked.get() : "Message addressed to local node must be delivered";

                conn.close();
            }
        } finally {
            service.stop();
        }
    }
}
