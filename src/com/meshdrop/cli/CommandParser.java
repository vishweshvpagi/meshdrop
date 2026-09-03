package com.meshdrop.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses raw command-line input strings into structured {@link Command} objects.
 *
 * Supports:
 *   - Simple commands: "help", "status"
 *   - Commands with arguments: "ping laptop"
 *   - Quoted arguments (both double quotes "..." and single quotes '...'):
 *     send abc "hello world"
 *     send abc 'hello world'
 *   - Two-argument commands where the second arg consumes the rest:
 *     "send abc hello this is a message" → command=send, args=[abc, hello this is a message]
 *   - Multiple and extra whitespace (spaces, tabs) is handled cleanly
 *   - Empty or blank input returns an empty Command
 */
public class CommandParser {

    /** Commands where the second argument should consume all remaining text. */
    private static final List<String> REST_COMMANDS = List.of("send");

    /**
     * Parses a raw input line into a Command.
     *
     * @param input the raw input string
     * @return parsed Command (never null; may have empty name for blank input)
     */
    public Command parse(String input) {
        if (input == null || input.isBlank()) {
            return new Command("", List.of());
        }

        String trimmed = input.trim();
        List<String> tokens = tokenize(trimmed);

        if (tokens.isEmpty()) {
            return new Command("", List.of());
        }

        String name = tokens.get(0).toLowerCase();
        List<String> args;

        if (tokens.size() <= 1) {
            args = List.of();
        } else if (REST_COMMANDS.contains(name) && tokens.size() >= 3) {
            // For 'send': first arg is peer identifier, rest is the message body
            String peer = tokens.get(1);
            String message = extractRestAfterSecondToken(trimmed);
            args = List.of(peer, message);
        } else {
            args = tokens.subList(1, tokens.size());
        }

        return new Command(name, args);
    }

    /**
     * Tokenizes input with support for double and single quoted strings.
     */
    private List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int len = input.length();

        while (i < len) {
            // Skip whitespace
            while (i < len && Character.isWhitespace(input.charAt(i))) {
                i++;
            }
            if (i >= len) break;

            char c = input.charAt(i);
            if (c == '"' || c == '\'') {
                // Quoted token
                char quoteChar = c;
                i++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (i < len && input.charAt(i) != quoteChar) {
                    sb.append(input.charAt(i));
                    i++;
                }
                if (i < len) i++; // skip closing quote
                tokens.add(sb.toString());
            } else {
                // Unquoted token
                StringBuilder sb = new StringBuilder();
                while (i < len && !Character.isWhitespace(input.charAt(i))) {
                    sb.append(input.charAt(i));
                    i++;
                }
                tokens.add(sb.toString());
            }
        }

        return tokens;
    }

    /**
     * Extracts everything after the second token as a single string.
     * Handles quoted second tokens (both " and ') correctly.
     *
     * Example: "send abc hello this is a message" → "hello this is a message"
     * Example: send abc "hello world" → "hello world"
     * Example: send abc 'hello world' → "hello world"
     */
    private String extractRestAfterSecondToken(String input) {
        int i = 0;
        int len = input.length();

        // Skip first token (command name)
        while (i < len && !Character.isWhitespace(input.charAt(i))) i++;
        // Skip whitespace
        while (i < len && Character.isWhitespace(input.charAt(i))) i++;

        // Skip second token (peer identifier)
        if (i < len && (input.charAt(i) == '"' || input.charAt(i) == '\'')) {
            char quote = input.charAt(i);
            i++; // skip opening quote
            while (i < len && input.charAt(i) != quote) i++;
            if (i < len) i++; // skip closing quote
        } else {
            while (i < len && !Character.isWhitespace(input.charAt(i))) i++;
        }

        // Skip whitespace
        while (i < len && Character.isWhitespace(input.charAt(i))) i++;

        if (i >= len) {
            return "";
        }

        String rest = input.substring(i);

        // Strip outer quotes from the message if present (double or single)
        if (rest.length() >= 2) {
            if ((rest.startsWith("\"") && rest.endsWith("\"")) || (rest.startsWith("'") && rest.endsWith("'"))) {
                return rest.substring(1, rest.length() - 1);
            }
        }

        return rest;
    }
}
