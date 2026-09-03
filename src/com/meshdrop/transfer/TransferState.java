package com.meshdrop.transfer;

/**
 * State machine representing the lifecycle stages of a file transfer,
 * including offer negotiation, chunked streaming, verification, interruption,
 * recoverable checkpoints, cancellation, and resumption.
 */
public enum TransferState {
    OFFERING,
    WAITING_FOR_ACCEPT,
    ACCEPTED,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED,
    RESUMABLE,
    RESUMING;

    /**
     * Checks if a transition from this state to the next state is permitted.
     */
    public boolean canTransitionTo(TransferState next) {
        if (this == next) {
            return true; // Idempotent
        }

        // Terminal states cannot transition to anything, except FAILED/TIMED_OUT which can be resumed
        if (this == COMPLETED || this == REJECTED || this == CANCELLED) {
            return false;
        }

        if (this == FAILED || this == TIMED_OUT) {
            return next == RESUMING || next == RESUMABLE;
        }

        // Any non-terminal state can transition to FAILED, CANCELLED, or TIMED_OUT
        if (next == FAILED || next == CANCELLED || next == TIMED_OUT) {
            return true;
        }

        return switch (this) {
            case OFFERING -> next == WAITING_FOR_ACCEPT || next == ACCEPTED || next == TRANSFERRING || next == REJECTED;
            case WAITING_FOR_ACCEPT -> next == ACCEPTED || next == TRANSFERRING || next == REJECTED;
            case ACCEPTED -> next == TRANSFERRING || next == VERIFYING;
            case TRANSFERRING -> next == VERIFYING || next == INTERRUPTED;
            case VERIFYING -> next == COMPLETED || next == INTERRUPTED || next == TRANSFERRING || next == RESUMING;
            case INTERRUPTED -> next == RESUMABLE || next == RESUMING || next == TRANSFERRING;
            case RESUMABLE -> next == RESUMING || next == TRANSFERRING || next == VERIFYING;
            case RESUMING -> next == TRANSFERRING || next == VERIFYING || next == INTERRUPTED;
            default -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    public boolean isResumable() {
        return this == INTERRUPTED || this == RESUMABLE || this == FAILED || this == TIMED_OUT;
    }
}
