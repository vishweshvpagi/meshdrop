package com.meshdrop.api;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.transfer.FileMetadata;
import com.meshdrop.transfer.Transfer;
import com.meshdrop.transfer.TransferDirection;
import com.meshdrop.transfer.TransferState;
import com.meshdrop.util.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Minimal local HTTP control server for MeshDrop.
 * Exposes read-only observation endpoints and safe connection controls
 * for the React frontend desktop control panel.
 */
public class HttpControlServer implements AutoCloseable {

    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8080"
    );

    private final Node node;
    private final int port;
    private final String host;
    private HttpServer server;

    public HttpControlServer(Node node, int port) {
        this(node, "127.0.0.1", port);
    }

    public HttpControlServer(Node node, String host, int port) {
        this.node = node;
        this.host = host != null ? host : "127.0.0.1";
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/peers", new PeersHandler());
        server.createContext("/api/connections", new ConnectionsHandler());
        server.createContext("/api/connect", new ConnectHandler());
        server.createContext("/api/transfers", new TransfersHandler());
        server.createContext("/api/transfers/cancel", new TransferCancelHandler());

        server.start();
        Logger.info("[API] MeshDrop Control API listening on http://" + host + ":" + getPort());
    }

    public synchronized void stop() {
        if (server != null) {
            Logger.info("[API] Stopping MeshDrop Control API...");
            server.stop(0);
            server = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : port;
    }

    private boolean handleCors(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && (ALLOWED_ORIGINS.contains(origin) || origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:"))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        } else {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://localhost:3000");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ========================================================================
    // Handlers
    // ========================================================================

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nodeId", node.getIdentity().nodeId().toString());
            data.put("displayName", node.getIdentity().displayName());
            data.put("running", node.getState() == NodeState.RUNNING);
            data.put("state", node.getState().name());
            data.put("tcpPort", node.getTcpServer() != null ? node.getTcpServer().getLocalPort() : node.getConfig().tcpPort());
            data.put("discoveryPort", node.getConfig().udpDiscoveryPort());
            data.put("discoveryRunning", node.getDiscoveryService() != null && node.getDiscoveryService().isRunning());
            data.put("fingerprint", node.getIdentity().fingerprint() != null ? node.getIdentity().fingerprint().toString() : "");
            data.put("uptimeMillis", node.getUptimeMillis());
            data.put("connectionCount", node.getConnectionCount());
            data.put("peerCount", node.getPeerManager() != null ? node.getPeerManager().getPeerCount() : 0);

            sendJsonResponse(exchange, 200, JsonUtils.toJson(data));
        }
    }

    private Map<String, Object> peerToMap(Peer peer) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", peer.getNodeId().toString());
        p.put("displayName", peer.getDisplayName());
        p.put("address", peer.getAddress() != null ? peer.getAddress().host() : "");
        p.put("port", peer.getAddress() != null ? peer.getAddress().tcpPort() : 0);
        p.put("state", peer.getState().name());
        p.put("connected", peer.isConnected());
        p.put("lastSeen", peer.getLastSeen() != null ? peer.getLastSeen().toString() : null);
        p.put("connectedAt", peer.getConnectedAt() != null ? peer.getConnectedAt().toString() : null);
        p.put("fingerprint", peer.getFingerprint() != null ? peer.getFingerprint().toString() : "");
        p.put("trustDecision", peer.getTrustDecision() != null ? peer.getTrustDecision().name() : "UNTRUSTED");
        return p;
    }

    private class PeersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }

            // Exact match: GET /api/peers -> list all peers
            if ("/api/peers".equals(path)) {
                if (!"GET".equals(method)) {
                    sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }

                List<Map<String, Object>> peerList = new ArrayList<>();
                if (node.getPeerManager() != null) {
                    for (Peer peer : node.getPeerManager().getPeers()) {
                        peerList.add(peerToMap(peer));
                    }
                }

                sendJsonResponse(exchange, 200, JsonUtils.toJson(peerList));
                return;
            }

            // Sub-paths: /api/peers/...
            if (path.startsWith("/api/peers/")) {
                String sub = path.substring("/api/peers/".length());
                String[] segments = sub.split("/");

                UUID peerId;
                try {
                    peerId = UUID.fromString(segments[0]);
                } catch (IllegalArgumentException e) {
                    sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid peer UUID: " + segments[0] + "\"}");
                    return;
                }

                if (node.getPeerManager() == null) {
                    sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"PeerManager not initialized\"}");
                    return;
                }

                var peerOpt = node.getPeerManager().findPeer(peerId);
                if (peerOpt.isEmpty()) {
                    sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Peer not found: " + peerId + "\"}");
                    return;
                }
                Peer peer = peerOpt.get();

                // 1. Single peer resource: GET /api/peers/{id}
                if (segments.length == 1) {
                    if ("GET".equals(method)) {
                        sendJsonResponse(exchange, 200, JsonUtils.toJson(peerToMap(peer)));
                    } else {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    }
                    return;
                }

                // 2. Action endpoints: POST /api/peers/{id}/{action}
                if (segments.length == 2 && "POST".equals(method)) {
                    String action = segments[1].toLowerCase();
                    if ("connect".equals(action)) {
                        try {
                            TcpConnection conn = node.connectToPeer(peerId);
                            Map<String, Object> res = new LinkedHashMap<>();
                            res.put("success", true);
                            res.put("connectionId", conn.getConnectionId());
                            res.put("peerId", peerId.toString());
                            res.put("state", peer.getState().name());
                            sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
                        } catch (Exception e) {
                            Map<String, Object> err = new LinkedHashMap<>();
                            err.put("success", false);
                            err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                            sendJsonResponse(exchange, 400, JsonUtils.toJson(err));
                        }
                        return;
                    } else if ("disconnect".equals(action)) {
                        try {
                            boolean disconnected = node.disconnectPeer(peerId);
                            Map<String, Object> res = new LinkedHashMap<>();
                            res.put("success", true);
                            res.put("disconnected", disconnected);
                            res.put("peerId", peerId.toString());
                            res.put("state", peer.getState().name());
                            sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
                        } catch (Exception e) {
                            Map<String, Object> err = new LinkedHashMap<>();
                            err.put("success", false);
                            err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                            sendJsonResponse(exchange, 500, JsonUtils.toJson(err));
                        }
                        return;
                    } else {
                        sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Unknown action: " + action + "\"}");
                        return;
                    }
                }

                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            sendJsonResponse(exchange, 404, "{\"error\":\"Not found\"}");
        }
    }

    private class ConnectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            List<Map<String, Object>> connList = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (TcpConnection conn : node.getActiveConnections()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("connectionId", conn.getConnectionId());
                c.put("peerId", conn.getRemoteIdentity() != null ? conn.getRemoteIdentity().nodeId().toString() : null);
                c.put("displayName", conn.getRemoteIdentity() != null ? conn.getRemoteIdentity().displayName() : null);
                c.put("state", conn.getState() != null ? conn.getState().name() : "UNKNOWN");
                c.put("direction", conn.getDirection() != null ? conn.getDirection().name() : "UNKNOWN");
                c.put("remoteAddress", conn.getRemoteAddress() != null ? conn.getRemoteAddress().toString() : "");
                c.put("connectedAt", conn.getConnectedAt());
                c.put("durationMillis", Math.max(0, now - conn.getConnectedAt()));
                connList.add(c);
            }

            sendJsonResponse(exchange, 200, JsonUtils.toJson(connList));
        }
    }

    private class ConnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String peerIdStr = JsonUtils.getString(body, "peerId");
            if (peerIdStr != null && !peerIdStr.isBlank()) {
                try {
                    UUID peerId = UUID.fromString(peerIdStr);
                    TcpConnection conn = node.connectToPeer(peerId);
                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("success", true);
                    res.put("connectionId", conn.getConnectionId());
                    res.put("peerId", peerId.toString());
                    sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
                    return;
                } catch (IllegalArgumentException e) {
                    sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid peer UUID: " + peerIdStr + "\"}");
                    return;
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("success", false);
                    err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    sendJsonResponse(exchange, 400, JsonUtils.toJson(err));
                    return;
                }
            }

            String host = JsonUtils.getString(body, "host");
            Integer port = JsonUtils.getInt(body, "port");

            if (host == null || host.isBlank() || port == null || port <= 0 || port > 65535) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid host or port parameter\"}");
                return;
            }

            try {
                TcpConnection conn = node.connectTo(host, port);
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", true);
                res.put("connectionId", conn.getConnectionId());
                sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                sendJsonResponse(exchange, 500, JsonUtils.toJson(err));
            }
        }
    }

    private Map<String, Object> transferToMap(Transfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transferId", t.getTransferId().toString());
        m.put("id", t.getTransferId().toString());

        FileMetadata meta = t.getFileMetadata();
        String fileName = meta != null ? meta.fileName() : (t.getLocalPath() != null ? t.getLocalPath().getFileName().toString() : "unknown");
        m.put("fileName", fileName);
        m.put("fileSize", t.getTotalBytes());
        m.put("transferredBytes", t.getBytesTransferred());
        m.put("remainingBytes", Math.max(0, t.getTotalBytes() - t.getBytesTransferred()));

        boolean isUpload = t.getDirection() == TransferDirection.UPLOAD;
        m.put("direction", isUpload ? "OUTGOING" : "INCOMING");

        UUID peerId = null;
        if (meta != null) {
            peerId = isUpload ? meta.recipientId() : meta.senderId();
        }
        m.put("peerId", peerId != null ? peerId.toString() : "");

        String peerName = "Unknown";
        if (peerId != null && node.getPeerManager() != null) {
            peerName = node.getPeerManager().findPeer(peerId)
                    .map(Peer::getDisplayName)
                    .orElse("Peer-" + peerId.toString().substring(0, 8));
        }
        m.put("peerName", peerName);

        TransferState state = t.getState();
        m.put("state", state.name());
        m.put("status", state.name());
        m.put("speedBytesPerSecond", Math.round(t.getTransferSpeedBps() * 10.0) / 10.0);
        m.put("speed", Math.round(t.getTransferSpeedBps() * 10.0) / 10.0);
        m.put("etaSeconds", t.getEstimatedRemainingSeconds());
        m.put("eta", t.getEstimatedRemainingSeconds());
        m.put("progressPercentage", Math.round(t.getProgressPercentage() * 10.0) / 10.0);
        m.put("errorMessage", t.getErrorMessage());
        m.put("startTime", t.getStartTimeMs());
        m.put("completedTime", t.getCompletedTimeMs());
        m.put("sha256", meta != null ? meta.sha256() : null);

        // Authoritative reliability capability flags computed by Java backend
        boolean isTerminal = state.isTerminal();
        boolean hasLocalFile = t.getLocalPath() != null && Files.isRegularFile(t.getLocalPath());
        boolean canCancel = !isTerminal || state == TransferState.INTERRUPTED || state == TransferState.RESUMABLE;
        boolean canResume = state.isResumable() && (!isUpload || hasLocalFile);
        boolean canRetry = isUpload && (state == TransferState.FAILED || state == TransferState.TIMED_OUT) && hasLocalFile;
        boolean canRemove = isTerminal && state != TransferState.RESUMABLE && state != TransferState.INTERRUPTED;

        m.put("canResume", canResume);
        m.put("canCancel", canCancel);
        m.put("canRetry", canRetry);
        m.put("canRemove", canRemove);
        m.put("hasCheckpoint", t.getCheckpoint() != null);

        return m;
    }

    private class TransfersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }

            // Exact match: /api/transfers
            if ("/api/transfers".equals(path)) {
                if ("GET".equals(method)) {
                    handleGetTransfers(exchange);
                } else if ("POST".equals(method)) {
                    handleStartTransfer(exchange);
                } else {
                    sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                }
                return;
            }

            // Sub-paths: /api/transfers/...
            if (path.startsWith("/api/transfers/")) {
                String sub = path.substring("/api/transfers/".length());
                String[] segments = sub.split("/");

                // Check for /api/transfers/cancel (legacy Phase 3 endpoint)
                if (segments.length == 1 && "cancel".equalsIgnoreCase(segments[0])) {
                    if ("POST".equals(method)) {
                        handleLegacyCancel(exchange);
                    } else {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    }
                    return;
                }

                UUID transferId;
                try {
                    transferId = UUID.fromString(segments[0]);
                } catch (IllegalArgumentException e) {
                    sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid transfer UUID: " + segments[0] + "\"}");
                    return;
                }

                if (node.getFileTransferService() == null || node.getFileTransferService().getTransferManager() == null) {
                    sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"FileTransferService not initialized\"}");
                    return;
                }

                var transferOpt = node.getFileTransferService().getTransferManager().getTransfer(transferId);
                if (transferOpt.isEmpty()) {
                    sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Transfer not found: " + transferId + "\"}");
                    return;
                }
                Transfer transfer = transferOpt.get();

                // 1. Single transfer resource: GET or DELETE /api/transfers/{id}
                if (segments.length == 1) {
                    if ("GET".equals(method)) {
                        sendJsonResponse(exchange, 200, JsonUtils.toJson(transferToMap(transfer)));
                    } else if ("DELETE".equals(method)) {
                        try {
                            boolean removed = node.removeTransfer(transferId);
                            if (removed) {
                                sendJsonResponse(exchange, 200, "{\"success\":true,\"transferId\":\"" + transferId + "\"}");
                            } else {
                                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Transfer could not be removed from history\"}");
                            }
                        } catch (IllegalStateException ex) {
                            sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"" + ex.getMessage() + "\"}");
                        }
                    } else {
                        sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    }
                    return;
                }

                // 2. Action endpoints: POST /api/transfers/{id}/{action}
                if (segments.length == 2 && "POST".equals(method)) {
                    String action = segments[1].toLowerCase();
                    switch (action) {
                        case "resume" -> handleResumeTransfer(exchange, transfer);
                        case "cancel" -> handleCancelTransfer(exchange, transfer);
                        case "retry" -> handleRetryTransfer(exchange, transfer);
                        case "interrupt" -> handleInterruptTransfer(exchange, transfer);
                        default -> sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Unknown action: " + action + "\"}");
                    }
                    return;
                }

                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            sendJsonResponse(exchange, 404, "{\"error\":\"Not found\"}");
        }

        private void handleGetTransfers(HttpExchange exchange) throws IOException {
            List<Map<String, Object>> list = new ArrayList<>();
            if (node.getFileTransferService() != null && node.getFileTransferService().getTransferManager() != null) {
                var transfers = node.getFileTransferService().getTransferManager().getAllTransfers();
                for (Transfer t : transfers) {
                    list.add(transferToMap(t));
                }
            }
            sendJsonResponse(exchange, 200, JsonUtils.toJson(list));
        }

        private void handleStartTransfer(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String peerIdStr = JsonUtils.getString(body, "peerId");
            String filePathStr = JsonUtils.getString(body, "filePath");

            if (peerIdStr == null || peerIdStr.isBlank() || filePathStr == null || filePathStr.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"peerId and filePath are required\"}");
                return;
            }

            UUID peerId;
            try {
                peerId = UUID.fromString(peerIdStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid peer UUID: " + peerIdStr + "\"}");
                return;
            }

            Path path = Path.of(filePathStr);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"File does not exist or is not a regular file: " + filePathStr + "\"}");
                return;
            }

            if (node.getPeerManager() == null) {
                sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"PeerManager not initialized\"}");
                return;
            }

            var peerOpt = node.getPeerManager().findPeer(peerId);
            if (peerOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Peer not found: " + peerId + "\"}");
                return;
            }

            Peer peer = peerOpt.get();
            if (!peer.isConnected()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Peer " + peer.getDisplayName() + " is not connected\"}");
                return;
            }

            try {
                Transfer transfer = node.startFileTransfer(peerId, path);
                Map<String, Object> res = transferToMap(transfer);
                res.put("success", true);
                sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                sendJsonResponse(exchange, 500, JsonUtils.toJson(err));
            }
        }

        private void handleResumeTransfer(HttpExchange exchange, Transfer transfer) throws IOException {
            try {
                node.resumeTransfer(transfer.getTransferId());
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", true);
                res.put("transferId", transfer.getTransferId().toString());
                res.put("state", transfer.getState().name());
                sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                sendJsonResponse(exchange, 400, JsonUtils.toJson(err));
            }
        }

        private void handleCancelTransfer(HttpExchange exchange, Transfer transfer) throws IOException {
            node.cancelTransfer(transfer.getTransferId());
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("transferId", transfer.getTransferId().toString());
            res.put("state", transfer.getState().name());
            sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
        }

        private void handleRetryTransfer(HttpExchange exchange, Transfer transfer) throws IOException {
            try {
                node.retryTransfer(transfer.getTransferId());
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", true);
                res.put("transferId", transfer.getTransferId().toString());
                res.put("state", transfer.getState().name());
                sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                sendJsonResponse(exchange, 400, JsonUtils.toJson(err));
            }
        }

        private void handleInterruptTransfer(HttpExchange exchange, Transfer transfer) throws IOException {
            node.interruptTransfer(transfer.getTransferId());
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("transferId", transfer.getTransferId().toString());
            res.put("state", transfer.getState().name());
            sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
        }

        private void handleLegacyCancel(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String transferIdStr = JsonUtils.getString(body, "transferId");
            if (transferIdStr == null || transferIdStr.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"transferId is required\"}");
                return;
            }

            UUID transferId;
            try {
                transferId = UUID.fromString(transferIdStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid transfer UUID: " + transferIdStr + "\"}");
                return;
            }

            if (node.getFileTransferService() == null) {
                sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"FileTransferService not initialized\"}");
                return;
            }

            var transferOpt = node.getFileTransferService().getTransferManager().getTransfer(transferId);
            if (transferOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Transfer not found: " + transferId + "\"}");
                return;
            }

            node.getFileTransferService().cancelTransfer(transferId);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("transferId", transferId.toString());
            sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
        }
    }

    private class TransferCancelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String transferIdStr = JsonUtils.getString(body, "transferId");
            if (transferIdStr == null || transferIdStr.isBlank()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"transferId is required\"}");
                return;
            }

            UUID transferId;
            try {
                transferId = UUID.fromString(transferIdStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid transfer UUID: " + transferIdStr + "\"}");
                return;
            }

            if (node.getFileTransferService() == null) {
                sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"FileTransferService not initialized\"}");
                return;
            }

            var transferOpt = node.getFileTransferService().getTransferManager().getTransfer(transferId);
            if (transferOpt.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Transfer not found: " + transferId + "\"}");
                return;
            }

            node.getFileTransferService().cancelTransfer(transferId);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("transferId", transferId.toString());
            sendJsonResponse(exchange, 200, JsonUtils.toJson(res));
        }
    }
}
