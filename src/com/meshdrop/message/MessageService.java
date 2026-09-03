package com.meshdrop.message;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core application service for sending and receiving reliable peer-to-peer text messages.
 *
 * Responsibilities:
 *   - Creates and validates Message domain models.
 *   - Enforces 64 KiB message length limit.
 *   - Verifies recipient and sender identities.
 *   - Tracks delivery acknowledgement via PendingMessageRegistry.
 *   - Suppresses duplicate messages using a bounded LRU cache.
 *   - Dispatches received messages to registered MessageListeners.
 *   - Handles graceful shutdown and cancels in-flight pending requests.
 */
public class MessageService {

    private static final int MAX_DUPLICATE_CACHE_SIZE = 1000;

    private final NodeIdentity localIdentity;
    private final PeerManager peerManager;
    private final PendingMessageRegistry pendingRegistry;
    private final long ackTimeoutMillis;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Bounded LRU cache of recently processed message IDs. */
    private final Set<UUID> duplicateCache = Collections.synchronizedSet(
            Collections.newSetFromMap(
                    new LinkedHashMap<UUID, Boolean>(128, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                            return size() > MAX_DUPLICATE_CACHE_SIZE;
                        }
                    }
            )
    );

    public MessageService(NodeIdentity localIdentity, PeerManager peerManager, long ackTimeoutMillis) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity must not be null");
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager must not be null");
        this.pendingRegistry = new PendingMessageRegistry();
        this.ackTimeoutMillis = ackTimeoutMillis > 0 ? ackTimeoutMillis : ProtocolConstants.DEFAULT_MESSAGE_ACK_TIMEOUT_MS;
    }

    public MessageService(NodeIdentity localIdentity, PeerManager peerManager) {
        this(localIdentity, peerManager, ProtocolConstants.DEFAULT_MESSAGE_ACK_TIMEOUT_MS);
    }

    /**
     * Registers a listener for incoming messages.
     */
    public void addListener(MessageListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    /**
     * Sends a text message to a connected peer by node UUID.
     *
     * @param recipientId the target peer's UUID
     * @param content text content
     * @return CompletableFuture resolving to MessageDeliveryResult
     */
    public CompletableFuture<MessageDeliveryResult> sendMessage(UUID recipientId, String content) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.NODE_SHUTTING_DOWN, "Node is shutting down"));
        }

        if (recipientId == null) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.INVALID_MESSAGE, "Recipient ID must not be null"));
        }

        if (recipientId.equals(localIdentity.nodeId())) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.INVALID_MESSAGE, "Cannot send message to self"));
        }

        Peer peer = peerManager.findPeer(recipientId).orElse(null);
        if (peer == null) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.PEER_NOT_FOUND, "Peer not found: " + recipientId));
        }

        return sendMessage(peer, content);
    }

    /**
     * Sends a text message to a connected peer.
     *
     * @param peer target peer (must be in CONNECTED state with ready TcpConnection)
     * @param content text content
     * @return CompletableFuture resolving to MessageDeliveryResult upon ACK or timeout
     */
    public CompletableFuture<MessageDeliveryResult> sendMessage(Peer peer, String content) {
        if (!running.get()) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.NODE_SHUTTING_DOWN, "Node is shutting down"));
        }

        if (peer == null) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.PEER_NOT_FOUND, "Peer must not be null"));
        }

        if (!peer.isConnected()) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.NOT_CONNECTED,
                            "Peer " + peer.getDisplayName() + " is not connected (state=" + peer.getState() + ")"));
        }

        TcpConnection connection = peer.getConnection();
        if (connection == null || !connection.isReady()) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.NOT_READY,
                            "Peer " + peer.getDisplayName() + " has no active ready TCP connection"));
        }

        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.INVALID_MESSAGE, "Message content must not be empty"));
        }

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > ProtocolConstants.MAX_MESSAGE_BYTES) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.error(MessageDeliveryResult.Status.MESSAGE_TOO_LARGE,
                            "Message size (" + contentBytes.length + " bytes) exceeds maximum limit of " +
                            ProtocolConstants.MAX_MESSAGE_BYTES + " bytes"));
        }

        // Construct Message domain model
        Message message = Message.create(localIdentity.nodeId(), peer.getNodeId(), content);

        // Register in-flight tracking for delivery ACK
        CompletableFuture<MessageDeliveryResult> ackFuture =
                pendingRegistry.register(message.messageId(), ackTimeoutMillis);

        // Serialize to Packet and transmit over wire
        try {
            Packet packet = Packet.createMessage(message);
            connection.sendPacket(packet);
            Logger.fine("[MESSAGE] Sent message " + message.messageId() + " to " + peer.getDisplayName());
        } catch (IOException e) {
            pendingRegistry.complete(message.messageId()); // remove from pending
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.failure(MessageDeliveryResult.Status.SEND_FAILED, message.messageId(),
                            "Failed to write packet to connection: " + e.getMessage()));
        }

        return ackFuture;
    }

    /**
     * Handles an incoming Packet from a TcpConnection.
     * Routes MESSAGE and MESSAGE_ACK packets appropriately.
     */
    public void handleIncomingPacket(TcpConnection connection, Packet packet) {
        if (!running.get()) {
            return;
        }

        if (packet.getType() == PacketType.MESSAGE_ACK) {
            handleIncomingAck(packet);
        } else if (packet.getType() == PacketType.MESSAGE) {
            handleIncomingMessage(connection, packet);
        }
    }

    private void handleIncomingAck(Packet packet) {
        try {
            MessageCodec.AckPayload ack = packet.decodeMessageAck();
            boolean matched = pendingRegistry.complete(ack.messageId());
            if (matched) {
                Logger.fine("[MESSAGE_ACK] Received delivery ACK for message: " + ack.messageId());
            } else {
                Logger.fine("[MESSAGE_ACK] Received unrequested or expired ACK for message: " + ack.messageId());
            }
        } catch (ProtocolException e) {
            Logger.warn("[MESSAGE_ACK] Malformed ACK packet payload: " + e.getMessage());
        }
    }

    private void handleIncomingMessage(TcpConnection connection, Packet packet) {
        Message message;
        try {
            message = packet.decodeMessage();
        } catch (ProtocolException e) {
            Logger.warn("[MESSAGE] Dropping malformed message packet from " +
                    connection.getRemoteAddress() + ": " + e.getMessage());
            return;
        }

        // 1. Verify recipient identity
        if (!message.recipientId().equals(localIdentity.nodeId())) {
            Logger.warn("[MESSAGE] Dropping message addressed to different node: " +
                    message.recipientId() + " (local ID is " + localIdentity.nodeId() + ")");
            return;
        }

        // 2. Verify sender identity against connection remote identity
        if (connection.getRemoteIdentity() == null ||
                !message.senderId().equals(connection.getRemoteIdentity().nodeId())) {
            Logger.warn("[MESSAGE] Sender identity verification failed: connection identity is " +
                    (connection.getRemoteIdentity() != null ? connection.getRemoteIdentity().nodeId() : "null") +
                    ", but message senderId is " + message.senderId());
            return;
        }

        // 3. Duplicate check
        boolean isDuplicate;
        synchronized (duplicateCache) {
            isDuplicate = !duplicateCache.add(message.messageId());
        }

        // Always acknowledge receipt to peer so they don't keep timing out or retrying
        sendAck(connection, message.messageId());

        if (isDuplicate) {
            Logger.fine("[MESSAGE] Suppressing duplicate message: " + message.messageId());
            return;
        }

        Logger.fine("[MESSAGE] Received valid message " + message.messageId() + " from " +
                connection.getRemoteIdentity().displayName());

        // 4. Notify registered listeners
        for (MessageListener listener : listeners) {
            try {
                listener.onMessageReceived(message);
            } catch (Exception e) {
                Logger.warn("[MESSAGE] Listener threw exception: " + e.getMessage());
            }
        }
    }

    private void sendAck(TcpConnection connection, UUID messageId) {
        if (connection.isReady()) {
            try {
                Packet ackPacket = Packet.createMessageAck(messageId);
                connection.sendPacket(ackPacket);
                Logger.fine("[MESSAGE_ACK] Sent delivery ACK for message: " + messageId);
            } catch (IOException e) {
                Logger.warn("[MESSAGE_ACK] Failed to send ACK for " + messageId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Shuts down the service and cancels all pending acknowledgements.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            pendingRegistry.stop();
            duplicateCache.clear();
        }
    }

    public PendingMessageRegistry getPendingRegistry() {
        return pendingRegistry;
    }

    public boolean isRunning() {
        return running.get();
    }
}
