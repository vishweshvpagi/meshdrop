package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency test sending 100 messages rapidly across virtual threads to verify thread safety,
 * unique message IDs, no corruption, and complete delivery.
 */
public class ConcurrentMessagingTest {

    public void runAll() throws Exception {
        testConcurrentRapidMessaging();
    }

    private void testConcurrentRapidMessaging() throws Exception {
        int messageCount = 100;
        NodeIdentity local = NodeIdentity.createRandom("NodeA");
        NodeIdentity remote = NodeIdentity.createRandom("NodeB");

        PeerManager pmA = new PeerManager(local.nodeId());
        PeerManager pmB = new PeerManager(remote.nodeId());

        MessageService serviceA = new MessageService(local, pmA, 5000);
        MessageService serviceB = new MessageService(remote, pmB, 5000);

        CountDownLatch bReceivedLatch = new CountDownLatch(messageCount);
        Set<UUID> receivedMessageIds = ConcurrentHashMap.newKeySet();
        Set<String> receivedContents = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateCount = new AtomicInteger(0);

        serviceB.addListener(msg -> {
            boolean added = receivedMessageIds.add(msg.messageId());
            if (!added) {
                duplicateCount.incrementAndGet();
            }
            receivedContents.add(msg.content());
            bReceivedLatch.countDown();
        });

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection connA = new TcpConnection(client);
                TcpConnection connB = new TcpConnection(server);

                connA.setState(ConnectionState.READY);
                connA.setRemoteIdentity(remote);
                connB.setState(ConnectionState.READY);
                connB.setRemoteIdentity(local);

                Peer peerB = pmA.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), connA);
                Peer peerA = pmB.registerConnected(local, new PeerAddress("127.0.0.1", ss.getLocalPort()), connB);

                connA.startReceiving((c, p) -> serviceA.handleIncomingPacket(c, p));
                connB.startReceiving((c, p) -> serviceB.handleIncomingPacket(c, p));

                CountDownLatch sendCompletionLatch = new CountDownLatch(messageCount);

                // Dispatch 100 concurrent messages across virtual threads
                for (int i = 0; i < messageCount; i++) {
                    final int idx = i;
                    Thread.ofVirtual().name("sender-vthread-" + idx).start(() -> {
                        try {
                            String content = "Concurrent payload #" + idx;
                            serviceA.sendMessage(peerB, content)
                                    .thenAccept(res -> {
                                        if (res.isSuccess()) {
                                            sendCompletionLatch.countDown();
                                        }
                                    });
                        } catch (Exception ignored) {}
                    });
                }

                // Wait for all sends and ACKs
                boolean allReceived = bReceivedLatch.await(8, TimeUnit.SECONDS);
                assert allReceived : "Node B must receive all 100 messages within 8s, received: " + receivedMessageIds.size();

                boolean allAcked = sendCompletionLatch.await(8, TimeUnit.SECONDS);
                assert allAcked : "Node A must receive all 100 delivery ACKs within 8s";

                assert duplicateCount.get() == 0 : "No duplicate message listener firings allowed";
                assert receivedMessageIds.size() == messageCount : "All message IDs must be unique";
                assert receivedContents.size() == messageCount : "All message payloads must be distinct and non-corrupted";

                connA.close();
                connB.close();
            }
        } finally {
            serviceA.stop();
            serviceB.stop();
        }
    }
}
