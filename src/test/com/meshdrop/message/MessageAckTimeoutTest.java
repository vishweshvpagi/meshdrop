package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Unit test verifying that an unacknowledged message properly times out and cleans up registry state.
 */
public class MessageAckTimeoutTest {

    public void runAll() throws Exception {
        testAckTimeoutAndPendingCleanup();
    }

    private void testAckTimeoutAndPendingCleanup() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("LocalNode");
        NodeIdentity remote = NodeIdentity.createRandom("SilentPeer");
        PeerManager pm = new PeerManager(local.nodeId());

        // Short timeout: 300 ms
        long shortTimeoutMs = 300;
        MessageService service = new MessageService(local, pm, shortTimeoutMs);

        try (ServerSocket ss = new ServerSocket(0)) {
            try (Socket client = new Socket("127.0.0.1", ss.getLocalPort());
                 Socket server = ss.accept()) {

                TcpConnection conn = new TcpConnection(client);
                TcpConnection remoteConn = new TcpConnection(server);

                conn.setState(ConnectionState.READY);
                conn.setRemoteIdentity(remote);
                remoteConn.setState(ConnectionState.READY);
                remoteConn.setRemoteIdentity(local);

                Peer peer = pm.registerConnected(remote, new PeerAddress("127.0.0.1", ss.getLocalPort()), conn);

                // Remote node receives the message but intentionally NEVER sends an ACK back
                remoteConn.startReceiving((c, p) -> {});

                CompletableFuture<MessageDeliveryResult> future =
                        service.sendMessage(peer, "Unacknowledged test message");

                assert service.getPendingRegistry().getPendingCount() == 1 : "In-flight message must be registered";

                // Wait for timeout
                MessageDeliveryResult result = future.get(2, TimeUnit.SECONDS);

                assert result != null;
                assert !result.isSuccess() : "Delivery must fail on timeout";
                assert result.status() == MessageDeliveryResult.Status.TIMEOUT : "Status must be TIMEOUT, got: " + result.status();
                assert result.description().contains("timed out");

                // Verify pending state was completely cleaned up (no memory leak)
                assert service.getPendingRegistry().getPendingCount() == 0 :
                        "Pending registry must be empty after timeout, got: " + service.getPendingRegistry().getPendingCount();

                conn.close();
                remoteConn.close();
            }
        } finally {
            service.stop();
        }
    }
}
