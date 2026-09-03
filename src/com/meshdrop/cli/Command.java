package com.meshdrop.cli;

import java.util.List;

/**
 * Represents a parsed user command with its name and arguments.
 */
public record Command(String name, List<String> arguments) {

    /**
     * Returns the argument at the specified index, or null if out of bounds.
     */
    public String arg(int index) {
        if (index >= 0 && index < arguments.size()) {
            return arguments.get(index);
        }
        return null;
    }

    /**
     * Returns the number of arguments.
     */
    public int argCount() {
        return arguments.size();
    }

    /**
     * Returns true if this command has no name (empty input).
     */
    public boolean isEmpty() {
        return name == null || name.isBlank();
    }
}
