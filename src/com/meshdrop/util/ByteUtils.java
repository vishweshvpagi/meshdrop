package com.meshdrop.util;

import java.util.HexFormat;

/**
 * Low-level byte manipulation, formatting, and size conversion utilities.
 */
public final class ByteUtils {
    private ByteUtils() {}

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "iB";
        return String.format("%.2f %s", bytes / Math.pow(1024, exp), pre);
    }

    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
