package com.meshdrop.message;

import java.util.UUID;

/**
 * Result carrier describing the outcome of an attempted message transmission.
 */
public record MessageDeliveryResult(
        Status status,
        UUID messageId,
        String description
) {

    public enum Status {
        SUCCESS,
        PEER_NOT_FOUND,
        NOT_CONNECTED,
        NOT_READY,
        MESSAGE_TOO_LARGE,
        INVALID_MESSAGE,
        SEND_FAILED,
        NODE_SHUTTING_DOWN,
        TIMEOUT
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static MessageDeliveryResult success(UUID messageId) {
        return new MessageDeliveryResult(Status.SUCCESS, messageId, "Message delivered and acknowledged.");
    }

    public static MessageDeliveryResult failure(Status status, UUID messageId, String description) {
        return new MessageDeliveryResult(status, messageId, description);
    }

    public static MessageDeliveryResult error(Status status, String description) {
        return new MessageDeliveryResult(status, null, description);
    }
}
