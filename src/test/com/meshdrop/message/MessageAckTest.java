package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit test verifying bidirectional MESSAGE transmission and MESSAGE_ACK correlation.
 */
public class MessageAckTest {

    public void runAll() throws Exception {
        testMessageAckTransmissionAndCorrelation();
    }

    private void testMessageAckTransmissionAndCorrelation() throws Exception {
        NodeIdentity nodeAIdentity = NodeIdentity.createRandom("NodeA");
        NodeIdentity nodeBIdentity = NodeIdentity.createRandom("NodeB");

        PeerManager pmA = new PeerManager(nodeAIdentity.nodeId());
        PeerManager pmB = new PeerManager(nodeBIdentity.nodeId());

        MessageService serviceA = new MessageService(nodeAIdentity, pmA, 5000);
        MessageService serviceB = new MessageService(nodeBIdentity, pmB, 5000);

        CountDownLatch bReceivedLatch = new CountDownLatch(1);
        AtomicReference<Message> bReceivedMessage = new AtomicReference<>();
        serviceB.addListener(msg -> {
            bReceivedMessage.set(msg);
            bReceivedLatch.countDown();
        });

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection connA = new TcpConnection(client);
                TcpConnection connB = new TcpConnection(server);

                connA.setState(ConnectionState.READY);
                connA.setRemoteIdentity(nodeBIdentity);
                connB.setState(ConnectionState.READY);
                connB.setRemoteIdentity(nodeAIdentity);

                Peer peerB = pmA.registerConnected(nodeBIdentity, new PeerAddress("127.0.0.1", ss.getLocalPort()), connA);
                Peer peerA = pmB.registerConnected(nodeAIdentity, new PeerAddress("127.0.0.1", ss.getLocalPort()), connB);

                // Both endpoints route packets into their respective MessageService
                connA.startReceiving((c, p) -> serviceA.handleIncomingPacket(c, p));
                connB.startReceiving((c, p) -> serviceB.handleIncomingPacket(c, p));

                // Node A sends message to Node B
                String text = "Hello Node B from Node A";
                CompletableFuture<MessageDeliveryResult> deliveryFuture = serviceA.sendMessage(peerB, text);

                // 1. Verify Node B receives the message
                assert bReceivedLatch.await(3, TimeUnit.SECONDS) : "Node B must receive message";
                assert text.equals(bReceivedMessage.get().content());
                assert nodeAIdentity.nodeId().equals(bReceivedMessage.get().senderId());
                assert nodeBIdentity.nodeId().equals(bReceivedMessage.get().recipientId());

                // 2. Verify Node A receives the MESSAGE_ACK and completes the delivery future
                MessageDeliveryResult deliveryResult = deliveryFuture.get(3, TimeUnit.SECONDS);
                assert deliveryResult != null : "Delivery result must not be null";
                assert deliveryResult.isSuccess() : "Delivery must be acknowledged as SUCCESS";
                assert deliveryResult.messageId().equals(bReceivedMessage.get().messageId()) :
                        "Acknowledged message ID must match sent message ID";

                connA.close();
                connB.close();
            }
        } finally {
            serviceA.stop();
            serviceB.stop();
        }
    }
}
