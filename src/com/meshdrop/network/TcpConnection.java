package com.meshdrop.network;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.protocol.Packet;
import com.meshdrop.protocol.PacketDecoder;
import com.meshdrop.protocol.PacketEncoder;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.protocol.ProtocolException;
import com.meshdrop.util.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Represents a single bidirectional TCP connection to a remote endpoint.
 *
 * TcpConnection manages the transport lifecycle (Socket, InputStream, OutputStream)
 * and uses PacketEncoder and PacketDecoder to transmit framed binary Packet messages.
 *
 * State Machine Lifecycle:
 *   CONNECTING -> CONNECTED -> HANDSHAKING -> READY -> CLOSING -> CLOSED
 */
public class TcpConnection implements AutoCloseable {

    private static final AtomicLong ID_COUNTER = new AtomicLong(0);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int IO_BUFFER_SIZE = 16 * 1024;

    private final long connectionId;
    private final Socket socket;
    private final ConnectionDirection direction;
    private final InputStream in;
    private final OutputStream out;
    private final PacketEncoder encoder;
    private final PacketDecoder decoder;
    private final List<Consumer<TcpConnection>> closeListeners = new CopyOnWriteArrayList<>();
    private final long connectedAt = System.currentTimeMillis();
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile ConnectionState state;
    private volatile NodeIdentity remoteIdentity;
    private Thread receiverThread;

    /**
     * Wraps an already-connected socket with an explicit direction.
     *
     * @param socket the connected Socket
     * @param direction connection establishment direction (INBOUND / OUTBOUND)
     * @throws IOException if obtaining streams fails
     */
    public TcpConnection(Socket socket, ConnectionDirection direction) throws IOException {
        this.connectionId = ID_COUNTER.incrementAndGet();
        this.socket = Objects.requireNonNull(socket, "socket must not be null");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        configureSocket(socket);
        this.in = new BufferedInputStream(socket.getInputStream(), IO_BUFFER_SIZE);
        this.out = new BufferedOutputStream(socket.getOutputStream(), IO_BUFFER_SIZE);
        this.encoder = new PacketEncoder();
        this.decoder = new PacketDecoder();
        this.state = ConnectionState.CONNECTED;
    }

    /**
     * Convenience constructor defaulting direction to INBOUND (for accepted sockets).
     */
    public TcpConnection(Socket socket) throws IOException {
        this(socket, ConnectionDirection.INBOUND);
    }

    /**
     * Creates an outgoing TCP connection to a remote host and port with a custom timeout.
     *
     * @param host remote hostname or IP address
     * @param port remote TCP port
     * @param timeoutMillis connection timeout in milliseconds
     * @return established OUTBOUND TcpConnection
     * @throws IOException if connection fails
     */
    public static TcpConnection connectTo(String host, int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis > 0 ? timeoutMillis : ProtocolConstants.DEFAULT_CONNECTION_TIMEOUT_MS);
            return new TcpConnection(socket, ConnectionDirection.OUTBOUND);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Creates an outgoing TCP connection to a remote host and port with default timeout.
     *
     * @param host remote hostname or IP address
     * @param port remote TCP port
     * @return established OUTBOUND TcpConnection
     * @throws IOException if connection fails
     */
    public static TcpConnection connectTo(String host, int port) throws IOException {
        return connectTo(host, port, ProtocolConstants.DEFAULT_CONNECTION_TIMEOUT_MS);
    }

    private static void configureSocket(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    public void addCloseListener(Consumer<TcpConnection> listener) {
        if (listener != null) {
            closeListeners.add(listener);
        }
    }

    public void removeCloseListener(Consumer<TcpConnection> listener) {
        closeListeners.remove(listener);
    }

    public void startReceiving(BiConsumer<TcpConnection, Packet> packetCallback) {
        Objects.requireNonNull(packetCallback, "packetCallback must not be null");
        if (receiverThread != null) {
            throw new IllegalStateException("Receiver already started for connection " + connectionId);
        }
        this.receiverThread = Thread.ofVirtual()
                .name("tcp-recv-" + connectionId)
                .start(() -> receiveLoop(packetCallback));
    }

    private void receiveLoop(BiConsumer<TcpConnection, Packet> packetCallback) {
        try {
            while (isOpen()) {
                Packet packet = decoder.decode(in);
                if (packet == null) {
                    Logger.info("Connection " + connectionId + ": remote peer closed gracefully (EOF)");
                    break;
                }

                lastActivity = System.currentTimeMillis();
                try {
                    packetCallback.accept(this, packet);
                } catch (Exception e) {
                    Logger.severe("Connection " + connectionId + ": packet callback error: " + e.getMessage(), e);
                    if (e instanceof ProtocolException || (e.getCause() instanceof ProtocolException)) {
                        break;
                    }
                }
            }
        } catch (SocketException e) {
            if (state != ConnectionState.CLOSING && state != ConnectionState.CLOSED) {
                Logger.warn("Connection " + connectionId + ": socket exception in receive loop: " + e.getMessage());
            }
        } catch (ProtocolException e) {
            Logger.severe("Connection " + connectionId + ": protocol error in receive loop: " + e.getMessage(), e);
        } catch (IOException e) {
            if (state != ConnectionState.CLOSING && state != ConnectionState.CLOSED) {
                Logger.severe("Connection " + connectionId + ": I/O error in receive loop", e);
            }
        } finally {
            closeQuietly();
        }
    }

    public void sendPacket(Packet packet) throws IOException {
        Objects.requireNonNull(packet, "packet must not be null");
        if (!isOpen()) {
            throw new IOException("Cannot send packet: connection " + connectionId + " is " + state);
        }
        lastActivity = System.currentTimeMillis();
        synchronized (out) {
            encoder.encode(packet, out);
        }
    }

    public long getConnectedDurationMillis() {
        return Math.max(0, System.currentTimeMillis() - connectedAt);
    }

    public long getIdleDurationMillis() {
        return Math.max(0, System.currentTimeMillis() - lastActivity);
    }

    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    public void send(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        if (!isOpen()) {
            throw new IOException("Cannot send: connection " + connectionId + " is " + state);
        }
        synchronized (out) {
            out.write(data);
            out.flush();
        }
    }

    public SocketAddress getRemoteAddress() {
        return socket.getRemoteSocketAddress();
    }

    public SocketAddress getLocalAddress() {
        return socket.getLocalSocketAddress();
    }

    public long getConnectionId() {
        return connectionId;
    }

    public ConnectionDirection getDirection() {
        return direction;
    }

    public ConnectionState getState() {
        return state;
    }

    public synchronized void setState(ConnectionState newState) {
        if (this.state == newState) {
            return;
        }
        if (this.state == ConnectionState.CLOSED) {
            throw new IllegalStateException("Cannot transition from CLOSED to " + newState);
        }
        if (this.state == ConnectionState.CLOSING && newState != ConnectionState.CLOSED) {
            throw new IllegalStateException("Cannot transition from CLOSING to " + newState);
        }
        this.state = newState;
    }

    public NodeIdentity getRemoteIdentity() {
        return remoteIdentity;
    }

    public void setRemoteIdentity(NodeIdentity remoteIdentity) {
        this.remoteIdentity = remoteIdentity;
    }

    public boolean isOpen() {
        return state == ConnectionState.CONNECTED
                || state == ConnectionState.HANDSHAKING
                || state == ConnectionState.READY;
    }

    public boolean isReady() {
        return state == ConnectionState.READY;
    }

    @Override
    public void close() throws IOException {
        if (state == ConnectionState.CLOSED) {
            return;
        }
        this.state = ConnectionState.CLOSING;
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } finally {
            this.state = ConnectionState.CLOSED;
            notifyCloseListeners();
        }
    }

    private void notifyCloseListeners() {
        for (Consumer<TcpConnection> listener : closeListeners) {
            try {
                listener.accept(this);
            } catch (Exception ignored) {}
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {}
    }

    @Override
    public String toString() {
        return "TcpConnection{id=" + connectionId +
                ", direction=" + direction +
                ", remote=" + getRemoteAddress() +
                ", state=" + state +
                (remoteIdentity != null ? ", peer=" + remoteIdentity : "") +
                '}';
    }
}
