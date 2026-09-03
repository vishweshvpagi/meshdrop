package com.meshdrop.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Handles peer discovery across the local subnet using UDP broadcasts.
 */
public class UdpDiscovery implements AutoCloseable {
    private final int discoveryPort;
    private DatagramSocket socket;
    private volatile boolean running;

    public UdpDiscovery(int discoveryPort) {
        this.discoveryPort = discoveryPort;
    }

    public void start() throws IOException {
        this.socket = new DatagramSocket(discoveryPort);
        this.running = true;
    }

    public void broadcast(byte[] data) throws IOException {
        // Implementation will broadcast discovery announcements in Phase 8
    }

    public DatagramPacket receive() throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        if (socket != null && running) {
            socket.receive(packet);
        }
        return packet;
    }

    public int getDiscoveryPort() {
        return discoveryPort;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        this.running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
