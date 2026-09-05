package com.meshdrop.api;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import com.meshdrop.transfer.FileMetadata;
import com.meshdrop.transfer.Transfer;
import com.meshdrop.transfer.TransferDirection;
import com.meshdrop.transfer.TransferState;

public class HttpControlServerTest {

    public void runAll() throws Exception {
        testStatusEndpoint();
        testPeersEndpoint();
        testConnectionsEndpoint();
        testTransfersEndpoint();
        testStartTransferValidation();
        testCancelTransferEndpoint();
        testTransferDetailAndCapabilities();
        testPeerConnectDisconnectEndpoints();
        testCorsHeaders();
    }

    private Node createTestNode(Path tempDir) {
        NodeConfig config = new NodeConfig(
                0,
                0,
                false,
                "239.255.77.88",
                5000,
                1024,
                1024 * 1024,
                2000,
                2000,
                tempDir,
                tempDir.resolve("dl"),
                tempDir.resolve("tmp")
        );
        NodeIdentity identity = NodeIdentity.createRandom("ApiTestNode");
        return new Node(config, identity);
    }

    public void testStatusEndpoint() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/status");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            assert responseCode == 200 : "Expected 200, got " + responseCode;

            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            assert body.contains("\"nodeId\"") : "Response missing nodeId: " + body;
            assert body.contains("\"displayName\":\"ApiTestNode\"") : "Response missing displayName: " + body;
            assert body.contains("\"running\":true") : "Response should report running true: " + body;
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testPeersEndpoint() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-peers-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/peers");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            assert conn.getResponseCode() == 200 : "Expected 200 from /api/peers";

            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assert body.startsWith("[") && body.endsWith("]") : "Expected JSON array: " + body;
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testConnectionsEndpoint() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-conns-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/connections");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            assert conn.getResponseCode() == 200 : "Expected 200 from /api/connections";

            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assert body.startsWith("[") && body.endsWith("]") : "Expected JSON array: " + body;
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testCorsHeaders() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-cors-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/status");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("OPTIONS");
            conn.setRequestProperty("Origin", "http://localhost:3000");
            int code = conn.getResponseCode();
            assert code == 204 : "Expected 204 for OPTIONS, got " + code;
            String allowOrigin = conn.getHeaderField("Access-Control-Allow-Origin");
            assert "http://localhost:3000".equals(allowOrigin) : "Expected allowed origin, got " + allowOrigin;
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testTransfersEndpoint() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-transfers-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/transfers");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            assert conn.getResponseCode() == 200 : "Expected 200 from /api/transfers";

            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assert body.startsWith("[") && body.endsWith("]") : "Expected JSON array: " + body;
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testStartTransferValidation() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-transfer-start-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            // Test missing fields
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/transfers");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            assert conn.getResponseCode() == 400 : "Expected 400 for empty body";

            // Test non-existent file
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            String payload = "{\"peerId\":\"00000000-0000-0000-0000-000000000001\",\"filePath\":\"non_existent.iso\"}";
            conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            assert conn.getResponseCode() == 400 : "Expected 400 for non-existent file";
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testCancelTransferEndpoint() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-transfer-cancel-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            // Test missing transferId
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/transfers/cancel");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            assert conn.getResponseCode() == 400 : "Expected 400 for missing transferId";

            // Test unknown transferId
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            String payload = "{\"transferId\":\"00000000-0000-0000-0000-000000000001\"}";
            conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            assert conn.getResponseCode() == 404 : "Expected 404 for unknown transferId";
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testTransferDetailAndCapabilities() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-transfer-detail-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            UUID tid = UUID.randomUUID();
            UUID sid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();
            FileMetadata meta = new FileMetadata(tid, sid, rid, "sample.iso", 1000L, System.currentTimeMillis(), "0".repeat(64));
            Transfer transfer = new Transfer(meta, TransferDirection.DOWNLOAD, null, TransferState.TRANSFERRING);
            transfer.setBytesTransferred(400L);
            node.getFileTransferService().getTransferManager().registerTransfer(transfer);

            // 1. GET /api/transfers/{id}
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/transfers/" + tid);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            assert conn.getResponseCode() == 200 : "Expected 200 from GET /api/transfers/{id}";

            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assert body.contains("\"transferId\":\"" + tid + "\"") : "Body must contain transferId";
            assert body.contains("\"remainingBytes\":600") : "Body must contain remainingBytes: 600";
            assert body.contains("\"canCancel\":true") : "Active transfer must be cancellable";

            // 2. POST /api/transfers/{id}/cancel
            URI cancelUri = URI.create("http://127.0.0.1:" + port + "/api/transfers/" + tid + "/cancel");
            conn = (HttpURLConnection) cancelUri.toURL().openConnection();
            conn.setRequestMethod("POST");
            assert conn.getResponseCode() == 200 : "Expected 200 from POST /api/transfers/{id}/cancel";
            assert transfer.getState() == TransferState.CANCELLED : "Transfer state must be CANCELLED";

            // 3. DELETE /api/transfers/{id}
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("DELETE");
            assert conn.getResponseCode() == 200 : "Expected 200 from DELETE /api/transfers/{id}";

            // 4. Verify removed: GET /api/transfers/{id} should return 404
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            assert conn.getResponseCode() == 404 : "Expected 404 after deletion";
        } finally {
            server.stop();
            node.stop();
        }
    }

    public void testPeerConnectDisconnectEndpoints() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("meshdrop-api-test-peer-ctrl-");
        Node node = createTestNode(tempDir);
        node.start();

        HttpControlServer server = new HttpControlServer(node, 0);
        server.start();
        int port = server.getPort();

        try {
            NodeIdentity peerIdentity = NodeIdentity.createRandom("RemotePeerTest");
            com.meshdrop.peer.PeerAddress peerAddress = new com.meshdrop.peer.PeerAddress("127.0.0.1", 59999);
            node.getPeerManager().registerDiscovered(peerIdentity, peerAddress);
            UUID peerId = peerIdentity.nodeId();

            // 1. GET /api/peers/{id}
            URI getUri = URI.create("http://127.0.0.1:" + port + "/api/peers/" + peerId);
            HttpURLConnection conn = (HttpURLConnection) getUri.toURL().openConnection();
            conn.setRequestMethod("GET");
            assert conn.getResponseCode() == 200 : "Expected 200 from GET /api/peers/{id}";

            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assert body.contains("\"displayName\":\"RemotePeerTest\"") : "Body must contain peer name: " + body;
            assert body.contains("\"connected\":false") : "Peer must not be connected: " + body;

            // 2. POST /api/peers/{id}/disconnect (no connection active)
            URI disconnectUri = URI.create("http://127.0.0.1:" + port + "/api/peers/" + peerId + "/disconnect");
            conn = (HttpURLConnection) disconnectUri.toURL().openConnection();
            conn.setRequestMethod("POST");
            assert conn.getResponseCode() == 200 : "Expected 200 from disconnect";

            // 3. POST /api/peers/{id}/connect (unreachable port -> 400 expected)
            URI connectUri = URI.create("http://127.0.0.1:" + port + "/api/peers/" + peerId + "/connect");
            conn = (HttpURLConnection) connectUri.toURL().openConnection();
            conn.setRequestMethod("POST");
            int connectStatus = conn.getResponseCode();
            assert connectStatus == 400 : "Expected 400 when connecting to unreachable peer, got " + connectStatus;
        } finally {
            server.stop();
            node.stop();
        }
    }
}
