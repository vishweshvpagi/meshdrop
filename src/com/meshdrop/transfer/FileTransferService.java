package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;
import com.meshdrop.security.HashUtils;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-level orchestration service for reliable, resumable peer-to-peer file transfers.
 *
 * Coordinates FileSender and FileReceiver instances, enforces offer and resume timeouts,
 * validates sender/recipient identities, manages non-blocking approvals, and maintains
 * verified on-disk checkpoints via RecoveryManager.
 */
public class FileTransferService {

    @FunctionalInterface
    public interface ApprovalHandler {
        CompletableFuture<Boolean> requestApproval(FileMetadata metadata, Peer sender);
    }

    private final NodeIdentity localIdentity;
    private final PeerManager peerManager;
    private final TransferManager transferManager;
    private final RecoveryManager recoveryManager;
    private final Path downloadsDir;
    private final Path tempDir;
    private final int chunkSize;
    private final long offerTimeoutMs;

    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Transfer>> pendingOfferFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Transfer>> pendingResumeFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FileReceiver> activeReceivers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FileSender> activeSenders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TcpConnection> transferConnections = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile ApprovalHandler approvalHandler = (meta, sender) -> CompletableFuture.completedFuture(true);

    public FileTransferService(
            NodeIdentity localIdentity,
            PeerManager peerManager,
            Path downloadsDir,
            Path tempDir,
            int chunkSize,
            long offerTimeoutMs
    ) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity must not be null");
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager must not be null");
        this.downloadsDir = Objects.requireNonNull(downloadsDir, "downloadsDir must not be null");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir must not be null");
        this.chunkSize = chunkSize > 0 ? chunkSize : ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE;
        this.offerTimeoutMs = offerTimeoutMs > 0 ? offerTimeoutMs : ProtocolConstants.DEFAULT_FILE_OFFER_TIMEOUT_MS;
        this.transferManager = new TransferManager();
        this.recoveryManager = new RecoveryManager(tempDir);
    }

    public FileTransferService(NodeIdentity localIdentity, PeerManager peerManager, Path downloadsDir, Path tempDir) {
        this(localIdentity, peerManager, downloadsDir, tempDir, ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE, ProtocolConstants.DEFAULT_FILE_OFFER_TIMEOUT_MS);
    }

    public void addListener(TransferListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(TransferListener listener) {
        listeners.remove(listener);
    }

    public void setApprovalHandler(ApprovalHandler approvalHandler) {
        this.approvalHandler = approvalHandler != null ? approvalHandler : (meta, sender) -> CompletableFuture.completedFuture(false);
    }

    public TransferManager getTransferManager() {
        return transferManager;
    }

    public RecoveryManager getRecoveryManager() {
        return recoveryManager;
    }

    /**
     * Scans recovery directory at startup and registers all consistent checkpoints as RESUMABLE transfers.
     */
    public void scanAndRegisterRecoverableTransfers() {
        List<TransferCheckpoint> checkpoints = recoveryManager.scanRecoverableCheckpoints();
        for (TransferCheckpoint cp : checkpoints) {
            Path partFile = recoveryManager.getPartFilePath(cp.transferId());
            Transfer transfer = Transfer.fromCheckpoint(cp, partFile);
            transferManager.registerRecoveredTransfer(transfer);
        }
    }

    // ========================================================================
    // Outbound File Transfer Initiation
    // ========================================================================

    /**
     * Initiates an outgoing file transfer to a connected peer.
     */
    public CompletableFuture<Transfer> sendFile(Peer peer, Path filePath) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IOException("FileTransferService is shutting down"));
        }

        Objects.requireNonNull(peer, "peer must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");

        if (!peer.isConnected() || peer.getConnection() == null || !peer.getConnection().isReady()) {
            return CompletableFuture.failedFuture(new IOException("Peer " + peer.getDisplayName() + " is not connected"));
        }

        if (!Files.isRegularFile(filePath)) {
            return CompletableFuture.failedFuture(new IOException("File does not exist or is not a regular file: " + filePath));
        }

        try {
            long fileSize = Files.size(filePath);
            String sha256 = HashUtils.sha256(filePath.toFile());
            String fileName = filePath.getFileName().toString();

            FileMetadata metadata = FileMetadata.create(localIdentity.nodeId(), peer.getNodeId(), fileName, fileSize, sha256);
            Transfer transfer = new Transfer(metadata, TransferDirection.UPLOAD, filePath);
            transferManager.registerTransfer(transfer);

            CompletableFuture<Transfer> completionFuture = new CompletableFuture<>();
            pendingOfferFutures.put(metadata.transferId(), completionFuture);
            transferConnections.put(metadata.transferId(), peer.getConnection());

            // Send FILE_OFFER packet
            Packet offerPacket = Packet.createFileOffer(metadata);
            peer.getConnection().sendPacket(offerPacket);
            transfer.transitionTo(TransferState.WAITING_FOR_ACCEPT);

            Logger.info("[TRANSFER] Sent FILE_OFFER for " + fileName + " (" + fileSize + " bytes) to " + peer.getDisplayName());

            // Schedule offer timeout (only triggers if peer never accepted/rejected within offerTimeoutMs)
            Thread.ofVirtual().name("offer-timeout-" + metadata.transferId()).start(() -> {
                try {
                    Thread.sleep(offerTimeoutMs);
                    if (transfer.getState() == TransferState.WAITING_FOR_ACCEPT) {
                        CompletableFuture<Transfer> pending = pendingOfferFutures.remove(metadata.transferId());
                        if (pending != null && !pending.isDone()) {
                            transfer.transitionTo(TransferState.FAILED);
                            transfer.setErrorMessage("Offer timed out after " + offerTimeoutMs + "ms");
                            pending.completeExceptionally(new IOException("Offer timed out waiting for peer acceptance"));
                            notifyFailed(transfer, "Offer timed out");
                        }
                    }
                } catch (InterruptedException ignored) {}
            });

            return completionFuture;

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Resumes an interrupted transfer to the target peer from its current checkpoint.
     */
    public CompletableFuture<Transfer> resumeTransfer(UUID transferId, Peer peer) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(new IOException("FileTransferService is shutting down"));
        }
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(peer, "peer must not be null");

        if (!peer.isConnected() || peer.getConnection() == null || !peer.getConnection().isReady()) {
            return CompletableFuture.failedFuture(new IOException("Peer " + peer.getDisplayName() + " is not connected"));
        }

        Transfer transfer = transferManager.getTransfer(transferId).orElse(null);
        if (transfer == null) {
            return CompletableFuture.failedFuture(new IOException("Transfer not found: " + transferId));
        }

        FileMetadata meta = transfer.getFileMetadata();
        if (meta == null) {
            return CompletableFuture.failedFuture(new IOException("Transfer metadata is missing for: " + transferId));
        }

        if (transfer.getState().canTransitionTo(TransferState.RESUMING)) {
            transfer.transitionTo(TransferState.RESUMING);
            notifyResuming(transfer);
        }

        CompletableFuture<Transfer> future = new CompletableFuture<>();
        pendingResumeFutures.put(transferId, future);
        transferConnections.put(transferId, peer.getConnection());

        FileTransferCodec.ResumeRequestPayload req = new FileTransferCodec.ResumeRequestPayload(
                transferId,
                meta.senderId(),
                meta.recipientId(),
                meta.fileSize(),
                chunkSize,
                meta.sha256()
        );

        try {
            peer.getConnection().sendPacket(Packet.createFileResumeRequest(req));
            Logger.info("[TRANSFER] Sent FILE_RESUME_REQUEST for " + meta.fileName() + " (ID: " + transferId + ") to " + peer.getDisplayName());
        } catch (IOException e) {
            pendingResumeFutures.remove(transferId);
            return CompletableFuture.failedFuture(e);
        }

        return future;
    }

    /**
     * Cancels an active, interrupted, or resumable transfer and cleans up artifacts.
     */
    public void cancelTransfer(UUID transferId) {
        if (transferId == null) return;

        Transfer transfer = transferManager.getTransfer(transferId).orElse(null);
        if (transfer != null) {
            transfer.cancel("Transfer cancelled by user");
        }

        FileReceiver receiver = activeReceivers.remove(transferId);
        if (receiver != null) {
            receiver.abort("Transfer cancelled by user");
        }

        TcpConnection conn = transferConnections.remove(transferId);
        if (conn != null && conn.isReady()) {
            try {
                conn.sendPacket(Packet.createFileError(transferId, "Transfer cancelled by peer"));
            } catch (Exception ignored) {}
        }

        recoveryManager.deleteTransferArtifacts(transferId);
        transferManager.cancelTransfer(transferId);

        CompletableFuture<Transfer> offerFuture = pendingOfferFutures.remove(transferId);
        if (offerFuture != null) offerFuture.completeExceptionally(new IOException("Transfer cancelled"));

        CompletableFuture<Transfer> resumeFuture = pendingResumeFutures.remove(transferId);
        if (resumeFuture != null) resumeFuture.completeExceptionally(new IOException("Transfer cancelled"));

        if (transfer != null) {
            notifyCancelled(transfer);
        }
        Logger.info("[TRANSFER] Cancelled transfer " + (transfer != null ? transfer.getShortId() : transferId));
    }

    // ========================================================================
    // Incoming Packet Dispatch
    // ========================================================================

    public void handleIncomingPacket(TcpConnection connection, Packet packet) {
        if (!running.get()) return;

        try {
            switch (packet.getType()) {
                case FILE_OFFER -> handleFileOffer(connection, packet);
                case FILE_ACCEPT -> handleFileAccept(connection, packet);
                case FILE_REJECT -> handleFileReject(connection, packet);
                case FILE_CHUNK -> handleFileChunk(connection, packet);
                case FILE_COMPLETE -> handleFileComplete(connection, packet);
                case FILE_ACK -> handleFileAck(connection, packet);
                case FILE_ERROR -> handleFileError(connection, packet);
                case FILE_RESUME_REQUEST -> handleFileResumeRequest(connection, packet);
                case FILE_RESUME_RESPONSE -> handleFileResumeResponse(connection, packet);
                default -> {}
            }
        } catch (Exception e) {
            Logger.warn("[TRANSFER] Error handling packet " + packet.getType() + ": " + e.getMessage());
        }
    }

    private void handleFileOffer(TcpConnection connection, Packet packet) {
        FileMetadata metadata;
        try {
            metadata = packet.decodeFileOffer();
        } catch (ProtocolException e) {
            Logger.warn("[TRANSFER] Dropping malformed FILE_OFFER: " + e.getMessage());
            return;
        }

        // 1. Recipient check
        if (!metadata.recipientId().equals(localIdentity.nodeId())) {
            Logger.warn("[TRANSFER] Rejecting offer addressed to another node: " + metadata.recipientId());
            sendReject(connection, metadata.transferId(), "INVALID_METADATA");
            return;
        }

        // 2. Sender identity check against connection
        if (connection.getRemoteIdentity() == null ||
                !metadata.senderId().equals(connection.getRemoteIdentity().nodeId())) {
            Logger.warn("[TRANSFER] Rejecting offer with forged sender ID: " + metadata.senderId());
            sendReject(connection, metadata.transferId(), "INVALID_METADATA");
            return;
        }

        // 3. Prevent duplicate active transfer
        if (transferManager.getTransfer(metadata.transferId()).isPresent()) {
            sendReject(connection, metadata.transferId(), "TRANSFER_ALREADY_EXISTS");
            return;
        }

        // 4. DoS check: Max concurrent transfers
        if (transferManager.getActiveTransfers().size() >= ProtocolConstants.MAX_CONCURRENT_TRANSFERS) {
            Logger.warn("[TRANSFER] Rejecting offer: max concurrent transfers limit reached (" +
                    ProtocolConstants.MAX_CONCURRENT_TRANSFERS + ")");
            sendReject(connection, metadata.transferId(), "TOO_MANY_TRANSFERS");
            return;
        }

        // 5. Bounds check: Max file size limit
        if (metadata.fileSize() > ProtocolConstants.MAX_ACCEPTED_FILE_SIZE) {
            Logger.warn("[TRANSFER] Rejecting offer: file size (" + metadata.fileSize() +
                    " bytes) exceeds maximum allowable limit (" + ProtocolConstants.MAX_ACCEPTED_FILE_SIZE + " bytes)");
            sendReject(connection, metadata.transferId(), "FILE_TOO_LARGE");
            return;
        }

        // 6. Disk space pre-check
        long freeSpace = downloadsDir.toFile().getUsableSpace();
        if (freeSpace > 0 && freeSpace < metadata.fileSize() + ProtocolConstants.DISK_SAFETY_BUFFER_BYTES) {
            Logger.warn("[TRANSFER] Rejecting offer: insufficient disk space. Required: " +
                    (metadata.fileSize() + ProtocolConstants.DISK_SAFETY_BUFFER_BYTES) + " bytes, available: " + freeSpace);
            sendReject(connection, metadata.transferId(), "INSUFFICIENT_STORAGE");
            return;
        }

        Peer senderPeer = peerManager.findPeer(metadata.senderId()).orElse(null);
        Transfer transfer = new Transfer(metadata, TransferDirection.DOWNLOAD, null);
        transferManager.registerTransfer(transfer);
        transferConnections.put(metadata.transferId(), connection);

        Logger.info("[TRANSFER] Received FILE_OFFER: " + metadata.fileName() +
                " (" + metadata.fileSize() + " bytes) from " + (senderPeer != null ? senderPeer.getDisplayName() : "peer"));

        // Request approval
        approvalHandler.requestApproval(metadata, senderPeer).thenAccept(accepted -> {
            if (accepted && running.get()) {
                try {
                    FileReceiver receiver = new FileReceiver(metadata, downloadsDir, tempDir, transfer, recoveryManager, createListenerForwarder());
                    activeReceivers.put(metadata.transferId(), receiver);
                    connection.addCloseListener(closedConn -> {
                        FileReceiver r = activeReceivers.remove(metadata.transferId());
                        if (r != null) {
                            r.pauseForInterruption("Connection closed");
                        }
                    });
                    transfer.transitionTo(TransferState.ACCEPTED);
                    connection.sendPacket(Packet.createFileAccept(metadata.transferId()));
                    Logger.info("[TRANSFER] Accepted file transfer " + metadata.transferId());
                } catch (IOException e) {
                    Logger.warn("[TRANSFER] Failed to initialize FileReceiver: " + e.getMessage());
                    transfer.transitionTo(TransferState.FAILED);
                    transfer.setErrorMessage(e.getMessage());
                    sendReject(connection, metadata.transferId(), "INSUFFICIENT_STORAGE");
                }
            } else {
                transfer.transitionTo(TransferState.REJECTED);
                sendReject(connection, metadata.transferId(), "DECLINED");
                Logger.info("[TRANSFER] Declined file transfer " + metadata.transferId());
            }
        });
    }

    private void handleFileAccept(TcpConnection connection, Packet packet) {
        UUID transferId;
        try {
            transferId = packet.decodeFileAccept();
        } catch (ProtocolException e) {
            return;
        }

        Transfer transfer = transferManager.getTransfer(transferId).orElse(null);
        if (transfer == null || transfer.getState() != TransferState.WAITING_FOR_ACCEPT) {
            return;
        }

        transfer.transitionTo(TransferState.ACCEPTED);
        Logger.info("[TRANSFER] Peer accepted transfer " + transferId + ". Starting streaming upload...");

        // Stream file chunks in a virtual thread with sliding-window flow control
        Thread.ofVirtual().name("sender-stream-" + transferId).start(() -> {
            FileSender sender = new FileSender(chunkSize, ProtocolConstants.DEFAULT_WINDOW_SIZE);
            activeSenders.put(transferId, sender);
            try {
                sender.streamFile(transfer.getLocalPath(), connection, transfer, createListenerForwarder());
            } catch (Exception e) {
                Logger.warn("[TRANSFER] Upload stream failed: " + e.getMessage());
                CompletableFuture<Transfer> future = pendingOfferFutures.remove(transferId);
                if (future != null) {
                    future.completeExceptionally(e);
                }
            } finally {
                activeSenders.remove(transferId);
            }
        });
    }

    private void handleFileReject(TcpConnection connection, Packet packet) {
        try {
            var reject = packet.decodeFileReject();
            Transfer transfer = transferManager.getTransfer(reject.transferId()).orElse(null);
            if (transfer != null && !transfer.getState().isTerminal()) {
                transfer.setErrorMessage("Rejected by peer: " + reject.reason());
                transfer.transitionTo(TransferState.REJECTED);
                notifyFailed(transfer, "Rejected: " + reject.reason());
            }
            CompletableFuture<Transfer> future = pendingOfferFutures.remove(reject.transferId());
            if (future != null) {
                future.completeExceptionally(new IOException("Transfer rejected by peer: " + reject.reason()));
            }
        } catch (ProtocolException ignored) {}
    }

    private void handleFileChunk(TcpConnection connection, Packet packet) {
        FileChunk chunk;
        try {
            chunk = packet.decodeFileChunk();
        } catch (ProtocolException e) {
            Logger.warn("[TRANSFER] Dropping malformed chunk: " + e.getMessage());
            return;
        }

        FileReceiver receiver = activeReceivers.get(chunk.transferId());
        if (receiver != null) {
            try {
                receiver.receiveChunk(chunk);
                // Send cumulative progress ACK for sliding-window flow control
                connection.sendPacket(Packet.createFileChunkAck(chunk.transferId(), chunk.chunkIndex(), receiver.getExpectedOffset()));
            } catch (IOException e) {
                Logger.severe("[TRANSFER] Receiver failed on chunk " + chunk.chunkIndex() + ": " + e.getMessage(), e);
                receiver.abort(e.getMessage());
                activeReceivers.remove(chunk.transferId());
                sendError(connection, chunk.transferId(), e.getMessage());
            }
        }
    }

    private void handleFileComplete(TcpConnection connection, Packet packet) {
        FileTransferCodec.CompletePayload complete;
        try {
            complete = packet.decodeFileComplete();
        } catch (ProtocolException e) {
            return;
        }

        FileReceiver receiver = activeReceivers.remove(complete.transferId());
        if (receiver != null) {
            try {
                receiver.completeTransfer(complete.totalChunks(), complete.totalBytes(), complete.sha256());
                connection.sendPacket(Packet.createFileAck(complete.transferId(), true));
            } catch (IOException e) {
                Logger.severe("[TRANSFER] Complete verification failed: " + e.getMessage(), e);
                receiver.abort(e.getMessage());
                try {
                    connection.sendPacket(Packet.createFileAck(complete.transferId(), false));
                } catch (Exception ignored) {}
            }
        }
    }

    private void handleFileAck(TcpConnection connection, Packet packet) {
        try {
            var ack = packet.decodeFileAck();

            // Sliding-window progress ACK
            if (ack.isWindowAck()) {
                FileSender sender = activeSenders.get(ack.transferId());
                if (sender != null) {
                    sender.onAckReceived(ack.highestContiguousChunk(), ack.receiverOffset());
                }
                return;
            }

            Transfer transfer = transferManager.getTransfer(ack.transferId()).orElse(null);
            CompletableFuture<Transfer> future = pendingOfferFutures.remove(ack.transferId());
            CompletableFuture<Transfer> resumeFuture = pendingResumeFutures.remove(ack.transferId());

            if (transfer != null) {
                if (ack.success()) {
                    transfer.transitionTo(TransferState.COMPLETED);
                    notifyCompleted(transfer);
                    if (future != null) future.complete(transfer);
                    if (resumeFuture != null) resumeFuture.complete(transfer);
                    Logger.info("[TRANSFER] Transfer " + ack.transferId() + " successfully completed and verified!");
                } else {
                    transfer.transitionTo(TransferState.FAILED);
                    transfer.setErrorMessage("Receiver reported verification failure in FILE_ACK");
                    notifyFailed(transfer, "Verification failed on remote peer");
                    if (future != null) future.completeExceptionally(new IOException("Remote peer failed verification"));
                    if (resumeFuture != null) resumeFuture.completeExceptionally(new IOException("Remote peer failed verification"));
                }
            }
        } catch (ProtocolException ignored) {}
    }

    private void handleFileError(TcpConnection connection, Packet packet) {
        try {
            var error = packet.decodeFileError();
            Transfer transfer = transferManager.getTransfer(error.transferId()).orElse(null);
            boolean isCancellation = error.message() != null && error.message().toLowerCase().contains("cancel");

            if (transfer != null && !transfer.getState().isTerminal()) {
                transfer.setErrorMessage(error.message());
                if (isCancellation) {
                    if (transfer.getState().canTransitionTo(TransferState.CANCELLED)) {
                        transfer.transitionTo(TransferState.CANCELLED);
                    }
                    notifyCancelled(transfer);
                } else {
                    transfer.transitionTo(TransferState.FAILED);
                    notifyFailed(transfer, error.message());
                }
            }
            FileReceiver receiver = activeReceivers.remove(error.transferId());
            if (receiver != null) {
                receiver.abort(error.message());
            }
            CompletableFuture<Transfer> future = pendingOfferFutures.remove(error.transferId());
            if (future != null) {
                future.completeExceptionally(new IOException(error.message()));
            }
            CompletableFuture<Transfer> resumeFuture = pendingResumeFutures.remove(error.transferId());
            if (resumeFuture != null) {
                resumeFuture.completeExceptionally(new IOException(error.message()));
            }
        } catch (ProtocolException ignored) {}
    }

    // ========================================================================
    // Resume Request & Response Handling
    // ========================================================================

    private void handleFileResumeRequest(TcpConnection connection, Packet packet) {
        FileTransferCodec.ResumeRequestPayload req;
        try {
            req = packet.decodeFileResumeRequest();
        } catch (ProtocolException e) {
            Logger.warn("[TRANSFER] Dropping malformed FILE_RESUME_REQUEST: " + e.getMessage());
            return;
        }

        // 1. Recipient check
        if (!req.recipientId().equals(localIdentity.nodeId())) {
            Logger.warn("[TRANSFER] Resume request recipient mismatch: expected " + localIdentity.nodeId() + ", got " + req.recipientId());
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_METADATA_MISMATCH, 0, 0, 0, "Recipient mismatch");
            return;
        }

        // 2. Sender check against connection
        if (connection.getRemoteIdentity() == null || !req.senderId().equals(connection.getRemoteIdentity().nodeId())) {
            Logger.warn("[TRANSFER] Resume request sender mismatch");
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_METADATA_MISMATCH, 0, 0, 0, "Sender mismatch");
            return;
        }

        // 3. Completed transfer check (Part 22)
        Transfer existing = transferManager.getTransfer(req.transferId()).orElse(null);
        if (existing != null && existing.getState() == TransferState.COMPLETED) {
            Logger.info("[TRANSFER] Transfer " + req.transferId() + " already completed. Returning RESUME_COMPLETE.");
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_COMPLETE, 0, req.fileSize(), req.fileSize(), "Transfer already completed");
            return;
        }

        // Check active receiver (Part 21)
        FileReceiver active = activeReceivers.get(req.transferId());
        if (active != null) {
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_ACCEPTED,
                    active.getExpectedChunkIndex(), active.getExpectedOffset(), active.getExpectedOffset(), "Already active");
            return;
        }

        // 4. Locate checkpoint
        TransferCheckpoint checkpoint = recoveryManager.loadCheckpoint(req.transferId()).orElse(null);
        if (checkpoint == null) {
            Logger.warn("[TRANSFER] No checkpoint found for resume request: " + req.transferId());
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_NOT_FOUND, 0, 0, 0, "Checkpoint not found");
            return;
        }

        // 5. Metadata compatibility validation (Part 5)
        if (checkpoint.fileSize() != req.fileSize()) {
            Logger.warn("[TRANSFER] Resume rejected: fileSize mismatch (" + checkpoint.fileSize() + " != " + req.fileSize() + ")");
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_METADATA_MISMATCH, 0, 0, 0, "FileSize mismatch");
            return;
        }

        if (!checkpoint.expectedSha256().equalsIgnoreCase(req.expectedSha256())) {
            Logger.warn("[TRANSFER] Resume rejected: SHA-256 mismatch");
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_HASH_MISMATCH, 0, 0, 0, "SHA-256 mismatch");
            return;
        }

        // 6. On-disk consistency verification (Part 23 & 24)
        if (!recoveryManager.verifyConsistency(checkpoint)) {
            Logger.warn("[TRANSFER] Resume rejected: partial file inconsistent with checkpoint for " + req.transferId());
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_INVALID, 0, 0, 0, "Partial file corrupted or inconsistent");
            return;
        }

        // 7. Initialize resumed receiver
        try {
            FileMetadata meta = new FileMetadata(
                    checkpoint.transferId(),
                    checkpoint.senderId(),
                    checkpoint.recipientId(),
                    checkpoint.fileName(),
                    checkpoint.fileSize(),
                    checkpoint.lastUpdated(),
                    checkpoint.expectedSha256()
            );

            if (existing == null) {
                existing = Transfer.fromCheckpoint(checkpoint, recoveryManager.getPartFilePath(checkpoint.transferId()));
                transferManager.registerRecoveredTransfer(existing);
            } else {
                existing.setCheckpoint(checkpoint);
                existing.setLocalPath(recoveryManager.getPartFilePath(checkpoint.transferId()));
                if (existing.getState().canTransitionTo(TransferState.RESUMING)) {
                    existing.transitionTo(TransferState.RESUMING);
                }
            }

            FileReceiver receiver = new FileReceiver(
                    meta,
                    downloadsDir,
                    tempDir,
                    existing,
                    recoveryManager,
                    checkpoint,
                    createListenerForwarder()
            );
            activeReceivers.put(req.transferId(), receiver);
            transferConnections.put(req.transferId(), connection);
            connection.addCloseListener(closedConn -> {
                FileReceiver r = activeReceivers.remove(req.transferId());
                if (r != null) {
                    r.pauseForInterruption("Connection closed");
                }
            });

            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_ACCEPTED,
                    checkpoint.nextExpectedChunk(), checkpoint.nextExpectedOffset(), checkpoint.bytesReceived(), "Resume accepted");

            Logger.info("[TRANSFER] Accepted resume for " + meta.fileName() + " at chunk " + checkpoint.nextExpectedChunk());
        } catch (IOException e) {
            Logger.severe("[TRANSFER] Failed to initialize resumed receiver: " + e.getMessage(), e);
            sendResumeResponse(connection, req.transferId(), ResumeStatus.RESUME_INVALID, 0, 0, 0, "Failed to initialize receiver: " + e.getMessage());
        }
    }

    private void handleFileResumeResponse(TcpConnection connection, Packet packet) {
        FileTransferCodec.ResumeResponsePayload resp;
        try {
            resp = packet.decodeFileResumeResponse();
        } catch (ProtocolException e) {
            Logger.warn("[TRANSFER] Dropping malformed FILE_RESUME_RESPONSE: " + e.getMessage());
            return;
        }

        CompletableFuture<Transfer> future = pendingResumeFutures.get(resp.transferId());
        Transfer transfer = transferManager.getTransfer(resp.transferId()).orElse(null);

        if (transfer == null) {
            pendingResumeFutures.remove(resp.transferId());
            if (future != null) future.completeExceptionally(new IOException("Transfer not found: " + resp.transferId()));
            return;
        }

        switch (resp.status()) {
            case RESUME_ACCEPTED -> {
                Logger.info("[TRANSFER] Remote peer accepted resume for " + resp.transferId() +
                        ". Resuming streaming from chunk " + resp.nextExpectedChunk() + " (offset " + resp.nextExpectedOffset() + ")...");
                if (transfer.getState().canTransitionTo(TransferState.RESUMING)) {
                    transfer.transitionTo(TransferState.RESUMING);
                }

                // Stream remaining file chunks in a virtual thread with sliding window
                Thread.ofVirtual().name("sender-resume-" + resp.transferId()).start(() -> {
                    FileSender sender = new FileSender(chunkSize, ProtocolConstants.DEFAULT_WINDOW_SIZE);
                    activeSenders.put(resp.transferId(), sender);
                    try {
                        sender.streamFile(
                                transfer.getLocalPath(),
                                connection,
                                transfer,
                                resp.nextExpectedChunk(),
                                resp.nextExpectedOffset(),
                                createListenerForwarder()
                        );
                    } catch (Exception e) {
                        Logger.warn("[TRANSFER] Resumed upload stream failed: " + e.getMessage());
                        pendingResumeFutures.remove(resp.transferId());
                        if (future != null && !future.isDone()) {
                            future.completeExceptionally(e);
                        }
                    } finally {
                        activeSenders.remove(resp.transferId());
                    }
                });
            }
            case RESUME_COMPLETE -> {
                pendingResumeFutures.remove(resp.transferId());
                Logger.info("[TRANSFER] Remote peer reported transfer " + resp.transferId() + " already completed!");
                transfer.setBytesTransferred(transfer.getTotalBytes());
                if (transfer.getState().canTransitionTo(TransferState.COMPLETED)) {
                    transfer.transitionTo(TransferState.COMPLETED);
                }
                notifyCompleted(transfer);
                if (future != null) future.complete(transfer);
            }
            default -> {
                pendingResumeFutures.remove(resp.transferId());
                Logger.warn("[TRANSFER] Remote peer declined resume: " + resp.status() + " (" + resp.reason() + ")");
                transfer.setErrorMessage("Resume declined: " + resp.status() + " - " + resp.reason());
                if (transfer.getState().canTransitionTo(TransferState.INTERRUPTED)) {
                    transfer.transitionTo(TransferState.INTERRUPTED);
                }
                if (transfer.getState().canTransitionTo(TransferState.RESUMABLE)) {
                    transfer.transitionTo(TransferState.RESUMABLE);
                }
                if (future != null) {
                    future.completeExceptionally(new IOException("Resume declined by peer: " + resp.status() + " (" + resp.reason() + ")"));
                }
            }
        }
    }

    private void sendReject(TcpConnection connection, UUID transferId, String reason) {
        try {
            connection.sendPacket(Packet.createFileReject(transferId, reason));
        } catch (IOException ignored) {}
    }

    private void sendError(TcpConnection connection, UUID transferId, String message) {
        try {
            connection.sendPacket(Packet.createFileError(transferId, message));
        } catch (IOException ignored) {}
    }

    private void sendResumeResponse(
            TcpConnection connection,
            UUID transferId,
            ResumeStatus status,
            int nextChunk,
            long nextOffset,
            long bytesRecv,
            String reason
    ) {
        try {
            var payload = new FileTransferCodec.ResumeResponsePayload(transferId, status, nextChunk, nextOffset, bytesRecv, reason);
            connection.sendPacket(Packet.createFileResumeResponse(payload));
        } catch (IOException ignored) {}
    }

    private TransferListener createListenerForwarder() {
        return new TransferListener() {
            public void onTransferStarted(Transfer t) { notifyStarted(t); }
            public void onTransferProgress(Transfer t) { notifyProgress(t); }
            public void onTransferInterrupted(Transfer t) { notifyInterrupted(t); }
            public void onTransferResuming(Transfer t) { notifyResuming(t); }
            public void onTransferCompleted(Transfer t) { notifyCompleted(t); }
            public void onTransferFailed(Transfer t, String r) { notifyFailed(t, r); }
            public void onTransferCancelled(Transfer t) { notifyCancelled(t); }
        };
    }

    private void notifyStarted(Transfer t) {
        for (TransferListener l : listeners) {
            try { l.onTransferStarted(t); } catch (Exception ignored) {}
        }
    }

    private void notifyProgress(Transfer t) {
        for (TransferListener l : listeners) {
            try { l.onTransferProgress(t); } catch (Exception ignored) {}
        }
    }

    private void notifyInterrupted(Transfer t) {
        for (TransferListener l : listeners) {
            try { l.onTransferInterrupted(t); } catch (Exception ignored) {}
        }
    }

    private void notifyResuming(Transfer t) {
        for (TransferListener l : listeners) {
            try { l.onTransferResuming(t); } catch (Exception ignored) {}
        }
    }

    private void notifyCompleted(Transfer t) {
        for (TransferListener l : listeners) {
            try { l.onTransferCompleted(t); } catch (Exception ignored) {}
        }
    }

    private void notifyFailed(Transfer t, String reason) {
        for (TransferListener l : listeners) {
            try { l.onTransferFailed(t, reason); } catch (Exception ignored) {}
        }
    }

    private void notifyCancelled(Transfer t) {
        for (TransferListener l : listeners) {
            try { l.onTransferCancelled(t); } catch (Exception ignored) {}
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            for (FileReceiver receiver : activeReceivers.values()) {
                receiver.pauseForInterruption("Service stopped");
            }
            activeReceivers.clear();

            for (CompletableFuture<Transfer> future : pendingOfferFutures.values()) {
                future.completeExceptionally(new IOException("Service stopped"));
            }
            pendingOfferFutures.clear();

            for (CompletableFuture<Transfer> future : pendingResumeFutures.values()) {
                future.completeExceptionally(new IOException("Service stopped"));
            }
            pendingResumeFutures.clear();

            transferManager.stop();
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
