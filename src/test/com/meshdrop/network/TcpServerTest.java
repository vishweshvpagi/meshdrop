package com.meshdrop.network;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketDecoder;
import com.meshdrop.protocol.PacketEncoder;
import com.meshdrop.protocol.PacketType;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Unit tests for TcpServer: port binding, accept, HELLO identity exchange, and shutdown.
 */
public class TcpServerTest {

    public void runAll() throws Exception {
        testServerStartAndStop();
        testAcceptAndGreeting();
        testMultipleConnections();
    }

    /**
     * Verifies that the server can bind a port, reports itself as running,
     * and can be stopped cleanly.
     */
    private void testServerStartAndStop() throws Exception {
        TcpConnectionHandler handler = new TcpConnectionHandler();
        TcpServer server = new TcpServer(0, handler);

        assert !server.isRunning() : "Server should not be running before start()";

        server.start();
        assert server.isRunning() : "Server should be running after start()";
        assert server.getLocalPort() > 0 : "Local port should be assigned after start()";

        Thread.sleep(100);

        server.close();
        Thread.sleep(100);
        assert !server.isRunning() : "Server should not be running after close()";
    }

    /**
     * Connects a client socket to the server and verifies that the server
     * sends its initial HELLO packet with valid NodeIdentity.
     */
    private void testAcceptAndGreeting() throws Exception {
        NodeIdentity serverIdentity = NodeIdentity.createRandom("ServerNode");
        TcpConnectionHandler handler = new TcpConnectionHandler(new com.meshdrop.protocol.HandshakeService(serverIdentity));
        TcpServer server = new TcpServer(0, handler);
        server.start();
        int actualPort = server.getLocalPort();
        Thread.sleep(200);

        try {
            try (Socket client = new Socket("127.0.0.1", actualPort)) {
                client.setSoTimeout(5_000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                PacketDecoder decoder = new PacketDecoder();
                PacketEncoder encoder = new PacketEncoder();

                // Read HELLO packet sent by server
                Packet greeting = decoder.decode(in);
                assert greeting != null : "Should have received greeting packet";
                assert greeting.getType() == PacketType.HELLO : "Greeting packet must be HELLO, got: " + greeting.getType();

                NodeIdentity remoteServerId = greeting.decodeIdentity();
                assert remoteServerId.nodeId().equals(serverIdentity.nodeId()) : "Server Node ID mismatch";
                assert remoteServerId.displayName().equals("ServerNode") : "Server display name mismatch";

                // Send HELLO_RESPONSE back with distinct client identity
                NodeIdentity clientId = NodeIdentity.createRandom("ClientNode");
                Packet response = Packet.createHelloResponse(greeting.getRequestId(), clientId);
                encoder.encode(response, out);
            }
            Thread.sleep(200);
        } finally {
            server.close();
        }
    }

    /**
     * Verifies that the server can handle multiple sequential connections.
     * Each connection should independently exchange HELLO identity packets.
     */
    private void testMultipleConnections() throws Exception {
        TcpConnectionHandler handler = new TcpConnectionHandler();
        TcpServer server = new TcpServer(0, handler);
        server.start();
        int actualPort = server.getLocalPort();
        Thread.sleep(200);

        try {
            PacketDecoder decoder = new PacketDecoder();
            PacketEncoder encoder = new PacketEncoder();

            for (int i = 0; i < 3; i++) {
                try (Socket client = new Socket("127.0.0.1", actualPort)) {
                    client.setSoTimeout(5_000);
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();

                    Packet greeting = decoder.decode(in);
                    assert greeting != null && greeting.getType() == PacketType.HELLO :
                            "Connection " + i + " should receive HELLO";

                    NodeIdentity clientId = NodeIdentity.createRandom("SeqClient-" + i);
                    Packet response = Packet.createHelloResponse(greeting.getRequestId(), clientId);
                    encoder.encode(response, out);

                    // Post-handshake message
                    Packet clientMsg = Packet.createMessage("Client-" + i);
                    encoder.encode(clientMsg, out);
                }
                Thread.sleep(100);
            }
        } finally {
            server.close();
        }
    }
}
