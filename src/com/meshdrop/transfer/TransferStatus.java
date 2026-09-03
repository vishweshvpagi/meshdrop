package com.meshdrop.transfer;

/**
 * Status lifecycle states for file transfer jobs.
 */
public enum TransferStatus {
    QUEUED,
    CONNECTING,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}
