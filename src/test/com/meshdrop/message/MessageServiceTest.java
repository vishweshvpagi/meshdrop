package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.peer.PeerState;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.protocol.ProtocolConstants;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive unit tests for MessageService transmission, validation, and lifecycle.
 */
public class MessageServiceTest {

    public void runAll() throws Exception {
        testSuccessfulSendAndAck();
        testUnknownPeerRejected();
        testDisconnectedPeerRejected();
        testNotReadyConnectionRejected();
        testOversizedMessageRejected();
        testInvalidMessageRejected();
        testShutdownBehavior();
    }

    private void testSuccessfulSendAndAck() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("RemotePeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm, 3000);

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection localConn = new TcpConnection(server);
                TcpConnection remoteConn = new TcpConnection(client);

                localConn.setState(ConnectionState.READY);
                localConn.setRemoteIdentity(remote);
                remoteConn.setState(ConnectionState.READY);
                remoteConn.setRemoteIdentity(local);

                Peer peer = pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), localConn);

                // Remote endpoint listens for the MESSAGE packet and auto-replies with MESSAGE_ACK
                remoteConn.startReceiving((conn, packet) -> {
                    if (packet.getType() == PacketType.MESSAGE) {
                        try {
                            Packet ack = Packet.createMessageAck(packet.getRequestId());
                            conn.sendPacket(ack);
                        } catch (Exception ignored) {}
                    }
                });

                // Local endpoint routes incoming packets to service
                localConn.startReceiving((conn, packet) -> {
                    service.handleIncomingPacket(conn, packet);
                });

                CompletableFuture<MessageDeliveryResult> future =
                        service.sendMessage(peer, "Hello from local node to remote peer!");

                MessageDeliveryResult result = future.get(3, TimeUnit.SECONDS);
                assert result != null : "Result must not be null";
                assert result.isSuccess() : "Delivery should succeed with ACK";
                assert result.messageId() != null;

                localConn.close();
                remoteConn.close();
            }
        } finally {
            service.stop();
        }
    }

    private void testUnknownPeerRejected() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        UUID unknownId = UUID.randomUUID();
        CompletableFuture<MessageDeliveryResult> future = service.sendMessage(unknownId, "Hello unknown");
        MessageDeliveryResult result = future.get(1, TimeUnit.SECONDS);

        assert !result.isSuccess();
        assert result.status() == MessageDeliveryResult.Status.PEER_NOT_FOUND;
        service.stop();
    }

    private void testDisconnectedPeerRejected() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("OfflinePeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        Peer peer = new Peer(remote, new PeerAddress("127.0.0.1", 5000), PeerState.DISCONNECTED);

        CompletableFuture<MessageDeliveryResult> future = service.sendMessage(peer, "Hello offline");
        MessageDeliveryResult result = future.get(1, TimeUnit.SECONDS);

        assert !result.isSuccess();
        assert result.status() == MessageDeliveryResult.Status.NOT_CONNECTED;
        service.stop();
    }

    private void testNotReadyConnectionRejected() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("HandshakingPeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.HANDSHAKING); // Not READY!

                Peer peer = new Peer(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), PeerState.CONNECTING);
                peer.setConnection(conn);

                CompletableFuture<MessageDeliveryResult> future = service.sendMessage(peer, "Hello handshaking");
                MessageDeliveryResult result = future.get(1, TimeUnit.SECONDS);

                assert !result.isSuccess();
                assert result.status() == MessageDeliveryResult.Status.NOT_CONNECTED ||
                       result.status() == MessageDeliveryResult.Status.NOT_READY;

                conn.close();
            }
        } finally {
            service.stop();
        }
    }

    private void testOversizedMessageRejected() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("TargetPeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                Peer peer = pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                String oversized = "X".repeat(ProtocolConstants.MAX_MESSAGE_BYTES + 10);
                CompletableFuture<MessageDeliveryResult> future = service.sendMessage(peer, oversized);
                MessageDeliveryResult result = future.get(1, TimeUnit.SECONDS);

                assert !result.isSuccess();
                assert result.status() == MessageDeliveryResult.Status.MESSAGE_TOO_LARGE;

                conn.close();
            }
        } finally {
            service.stop();
        }
    }

    private void testInvalidMessageRejected() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("TargetPeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(server);
                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                Peer peer = pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                // Empty content
                CompletableFuture<MessageDeliveryResult> f1 = service.sendMessage(peer, "");
                assert !f1.get().isSuccess();
                assert f1.get().status() == MessageDeliveryResult.Status.INVALID_MESSAGE;

                // Self send
                CompletableFuture<MessageDeliveryResult> f2 = service.sendMessage(local.nodeId(), "Self message");
                assert !f2.get().isSuccess();
                assert f2.get().status() == MessageDeliveryResult.Status.INVALID_MESSAGE;

                conn.close();
            }
        } finally {
            service.stop();
        }
    }

    private void testShutdownBehavior() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("TargetPeer");
        PeerManager pm = new PeerManager(local.nodeId());
        MessageService service = new MessageService(local, pm);

        service.stop();
        assert !service.isRunning();

        CompletableFuture<MessageDeliveryResult> future = service.sendMessage(remote.nodeId(), "Hello stopped");
        MessageDeliveryResult result = future.get(1, TimeUnit.SECONDS);

        assert !result.isSuccess();
        assert result.status() == MessageDeliveryResult.Status.NODE_SHUTTING_DOWN;
    }
}
