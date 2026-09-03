package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit test verifying UTF-8 encoding correctness across various languages, emojis, and symbols.
 */
public class UnicodeMessagingTest {

    public void runAll() throws Exception {
        testUnicodeMessageTransmission();
    }

    private void testUnicodeMessageTransmission() throws Exception {
        NodeIdentity local = NodeIdentity.createRandom("NodeA");
        NodeIdentity remote = NodeIdentity.createRandom("NodeB");

        PeerManager pmA = new PeerManager(local.nodeId());
        PeerManager pmB = new PeerManager(remote.nodeId());

        MessageService serviceA = new MessageService(local, pmA, 5000);
        MessageService serviceB = new MessageService(remote, pmB, 5000);

        List<String> unicodeSamples = List.of(
                "ನಮಸ್ಕಾರ MeshDrop 🌐",
                "Hello, world! 🚀 🌟 💻",
                "こんにちは世界！ MeshDropへようこそ 🇯🇵",
                "Café, naïve, résumé, crème brûlée - Français 🥐",
                "مرحبا بالعالم - Arabic test 🌍",
                "Привет мир - Russian test 🇷🇺",
                "🎉✨ Multi-byte Unicode: 𝄞 𝄢 𠮷野家 ✨🎉"
        );

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

                for (String sample : unicodeSamples) {
                    CountDownLatch latch = new CountDownLatch(1);
                    AtomicReference<Message> received = new AtomicReference<>();

                    MessageListener listener = msg -> {
                        received.set(msg);
                        latch.countDown();
                    };
                    serviceB.addListener(listener);

                    MessageDeliveryResult result = serviceA.sendMessage(peerB, sample).get(3, TimeUnit.SECONDS);
                    assert result.isSuccess() : "Send must succeed for sample: " + sample;

                    assert latch.await(3, TimeUnit.SECONDS) : "Must receive sample within 3s: " + sample;
                    assert sample.equals(received.get().content()) :
                            "Payload mismatch: expected '" + sample + "', got '" + received.get().content() + "'";

                    serviceB.removeListener(listener);
                }

                connA.close();
                connB.close();
            }
        } finally {
            serviceA.stop();
            serviceB.stop();
        }
    }
}
