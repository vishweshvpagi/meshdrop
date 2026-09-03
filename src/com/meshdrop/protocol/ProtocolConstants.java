package com.meshdrop.protocol;

/**
 * Binary protocol constants for MeshDrop frame encoding, framing, validation, and handshake.
 */
public final class ProtocolConstants {
    private ProtocolConstants() {}

    /** Magic bytes: 'M', 'D', 'R', 'P' = 0x4D, 0x44, 0x52, 0x50 */
    public static final int MAGIC = 0x4D445250;

    /** Current protocol version */
    public static final byte CURRENT_VERSION = 0x01;

    /** Fixed header length: 4 (Magic) + 1 (Ver) + 1 (Type) + 2 (Flags) + 4 (Length) + 16 (RequestId) = 28 bytes */
    public static final int HEADER_LENGTH = 28;

    /** Maximum allowable payload size (16 MiB) to prevent memory exhaustion */
    public static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    /** Maximum allowable UTF-8 byte length for peer display names */
    public static final int MAX_DISPLAY_NAME_BYTES = 128;

    /** Maximum allowable UTF-8 byte length for application text messages (64 KiB) */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    /** Default message delivery acknowledgement timeout in milliseconds */
    public static final int DEFAULT_MESSAGE_ACK_TIMEOUT_MS = 5_000;

    /** Default file chunk size for peer transfers (64 KiB) */
    public static final int DEFAULT_FILE_CHUNK_SIZE = 64 * 1024;

    /** Default file transfer offer acceptance timeout in milliseconds */
    public static final int DEFAULT_FILE_OFFER_TIMEOUT_MS = 30_000;

    /** Default file transfer inactivity/idle timeout in milliseconds */
    public static final int DEFAULT_FILE_TRANSFER_IDLE_TIMEOUT_MS = 15_000;

    /** Default handshake timeout in milliseconds */
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;

    /** Default TCP connection timeout in milliseconds */
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
}
