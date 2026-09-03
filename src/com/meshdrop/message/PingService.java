package com.meshdrop.message;

import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketType;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Application-level service for MeshDrop protocol PING/PONG latency measurement.
 *
 * Sends PING packets with tracked request IDs and completes futures when
 * matching PONG responses arrive. Leverages the existing HandshakeService
 * PING→PONG auto-response on the remote side.
 */
public class PingService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    /** Pending ping requests keyed by request ID. */
    private final ConcurrentHashMap<UUID, PendingPing> pendingPings = new ConcurrentHashMap<>();

    /**
     * Sends a PING packet to the specified peer and returns a future
     * that completes with the round-trip latency in milliseconds.
     *
     * @param peer the target peer (must be CONNECTED)
     * @return CompletableFuture resolving to latency in ms, or completing exceptionally on timeout/error
     * @throws IOException if the peer is not connected or sending fails
     */
    public CompletableFuture<Long> ping(Peer peer) throws IOException {
        Objects.requireNonNull(peer, "peer must not be null");

        if (!peer.isConnected()) {
            throw new IOException("Peer " + peer.getDisplayName() + " is not connected");
        }

        TcpConnection connection = peer.getConnection();
        if (connection == null || !connection.isReady()) {
            throw new IOException("Peer " + peer.getDisplayName() + " has no active ready connection");
        }

        UUID requestId = UUID.randomUUID();
        long sentAt = System.nanoTime();

        CompletableFuture<Long> future = new CompletableFuture<>();
        PendingPing pending = new PendingPing(requestId, sentAt, future);
        pendingPings.put(requestId, pending);

        // Schedule timeout cleanup
        Thread.ofVirtual().name("ping-timeout-" + requestId).start(() -> {
            try {
                Thread.sleep(DEFAULT_TIMEOUT_MS);
                PendingPing expired = pendingPings.remove(requestId);
                if (expired != null) {
                    expired.future().completeExceptionally(
                            new IOException("Ping to " + peer.getDisplayName() + " timed out after " + DEFAULT_TIMEOUT_MS + "ms"));
                }
            } catch (InterruptedException ignored) {}
        });

        // Send the PING packet
        Packet pingPacket = Packet.createPing(requestId);
        connection.sendPacket(pingPacket);

        Logger.fine("[PING] Sent PING (req=" + requestId + ") to " + peer.getDisplayName());

        return future;
    }

    /**
     * Handles an incoming PONG packet by completing the matching pending ping future.
     *
     * @param packet the PONG packet (must have type PONG)
     */
    public void handlePong(Packet packet) {
        if (packet.getType() != PacketType.PONG) {
            return;
        }

        UUID requestId = packet.getRequestId();
        PendingPing pending = pendingPings.remove(requestId);
        if (pending != null) {
            long latencyNanos = System.nanoTime() - pending.sentAtNanos();
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(latencyNanos);
            pending.future().complete(latencyMs);
            Logger.fine("[PING] PONG received (req=" + requestId + "), latency=" + latencyMs + "ms");
        }
    }

    /**
     * Returns the number of currently pending (unanswered) pings.
     */
    public int getPendingCount() {
        return pendingPings.size();
    }

    /**
     * Represents a pending ping request awaiting PONG response.
     */
    private record PendingPing(UUID requestId, long sentAtNanos, CompletableFuture<Long> future) {}
}
