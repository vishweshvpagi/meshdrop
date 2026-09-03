package com.meshdrop.transfer;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe manager coordinating all active and completed file transfer jobs.
 */
public class TransferManager {

    private final ConcurrentHashMap<UUID, Transfer> transfers = new ConcurrentHashMap<>();

    public void registerTransfer(Transfer transfer) {
        Objects.requireNonNull(transfer, "transfer must not be null");
        Transfer existing = transfers.putIfAbsent(transfer.getTransferId(), transfer);
        if (existing != null) {
            throw new IllegalArgumentException("Transfer with ID " + transfer.getTransferId() + " already exists");
        }
    }

    public Optional<Transfer> getTransfer(UUID transferId) {
        if (transferId == null) return Optional.empty();
        return Optional.ofNullable(transfers.get(transferId));
    }

    public Collection<Transfer> getAllTransfers() {
        return Collections.unmodifiableCollection(transfers.values());
    }

    public List<Transfer> getActiveTransfers() {
        return transfers.values().stream()
                .filter(t -> !t.getState().isTerminal() && !t.getState().isResumable())
                .toList();
    }

    public List<Transfer> getResumableTransfers() {
        return transfers.values().stream()
                .filter(t -> t.getState().isResumable())
                .toList();
    }

    public List<Transfer> getCompletedTransfers() {
        return transfers.values().stream()
                .filter(t -> t.getState() == TransferState.COMPLETED)
                .toList();
    }

    public Optional<Transfer> findTransfer(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String q = query.trim().toLowerCase();

        // 1. Exact UUID match
        try {
            UUID id = UUID.fromString(q);
            Transfer match = transfers.get(id);
            if (match != null) return Optional.of(match);
        } catch (IllegalArgumentException ignored) {}

        // 2. Short ID, prefix, or filename match
        return transfers.values().stream()
                .filter(t -> t.matchesIdentifier(query) ||
                        (t.getFileMetadata() != null && t.getFileMetadata().fileName().toLowerCase().contains(q)))
                .findFirst();
    }

    public void registerRecoveredTransfer(Transfer transfer) {
        Objects.requireNonNull(transfer, "transfer must not be null");
        transfers.put(transfer.getTransferId(), transfer);
    }

    public boolean removeTransfer(UUID transferId) {
        return transfers.remove(transferId) != null;
    }

    public void cancelTransfer(UUID transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer != null && !transfer.getState().isTerminal()) {
            transfer.setErrorMessage("Transfer cancelled by user");
            transfer.transitionTo(TransferState.CANCELLED);
        }
    }

    public void stop() {
        for (Transfer transfer : transfers.values()) {
            if (!transfer.getState().isTerminal()) {
                try {
                    transfer.transitionTo(TransferState.CANCELLED);
                } catch (Exception ignored) {}
            }
        }
    }
}
