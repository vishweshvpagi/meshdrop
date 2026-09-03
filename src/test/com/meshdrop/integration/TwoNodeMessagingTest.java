package com.meshdrop.integration;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.message.Message;
import com.meshdrop.message.MessageDeliveryResult;
import com.meshdrop.peer.Peer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end integration test verifying two live MeshDrop nodes:
 *   1. Automatic UDP discovery
 *   2. TCP connection and handshake
 *   3. Message sending with reliable delivery ACK (A -> B)
 *   4. Message field verification (ID, sender, recipient, timestamp, payload)
 *   5. Reverse message sending with reliable delivery ACK (B -> A)
 *   6. PING/PONG latency measurement
 *   7. Graceful clean shutdown
 */
public class TwoNodeMessagingTest {

    public void runAll() throws Exception {
        testTwoNodeEndToEndMessaging();
    }

    private void testTwoNodeEndToEndMessaging() throws Exception {
        NodeIdentity idA = NodeIdentity.createRandom("NodeA-Messaging");
        NodeIdentity idB = NodeIdentity.createRandom("NodeB-Messaging");

        NodeConfig configA = NodeConfig.withDiscovery(0, 0, true);
        NodeConfig configB = NodeConfig.withDiscovery(0, 0, true);

        Node nodeA = new Node(configA, idA);
        Node nodeB = new Node(configB, idB);

        CountDownLatch bReceivedLatch = new CountDownLatch(1);
        AtomicReference<Message> bReceivedMessage = new AtomicReference<>();

        CountDownLatch aReceivedLatch = new CountDownLatch(1);
        AtomicReference<Message> aReceivedMessage = new AtomicReference<>();

        nodeA.start();
        nodeB.start();

        // Register Phase 10 MessageListeners
        nodeB.getMessageService().addListener(msg -> {
            bReceivedMessage.set(msg);
            bReceivedLatch.countDown();
        });

        nodeA.getMessageService().addListener(msg -> {
            aReceivedMessage.set(msg);
            aReceivedLatch.countDown();
        });

        try {
            int portA = nodeA.getDiscoveryService().getUdpDiscoveryPort();
            int portB = nodeB.getDiscoveryService().getUdpDiscoveryPort();

            // Trigger discovery by sending beacons to each other's UDP discovery port
            nodeA.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portB);
            nodeB.getDiscoveryService().sendUnicastBeacon("127.0.0.1", portA);

            // Wait for both sides to establish active CONNECTED session
            long deadline = System.currentTimeMillis() + 6000;
            while ((nodeA.getPeerManager().getConnectedPeerCount() == 0 ||
                    nodeB.getPeerManager().getConnectedPeerCount() == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert nodeA.getPeerManager().getConnectedPeerCount() >= 1 : "Node A must have connected peer";
            assert nodeB.getPeerManager().getConnectedPeerCount() >= 1 : "Node B must have connected peer";

            Peer peerBonA = nodeA.getPeerManager().findPeer(idB.nodeId()).orElseThrow();
            assert peerBonA.isConnected() : "Peer B must be connected on Node A";

            Peer peerAonB = nodeB.getPeerManager().findPeer(idA.nodeId()).orElseThrow();
            assert peerAonB.isConnected() : "Peer A must be connected on Node B";

            // Step 1: Node A sends message to Node B
            String msgAtoB = "Hello from Node A to Node B";
            long beforeSendA = System.currentTimeMillis();
            CompletableFuture<MessageDeliveryResult> futureA = nodeA.sendMessage(idB.nodeId(), msgAtoB);

            // Verify B receives message
            assert bReceivedLatch.await(3, TimeUnit.SECONDS) : "Node B must receive message from Node A within 3s";
            Message receivedAtB = bReceivedMessage.get();
            assert receivedAtB != null;
            assert msgAtoB.equals(receivedAtB.content()) : "Message content mismatch at B: " + receivedAtB.content();
            assert idA.nodeId().equals(receivedAtB.senderId()) : "Sender ID mismatch at B";
            assert idB.nodeId().equals(receivedAtB.recipientId()) : "Recipient ID mismatch at B";
            assert receivedAtB.timestamp() >= beforeSendA : "Timestamp should be >= beforeSendA";
            assert receivedAtB.messageId() != null : "Message ID must be present";

            // Verify A receives delivery ACK
            MessageDeliveryResult resultA = futureA.get(3, TimeUnit.SECONDS);
            assert resultA != null && resultA.isSuccess() : "Node A must receive ACK for sent message";
            assert resultA.messageId().equals(receivedAtB.messageId()) : "ACK message ID must match sent message ID";

            // Step 2: Node B sends message to Node A (reverse direction)
            String msgBtoA = "Hello back from Node B to Node A";
            long beforeSendB = System.currentTimeMillis();
            CompletableFuture<MessageDeliveryResult> futureB = nodeB.sendMessage(idA.nodeId(), msgBtoA);

            // Verify A receives message
            assert aReceivedLatch.await(3, TimeUnit.SECONDS) : "Node A must receive message from Node B within 3s";
            Message receivedAtA = aReceivedMessage.get();
            assert receivedAtA != null;
            assert msgBtoA.equals(receivedAtA.content()) : "Message content mismatch at A: " + receivedAtA.content();
            assert idB.nodeId().equals(receivedAtA.senderId()) : "Sender ID mismatch at A";
            assert idA.nodeId().equals(receivedAtA.recipientId()) : "Recipient ID mismatch at A";
            assert receivedAtA.timestamp() >= beforeSendB : "Timestamp should be >= beforeSendB";
            assert receivedAtA.messageId() != null : "Message ID must be present";

            // Verify B receives delivery ACK
            MessageDeliveryResult resultB = futureB.get(3, TimeUnit.SECONDS);
            assert resultB != null && resultB.isSuccess() : "Node B must receive ACK for reverse message";
            assert resultB.messageId().equals(receivedAtA.messageId()) : "ACK message ID must match reverse message ID";

            // Step 3: Node A pings Node B
            CompletableFuture<Long> pingFuture = nodeA.pingPeer(idB.nodeId());
            Long latency = pingFuture.get(3, TimeUnit.SECONDS);
            assert latency != null && latency >= 0 : "Ping latency must be non-negative: " + latency;

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }
}
