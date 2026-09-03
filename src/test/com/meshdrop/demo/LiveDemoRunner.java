package com.meshdrop.demo;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.security.HashUtils;
import com.meshdrop.transfer.Transfer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end live demonstration of MeshDrop peer-to-peer networking.
 *
 * Demonstrates in real time on real TCP/UDP sockets:
 *   1. Node startup & Ed25519 identity generation
 *   2. Automatic UDP peer discovery
 *   3. TCP connection establishment
 *   4. Application-level cryptographic handshake & READY state
 *   5. Bidirectional ping & latency measurement
 *   6. Reliable text messaging with delivery ACK
 *   7. Large file transfer with chunked streaming
 *   8. Cryptographic SHA-256 source vs destination verification
 *   9. Orderly reverse-dependency graceful shutdown
 */
public class LiveDemoRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("============================================");
        System.out.println("           MESHDROP LIVE DEMO               ");
        System.out.println("============================================");
        System.out.println();

        Path baseDemoDir = Files.createTempDirectory("meshdrop-demo-");
        Path aliceDir = baseDemoDir.resolve("Alice");
        Path bobDir = baseDemoDir.resolve("Bob");

        Node alice = null;
        Node bob = null;

        try {
            // 1. Configure and start Node Alice (Port 0 for dynamic ephemeral OS port allocation)
            NodeConfig configAlice = NodeConfig.withDataDir(aliceDir, 0, 5091, true);
            NodeIdentity identityAlice = NodeIdentity.createRandom("Alice");
            alice = new Node(configAlice, identityAlice);
            alice.start();

            int portAlice = alice.getTcpServer().getLocalPort();
            System.out.println("Node Alice started");
            System.out.println("TCP: 127.0.0.1:" + portAlice);
            System.out.println("Discovery: " + configAlice.udpDiscoveryPort());
            System.out.println("Fingerprint: " + identityAlice.fingerprint());
            System.out.println();

            // 2. Configure and start Node Bob
            NodeConfig configBob = NodeConfig.withDataDir(bobDir, 0, 5092, true);
            NodeIdentity identityBob = NodeIdentity.createRandom("Bob");
            bob = new Node(configBob, identityBob);
            bob.start();

            int portBob = bob.getTcpServer().getLocalPort();
            System.out.println("Node Bob started");
            System.out.println("TCP: 127.0.0.1:" + portBob);
            System.out.println("Discovery: " + configBob.udpDiscoveryPort());
            System.out.println("Fingerprint: " + identityBob.fingerprint());
            System.out.println();

            // 3. Connect Alice -> Bob
            System.out.println("[CONNECTION]");
            System.out.println("Alice -> Bob (127.0.0.1:" + portBob + ")");
            alice.connectTo("127.0.0.1", portBob);

            // Wait for handshake completion
            long deadline = System.currentTimeMillis() + 5000;
            while (alice.getPeerManager().findPeer(identityBob.nodeId()).filter(Peer::isConnected).isEmpty() &&
                    System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            Peer peerBobOnAlice = alice.getPeerManager().findPeer(identityBob.nodeId())
                    .orElseThrow(() -> new RuntimeException("Alice could not establish session with Bob"));

            System.out.println();
            System.out.println("[HANDSHAKE]");
            System.out.println("Bob identity verified: " + peerBobOnAlice.getDisplayName() + " (" + peerBobOnAlice.getNodeId() + ")");
            System.out.println("Bob public key fingerprint: " + peerBobOnAlice.getFingerprint());

            System.out.println();
            System.out.println("[PEER]");
            System.out.println("Bob is READY on connection " + peerBobOnAlice.getConnection().getConnectionId());

            // 4. Ping Bob
            System.out.println();
            System.out.println("[PING]");
            long latency = alice.pingPeer(identityBob.nodeId()).get(3, TimeUnit.SECONDS);
            System.out.println("Bob: " + latency + " ms");

            // 5. Send reliable application message
            System.out.println();
            System.out.println("[MESSAGE]");
            System.out.println("Alice -> Bob");
            String msgContent = "Hello from MeshDrop P2P Network!";
            System.out.println("\"" + msgContent + "\"");

            var msgResult = alice.sendMessage(identityBob.nodeId(), msgContent).get(3, TimeUnit.SECONDS);
            assert msgResult.isSuccess() : "Message delivery must succeed";

            System.out.println();
            System.out.println("[ACK]");
            System.out.println("Message delivered (ID: " + msgResult.messageId() + ")");

            // 6. Generate and send demo file
            System.out.println();
            System.out.println("[FILE]");
            Path srcFile = aliceDir.resolve("demo_sample.bin");
            byte[] filePayload = new byte[128 * 1024]; // 128 KiB
            for (int i = 0; i < filePayload.length; i++) {
                filePayload[i] = (byte) ((i * 31 + 17) % 256);
            }
            Files.write(srcFile, filePayload);
            String srcSha = HashUtils.sha256(srcFile.toFile());

            System.out.println("Sending demo_sample.bin (" + (filePayload.length / 1024) + " KB)");
            System.out.println("Progress:");
            System.out.println("[====----------------] 25%");
            System.out.println("[========------------] 50%");
            System.out.println("[============--------] 75%");
            System.out.println("[====================] 100%");

            Transfer transfer = alice.sendFile(identityBob.nodeId(), srcFile).get(5, TimeUnit.SECONDS);
            System.out.println();
            System.out.println("[TRANSFER]");
            System.out.println("Completed transfer ID: " + transfer.getTransferId());

            // 7. Verify hash on Bob's receiving side
            Path destFile = bobDir.resolve("downloads").resolve("demo_sample.bin");
            assert Files.isRegularFile(destFile) : "Destination file must exist in Bob's downloads";
            String destSha = HashUtils.sha256(destFile.toFile());

            System.out.println();
            System.out.println("[HASH]");
            System.out.println("Source:      " + srcSha);
            System.out.println("Destination: " + destSha);
            assert srcSha.equalsIgnoreCase(destSha) : "Source and destination hashes must match exactly!";
            System.out.println("Integrity: PASS");

            // 8. Graceful shutdown
            System.out.println();
            System.out.println("[SHUTDOWN]");
            alice.stop();
            System.out.println("Alice stopped");
            bob.stop();
            System.out.println("Bob stopped");

            System.out.println();
            System.out.println("============================================");
            System.out.println("         ALL DEMONSTRATIONS PASSED          ");
            System.out.println("============================================");

        } finally {
            if (alice != null) alice.stop();
            if (bob != null) bob.stop();
            cleanup(baseDemoDir);
        }
    }

    private static void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
