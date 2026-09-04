package com.meshdrop.cli;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.discovery.DiscoveryService;
import com.meshdrop.message.Message;
import com.meshdrop.message.MessageListener;
import com.meshdrop.network.ConnectionState;
import com.meshdrop.network.TcpConnection;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerManager;
import com.meshdrop.peer.PeerState;
import com.meshdrop.protocol.ProtocolConstants;
import com.meshdrop.transfer.FileMetadata;
import com.meshdrop.transfer.Transfer;
import com.meshdrop.transfer.TransferDirection;
import com.meshdrop.transfer.TransferListener;
import com.meshdrop.transfer.TransferState;
import com.meshdrop.util.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Interactive command-line interface for controlling a MeshDrop node.
 *
 * The CLI is a pure presentation and operator control layer. It never directly
 * manipulates sockets or threads; it delegates all networking and transfers
 * to Node and its subsystem services.
 */
public class CommandLineInterface implements MessageListener {

    public static final String PROMPT = "meshdrop> ";

    private final Node node;
    private final BufferedReader reader;
    private final PrintStream output;
    private final CommandParser parser = new CommandParser();
    private final Map<String, CommandHandler> commands = new LinkedHashMap<>();
    private volatile boolean running = false;

    private record PendingApproval(FileMetadata metadata, Peer sender, CompletableFuture<Boolean> future) {}
    private final java.util.Queue<PendingApproval> pendingApprovals = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean autoAccept = true;

    @FunctionalInterface
    public interface CommandHandler {
        CommandResult execute(Command command);
    }

    public CommandLineInterface(Node node, BufferedReader reader, PrintStream output) {
        this.node = node;
        this.reader = reader != null ? reader : new BufferedReader(new InputStreamReader(System.in));
        this.output = output;
        registerCommands();

        // Register as a message listener to display incoming messages
        if (node.getMessageService() != null) {
            node.getMessageService().addListener(this);
        }

        // Register transfer approval handler and listener
        if (node.getFileTransferService() != null) {
            node.getFileTransferService().setApprovalHandler(this::handleTransferApproval);
            node.getFileTransferService().addListener(new TransferListener() {
                @Override
                public void onTransferCompleted(Transfer transfer) {
                    if (transfer.getDirection() == TransferDirection.DOWNLOAD) {
                        println("");
                        println("[FILE RECEIVED]");
                        println("  File:     " + (transfer.getFileMetadata() != null ? transfer.getFileMetadata().fileName() : "unknown"));
                        println("  Size:     " + formatFileSize(transfer.getTotalBytes()));
                        println("  Saved to: " + (transfer.getLocalPath() != null ? transfer.getLocalPath().toAbsolutePath().normalize() : "downloads"));
                        println("  Status:   COMPLETED (SHA-256 Verified)");
                        printPrompt();
                    }
                }
            });
        }
    }

    public CommandLineInterface(Node node) {
        this(node, null, null);
    }

    private void registerCommands() {
        commands.put("help", this::cmdHelp);
        commands.put("status", this::cmdStatus);
        commands.put("info", this::cmdInfo);
        commands.put("peers", this::cmdPeers);
        commands.put("connections", this::cmdConnections);
        commands.put("connect", this::cmdConnect);
        commands.put("discover", this::cmdDiscover);
        commands.put("send", this::cmdSend);
        commands.put("sendfile", this::cmdSendFile);
        commands.put("autoaccept", this::cmdAutoAccept);
        commands.put("downloads", this::cmdDownloads);
        commands.put("transfers", this::cmdTransfers);
        commands.put("resume", this::cmdResume);
        commands.put("cancel", this::cmdCancel);
        commands.put("ping", this::cmdPing);
        commands.put("trust", this::cmdTrust);
        commands.put("untrust", this::cmdUntrust);
        commands.put("block", this::cmdBlock);
        commands.put("clear", this::cmdClear);
        commands.put("exit", this::cmdExit);
        commands.put("quit", this::cmdExit);
    }

    /**
     * Runs the interactive CLI loop. Blocks until the user exits or input reaches EOF.
     */
    public void run() {
        running = true;

        while (running) {
            printPrompt();

            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                break;
            }

            if (line == null) {
                break;
            }

            CommandResult result = executeCommand(line);
            if (result != null && result.message() != null && !result.message().isEmpty()) {
                println(result.message());
            }
        }
    }

    public CommandResult executeCommand(String commandLine) {
        if (!pendingApprovals.isEmpty()) {
            String trimmed = commandLine != null ? commandLine.trim() : "";
            if (trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes")) {
                PendingApproval app = pendingApprovals.poll();
                if (app != null) {
                    app.future().complete(true);
                    return CommandResult.ok("File transfer accepted.");
                }
            } else if (trimmed.equalsIgnoreCase("n") || trimmed.equalsIgnoreCase("no")) {
                PendingApproval app = pendingApprovals.poll();
                if (app != null) {
                    app.future().complete(false);
                    return CommandResult.ok("File transfer declined.");
                }
            }
        }

        Command cmd = parser.parse(commandLine);

        if (cmd.isEmpty()) {
            return CommandResult.ok();
        }

        CommandHandler handler = commands.get(cmd.name());
        if (handler == null) {
            return CommandResult.error("Unknown command: " + cmd.name() + "\n\nType 'help' for available commands.");
        }

        return handler.execute(cmd);
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void onMessageReceived(Message message) {
        String senderName = node.getPeerManager().findPeer(message.senderId())
                .map(Peer::getDisplayName)
                .orElse("Peer-" + message.senderId().toString().substring(0, 8));

        String time = Instant.ofEpochMilli(message.timestamp())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));

        println("");
        println("[" + time + "] " + senderName + ":\n" + message.content());
        printPrompt();
    }

    // ========================================================================
    // Command Implementations
    // ========================================================================

    private CommandResult cmdHelp(Command cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nMeshDrop commands:\n\n");
        sb.append("------------------------------------------------\n");
        sb.append("MeshDrop Commands\n");
        sb.append("------------------------------------------------\n");
        sb.append(String.format("%-24s%s%n", "peers", "List discovered peers"));
        sb.append(String.format("%-24s%s%n", "connections", "Show TCP connections"));
        sb.append(String.format("%-24s%s%n", "connect <host> [port]", "Connect to a peer directly"));
        sb.append(String.format("%-24s%s%n", "status", "Show node status"));
        sb.append(String.format("%-24s%s%n", "info", "Show local identity"));
        sb.append(String.format("%-24s%s%n", "discover", "Run peer discovery"));
        sb.append(String.format("%-24s%s%n", "send <peer> <message>", "Send a message"));
        sb.append(String.format("%-24s%s%n", "sendfile <peer> <path>", "Send a file"));
        sb.append(String.format("%-24s%s%n", "autoaccept [on|off]", "Toggle auto-accept for incoming files"));
        sb.append(String.format("%-24s%s%n", "downloads [open]", "View downloads folder or open in Explorer"));
        sb.append(String.format("%-24s%s%n", "transfers", "Show transfers"));
        sb.append(String.format("%-24s%s%n", "resume <transferId>", "Resume transfer"));
        sb.append(String.format("%-24s%s%n", "cancel <transferId>", "Cancel transfer"));
        sb.append(String.format("%-24s%s%n", "ping <peer>", "Ping peer"));
        sb.append(String.format("%-24s%s%n", "trust <peer>", "Trust peer identity"));
        sb.append(String.format("%-24s%s%n", "untrust <peer>", "Untrust peer identity"));
        sb.append(String.format("%-24s%s%n", "block <peer>", "Block peer"));
        sb.append(String.format("%-24s%s%n", "clear", "Clear terminal"));
        sb.append(String.format("%-24s%s%n", "exit", "Shutdown MeshDrop"));
        sb.append("------------------------------------------------");
        return CommandResult.ok(sb.toString());
    }

    private CommandResult cmdStatus(Command cmd) {
        NodeIdentity id = node.getIdentity();
        PeerManager pm = node.getPeerManager();
        DiscoveryService ds = node.getDiscoveryService();

        int discovered = pm.getPeerCount();
        int connected = pm.getConnectedPeers().size();
        int ready = (int) node.getActiveConnections().stream()
                .filter(c -> c.getState() == ConnectionState.READY).count();

        int activeTransfers = 0;
        int resumableTransfers = 0;
        int completedTransfers = 0;
        int failedTransfers = 0;

        if (node.getFileTransferService() != null) {
            var tm = node.getFileTransferService().getTransferManager();
            activeTransfers = tm.getActiveTransfers().size();
            resumableTransfers = tm.getResumableTransfers().size();
            completedTransfers = tm.getCompletedTransfers().size();
            failedTransfers = (int) tm.getAllTransfers().stream()
                    .filter(t -> t.getState() == TransferState.FAILED).count();
        }

        String uptimeStr = formatDuration(Duration.ofMillis(node.getUptimeMillis()));
        int tcpPort = node.getTcpServer() != null ? node.getTcpServer().getLocalPort() : node.getConfig().tcpPort();
        int discPort = node.getConfig().udpDiscoveryPort();

        StringBuilder sb = new StringBuilder();
        sb.append("\nNode Status\n");
        sb.append("-----------\n");
        sb.append("Name:               ").append(id.displayName()).append("\n");
        sb.append("Identity:           ").append(id.nodeId()).append("\n");
        sb.append("State:              ").append(node.getState()).append("\n");
        sb.append("Peers:              ").append(discovered).append("\n");
        sb.append("Connections:        ").append(node.getConnectionCount()).append("\n");
        sb.append("Discovery:          ").append(node.getConfig().discoveryEnabled() ? "RUNNING" : "DISABLED").append("\n");
        sb.append("Listening TCP Port: ").append(tcpPort).append("\n");
        sb.append("Discovery Port:     ").append(node.getConfig().discoveryEnabled() ? discPort : "DISABLED").append("\n");
        sb.append("Uptime:             ").append(uptimeStr).append("\n");

        sb.append("\nNetwork:\n");
        sb.append("  Discovered Peers: ").append(discovered).append("\n");
        sb.append("  Connected Peers:  ").append(connected).append("\n");
        sb.append("  Ready Peers:      ").append(ready).append("\n");

        sb.append("\nTransfers:\n");
        sb.append("  Active:           ").append(activeTransfers).append("\n");
        sb.append("  Resumable:        ").append(resumableTransfers).append("\n");
        sb.append("  Completed:        ").append(completedTransfers).append("\n");
        sb.append("  Failed:           ").append(failedTransfers);

        return CommandResult.ok(sb.toString());
    }

    private CommandResult cmdInfo(Command cmd) {
        NodeIdentity id = node.getIdentity();
        NodeConfig config = node.getConfig();
        var storage = node.getStorageManager();

        StringBuilder sb = new StringBuilder();
        sb.append("\nLocal Node\n");
        sb.append("----------\n");
        sb.append("Name:                   ").append(id.displayName()).append("\n");
        sb.append("ID:                     ").append(id.nodeId()).append("\n");
        sb.append("TCP Port:               ").append(node.getTcpServer() != null ? node.getTcpServer().getLocalPort() : config.tcpPort()).append("\n");
        sb.append("UDP Discovery Port:     ").append(config.discoveryEnabled() ? config.udpDiscoveryPort() : "DISABLED").append("\n");
        sb.append("State:                  ").append(node.getState()).append("\n");
        sb.append("Public Key Fingerprint: ").append(id.fingerprint() != null ? id.fingerprint() : "N/A").append("\n");
        if (storage != null) {
            sb.append("Storage Directories:\n");
            sb.append("  Base:                 ").append(storage.getStorageDir().toAbsolutePath().normalize()).append("\n");
            sb.append("  Downloads:            ").append(storage.getDownloadsDir().toAbsolutePath().normalize()).append("\n");
            sb.append("  Transfers:            ").append(storage.getTempDir().toAbsolutePath().normalize()).append("\n");
            sb.append("  Identity:             ").append(storage.getIdentityDir().toAbsolutePath().normalize()).append("\n");
            sb.append("  Trust:                ").append(storage.getTrustDir().toAbsolutePath().normalize());
        }

        return CommandResult.ok(sb.toString());
    }

    private CommandResult cmdPeers(Command cmd) {
        PeerManager pm = node.getPeerManager();
        List<Peer> peers = pm.getPeers();

        if (peers.isEmpty()) {
            return CommandResult.ok("No peers discovered.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-36s | %-16s | %-20s | %-12s | %-10s%n",
                "ID", "NAME", "ADDRESS", "STATE", "TRUST"));
        sb.append("-".repeat(36)).append("-+-")
                .append("-".repeat(16)).append("-+-")
                .append("-".repeat(20)).append("-+-")
                .append("-".repeat(12)).append("-+-")
                .append("-".repeat(10)).append("\n");

        for (Peer peer : peers) {
            String addrStr = peer.getAddress() != null ? peer.getAddress().host() + ":" + peer.getAddress().tcpPort() : "N/A";
            String trustStr = peer.getTrustDecision() != null ? peer.getTrustDecision().name() : "UNTRUSTED";
            sb.append(String.format("%-36s | %-16s | %-20s | %-12s | %-10s%n",
                    peer.getNodeId(),
                    truncate(peer.getDisplayName(), 16),
                    truncate(addrStr, 20),
                    peer.getState(),
                    trustStr));
        }

        return CommandResult.ok(sb.toString().trim());
    }

    private CommandResult cmdConnections(Command cmd) {
        Collection<TcpConnection> connections = node.getActiveConnections();

        if (connections.isEmpty()) {
            return CommandResult.ok("No active connections.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-18s | %-20s | %-10s | %-14s | %-14s%n",
                "PEER", "ADDRESS", "STATE", "CONNECTED FOR", "LAST ACTIVITY"));
        sb.append("-".repeat(18)).append("-+-")
                .append("-".repeat(20)).append("-+-")
                .append("-".repeat(10)).append("-+-")
                .append("-".repeat(14)).append("-+-")
                .append("-".repeat(14)).append("\n");

        for (TcpConnection conn : connections) {
            String remoteName = conn.getRemoteIdentity() != null ? conn.getRemoteIdentity().displayName() : "Unknown";
            String remoteAddr = conn.getRemoteAddress() != null ? conn.getRemoteAddress().toString() : "N/A";
            if (remoteAddr.startsWith("/")) {
                remoteAddr = remoteAddr.substring(1);
            }
            String connectedFor = formatDuration(Duration.ofMillis(conn.getConnectedDurationMillis()));
            String lastActivity = formatDuration(Duration.ofMillis(conn.getIdleDurationMillis())) + " ago";

            sb.append(String.format("%-18s | %-20s | %-10s | %-14s | %-14s%n",
                    truncate(remoteName, 18),
                    truncate(remoteAddr, 20),
                    conn.getState(),
                    connectedFor,
                    lastActivity));
        }

        return CommandResult.ok(sb.toString().trim());
    }

    private CommandResult cmdDiscover(Command cmd) {
        DiscoveryService ds = node.getDiscoveryService();

        StringBuilder sb = new StringBuilder();
        sb.append("Starting LAN discovery...\n");

        if (ds != null && ds.isRunning()) {
            ds.sendBeacon();
            sb.append("Discovery service: RUNNING\n");
            sb.append("Multicast group:   ").append(ds.getMulticastGroup()).append(":").append(ds.getUdpDiscoveryPort()).append("\n");
            sb.append("Discovery beacon broadcasted.\n");
        } else {
            sb.append("Discovery service: NOT RUNNING\n");
        }

        sb.append("Known peers:       ").append(node.getPeerManager().getPeerCount());
        return CommandResult.ok(sb.toString());
    }

    private CommandResult cmdConnect(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: connect <host> [port]\nExample: connect 127.0.0.1 5002");
        }
        String host = cmd.arg(0);
        int port = 5000;
        if (cmd.argCount() >= 2) {
            try {
                port = Integer.parseInt(cmd.arg(1));
            } catch (NumberFormatException e) {
                return CommandResult.error("Invalid port: " + cmd.arg(1));
            }
        }
        try {
            node.connectTo(host, port);
            return CommandResult.ok("Connecting to " + host + ":" + port + "...");
        } catch (Exception e) {
            return CommandResult.error("Connection attempt failed: " + e.getMessage());
        }
    }

    private CommandResult cmdSend(Command cmd) {
        if (cmd.argCount() < 2) {
            return CommandResult.error("Usage: send <peer> <message>");
        }

        String peerIdentifier = cmd.arg(0);
        String message = cmd.arg(1);

        if (message == null || message.isBlank()) {
            return CommandResult.error("Usage: send <peer> <message>");
        }

        List<Peer> matches = node.getPeerManager().findPeersByIdentifier(peerIdentifier);

        if (matches.isEmpty()) {
            return CommandResult.error("Error: peer not found");
        }

        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: multiple peers match '").append(peerIdentifier).append("':");
            for (Peer p : matches) {
                sb.append("\n  ").append(p.getNodeId()).append("  ").append(p.getDisplayName());
            }
            sb.append("\nUse a longer ID to be more specific.");
            return CommandResult.error(sb.toString());
        }

        Peer peer = matches.get(0);

        if (!peer.isConnected()) {
            return CommandResult.error("Error: peer is not connected");
        }

        CompletableFuture<com.meshdrop.message.MessageDeliveryResult> future =
                node.sendMessage(peer.getNodeId(), message);
        try {
            com.meshdrop.message.MessageDeliveryResult result = future.get(5, TimeUnit.SECONDS);
            if (result.isSuccess()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Message sent successfully\nMessage ID: ").append(result.messageId());
                return CommandResult.ok(sb.toString());
            } else {
                return CommandResult.error("Delivery failed: " + result.description());
            }
        } catch (Exception e) {
            return CommandResult.error("Delivery failed: acknowledgement timed out.");
        }
    }

    private CompletableFuture<Boolean> handleTransferApproval(FileMetadata metadata, Peer sender) {
        String senderName = sender != null ? sender.getDisplayName() : "peer";
        if (autoAccept) {
            println("");
            println("[FILE] Auto-accepting incoming file '" + metadata.fileName() + "' (" +
                    formatFileSize(metadata.fileSize()) + ") from " + senderName);
            printPrompt();
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.add(new PendingApproval(metadata, sender, future));
        println("");
        println("[FILE] Incoming file offer:");
        println("  From: " + senderName);
        println("  File: " + metadata.fileName());
        println("  Size: " + formatFileSize(metadata.fileSize()));
        println("Accept? [y/N] (or type 'autoaccept on' to accept automatically)");
        printPrompt();
        return future;
    }

    private CommandResult cmdAutoAccept(Command cmd) {
        if (cmd.argCount() > 0) {
            String arg = cmd.arg(0).toLowerCase();
            if (arg.equals("on") || arg.equals("true") || arg.equals("enable") || arg.equals("yes")) {
                autoAccept = true;
                return CommandResult.ok("Auto-accept is now ENABLED. Incoming file offers will be accepted automatically.");
            } else if (arg.equals("off") || arg.equals("false") || arg.equals("disable") || arg.equals("no")) {
                autoAccept = false;
                return CommandResult.ok("Auto-accept is now DISABLED. You will be prompted before accepting files.");
            } else {
                return CommandResult.error("Usage: autoaccept [on|off]");
            }
        }
        return CommandResult.ok("Auto-accept is currently " + (autoAccept ? "ENABLED" : "DISABLED") + ".\nType 'autoaccept on' or 'autoaccept off' to change.");
    }

    private CommandResult cmdDownloads(Command cmd) {
        Path dlDir = node.getStorageManager() != null ?
                node.getStorageManager().getDownloadsDir() :
                node.getConfig().downloadsDir();

        if (dlDir == null) {
            return CommandResult.error("Downloads directory is not configured");
        }

        Path absoluteDl = dlDir.toAbsolutePath().normalize();

        if (cmd.argCount() >= 1 && "open".equalsIgnoreCase(cmd.arg(0))) {
            try {
                if (!Files.exists(absoluteDl)) {
                    Files.createDirectories(absoluteDl);
                }
                new ProcessBuilder("explorer.exe", absoluteDl.toString()).start();
                return CommandResult.ok("Opening downloads folder in File Explorer:\n  " + absoluteDl);
            } catch (Exception e) {
                return CommandResult.error("Failed to open File Explorer: " + e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nDownloads Directory:\n");
        sb.append("  ").append(absoluteDl).append("\n\n");

        try {
            if (!Files.exists(absoluteDl)) {
                Files.createDirectories(absoluteDl);
            }
            List<Path> files;
            try (var stream = Files.list(absoluteDl)) {
                files = stream.filter(Files::isRegularFile).toList();
            }

            if (files.isEmpty()) {
                sb.append("Downloaded files (0): No files in downloads folder yet.");
            } else {
                sb.append(String.format("Downloaded files (%d):%n", files.size()));
                for (Path f : files) {
                    long size = Files.size(f);
                    sb.append(String.format("  %-32s %10s%n", f.getFileName(), formatFileSize(size)));
                }
            }
            sb.append("\nTip: Type 'downloads open' to open this folder in Windows File Explorer.");
        } catch (IOException e) {
            sb.append("Error reading downloads folder: ").append(e.getMessage());
        }

        return CommandResult.ok(sb.toString().trim());
    }

    private CommandResult cmdSendFile(Command cmd) {
        if (cmd.argCount() < 2) {
            return CommandResult.error("Usage: sendfile <peer> <path>");
        }

        String peerIdentifier = cmd.arg(0);
        String filePathStr = cmd.arg(1);

        List<Peer> matches = node.getPeerManager().findPeersByIdentifier(peerIdentifier);
        if (matches.isEmpty()) {
            return CommandResult.error("Error: peer not found");
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: multiple peers match '").append(peerIdentifier).append("':");
            for (Peer p : matches) {
                sb.append("\n  ").append(p.getNodeId()).append("  ").append(p.getDisplayName());
            }
            sb.append("\nUse a longer ID to be more specific.");
            return CommandResult.error(sb.toString());
        }

        Peer peer = matches.get(0);
        if (!peer.isConnected()) {
            return CommandResult.error("Error: peer is not connected");
        }

        Path path = Path.of(filePathStr);
        if (!Files.isRegularFile(path)) {
            return CommandResult.error("Error: file does not exist or is not a regular file: " + filePathStr);
        }

        try {
            long size = Files.size(path);
            String sha256 = com.meshdrop.security.HashUtils.sha256(path.toFile());

            println("\nPreparing file...");
            println("Sending " + path.getFileName());
            println("Size: " + formatFileSize(size));
            println("SHA-256: " + sha256);
            println("\nWaiting for " + peer.getDisplayName() + " to accept...");

            CompletableFuture<Transfer> future = node.sendFile(peer.getNodeId(), path);

            // Phase 1: Wait for acceptance (up to offer timeout of 30 seconds)
            long offerStart = System.currentTimeMillis();
            Transfer transfer = null;
            while (!future.isDone() && transfer == null) {
                var active = node.getFileTransferService().getTransferManager().getActiveTransfers();
                for (Transfer t : active) {
                    if (t.getDirection() == TransferDirection.UPLOAD && path.equals(t.getLocalPath())) {
                        transfer = t;
                        break;
                    }
                }
                if (transfer != null && transfer.getState() != TransferState.WAITING_FOR_ACCEPT) {
                    break;
                }
                if (System.currentTimeMillis() - offerStart > ProtocolConstants.DEFAULT_FILE_OFFER_TIMEOUT_MS) {
                    if (transfer != null) {
                        node.getFileTransferService().cancelTransfer(transfer.getTransferId());
                    }
                    return CommandResult.error("Transfer offer timed out: " + peer.getDisplayName() +
                            " did not accept within 30 seconds.\n(Tip: On " + peer.getDisplayName() +
                            ", type 'y' to accept incoming files, or type 'autoaccept on' to accept automatically)");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CommandResult.error("Transfer interrupted");
                }
            }

            println("[TRANSFER] Accepted by " + peer.getDisplayName() + ". Streaming data...");

            // Phase 2: Stream data with live progress bar (no arbitrary 30s timeout on data transfer!)
            long lastBytes = 0;
            long lastActivityTime = System.currentTimeMillis();
            while (!future.isDone()) {
                if (transfer != null) {
                    long currentBytes = transfer.getBytesTransferred();
                    double pct = transfer.getProgressPercentage();
                    double speedMB = transfer.getTransferSpeedBps() / (1024.0 * 1024.0);
                    long elapsedSec = transfer.getElapsedDurationMs() / 1000;
                    long etaSec = transfer.getEstimatedRemainingSeconds();
                    String etaStr = etaSec >= 0 ? formatDurationSeconds(etaSec) : "--:--";

                    if (currentBytes > lastBytes) {
                        lastBytes = currentBytes;
                        lastActivityTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - lastActivityTime > ProtocolConstants.DEFAULT_FILE_TRANSFER_IDLE_TIMEOUT_MS) {
                        node.getFileTransferService().cancelTransfer(transfer.getTransferId());
                        return CommandResult.error("Transfer timed out: no network activity for " +
                                (ProtocolConstants.DEFAULT_FILE_TRANSFER_IDLE_TIMEOUT_MS / 1000) + " seconds.");
                    }

                    print("\r" + renderProgressBar(pct) + String.format(" %5.1f%% | %s / %s | %5.1f MB/s | ETA: %s | %s   ",
                            pct, formatFileSize(currentBytes), formatFileSize(size), speedMB, etaStr, transfer.getShortId()));
                }

                try {
                    transfer = future.get(250, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException ignored) {
                    // Continue progress loop
                }
            }

            if (transfer == null && future.isDone()) {
                transfer = future.get();
            }

            // Print completed 100% status
            long totalElapsedSec = transfer != null ? transfer.getElapsedDurationMs() / 1000 : 0;
            print("\r" + renderProgressBar(100.0) + String.format(" 100.0%% | %s / %s | Elapsed: %s | DONE          \n",
                    formatFileSize(size), formatFileSize(size), formatDurationSeconds(totalElapsedSec)));

            StringBuilder sb = new StringBuilder();
            sb.append("\n[TRANSFER COMPLETED]\n");
            sb.append("  ID:        ").append(transfer != null ? transfer.getShortId() + " (" + transfer.getTransferId() + ")" : "N/A").append("\n");
            sb.append("  File:      ").append(path.getFileName()).append("\n");
            sb.append("  Size:      ").append(formatFileSize(size)).append("\n");
            sb.append("  Peer:      ").append(peer.getDisplayName()).append("\n");
            sb.append("  Elapsed:   ").append(formatDurationSeconds(totalElapsedSec)).append("\n");
            sb.append("  SHA-256:   ").append(sha256).append(" (VERIFIED)\n");
            sb.append("  Status:    COMPLETED");
            return CommandResult.ok(sb.toString());

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = cause.getClass().getSimpleName();
            }
            return CommandResult.error("Transfer failed: " + msg);
        }
    }

    private CommandResult cmdTransfers(Command cmd) {
        if (node.getFileTransferService() == null) {
            return CommandResult.error("File transfer service is not active");
        }

        var tm = node.getFileTransferService().getTransferManager();
        var all = tm.getAllTransfers();

        if (all.isEmpty()) {
            return CommandResult.ok("No transfers recorded.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s | %-16s | %-12s | %-8s | %-10s | %-8s | %-12s | %-10s%n",
                "ID", "FILE", "PEER", "DIR", "SIZE", "PROGRESS", "STATE", "SPEED"));
        sb.append("-".repeat(10)).append("-+-")
                .append("-".repeat(16)).append("-+-")
                .append("-".repeat(12)).append("-+-")
                .append("-".repeat(8)).append("-+-")
                .append("-".repeat(10)).append("-+-")
                .append("-".repeat(8)).append("-+-")
                .append("-".repeat(12)).append("-+-")
                .append("-".repeat(10)).append("\n");

        for (var t : all) {
            String fileName = t.getFileMetadata() != null ? t.getFileMetadata().fileName() : "unknown";
            String peerName = "peer";
            if (t.getFileMetadata() != null) {
                var pid = t.getDirection() == TransferDirection.UPLOAD ? t.getFileMetadata().recipientId() : t.getFileMetadata().senderId();
                peerName = node.getPeerManager().findPeer(pid).map(Peer::getDisplayName).orElse("peer");
            }
            String speedStr = t.getState() == TransferState.TRANSFERRING ?
                    String.format("%.1f MB/s", t.getTransferSpeedBps() / (1024.0 * 1024.0)) : "-";

            sb.append(String.format("%-10s | %-16s | %-12s | %-8s | %-10s | %-7.1f%% | %-12s | %-10s%n",
                    t.getShortId(),
                    truncate(fileName, 16),
                    truncate(peerName, 12),
                    t.getDirection(),
                    formatFileSize(t.getTotalBytes()),
                    t.getProgressPercentage(),
                    t.getState(),
                    speedStr));
        }

        return CommandResult.ok(sb.toString().trim());
    }

    private CommandResult cmdResume(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: resume <transferId>");
        }
        if (node.getFileTransferService() == null) {
            return CommandResult.error("File transfer service is not active");
        }

        String query = cmd.arg(0);
        var transferOpt = node.getFileTransferService().getTransferManager().findTransfer(query);
        if (transferOpt.isEmpty()) {
            return CommandResult.error("Error: transfer not found matching '" + query + "'");
        }

        Transfer transfer = transferOpt.get();
        if (transfer.getState() == TransferState.COMPLETED) {
            return CommandResult.ok("Transfer " + transfer.getShortId() + " (" + transfer.getTransferId() + ") is already completed.");
        }
        if (transfer.getState() == TransferState.TRANSFERRING) {
            return CommandResult.ok("Transfer " + transfer.getShortId() + " (" + transfer.getTransferId() + ") is already actively transferring.");
        }

        println("[TRANSFER] Resuming transfer " + transfer.getShortId() + " (" +
                (transfer.getFileMetadata() != null ? transfer.getFileMetadata().fileName() : "file") + ")...");
        try {
            CompletableFuture<Transfer> future = node.resumeTransfer(transfer.getTransferId());
            Transfer result = future.get(ProtocolConstants.DEFAULT_FILE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return CommandResult.ok("[TRANSFER] Transfer " + result.getShortId() + " (" + result.getTransferId() + ") resumed successfully and completed.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() != null && !cause.getMessage().isBlank() ? cause.getMessage() : cause.getClass().getSimpleName();
            return CommandResult.error("Resume failed: " + msg);
        }
    }

    private CommandResult cmdCancel(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: cancel <transferId>");
        }
        if (node.getFileTransferService() == null) {
            return CommandResult.error("File transfer service is not active");
        }

        String query = cmd.arg(0);
        var transferOpt = node.getFileTransferService().getTransferManager().findTransfer(query);
        if (transferOpt.isEmpty()) {
            return CommandResult.error("Error: transfer not found matching '" + query + "'");
        }

        Transfer transfer = transferOpt.get();
        node.cancelTransfer(transfer.getTransferId());
        return CommandResult.ok("Transfer " + transfer.getShortId() + " (" + transfer.getTransferId() + ") cancelled.");
    }

    private CommandResult cmdTrust(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: trust <peer> [alias]");
        }
        String peerIdStr = cmd.arg(0);
        String alias = cmd.argCount() >= 2 ? cmd.arg(1) : null;

        List<Peer> matches = node.getPeerManager().findPeersByIdentifier(peerIdStr);
        if (matches.isEmpty()) {
            return CommandResult.error("Peer not found: " + peerIdStr);
        }
        Peer peer = matches.get(0);
        node.trustPeer(peer.getNodeId(), alias);
        return CommandResult.ok("Peer " + peer.getDisplayName() + " (" + peer.getNodeId() + ") is now TRUSTED");
    }

    private CommandResult cmdUntrust(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: untrust <peer>");
        }
        String peerIdStr = cmd.arg(0);
        List<Peer> matches = node.getPeerManager().findPeersByIdentifier(peerIdStr);
        if (matches.isEmpty()) {
            return CommandResult.error("Peer not found: " + peerIdStr);
        }
        Peer peer = matches.get(0);
        node.untrustPeer(peer.getNodeId());
        return CommandResult.ok("Peer " + peer.getDisplayName() + " (" + peer.getNodeId() + ") is now UNTRUSTED");
    }

    private CommandResult cmdBlock(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: block <peer> [reason]");
        }
        String peerIdStr = cmd.arg(0);
        String reason = cmd.argCount() >= 2 ? cmd.arg(1) : "manual block";
        List<Peer> matches = node.getPeerManager().findPeersByIdentifier(peerIdStr);
        if (matches.isEmpty()) {
            return CommandResult.error("Peer not found: " + peerIdStr);
        }
        Peer peer = matches.get(0);
        node.blockPeer(peer.getNodeId(), reason);
        return CommandResult.ok("Peer " + peer.getDisplayName() + " (" + peer.getNodeId() + ") is now BLOCKED");
    }

    private CommandResult cmdPing(Command cmd) {
        if (cmd.argCount() < 1) {
            return CommandResult.error("Usage: ping <peer>");
        }

        String peerIdentifier = cmd.arg(0);
        List<Peer> matches = node.getPeerManager().findPeersByIdentifier(peerIdentifier);

        if (matches.isEmpty()) {
            return CommandResult.error("Error: peer not found");
        }

        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: multiple peers match '").append(peerIdentifier).append("':");
            for (Peer p : matches) {
                sb.append("\n  ").append(p.getNodeId()).append("  ").append(p.getDisplayName());
            }
            sb.append("\nUse a longer ID to be more specific.");
            return CommandResult.error(sb.toString());
        }

        Peer peer = matches.get(0);
        if (!peer.isConnected()) {
            return CommandResult.error("Error: peer is not connected");
        }

        println("Pinging " + peer.getDisplayName() + "...");

        try {
            CompletableFuture<Long> future = node.pingPeer(peer.getNodeId());
            Long latency = future.get(5, TimeUnit.SECONDS);

            StringBuilder sb = new StringBuilder();
            sb.append("\nResponse received.\n\nLatency: ").append(latency).append(" ms");
            return CommandResult.ok(sb.toString());

        } catch (Exception e) {
            return CommandResult.error("Error: request timed out");
        }
    }

    private CommandResult cmdClear(Command cmd) {
        boolean cleared = false;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
                cleared = true;
            } catch (Exception ignored) {}
        }
        if (!cleared) {
            try {
                if (output != null) {
                    output.print("\033[H\033[2J");
                    output.flush();
                } else {
                    System.out.print("\033[H\033[2J");
                    System.out.flush();
                }
                cleared = true;
            } catch (Exception ignored) {}
        }
        if (!cleared) {
            println("\n".repeat(40));
        }
        return CommandResult.ok();
    }

    private CommandResult cmdExit(Command cmd) {
        println("Shutting down MeshDrop...");
        running = false;
        return CommandResult.ok();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void printPrompt() {
        if (output != null) {
            output.print(PROMPT);
            output.flush();
        } else {
            Logger.consolePrint(PROMPT);
        }
    }

    private void print(String message) {
        if (output != null) {
            output.print(message);
            output.flush();
        } else {
            Logger.consolePrint(message);
        }
    }

    private void println(String message) {
        if (output != null) {
            output.println(message);
        } else {
            Logger.console(message);
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatDuration(Duration d) {
        long seconds = d.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes < 60) {
            return minutes + "m " + remainingSeconds + "s";
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        return hours + "h " + remainingMinutes + "m";
    }

    public static String formatDurationSeconds(long seconds) {
        if (seconds < 0) return "--:--";
        if (seconds >= 3600) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 1) + "…";
    }

    private static String renderProgressBar(double percent) {
        int totalBars = 20;
        int filled = (int) Math.round((percent / 100.0) * totalBars);
        filled = Math.max(0, Math.min(totalBars, filled));
        return "[" + "=".repeat(filled) + "-".repeat(totalBars - filled) + "]";
    }
}
