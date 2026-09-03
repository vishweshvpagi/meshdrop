package com.meshdrop.network;

import com.meshdrop.util.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * TCP Server that listens for incoming peer connections on a dedicated port.
 *
 * Uses java.net.ServerSocket to bind, listen, and accept incoming TCP connections.
 * The accept loop runs on a Java 26 virtual thread so that it never blocks the
 * thread that called start(). Each accepted connection is wrapped in a TcpConnection
 * and dispatched to the TcpConnectionHandler.
 *
 * Why ServerSocket.accept() blocks:
 *   accept() parks the calling thread until the OS TCP stack completes a three-way
 *   handshake (SYN → SYN-ACK → ACK) with a remote client. Using a virtual thread
 *   for the accept loop means the underlying platform thread is released back to
 *   the carrier pool while waiting, giving us scalability without NIO complexity.
 *
 * Graceful shutdown:
 *   Closing the ServerSocket from another thread causes accept() to throw a
 *   SocketException, which we catch to exit the loop cleanly.
 */
public class TcpServer implements AutoCloseable {
    private final int port;
    private final TcpConnectionHandler connectionHandler;

    /**
     * Callback invoked after a TcpConnection is fully initialised.
     * Allows the Node (or test) to track accepted connections without
     * the TcpServer knowing about connection management.
     */
    private final ConnectionCallback connectionCallback;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * Functional interface for connection lifecycle notifications.
     * The server notifies the callback after the handler has initialised
     * the connection (greeting sent, receive loop started).
     */
    @FunctionalInterface
    public interface ConnectionCallback {
        void onConnectionEstablished(TcpConnection connection);
    }

    /**
     * Creates a server with a connection callback for tracking.
     */
    public TcpServer(int port, TcpConnectionHandler connectionHandler, ConnectionCallback connectionCallback) {
        this.port = port;
        this.connectionHandler = connectionHandler;
        this.connectionCallback = connectionCallback;
    }

    /**
     * Creates a server without a connection callback (backward-compatible).
     */
    public TcpServer(int port, TcpConnectionHandler connectionHandler) {
        this(port, connectionHandler, conn -> {});
    }

    /**
     * Binds the server socket and starts the accept loop on a virtual thread.
     *
     * ServerSocket(port) performs two OS-level operations:
     *   1. bind() — associates the socket with the local port number.
     *   2. listen() — tells the OS to start accepting SYN packets on that port.
     * After this call returns, remote clients can begin TCP handshakes.
     */
    public void start() throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.running = true;

        // Run the blocking accept loop on a virtual thread so that the caller
        // (typically Main or Node) is not blocked and can continue startup.
        this.acceptThread = Thread.ofVirtual()
                .name("tcp-accept-loop")
                .start(this::acceptLoop);

        Logger.info("TCP server started on port " + getLocalPort());
    }

    /**
     * Blocking accept loop. Runs until the server is stopped.
     *
     * ServerSocket.accept() blocks until a remote client completes the TCP
     * three-way handshake. Once a connection is established, accept() returns
     * a new Socket representing that specific connection. The ServerSocket
     * itself remains open and continues listening for additional connections.
     *
     * Each accepted socket is handed to a new virtual thread so that multiple
     * peers can connect simultaneously without serialising their I/O.
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // Dispatch each connection to its own virtual thread
                Thread.ofVirtual()
                        .name("tcp-accept-handle-" + clientSocket.getRemoteSocketAddress())
                        .start(() -> handleAcceptedSocket(clientSocket));
            } catch (SocketException e) {
                // SocketException is the expected way accept() terminates when
                // another thread closes the ServerSocket during shutdown.
                if (running) {
                    Logger.severe("Accept loop socket error", e);
                }
                break;
            } catch (IOException e) {
                if (running) {
                    Logger.severe("Accept loop I/O error", e);
                }
            }
        }
        Logger.info("TCP accept loop exited.");
    }

    /**
     * Wraps an accepted socket in a TcpConnection, delegates to the handler,
     * and notifies the connection callback.
     *
     * Any exception from the handler closes only the affected connection, never
     * the entire server — a critical resilience property.
     */
    private void handleAcceptedSocket(Socket clientSocket) {
        Logger.info("Incoming TCP connection: " + clientSocket.getRemoteSocketAddress());
        try {
            TcpConnection connection = new TcpConnection(clientSocket);
            connectionHandler.handle(connection);
            connectionCallback.onConnectionEstablished(connection);
        } catch (Exception e) {
            Logger.severe("Error handling connection from " + clientSocket.getRemoteSocketAddress(), e);
            try {
                clientSocket.close();
            } catch (IOException closeEx) {
                // Socket was likely already broken; nothing to recover.
            }
        }
    }

    /**
     * Returns the port number the server was configured with.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the actual port the server is bound to.
     * When port 0 is used, the OS assigns an ephemeral port. This method
     * returns that actual port, which is essential for testing.
     */
    public int getLocalPort() {
        if (serverSocket != null) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Graceful shutdown sequence:
     * 1. Set running = false so the accept loop knows to stop.
     * 2. Close the ServerSocket. This unblocks accept() with a SocketException.
     * 3. The accept loop catches the exception, sees running == false, and exits.
     *
     * Active connections are NOT closed here. That responsibility belongs to the
     * Node, which tracks all connections and closes them during shutdown.
     */
    @Override
    public void close() throws IOException {
        Logger.info("Stopping TCP server...");
        this.running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
