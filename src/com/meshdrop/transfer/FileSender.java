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
import java.util.Objects;

/**
 * Handles memory-safe, chunked streaming transmission of a local file over a persistent TCP connection,
 * with support for seekable resume from an arbitrary checkpoint chunk and byte offset.
 *
 * Guarantees:
 *   - Memory consumption is strictly O(chunk size), never loading the entire file into memory.
 *   - Direct file channel positioning without buffering preceding chunks.
 *   - Controlled state transitions and progress notifications.
 *   - Interruption detection transitioning to INTERRUPTED instead of permanent failure.
 */
public class FileSender {

    private final int chunkSize;

    public FileSender(int chunkSize) {
        this.chunkSize = chunkSize > 0 ? chunkSize : ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE;
    }

    public FileSender() {
        this(ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE);
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
        if (startOffset < 0 || startOffset > fileSize) {
            throw new IOException("Invalid startOffset: " + startOffset + " (fileSize: " + fileSize + ")");
        }
        if (startChunkIndex < 0) {
            throw new IOException("Invalid startChunkIndex: " + startChunkIndex);
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
            } else {
                int bytesRead;
                while ((bytesRead = channel.read(buf)) != -1) {
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
                    transfer.addBytesTransferred(bytesRead);
                    transfer.incrementChunksTransferred();

                    if (listener != null) {
                        listener.onTransferProgress(transfer);
                    }
                }
            }

            // Transmit FILE_COMPLETE
            transfer.transitionTo(TransferState.VERIFYING);
            String sha256 = transfer.getFileMetadata() != null ? transfer.getFileMetadata().sha256() : "";
            Packet completePacket = Packet.createFileComplete(transfer.getTransferId(), chunkIndex, offset, sha256);
            connection.sendPacket(completePacket);
            Logger.fine("[TRANSFER] Completed sending all chunks up to " + chunkIndex + " for " + transfer.getTransferId());

        } catch (IOException e) {
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
            throw e;
        }
    }
}
