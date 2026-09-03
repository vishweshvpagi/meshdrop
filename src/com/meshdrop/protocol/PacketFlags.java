package com.meshdrop.protocol;

/**
 * 16-bit bitmask flags for packet headers.
 */
public final class PacketFlags {
    private PacketFlags() {}

    public static final short NONE = 0x0000;
    public static final short COMPRESSED = 0x0001;
    public static final short URGENT = 0x0002;
    public static final short ACK_REQUESTED = 0x0004;

    public static boolean hasFlag(short flags, short mask) {
        return (flags & mask) == mask;
    }
}
