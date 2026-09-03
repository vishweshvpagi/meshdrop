package com.meshdrop.core;

import com.meshdrop.discovery.DiscoveryConstants;
import com.meshdrop.protocol.ProtocolConstants;

import java.nio.file.Path;

/**
 * Immutable configuration settings for a MeshDrop node.
 */
public record NodeConfig(
        int tcpPort,
        int udpDiscoveryPort,
        boolean discoveryEnabled,
        String discoveryMulticastGroup,
        int discoveryIntervalMillis,
        int maxDiscoveryPacketSize,
        int chunkSize,
        int handshakeTimeoutMillis,
        int connectionTimeoutMillis,
        Path storageDir,
        Path downloadsDir,
        Path tempDir
) {
    public static final int DEFAULT_TCP_PORT = 5000;
    public static final int DEFAULT_UDP_PORT = DiscoveryConstants.DEFAULT_DISCOVERY_PORT;
    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MiB
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = ProtocolConstants.DEFAULT_CONNECTION_TIMEOUT_MS;

    public NodeConfig(
            int tcpPort,
            int udpDiscoveryPort,
            boolean discoveryEnabled,
            String discoveryMulticastGroup,
            int discoveryIntervalMillis,
            int maxDiscoveryPacketSize,
            int chunkSize,
            int handshakeTimeoutMillis,
            Path storageDir,
            Path downloadsDir,
            Path tempDir
    ) {
        this(
                tcpPort,
                udpDiscoveryPort,
                discoveryEnabled,
                discoveryMulticastGroup,
                discoveryIntervalMillis,
                maxDiscoveryPacketSize,
                chunkSize,
                handshakeTimeoutMillis,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                storageDir,
                downloadsDir,
                tempDir
        );
    }

    public NodeConfig(int tcpPort, int udpDiscoveryPort, int chunkSize, int handshakeTimeoutMillis, Path storageDir, Path downloadsDir, Path tempDir) {
        this(
                tcpPort,
                udpDiscoveryPort,
                true,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE,
                chunkSize,
                handshakeTimeoutMillis,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                storageDir,
                downloadsDir,
                tempDir
        );
    }

    public NodeConfig(int tcpPort, int udpDiscoveryPort, int chunkSize, Path storageDir, Path downloadsDir, Path tempDir) {
        this(tcpPort, udpDiscoveryPort, chunkSize, ProtocolConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS, storageDir, downloadsDir, tempDir);
    }

    public static NodeConfig defaultConfig() {
        Path baseStorage = Path.of("storage");
        return new NodeConfig(
                DEFAULT_TCP_PORT,
                DEFAULT_UDP_PORT,
                true,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE,
                DEFAULT_CHUNK_SIZE,
                ProtocolConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                baseStorage,
                baseStorage.resolve("downloads"),
                baseStorage.resolve("temp")
        );
    }

    public static NodeConfig withPortAndTimeout(int tcpPort, int handshakeTimeoutMillis) {
        Path baseStorage = Path.of("storage");
        return new NodeConfig(
                tcpPort,
                DEFAULT_UDP_PORT,
                false, // Discovery disabled for TCP-specific tests
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE,
                DEFAULT_CHUNK_SIZE,
                handshakeTimeoutMillis,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                baseStorage,
                baseStorage.resolve("downloads"),
                baseStorage.resolve("temp")
        );
    }

    public static NodeConfig withDiscovery(int tcpPort, int udpDiscoveryPort, boolean discoveryEnabled) {
        Path baseStorage = Path.of("storage");
        return new NodeConfig(
                tcpPort,
                udpDiscoveryPort,
                discoveryEnabled,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE,
                DEFAULT_CHUNK_SIZE,
                ProtocolConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                baseStorage,
                baseStorage.resolve("downloads"),
                baseStorage.resolve("temp")
        );
    }

    public static NodeConfig forTesting(int tcpPort, int udpDiscoveryPort, Path downloadsDir, Path tempDir) {
        Path baseStorage = downloadsDir.getParent() != null ? downloadsDir.getParent() : Path.of("storage");
        return new NodeConfig(
                tcpPort,
                udpDiscoveryPort,
                true,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE,
                ProtocolConstants.DEFAULT_FILE_CHUNK_SIZE,
                ProtocolConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                baseStorage,
                downloadsDir,
                tempDir
        );
    }

    public Path identityDir() {
        return storageDir != null ? storageDir.resolve("identity") : Path.of("storage", "identity");
    }

    public Path trustDir() {
        return storageDir != null ? storageDir.resolve("trust") : Path.of("storage", "trust");
    }

    public Path logsDir() {
        return storageDir != null ? storageDir.resolve("logs") : Path.of("storage", "logs");
    }

    public static NodeConfig withDataDir(Path baseDir, int tcpPort, int udpDiscoveryPort, boolean discoveryEnabled) {
        return new NodeConfig(
                tcpPort,
                udpDiscoveryPort,
                discoveryEnabled,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE,
                DEFAULT_CHUNK_SIZE,
                ProtocolConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                baseDir,
                baseDir.resolve("downloads"),
                baseDir.resolve("transfers")
        );
    }
}
