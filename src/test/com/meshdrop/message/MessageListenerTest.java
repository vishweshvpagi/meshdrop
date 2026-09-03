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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for MessageListener notification, multiple listener dispatch, and exception resilience.
 */
public class MessageListenerTest {

    public void runAll() throws Exception {
        testSingleListenerNotification();
        testMultipleListenersDispatched();
        testListenerExceptionDoesNotCrashNetworking();
    }

    private void testSingleListenerNotification() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalReceiver");
        NodeIdentity remote = NodeIdentity.createRandom("RemoteSender");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        AtomicReference<Message> receivedMsg = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addListener(msg -> {
            receivedMsg.set(msg);
            latch.countDown();
        });

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                Message msg = Message.create(remote.nodeId(), local.nodeId(), "Test content");
                Packet packet = Packet.createMessage(msg);

                service.handleIncomingPacket(conn, packet);

                assert latch.await(2, TimeUnit.SECONDS) : "Listener must receive message within 2s";
                assert receivedMsg.get() != null;
                assert receivedMsg.get().messageId().equals(msg.messageId());
                assert "Test content".equals(receivedMsg.get().content());

                conn.close();
            }
        } finally {
            service.stop();
        }
    }

    private void testMultipleListenersDispatched() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalReceiver");
        NodeIdentity remote = NodeIdentity.createRandom("RemoteSender");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        service.addListener(msg -> { count.incrementAndGet(); latch.countDown(); });
        service.addListener(msg -> { count.incrementAndGet(); latch.countDown(); });
        service.addListener(msg -> { count.incrementAndGet(); latch.countDown(); });

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                Message msg = Message.create(remote.nodeId(), local.nodeId(), "Broadcast content");
                Packet packet = Packet.createMessage(msg);

                service.handleIncomingPacket(conn, packet);

                assert latch.await(2, TimeUnit.SECONDS) : "All 3 listeners must receive the message";
                assert count.get() == 3 : "Expected count 3, got " + count.get();

                conn.close();
            }
        } finally {
            service.stop();
        }
    }

    private void testListenerExceptionDoesNotCrashNetworking() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalReceiver");
        NodeIdentity remote = NodeIdentity.createRandom("RemoteSender");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        AtomicInteger healthyCount = new AtomicInteger(0);

        // First listener throws runtime exception
        service.addListener(msg -> { throw new RuntimeException("Bad listener crash"); });
        // Second listener must still be invoked
        service.addListener(msg -> healthyCount.incrementAndGet());

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                Message msg = Message.create(remote.nodeId(), local.nodeId(), "Fault tolerance test");
                Packet packet = Packet.createMessage(msg);

                // Must not throw or crash
                service.handleIncomingPacket(conn, packet);

                assert healthyCount.get() == 1 : "Healthy listener must still execute";

                conn.close();
            }
        } finally {
            service.stop();
        }
    }
}
