package com.meshdrop.protocol;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Manages peer identity exchange, HELLO/HELLO_RESPONSE negotiation, and connection state transitions.
 *
 * Responsibilities:
 *   1. Initiates handshakes by sending HELLO containing local NodeIdentity.
 *   2. Enforces handshake timeouts (closes stalled connections).
 *   3. Validates incoming HELLO and HELLO_RESPONSE payloads.
 *   4. Rejects self-connections (remote Node ID == local Node ID).
 *   5. Rejects application packets (e.g. MESSAGE) arriving before READY state.
 *   6. Transitions connection state from CONNECTED -> HANDSHAKING -> READY.
 */
public class HandshakeService {

    private final NodeIdentity localIdentity;
    private final int handshakeTimeoutMillis;
    private final BiConsumer<TcpConnection, NodeIdentity> onHandshakeCompleted;

    public HandshakeService(NodeIdentity localIdentity, int handshakeTimeoutMillis,
                            BiConsumer<TcpConnection, NodeIdentity> onHandshakeCompleted) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity must not be null");
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.onHandshakeCompleted = onHandshakeCompleted != null ? onHandshakeCompleted : (conn, id) -> {};
    }

    public HandshakeService(NodeIdentity localIdentity, int handshakeTimeoutMillis) {
        this(localIdentity, handshakeTimeoutMillis, (conn, id) -> {});
    }

    public HandshakeService(NodeIdentity localIdentity) {
        this(localIdentity, ProtocolConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS, (conn, id) -> {});
    }

    public NodeIdentity getLocalIdentity() {
        return localIdentity;
    }

    public int getHandshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    /**
     * Initiates the handshake on an active TcpConnection.
     *
     * @param connection the TcpConnection
     * @throws IOException if sending the HELLO packet fails
     */
    public void initiateHandshake(TcpConnection connection) throws IOException {
        Objects.requireNonNull(connection, "connection must not be null");

        // Transition to HANDSHAKING
        connection.setState(ConnectionState.HANDSHAKING);

        // Schedule handshake timeout timer
        scheduleHandshakeTimeout(connection);

        // Send HELLO packet with local NodeIdentity
        Packet helloPacket = Packet.createHello(localIdentity);
        connection.sendPacket(helloPacket);
        Logger.info("[HANDSHAKE] Sent HELLO (" + localIdentity.displayName() + ") to " + connection.getRemoteAddress());
    }

    /**
     * Processes an incoming Packet during or after handshake.
     *
     * @param connection the TcpConnection
     * @param packet the received Packet
     * @return true if the packet was consumed by HandshakeService (HELLO, HELLO_RESPONSE, PING),
     *         or false if it should be passed to higher-level application listeners (MESSAGE, etc.)
     * @throws IOException on protocol violations or self-connections
     */
    public boolean handlePacket(TcpConnection connection, Packet packet) throws IOException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(packet, "packet must not be null");

        if (connection.getState() == ConnectionState.CLOSED || connection.getState() == ConnectionState.CLOSING) {
            return true; // Ignore on closing/closed connections
        }

        // --- PRE-READY STATE: Only handshake packets are permitted ---
        if (!connection.isReady()) {
            if (packet.getType() == PacketType.HELLO) {
                NodeIdentity remoteId = NodeIdentity.decode(packet.getPayload());
                validateRemoteIdentity(connection, remoteId);

                connection.setRemoteIdentity(remoteId);

                // Respond with HELLO_RESPONSE echoing the request ID
                Packet response = Packet.createHelloResponse(packet.getRequestId(), localIdentity);
                connection.sendPacket(response);
                Logger.info("[HANDSHAKE] Sent HELLO_RESPONSE (" + localIdentity.displayName() + ") to " + connection.getRemoteAddress());

                // Transition to READY
                connection.setState(ConnectionState.READY);
                Logger.info("[HANDSHAKE] Connection " + connection.getConnectionId() + " is now READY with peer: " + remoteId);

                onHandshakeCompleted.accept(connection, remoteId);
                return true;
            }

            if (packet.getType() == PacketType.HELLO_RESPONSE) {
                NodeIdentity remoteId = NodeIdentity.decode(packet.getPayload());
                validateRemoteIdentity(connection, remoteId);

                connection.setRemoteIdentity(remoteId);

                // Transition to READY
                connection.setState(ConnectionState.READY);
                Logger.info("[HANDSHAKE] Received HELLO_RESPONSE from " + remoteId + ". Connection " +
                        connection.getConnectionId() + " is now READY.");

                onHandshakeCompleted.accept(connection, remoteId);
                return true;
            }

            // Any non-handshake packet before READY is a protocol violation
            Logger.warn("[HANDSHAKE] Received unexpected packet " + packet.getType() +
                    " before connection " + connection.getConnectionId() + " reached READY state");
            connection.close();
            throw new ProtocolException("Protocol violation: received " + packet.getType() +
                    " before handshake reached READY state");
        }

        // --- POST-READY STATE: Connection is fully established ---
        if (packet.getType() == PacketType.HELLO) {
            Logger.fine("[HANDSHAKE] Duplicate HELLO received from " + connection.getRemoteAddress() + " (ignored)");
            return true;
        }

        if (packet.getType() == PacketType.HELLO_RESPONSE) {
            Logger.fine("[HANDSHAKE] Duplicate HELLO_RESPONSE received from " + connection.getRemoteAddress() + " (ignored)");
            return true;
        }

        if (packet.getType() == PacketType.PING) {
            Logger.fine("[HANDSHAKE] Received PING from " + connection.getRemoteAddress() + ", responding with PONG");
            Packet pong = Packet.createPong(packet.getRequestId());
            connection.sendPacket(pong);
            return true;
        }

        // Forward application packets (MESSAGE, FILE_*, etc.) to higher layers
        return false;
    }

    /**
     * Validates remote peer identity, specifically detecting self-connections.
     */
    private void validateRemoteIdentity(TcpConnection connection, NodeIdentity remoteIdentity) throws ProtocolException {
        if (remoteIdentity.nodeId().equals(localIdentity.nodeId())) {
            Logger.warn("[HANDSHAKE] Self-connection detected from " + connection.getRemoteAddress() +
                    " (Remote Node ID matches local: " + localIdentity.nodeId() + ")");
            try {
                connection.close();
            } catch (IOException ignored) {}
            throw new ProtocolException("Self-connection rejected: remote Node ID " + remoteIdentity.nodeId() +
                    " matches local Node ID");
        }
    }

    /**
     * Schedules a background timeout task that closes the connection if handshake does not complete.
     */
    private void scheduleHandshakeTimeout(TcpConnection connection) {
        Thread.ofVirtual().name("handshake-timeout-" + connection.getConnectionId()).start(() -> {
            try {
                Thread.sleep(handshakeTimeoutMillis);
                if (connection.isOpen() && !connection.isReady()) {
                    Logger.warn("[HANDSHAKE] Handshake timeout (" + handshakeTimeoutMillis +
                            "ms) expired for connection " + connection.getConnectionId() + ". Closing.");
                    connection.close();
                }
            } catch (InterruptedException | IOException ignored) {}
        });
    }
}
