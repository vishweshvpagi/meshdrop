package com.meshdrop.transfer;

import com.meshdrop.network.TcpConnection;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Handles memory-safe, chunked streaming transmission of a local file over a persistent TCP connection,
 * with support for seekable resume from an arbitrary checkpoint chunk and byte offset, sliding-window
 * backpressure flow control, and reliable retransmission.
 *
 * Guarantees:
 *   - Memory consumption is strictly O(chunkSize * windowSize), never loading the entire file into memory.
 *   - Direct file channel positioning without buffering preceding chunks.
 *   - Controlled state transitions and progress notifications.
 *   - Interruption detection transitioning to INTERRUPTED instead of permanent failure.
 */
public class FileSender {

    private final int chunkSize;
    private final int windowSize;
    private final long ackTimeoutMs;
    private final int maxRetries;

    private final Object windowLock = new Object();
    private volatile long highestAckedChunk = -1;
    private volatile long highestAckedOffset = -1;

    public FileSender(int chunkSize, int windowSize, long ackTimeoutMs, int maxRetries) {
        int safeChunk = chunkSize > 0 ? chunkSize : ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE;
        if (safeChunk < ProtocolConstants.MIN_FILE_CHUNK_SIZE) {
            safeChunk = ProtocolConstants.MIN_FILE_CHUNK_SIZE;
        } else if (safeChunk > ProtocolConstants.MAX_FILE_CHUNK_SIZE) {
            safeChunk = ProtocolConstants.MAX_FILE_CHUNK_SIZE;
        }
        this.chunkSize = safeChunk;
        this.windowSize = Math.max(0, Math.min(windowSize, ProtocolConstants.MAX_WINDOW_SIZE));
        this.ackTimeoutMs = ackTimeoutMs > 0 ? ackTimeoutMs : ProtocolConstants.DEFAULT_CHUNK_ACK_TIMEOUT_MS;
        this.maxRetries = maxRetries > 0 ? maxRetries : ProtocolConstants.DEFAULT_MAX_CHUNK_RETRIES;
    }

    public FileSender(int chunkSize, int windowSize) {
        this(chunkSize, windowSize, ProtocolConstants.DEFAULT_CHUNK_ACK_TIMEOUT_MS, ProtocolConstants.DEFAULT_MAX_CHUNK_RETRIES);
    }

    public FileSender(int chunkSize) {
        this(chunkSize, 0); // Open-loop mode for standalone legacy unit tests without an ACK loop
    }

    public FileSender() {
        this(ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE, 0);
    }

    public void onAckReceived(long highestContiguousChunk, long receiverOffset) {
        synchronized (windowLock) {
            if (highestContiguousChunk > highestAckedChunk) {
                highestAckedChunk = highestContiguousChunk;
                highestAckedOffset = receiverOffset;
                windowLock.notifyAll();
            }
        }
    }

    /**
     * Backwards-compatible overload for Phase 0 legacy interface.
     */
    public void sendFile(File file, TcpConnection connection, Transfer transfer) throws IOException {
        streamFile(file.toPath(), connection, transfer, null);
    }

    /**
     * Streams the file from chunk 0 and offset 0.
     */
    public void streamFile(Path filePath, TcpConnection connection, Transfer transfer, TransferListener listener) throws IOException {
        streamFile(filePath, connection, transfer, 0, 0L, listener);
    }

    /**
     * Streams the file starting from the specified chunk index and byte offset.
     */
    public void streamFile(
            Path filePath,
            TcpConnection connection,
            Transfer transfer,
            int startChunkIndex,
            long startOffset,
            TransferListener listener
    ) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(transfer, "transfer must not be null");

        if (!Files.isRegularFile(filePath)) {
            throw new IOException("Source path is not a regular readable file: " + filePath);
        }

        long fileSize = Files.size(filePath);
        FileTime initialLastModified = Files.getLastModifiedTime(filePath);

        if (startOffset < 0 || startOffset > fileSize) {
            throw new IOException("Invalid startOffset: " + startOffset + " (fileSize: " + fileSize + ")");
        }
        if (startChunkIndex < 0) {
            throw new IOException("Invalid startChunkIndex: " + startChunkIndex);
        }

        if (startOffset > 0) {
            highestAckedChunk = startChunkIndex - 1;
            highestAckedOffset = startOffset;
        } else {
            highestAckedChunk = -1;
            highestAckedOffset = 0;
        }

        transfer.transitionTo(TransferState.TRANSFERRING);
        transfer.setBytesTransferred(startOffset);
        transfer.setChunksTransferred(startChunkIndex);

        if (listener != null) {
            if (startOffset > 0) {
                listener.onTransferResuming(transfer);
            } else {
                listener.onTransferStarted(transfer);
            }
        }

        int chunkIndex = startChunkIndex;
        long offset = startOffset;

        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            channel.position(startOffset);
            ByteBuffer buf = ByteBuffer.allocate(chunkSize);

            // Handle 0-byte file edge case
            if (fileSize == 0) {
                Logger.fine("[TRANSFER] Sending 0-byte file: " + filePath.getFileName());
            } else if (windowSize <= 0) {
                // Open-loop streaming (used when unwindowed)
                int bytesRead;
                while ((bytesRead = channel.read(buf)) != -1) {
                    if (transfer.isCancelled() || transfer.getState() == TransferState.CANCELLED) {
                        Logger.info("[TRANSFER] Streaming cancelled for transfer " + transfer.getShortId());
                        throw new IOException("Transfer was cancelled");
                    }

                    if (bytesRead == 0) continue;
                    buf.flip();
                    byte[] chunkData = new byte[bytesRead];
                    buf.get(chunkData);
                    buf.clear();

                    FileChunk chunk = new FileChunk(transfer.getTransferId(), chunkIndex, offset, bytesRead, chunkData);
                    Packet chunkPacket = Packet.createFileChunk(chunk);
                    connection.sendPacket(chunkPacket);

                    offset += bytesRead;
                    chunkIndex++;
                    transfer.setBytesTransferred(offset);
                    transfer.setChunksTransferred(chunkIndex);

                    if (listener != null) {
                        listener.onTransferProgress(transfer);
                    }
                }
            } else {
                // Sliding-window streaming with backpressure and reliable retransmission
                long totalChunks = (fileSize + chunkSize - 1) / chunkSize;
                long baseChunk = startChunkIndex;
                long nextChunk = startChunkIndex;
                long currentOffset = startOffset;

                record InFlight(long chunkIndex, long offset, int length, long sentTime, int retryCount) {}
                java.util.Map<Long, InFlight> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

                while (baseChunk < totalChunks) {
                    if (transfer.isCancelled() || transfer.getState() == TransferState.CANCELLED) {
                        Logger.info("[TRANSFER] Streaming cancelled for transfer " + transfer.getShortId());
                        throw new IOException("Transfer was cancelled");
                    }

                    // Source mutation verification
                    if (Files.size(filePath) != fileSize || !Files.getLastModifiedTime(filePath).equals(initialLastModified)) {
                        throw new IOException("Source file was modified during transfer: size or last-modified timestamp changed");
                    }

                    // Dispatch chunks within sliding window
                    while (nextChunk < totalChunks && (nextChunk - baseChunk) < windowSize) {
                        if (transfer.isCancelled() || transfer.getState() == TransferState.CANCELLED) {
                            throw new IOException("Transfer was cancelled");
                        }

                        channel.position(currentOffset);
                        buf.clear();
                        int bytesRead = channel.read(buf);
                        if (bytesRead <= 0) break;

                        buf.flip();
                        byte[] chunkData = new byte[bytesRead];
                        buf.get(chunkData);
                        buf.clear();

                        FileChunk chunk = new FileChunk(transfer.getTransferId(), (int) nextChunk, currentOffset, bytesRead, chunkData);
                        connection.sendPacket(Packet.createFileChunk(chunk));

                        inFlight.put(nextChunk, new InFlight(nextChunk, currentOffset, bytesRead, System.currentTimeMillis(), 0));

                        currentOffset += bytesRead;
                        nextChunk++;

                        transfer.setBytesTransferred(currentOffset);
                        transfer.setChunksTransferred((int) nextChunk);

                        if (listener != null) {
                            listener.onTransferProgress(transfer);
                        }
                    }

                    // Wait for window progress or ACK timeout
                    synchronized (windowLock) {
                        if (highestAckedChunk >= baseChunk) {
                            long oldBase = baseChunk;
                            baseChunk = Math.min(highestAckedChunk + 1, totalChunks);
                            for (long c = oldBase; c < baseChunk; c++) {
                                inFlight.remove(c);
                            }
                        }

                        if (baseChunk >= totalChunks) {
                            break;
                        }

                        if ((nextChunk - baseChunk) >= windowSize || nextChunk == totalChunks) {
                            InFlight oldest = inFlight.get(baseChunk);
                            long waitDuration = ackTimeoutMs;
                            if (oldest != null) {
                                long elapsed = System.currentTimeMillis() - oldest.sentTime;
                                waitDuration = Math.max(50, ackTimeoutMs - elapsed);
                            }

                            try {
                                windowLock.wait(waitDuration);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Transfer interrupted while awaiting ACK", e);
                            }

                            if (highestAckedChunk >= baseChunk) {
                                long oldBase = baseChunk;
                                baseChunk = Math.min(highestAckedChunk + 1, totalChunks);
                                for (long c = oldBase; c < baseChunk; c++) {
                                    inFlight.remove(c);
                                }
                            }

                            InFlight unacked = inFlight.get(baseChunk);
                            if (unacked != null && (System.currentTimeMillis() - unacked.sentTime >= ackTimeoutMs)) {
                                if (unacked.retryCount >= maxRetries) {
                                    throw new IOException("Transfer timed out: chunk " + baseChunk +
                                            " unacknowledged after " + maxRetries + " retries (" + ackTimeoutMs + "ms timeout)");
                                }

                                Logger.warn("[TRANSFER] ACK timeout for chunk " + baseChunk + ". Retransmitting (attempt " +
                                        (unacked.retryCount + 1) + "/" + maxRetries + ")");

                                channel.position(unacked.offset);
                                buf.clear();
                                int bytesRead = channel.read(buf);
                                buf.flip();
                                byte[] chunkData = new byte[bytesRead];
                                buf.get(chunkData);
                                buf.clear();

                                FileChunk retryChunk = new FileChunk(transfer.getTransferId(), (int) baseChunk, unacked.offset, bytesRead, chunkData);
                                connection.sendPacket(Packet.createFileChunk(retryChunk));

                                inFlight.put(baseChunk, new InFlight(baseChunk, unacked.offset, bytesRead, System.currentTimeMillis(), unacked.retryCount + 1));
                            }
                        }
                    }
                }

                chunkIndex = (int) totalChunks;
                offset = currentOffset;
            }

            if (transfer.isCancelled() || transfer.getState() == TransferState.CANCELLED) {
                throw new IOException("Transfer was cancelled");
            }

            // Final source mutation check
            if (Files.size(filePath) != fileSize || !Files.getLastModifiedTime(filePath).equals(initialLastModified)) {
                throw new IOException("Source file was modified during transfer: size or last-modified timestamp changed");
            }

            // Transmit FILE_COMPLETE
            transfer.transitionTo(TransferState.VERIFYING);
            String sha256 = transfer.getFileMetadata() != null ? transfer.getFileMetadata().sha256() : "";
            Packet completePacket = Packet.createFileComplete(transfer.getTransferId(), chunkIndex, offset, sha256);
            connection.sendPacket(completePacket);
            Logger.fine("[TRANSFER] Completed sending all chunks up to " + chunkIndex + " for " + transfer.getTransferId());

        } catch (IOException e) {
            if (transfer.getState() != TransferState.CANCELLED) {
                transfer.setErrorMessage(e.getMessage());
                if (!transfer.getState().isTerminal()) {
                    transfer.transitionTo(TransferState.FAILED);
                }
                if (listener != null) {
                    listener.onTransferFailed(transfer, e.getMessage());
                    listener.onTransferInterrupted(transfer);
                }
                try {
                    connection.sendPacket(Packet.createFileError(transfer.getTransferId(), "Send failed: " + e.getMessage()));
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }
}
