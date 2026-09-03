package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit test verifying that duplicate MESSAGE packets with the same messageId are delivered only once.
 */
public class MessageDeduplicationTest {

    public void runAll() throws Exception {
        testDuplicateMessageSuppression();
    }

    private void testDuplicateMessageSuppression() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("RemotePeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        AtomicInteger deliveryCount = new AtomicInteger(0);
        service.addListener(msg -> deliveryCount.incrementAndGet());

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                Message msg = Message.create(remote.nodeId(), local.nodeId(), "Identical message content");
                Packet packet = Packet.createMessage(msg);

                // Send first time -> should notify listener
                service.handleIncomingPacket(conn, packet);
                assert deliveryCount.get() == 1 : "First delivery must fire listener, got: " + deliveryCount.get();

                // Send identical message second time -> duplicate cache must suppress listener notification
                service.handleIncomingPacket(conn, packet);
                assert deliveryCount.get() == 1 : "Duplicate message must NOT fire listener a second time";

                // Send identical message third time
                service.handleIncomingPacket(conn, packet);
                assert deliveryCount.get() == 1 : "Duplicate message must NOT fire listener on subsequent attempts";

                // Send a different message with new messageId -> must notify
                Message newMsg = Message.create(remote.nodeId(), local.nodeId(), "Different message content");
                Packet newPacket = Packet.createMessage(newMsg);
                service.handleIncomingPacket(conn, newPacket);
                assert deliveryCount.get() == 2 : "New message ID must fire listener";

                conn.close();
            }
        } finally {
            service.stop();
        }
    }
}
