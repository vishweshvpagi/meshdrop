package com.meshdrop.discovery;

/**
 * Constants for MeshDrop UDP multicast peer discovery.
 */
public final class DiscoveryConstants {
    private DiscoveryConstants() {}

    /** Default administratively-scoped multicast group for LAN discovery */
    public static final String DEFAULT_MULTICAST_GROUP = "239.255.77.80";

    /** Default UDP discovery port */
    public static final int DEFAULT_DISCOVERY_PORT = 5001;

    /** Default discovery beacon announcement interval in milliseconds (5 seconds) */
    public static final int DEFAULT_DISCOVERY_INTERVAL_MS = 5000;

    /** Maximum allowed UDP discovery packet size in bytes */
    public static final int MAX_DISCOVERY_PACKET_SIZE = 512;
}
