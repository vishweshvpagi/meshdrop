package com.meshdrop.network;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.protocol.HandshakeService;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketDecoder;
import com.meshdrop.protocol.PacketType;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive unit and integration tests for TcpConnection using framed binary Packets.
 */
public class TcpConnectionTest {

    public void runAll() throws Exception {
        testIncomingConnectionState();
        testOutgoingConnection();
        testSendAndReceivePacket();
        testBidirectionalPacketCommunication();
        testHelloAndPingPongExchange();
        testMultiplePacketsOnPersistentConnection();
        testTenSimultaneousConnections();
        testConnectionClose();
        testSendAfterCloseFails();
        testRemoteAndLocalAddress();
        testDisconnectionDetection();
    }

    // ========================================================================
    // Test 1: Incoming (accepted) socket wrapping
    // ========================================================================
    private void testIncomingConnectionState() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Socket clientSock = new Socket("127.0.0.1", port);
            Socket acceptedSock = ss.accept();

            TcpConnection conn = new TcpConnection(acceptedSock);
            assert conn.getState() == ConnectionState.CONNECTED :
                    "Accepted socket should be CONNECTED, got " + conn.getState();
            assert conn.getConnectionId() > 0 : "Connection ID should be positive";
            assert conn.isOpen() : "Connection should be open";

            conn.close();
            clientSock.close();
            assert conn.getState() == ConnectionState.CLOSED : "Should be CLOSED after close()";
            assert !conn.isOpen() : "Should not be open after close()";
        }
    }

    // ========================================================================
    // Test 2: Outgoing connection via connectTo()
    // ========================================================================
    private void testOutgoingConnection() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection outgoing = TcpConnection.connectTo("127.0.0.1", port);
            Socket accepted = ss.accept();

            assert outgoing.getState() == ConnectionState.CONNECTED :
                    "Outgoing connection should be CONNECTED";
            assert outgoing.isOpen() : "Outgoing connection should be open";

            outgoing.close();
            accepted.close();
        }
    }

    // ========================================================================
    // Test 3: Send a Packet from client and receive on server
    // ========================================================================
    private void testSendAndReceivePacket() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection clientConn = TcpConnection.connectTo("127.0.0.1", port);
            Socket serverSock = ss.accept();

            UUID reqId = UUID.randomUUID();
            Packet messagePacket = Packet.createMessage(reqId, "Hello Packet World");
            clientConn.sendPacket(messagePacket);

            PacketDecoder decoder = new PacketDecoder();
            Packet received = decoder.decode(serverSock.getInputStream());

            assert received != null : "Received packet must not be null";
            assert received.getType() == PacketType.MESSAGE : "Type mismatch";
            assert received.getRequestId().equals(reqId) : "Request ID mismatch";
            assert new String(received.getPayload(), StandardCharsets.UTF_8).equals("Hello Packet World") : "Payload mismatch";

            clientConn.close();
            serverSock.close();
        }
    }

    // ========================================================================
    // Test 4: Bidirectional Packet communication on a single connection
    // ========================================================================
    private void testBidirectionalPacketCommunication() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection clientConn = TcpConnection.connectTo("127.0.0.1", port);
            Socket serverSock = ss.accept();
            TcpConnection serverConn = new TcpConnection(serverSock);

            CopyOnWriteArrayList<Packet> clientReceived = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<Packet> serverReceived = new CopyOnWriteArrayList<>();
            CountDownLatch clientLatch = new CountDownLatch(1);
            CountDownLatch serverLatch = new CountDownLatch(1);

            clientConn.startReceiving((conn, packet) -> {
                clientReceived.add(packet);
                clientLatch.countDown();
            });

            serverConn.startReceiving((conn, packet) -> {
                serverReceived.add(packet);
                serverLatch.countDown();
            });

            // Client -> Server
            clientConn.sendPacket(Packet.createMessage("Client message"));
            // Server -> Client
            serverConn.sendPacket(Packet.createMessage("Server message"));

            assert serverLatch.await(3, TimeUnit.SECONDS) : "Server should receive packet within 3s";
            assert clientLatch.await(3, TimeUnit.SECONDS) : "Client should receive packet within 3s";

            assert !serverReceived.isEmpty() && serverReceived.get(0).getType() == PacketType.MESSAGE : "Server packet mismatch";
            assert !clientReceived.isEmpty() && clientReceived.get(0).getType() == PacketType.MESSAGE : "Client packet mismatch";

            clientConn.close();
            serverConn.close();
        }
    }

    // ========================================================================
    // Test 5: REAL TCP INTEGRATION: HELLO -> HELLO_RESPONSE then PING -> PONG
    // ========================================================================
    private void testHelloAndPingPongExchange() throws Exception {
        NodeIdentity serverId = NodeIdentity.createRandom("Server");
        NodeIdentity clientId = NodeIdentity.createRandom("Client");

        HandshakeService serverHandshake = new HandshakeService(serverId);
        TcpConnectionHandler handler = new TcpConnectionHandler(serverHandshake);
        TcpServer server = new TcpServer(0, handler);
        server.start();
        int port = server.getLocalPort();
        Thread.sleep(100);

        try {
            TcpConnection client = TcpConnection.connectTo("127.0.0.1", port);

            CopyOnWriteArrayList<Packet> receivedPackets = new CopyOnWriteArrayList<>();
            CountDownLatch helloResponseLatch = new CountDownLatch(1);
            CountDownLatch pongLatch1 = new CountDownLatch(1);
            CountDownLatch pongLatch2 = new CountDownLatch(1);

            client.startReceiving((conn, packet) -> {
                receivedPackets.add(packet);
                if (packet.getType() == PacketType.HELLO_RESPONSE) {
                    helloResponseLatch.countDown();
                } else if (packet.getType() == PacketType.PONG) {
                    if (pongLatch1.getCount() > 0) {
                        pongLatch1.countDown();
                    } else {
                        pongLatch2.countDown();
                    }
                }
            });

            // 1. Send HELLO with client identity, expect HELLO_RESPONSE with server identity
            UUID helloReqId = UUID.randomUUID();
            client.sendPacket(Packet.createHello(helloReqId, clientId));
            assert helloResponseLatch.await(3, TimeUnit.SECONDS) : "Should receive HELLO_RESPONSE";

            // 2. Send 1st PING, expect 1st PONG on same connection
            UUID ping1 = UUID.randomUUID();
            client.sendPacket(Packet.createPing(ping1));
            assert pongLatch1.await(3, TimeUnit.SECONDS) : "Should receive first PONG";

            // 3. Send 2nd PING, expect 2nd PONG on same connection
            UUID ping2 = UUID.randomUUID();
            client.sendPacket(Packet.createPing(ping2));
            assert pongLatch2.await(3, TimeUnit.SECONDS) : "Should receive second PONG";

            assert client.isOpen() : "Connection must remain open throughout multi-packet exchange";

            client.close();
        } finally {
            server.close();
        }
    }

    // ========================================================================
    // Test 6: Multiple messages over one persistent connection
    // ========================================================================
    private void testMultiplePacketsOnPersistentConnection() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection sender = TcpConnection.connectTo("127.0.0.1", port);
            Socket receiverSock = ss.accept();
            TcpConnection receiver = new TcpConnection(receiverSock);

            int totalPackets = 5;
            CountDownLatch allReceived = new CountDownLatch(totalPackets);
            CopyOnWriteArrayList<Packet> packets = new CopyOnWriteArrayList<>();

            receiver.startReceiving((conn, packet) -> {
                packets.add(packet);
                allReceived.countDown();
            });

            for (int i = 0; i < totalPackets; i++) {
                sender.sendPacket(Packet.createMessage("Message-" + i));
            }

            assert allReceived.await(5, TimeUnit.SECONDS) : "All " + totalPackets + " packets should arrive within 5s";
            assert packets.size() == totalPackets : "Expected " + totalPackets + " packets, got: " + packets.size();

            for (int i = 0; i < totalPackets; i++) {
                String text = new String(packets.get(i).getPayload(), StandardCharsets.UTF_8);
                assert text.equals("Message-" + i) : "Packet " + i + " payload mismatch: " + text;
            }

            assert sender.isOpen() : "Sender should still be open";
            assert receiver.isOpen() : "Receiver should still be open";

            sender.close();
            receiver.close();
        }
    }

    // ========================================================================
    // Test 7: 10 simultaneous connections exchanging packets concurrently
    // ========================================================================
    private void testTenSimultaneousConnections() throws Exception {
        int numClients = 10;
        CountDownLatch allPacketsReceived = new CountDownLatch(numClients);

        NodeIdentity serverId = NodeIdentity.createRandom("TenConnServer");
        HandshakeService handshakeService = new HandshakeService(serverId);

        TcpConnectionHandler handler = new TcpConnectionHandler(handshakeService, (conn, packet) -> {
            if (packet.getType() == PacketType.MESSAGE) {
                allPacketsReceived.countDown();
            }
        });

        TcpServer server = new TcpServer(0, handler);
        server.start();
        int port = server.getLocalPort();
        Thread.sleep(100);

        TcpConnection[] clients = new TcpConnection[numClients];
        try {
            for (int i = 0; i < numClients; i++) {
                clients[i] = TcpConnection.connectTo("127.0.0.1", port);
                NodeIdentity cid = NodeIdentity.createRandom("Client-" + i);
                clients[i].startReceiving((conn, packet) -> {});
                // Send HELLO to complete handshake
                clients[i].sendPacket(Packet.createHello(cid));
            }

            Thread.sleep(300);

            for (int i = 0; i < numClients; i++) {
                clients[i].sendPacket(Packet.createMessage("ClientMsg-" + i));
            }

            assert allPacketsReceived.await(5, TimeUnit.SECONDS) :
                    "Server should receive messages from all 10 clients concurrently";

            for (int i = 0; i < numClients; i++) {
                assert clients[i].isOpen() : "Client " + i + " should still be open";
            }

            // Disconnecting one client should not affect others
            clients[3].close();
            Thread.sleep(100);
            assert !clients[3].isOpen() : "Client 3 should be closed";
            assert clients[0].isOpen() : "Client 0 should remain open";
            assert clients[9].isOpen() : "Client 9 should remain open";

        } finally {
            for (TcpConnection c : clients) {
                if (c != null) {
                    try { c.close(); } catch (IOException ignored) {}
                }
            }
            server.close();
        }
    }

    // ========================================================================
    // Test 8: Connection close and state transitions
    // ========================================================================
    private void testConnectionClose() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection conn = TcpConnection.connectTo("127.0.0.1", port);
            Socket accepted = ss.accept();

            assert conn.getState() == ConnectionState.CONNECTED : "Should be CONNECTED";

            conn.close();
            assert conn.getState() == ConnectionState.CLOSED : "Should be CLOSED after close()";

            // Idempotent close
            conn.close();
            assert conn.getState() == ConnectionState.CLOSED : "Should remain CLOSED";

            accepted.close();
        }
    }

    // ========================================================================
    // Test 9: Send after close throws IOException
    // ========================================================================
    private void testSendAfterCloseFails() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection conn = TcpConnection.connectTo("127.0.0.1", port);
            Socket accepted = ss.accept();

            conn.close();

            boolean threw = false;
            try {
                conn.sendPacket(Packet.createPing());
            } catch (IOException e) {
                threw = true;
                assert e.getMessage().contains("CLOSED") :
                        "Exception should mention CLOSED state, got: " + e.getMessage();
            }
            assert threw : "sendPacket() after close() should throw IOException";

            accepted.close();
        }
    }

    // ========================================================================
    // Test 10: Remote and local address accessors
    // ========================================================================
    private void testRemoteAndLocalAddress() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection conn = TcpConnection.connectTo("127.0.0.1", port);
            Socket accepted = ss.accept();

            assert conn.getRemoteAddress() != null : "Remote address should not be null";
            assert conn.getLocalAddress() != null : "Local address should not be null";
            assert conn.getRemoteAddress().toString().contains(String.valueOf(port)) :
                    "Remote address should contain port " + port;

            conn.close();
            accepted.close();
        }
    }

    // ========================================================================
    // Test 11: Disconnection detection — server detects EOF on remote close
    // ========================================================================
    private void testDisconnectionDetection() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            TcpConnection clientConn = TcpConnection.connectTo("127.0.0.1", port);
            Socket acceptedSock = ss.accept();
            TcpConnection serverConn = new TcpConnection(acceptedSock);

            serverConn.startReceiving((conn, packet) -> {});

            // Client disconnects
            clientConn.close();

            // Wait for server-side receive loop to detect EOF and close connection
            long deadline = System.currentTimeMillis() + 3_000;
            while (serverConn.getState() != ConnectionState.CLOSED && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assert serverConn.getState() == ConnectionState.CLOSED :
                    "Server connection should detect client disconnect, state=" + serverConn.getState();

            // Verify another client can connect to ServerSocket
            TcpConnection client2 = TcpConnection.connectTo("127.0.0.1", port);
            Socket accepted2 = ss.accept();
            assert client2.isOpen() : "New client should connect successfully";
            client2.close();
            accepted2.close();
        }
    }
}
