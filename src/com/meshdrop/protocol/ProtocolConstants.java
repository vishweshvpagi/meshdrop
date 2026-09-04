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

    /** Minimum allowable file chunk size (4 KiB) */
    public static final int MIN_FILE_CHUNK_SIZE = 4 * 1024;

    /** Default file chunk size for peer transfers (64 KiB) */
    public static final int DEFAULT_FILE_CHUNK_SIZE = 64 * 1024;

    /** Maximum allowable file chunk size (4 MiB) */
    public static final int MAX_FILE_CHUNK_SIZE = 4 * 1024 * 1024;

    /** Default sliding window size (maximum in-flight unacknowledged chunks) */
    public static final int DEFAULT_WINDOW_SIZE = 8;

    /** Minimum allowable sliding window size */
    public static final int MIN_WINDOW_SIZE = 1;

    /** Maximum allowable sliding window size */
    public static final int MAX_WINDOW_SIZE = 64;

    /** Default chunk acknowledgement timeout in milliseconds */
    public static final long DEFAULT_CHUNK_ACK_TIMEOUT_MS = 5_000;

    /** Maximum chunk retransmission retries before marking transfer interrupted */
    public static final int DEFAULT_MAX_CHUNK_RETRIES = 5;

    /** Checkpoint interval in bytes for periodic on-disk flush (1 MiB) */
    public static final long DEFAULT_CHECKPOINT_INTERVAL_BYTES = 1024 * 1024;

    /** Checkpoint interval in milliseconds for periodic on-disk flush (1s) */
    public static final long DEFAULT_CHECKPOINT_INTERVAL_MS = 1_000;

    /** Default file transfer offer acceptance timeout in milliseconds */
    public static final int DEFAULT_FILE_OFFER_TIMEOUT_MS = 30_000;

    /** Default file transfer streaming idle inactivity timeout in milliseconds (60s) */
    public static final int DEFAULT_FILE_TRANSFER_IDLE_TIMEOUT_MS = 60_000;

    /** Maximum allowable file size accepted over peer transfers (100 GiB) */
    public static final long MAX_ACCEPTED_FILE_SIZE = 100L * 1024 * 1024 * 1024;

    /** Maximum concurrent in-flight transfers per node */
    public static final int MAX_CONCURRENT_TRANSFERS = 10;

    /** Maximum concurrent pending inbound file transfer offers awaiting decision */
    public static final int MAX_PENDING_OFFERS = 20;

    /** Safety margin buffer required when validating free disk space (10 MiB) */
    public static final long DISK_SAFETY_BUFFER_BYTES = 10 * 1024 * 1024;

    /** Default handshake timeout in milliseconds */
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;

    /** Default TCP connection timeout in milliseconds */
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
}
