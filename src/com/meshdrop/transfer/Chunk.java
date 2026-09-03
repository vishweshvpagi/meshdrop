package com.meshdrop.transfer;

import java.util.UUID;

/**
 * Represents a single chunk slice of a file during transfer.
 */
public record Chunk(
        UUID transferId,
        int index,
        long offset,
        int length
) {}
