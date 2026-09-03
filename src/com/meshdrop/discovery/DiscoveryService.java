package com.meshdrop.discovery;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.protocol.ProtocolException;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages periodic UDP multicast beacon announcements and continuous background discovery reception.
 *
 * Distinct roles:
 *   - UDP Multicast = Lightweight local discovery (creates PeerState.DISCOVERED).
 *   - TCP Transport = Reliable streaming, handshake negotiation, and data transfers.
 */
public class DiscoveryService {

    private final NodeIdentity localIdentity;
    private final int tcpPort;
    private final int udpDiscoveryPort;
    private final String multicastGroup;
    private final int intervalMillis;
    private final DiscoveryListener listener;

    private volatile boolean running = false;
    private MulticastSocket socket;
    private InetAddress groupAddress;
    private ScheduledExecutorService beaconScheduler;
    private Thread receiverThread;

    public DiscoveryService(
            NodeIdentity localIdentity,
            int tcpPort,
            int udpDiscoveryPort,
            String multicastGroup,
            int intervalMillis,
            DiscoveryListener listener
    ) {
        this.localIdentity = Objects.requireNonNull(localIdentity, "localIdentity must not be null");
        this.tcpPort = tcpPort;
        this.udpDiscoveryPort = udpDiscoveryPort;
        this.multicastGroup = Objects.requireNonNull(multicastGroup, "multicastGroup must not be null");
        this.intervalMillis = intervalMillis > 0 ? intervalMillis : DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS;
        this.listener = listener;
    }

    public DiscoveryService(
            NodeIdentity localIdentity,
            int tcpPort,
            DiscoveryListener listener
    ) {
        this(
                localIdentity,
                tcpPort,
                DiscoveryConstants.DEFAULT_DISCOVERY_PORT,
                DiscoveryConstants.DEFAULT_MULTICAST_GROUP,
                DiscoveryConstants.DEFAULT_DISCOVERY_INTERVAL_MS,
                listener
        );
    }

    /**
     * Starts the discovery subsystem (binds multicast socket, joins group, starts receiver loop and beacon scheduler).
     *
     * @throws IOException if the multicast socket cannot be bound
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        this.groupAddress = InetAddress.getByName(multicastGroup);
        this.socket = new MulticastSocket(null);
        this.socket.setReuseAddress(true);
        this.socket.bind(new InetSocketAddress(udpDiscoveryPort));

        // Join multicast group across active multicast-capable network interfaces
        joinMulticastGroup();

        this.running = true;

        // Start background receiver thread
        this.receiverThread = Thread.ofVirtual()
                .name("discovery-recv-" + getUdpDiscoveryPort())
                .start(this::receiveLoop);

        // Start periodic discovery beacon scheduler
        this.beaconScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discovery-beacon");
            t.setDaemon(true);
            return t;
        });

        this.beaconScheduler.scheduleAtFixedRate(
                this::sendBeacon,
                0,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );

        Logger.info("[DISCOVERY] UDP discovery started on port " + getUdpDiscoveryPort() + " (group " + multicastGroup + ")");
    }

    /**
     * Broadcasts a single discovery beacon over UDP multicast.
     */
    public void sendBeacon() {
        if (!running || socket == null || socket.isClosed()) {
            return;
        }
        try {
            DiscoveryMessage msg = DiscoveryMessage.beacon(localIdentity.nodeId(), tcpPort, localIdentity.displayName());
            byte[] bytes = msg.encode();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, groupAddress, getUdpDiscoveryPort());
            socket.send(packet);
            Logger.fine("[DISCOVERY] Sent discovery beacon for " + localIdentity.displayName() + " (" + localIdentity.nodeId() + ")");
        } catch (Exception e) {
            if (running) {
                Logger.warn("[DISCOVERY] Failed to send discovery beacon: " + e.getMessage());
            }
        }
    }

    /**
     * Sends a direct unicast discovery beacon to a specific target host/port (useful in tests and manual discovery).
     */
    public void sendUnicastBeacon(String host, int port) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Discovery socket is not open");
        }
        DiscoveryMessage msg = DiscoveryMessage.beacon(localIdentity.nodeId(), tcpPort, localIdentity.displayName());
        byte[] bytes = msg.encode();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName(host), port);
        socket.send(packet);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[DiscoveryConstants.MAX_DISCOVERY_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                packet.setData(buffer);
                packet.setLength(buffer.length);
                socket.receive(packet);

                handleReceivedPacket(packet);
            } catch (SocketException e) {
                if (!running) {
                    break; // Clean shutdown
                }
                Logger.warn("[DISCOVERY] Socket exception in receive loop: " + e.getMessage());
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                Logger.warn("[DISCOVERY] Error receiving discovery packet: " + e.getMessage());
            }
        }
    }

    private void handleReceivedPacket(DatagramPacket packet) {
        try {
            DiscoveryMessage msg = DiscoveryMessage.decode(packet.getData(), packet.getLength());

            // 1. Filter out self-discovery
            if (msg.nodeId().equals(localIdentity.nodeId())) {
                return;
            }

            // 2. Derive peer address from UDP source IP + advertised TCP port
            String senderHost = packet.getAddress().getHostAddress();
            PeerAddress address = new PeerAddress(senderHost, msg.tcpPort());
            NodeIdentity remoteIdentity = NodeIdentity.of(msg.nodeId(), msg.displayName());

            Logger.fine("[DISCOVERY] Peer discovered via UDP: " + remoteIdentity.displayName() +
                    " (" + remoteIdentity.nodeId() + ") at " + address);

            if (listener != null) {
                listener.onPeerDiscovered(remoteIdentity, address);
            }
        } catch (ProtocolException e) {
            Logger.fine("[DISCOVERY] Ignored invalid discovery packet from " + packet.getAddress() + ": " + e.getMessage());
        }
    }

    private void joinMulticastGroup() {
        SocketAddress sa = new InetSocketAddress(groupAddress, getUdpDiscoveryPort());
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface netIf = interfaces.nextElement();
                    if (netIf.isUp() && netIf.supportsMulticast()) {
                        try {
                            socket.joinGroup(sa, netIf);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn("[DISCOVERY] Error joining multicast groups across interfaces: " + e.getMessage());
        }
    }

    private void leaveMulticastGroup() {
        if (socket == null || socket.isClosed() || groupAddress == null) {
            return;
        }
        SocketAddress sa = new InetSocketAddress(groupAddress, getUdpDiscoveryPort());
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface netIf = interfaces.nextElement();
                    if (netIf.isUp() && netIf.supportsMulticast()) {
                        try {
                            socket.leaveGroup(sa, netIf);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Gracefully stops the discovery subsystem (cancels beacon scheduler, leaves multicast group, closes socket).
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        this.running = false;

        Logger.info("[DISCOVERY] Stopping UDP discovery...");

        if (beaconScheduler != null) {
            beaconScheduler.shutdownNow();
        }

        if (socket != null && !socket.isClosed()) {
            try {
                leaveMulticastGroup();
            } catch (Exception ignored) {}
            socket.close();
        }

        Logger.info("[DISCOVERY] UDP discovery stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    public int getUdpDiscoveryPort() {
        if (socket != null && !socket.isClosed()) {
            return socket.getLocalPort();
        }
        return udpDiscoveryPort;
    }

    public String getMulticastGroup() {
        return multicastGroup;
    }
}
