package com.meshdrop.util;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight console and diagnostic logger for MeshDrop events.
 *
 * All output is synchronized to prevent interleaved lines when networking
 * threads and the CLI write concurrently.
 */
public final class Logger {
    public enum Level {
        FINE,
        INFO,
        WARNING,
        SEVERE
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static Level currentLevel = Level.INFO;
    private static PrintStream output = System.out;

    private Logger() {}

    public static void setLevel(Level level) {
        currentLevel = level;
    }

    public static void setOutput(PrintStream out) {
        output = out != null ? out : System.out;
    }

    public static void fine(String message) {
        log(Level.FINE, message);
    }

    public static void info(String message) {
        log(Level.INFO, message);
    }

    public static void warn(String message) {
        log(Level.WARNING, message);
    }

    public static void severe(String message, Throwable t) {
        log(Level.SEVERE, message + (t != null ? " - " + t.getMessage() : ""));
    }

    private static void log(Level level, String message) {
        if (level.ordinal() >= currentLevel.ordinal()) {
            String timestamp = LocalDateTime.now().format(FORMATTER);
            String line = String.format("[%s] [%-7s] %s", timestamp, level.name(), message);
            synchronized (LOCK) {
                output.println(line);
            }
        }
    }

    /**
     * Prints a line to the console output with synchronization to prevent
     * interleaving with log messages. Used by the CLI for user-facing output.
     */
    public static void console(String message) {
        synchronized (LOCK) {
            output.println(message);
        }
    }

    /**
     * Prints to the console output without a trailing newline, synchronized.
     * Used for prompts.
     */
    public static void consolePrint(String message) {
        synchronized (LOCK) {
            output.print(message);
            output.flush();
        }
    }
}
