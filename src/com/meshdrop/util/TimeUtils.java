package com.meshdrop.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Time and duration formatting utility functions.
 */
public final class TimeUtils {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private TimeUtils() {}

    public static String formatTime(Instant instant) {
        if (instant == null) return "--:--:--";
        return TIME_FORMATTER.format(instant);
    }
}
