package com.meshdrop.message;

import com.meshdrop.util.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe correlation registry managing in-flight outgoing messages awaiting ACK.
 *
 * Responsibilities:
 *   - Registers messageId -> CompletableFuture<MessageDeliveryResult>.
 *   - Completes corresponding future when a valid matching MESSAGE_ACK arrives.
 *   - Enforces asynchronous timeout without blocking networking threads.
 *   - Cancels all pending requests upon node shutdown to prevent leaked promises.
 */
public class PendingMessageRegistry {

    private final ConcurrentHashMap<UUID, PendingEntry> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private record PendingEntry(
            UUID messageId,
            long sentAtNanos,
            CompletableFuture<MessageDeliveryResult> future
    ) {}

    /**
     * Registers an in-flight message and returns a CompletableFuture that completes
     * when an ACK is received or when the timeout expires.
     *
     * @param messageId unique message ID
     * @param timeoutMs timeout in milliseconds
     * @return CompletableFuture resolving to MessageDeliveryResult
     */
    public CompletableFuture<MessageDeliveryResult> register(UUID messageId, long timeoutMs) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        if (!running.get()) {
            return CompletableFuture.completedFuture(
                    MessageDeliveryResult.failure(MessageDeliveryResult.Status.NODE_SHUTTING_DOWN, messageId, "Node is shutting down"));
        }

        CompletableFuture<MessageDeliveryResult> future = new CompletableFuture<>();
        PendingEntry entry = new PendingEntry(messageId, System.nanoTime(), future);
        pending.put(messageId, entry);

        // Schedule timeout cleanup on a virtual thread
        Thread.ofVirtual().name("ack-timeout-" + messageId).start(() -> {
            try {
                Thread.sleep(timeoutMs);
                PendingEntry removed = pending.remove(messageId);
                if (removed != null) {
                    removed.future().complete(
                            MessageDeliveryResult.failure(MessageDeliveryResult.Status.TIMEOUT, messageId,
                                    "Delivery acknowledgement timed out after " + timeoutMs + "ms"));
                }
            } catch (InterruptedException ignored) {}
        });

        return future;
    }

    /**
     * Completes a pending message with success upon arrival of a matching MESSAGE_ACK.
     *
     * @param messageId acknowledged message ID
     * @return true if an entry was found and completed, false if unknown/already completed
     */
    public boolean complete(UUID messageId) {
        Objects.requireNonNull(messageId, "messageId must not be null");

        PendingEntry entry = pending.remove(messageId);
        if (entry != null) {
            entry.future().complete(MessageDeliveryResult.success(messageId));
            return true;
        }
        return false;
    }

    /**
     * Cancels all pending requests during node shutdown.
     */
    public void stop() {
        running.set(false);
        for (UUID id : pending.keySet()) {
            PendingEntry entry = pending.remove(id);
            if (entry != null) {
                entry.future().complete(
                        MessageDeliveryResult.failure(MessageDeliveryResult.Status.NODE_SHUTTING_DOWN, id,
                                "Node stopped before message acknowledgement was received"));
            }
        }
        pending.clear();
    }

    public int getPendingCount() {
        return pending.size();
    }

    public boolean isRunning() {
        return running.get();
    }
}
