package com.meshdrop.network;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.protocol.HandshakeService;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Handles application-level binary packet dispatching for active TCP connections.
 *
 * Responsibilities:
 *   1. Initialises newly established connections by starting the packet receiver loop
 *      and delegating handshake negotiation to HandshakeService.
 *   2. Forwards verified post-handshake application packets to registered listeners.
 *   3. Enforces that protocol or handshake failures immediately terminate the connection.
 */
public class TcpConnectionHandler {

    private final HandshakeService handshakeService;
    private final BiConsumer<TcpConnection, Packet> onPacketReceived;

    public TcpConnectionHandler(HandshakeService handshakeService, BiConsumer<TcpConnection, Packet> onPacketReceived) {
        this.handshakeService = Objects.requireNonNull(handshakeService, "handshakeService must not be null");
        this.onPacketReceived = onPacketReceived != null ? onPacketReceived : (conn, p) -> {};
    }

    public TcpConnectionHandler(HandshakeService handshakeService) {
        this(handshakeService, (conn, p) -> {});
    }

    public TcpConnectionHandler(BiConsumer<TcpConnection, Packet> onPacketReceived) {
        this(new HandshakeService(NodeIdentity.createRandom()), onPacketReceived);
    }

    public TcpConnectionHandler() {
        this(new HandshakeService(NodeIdentity.createRandom()), (conn, p) -> {});
    }

    public HandshakeService getHandshakeService() {
        return handshakeService;
    }

    /**
     * Initialises a newly connected TcpConnection:
     *   1. Starts the asynchronous Packet receiver loop.
     *   2. Initiates the peer handshake.
     *
     * @param connection the connected TcpConnection
     * @throws IOException if initiating the handshake fails
     */
    public void handle(TcpConnection connection) throws IOException {
        // Start the packet receiver loop
        connection.startReceiving(this::dispatchPacket);

        // Initiate handshake
        handshakeService.initiateHandshake(connection);
    }

    /**
     * Internal packet dispatcher.
     */
    private void dispatchPacket(TcpConnection connection, Packet packet) {
        try {
            boolean handled = handshakeService.handlePacket(connection, packet);
            if (!handled) {
                if (packet.getType() == PacketType.MESSAGE) {
                    Logger.fine("Received MESSAGE frame from " + connection.getRemoteAddress() + " (" + packet.getLength() + " bytes)");
                } else if (packet.getType() == PacketType.MESSAGE_ACK) {
                    Logger.fine("Received MESSAGE_ACK frame from " + connection.getRemoteAddress());
                } else if (packet.getType() == PacketType.ERROR) {
                    String err = new String(packet.getPayload(), StandardCharsets.UTF_8);
                    Logger.warn("Received ERROR from " + connection.getRemoteAddress() + ": " + err);
                }
                onPacketReceived.accept(connection, packet);
            }
        } catch (Exception e) {
            Logger.severe("Error handling packet " + packet.getType() + " from " + connection.getRemoteAddress() + ": " + e.getMessage(), e);
            try {
                connection.close();
            } catch (IOException ignored) {}
        }
    }
}
