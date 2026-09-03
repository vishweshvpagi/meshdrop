package com.meshdrop.protocol;

import java.io.IOException;

/**
 * Thrown when an incoming network frame violates the MeshDrop binary protocol specification.
 *
 * Examples include:
 *  - Invalid magic bytes
 *  - Unsupported protocol version
 *  - Unknown packet type code
 *  - Declared payload length exceeding maximum allowed size or negative
 *  - Unexpected EOF / truncated frame
 */
public class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
