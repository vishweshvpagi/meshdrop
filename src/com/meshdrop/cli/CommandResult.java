package com.meshdrop.cli;

/**
 * Represents the result of executing a CLI command.
 */
public record CommandResult(boolean success, String message) {

    public static CommandResult ok(String message) {
        return new CommandResult(true, message);
    }

    public static CommandResult error(String message) {
        return new CommandResult(false, message);
    }

    public static CommandResult ok() {
        return new CommandResult(true, null);
    }
}
