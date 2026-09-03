package com.meshdrop.transfer;

/**
 * Event listener for tracking file transfer lifecycle and progress events.
 */
public interface TransferListener {

    default void onTransferStarted(Transfer transfer) {}

    default void onTransferProgress(Transfer transfer) {}

    default void onTransferInterrupted(Transfer transfer) {}

    default void onTransferResuming(Transfer transfer) {}

    default void onTransferCompleted(Transfer transfer) {}

    default void onTransferFailed(Transfer transfer, String reason) {}

    default void onTransferCancelled(Transfer transfer) {}
}
