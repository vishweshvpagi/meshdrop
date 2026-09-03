package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Unit test verifying that stopping MessageService while operations are pending
 * cancels all in-flight futures cleanly without hangs, memory leaks, or thread leaks.
 */
public class ShutdownMessagingTest {

    public void runAll() throws Exception {
        testShutdownCancelsPendingAcksCleanly();
    }

    private void testShutdownCancelsPendingAcksCleanly() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("NodeA");
        NodeIdentity remote = NodeIdentity.createRandom("NodeB");

        PeerManager pmA = new PeerManager(local.nodeId());
        // Long ACK timeout so operations stay in pending registry
        MessageService serviceA = new MessageService(local, pmA, 30_000);

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

                // Remote endpoint receives packets but intentionally never sends ACKs
                connB.startReceiving((c, p) -> {});

                List<CompletableFuture<MessageDeliveryResult>> futures = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    futures.add(serviceA.sendMessage(peerB, "Pending payload #" + i));
                }

                assert serviceA.getPendingRegistry().getPendingCount() == 10 :
                        "All 10 messages should be in pending registry";

                // Now abruptly shut down MessageService
                serviceA.stop();

                assert !serviceA.isRunning() : "Service must report not running";
                assert serviceA.getPendingRegistry().getPendingCount() == 0 :
                        "Pending registry must be completely emptied upon shutdown";

                // All in-flight futures must complete immediately without blocking or hanging
                for (CompletableFuture<MessageDeliveryResult> f : futures) {
                    MessageDeliveryResult res = f.get(1, TimeUnit.SECONDS);
                    assert res != null;
                    assert !res.isSuccess() : "Pending message must not be marked success";
                    assert res.status() == MessageDeliveryResult.Status.NODE_SHUTTING_DOWN :
                            "Status must be NODE_SHUTTING_DOWN, got: " + res.status();
                }

                connA.close();
                connB.close();
            }
        }
    }
}
