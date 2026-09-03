package com.meshdrop.peer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Immutable network address and port where a remote peer may be reached.
 *
 * PeerAddress is separate from NodeIdentity:
 *   - NodeIdentity (UUID) is permanent and authoritative.
 *   - PeerAddress is dynamic (IP/port can change as devices roam).
 */
public record PeerAddress(
        String host,
        int tcpPort
) {
    public PeerAddress {
        Objects.requireNonNull(host, "host must not be null");
        if (tcpPort < 0 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid port number: " + tcpPort);
        }
    }

    public static PeerAddress fromSocketAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress inet) {
            String host = inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
            return new PeerAddress(host, inet.getPort());
        }
        return new PeerAddress("127.0.0.1", 0);
    }

    @Override
    public String toString() {
        return host + ":" + tcpPort;
    }
}
