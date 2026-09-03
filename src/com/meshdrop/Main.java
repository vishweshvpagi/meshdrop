package com.meshdrop;

import com.meshdrop.cli.CommandLineInterface;
import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.security.IdentityStorage;
import com.meshdrop.util.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the MeshDrop node application.
 *
 * Launches a MeshDrop node with cryptographic identity, local storage layout,
 * networking services (TCP server, UDP multicast discovery), and interactive CLI.
 *
 * Command line options:
 *   --name <name>         Display name for this node (default: auto-generated or persisted)
 *   --tcp-port <port>     TCP port to listen on (default: 5000)
 *   --udp-port <port>     UDP multicast discovery port (default: 5001)
 *   --data-dir <path>     Application data root directory (default: data)
 *   --no-discovery        Disable UDP multicast peer discovery
 *   --no-cli              Run in non-interactive daemon mode
 *   connect <host> <port> Connect to a remote peer immediately upon startup
 */
public class Main {
    public static void main(String[] args) {
        String name = null;
        int tcpPort = NodeConfig.DEFAULT_TCP_PORT;
        int udpPort = NodeConfig.DEFAULT_UDP_PORT;
        String dataDirStr = null;
        boolean discoveryEnabled = true;
        boolean enableCli = true;
        String connectHost = null;
        int connectPort = 0;

        for (int i = 0; i < args.length; i++) {
            if ("--name".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                name = args[++i];
            } else if ("--tcp-port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                tcpPort = Integer.parseInt(args[++i]);
            } else if ("--udp-port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                udpPort = Integer.parseInt(args[++i]);
            } else if ("--data-dir".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                dataDirStr = args[++i];
            } else if ("--no-discovery".equalsIgnoreCase(args[i])) {
                discoveryEnabled = false;
            } else if ("--no-cli".equalsIgnoreCase(args[i])) {
                enableCli = false;
            } else if ("connect".equalsIgnoreCase(args[i]) && i + 2 < args.length) {
                connectHost = args[++i];
                connectPort = Integer.parseInt(args[++i]);
            }
        }

        // Determine base data directory
        Path dataPath = dataDirStr != null ? Path.of(dataDirStr) : Path.of("data");
        if (dataDirStr == null && name != null) {
            dataPath = Path.of("data", name);
        }

        NodeConfig config = NodeConfig.withDataDir(dataPath, tcpPort, udpPort, discoveryEnabled);

        // Load or create persistent cryptographic identity
        IdentityStorage idStorage = new IdentityStorage(config.identityDir());
        NodeIdentity identity;
        try {
            var loaded = idStorage.loadOrCreate(name);
            identity = loaded.identity();
        } catch (IOException e) {
            Logger.warn("Failed to initialize persistent identity, using memory identity: " + e.getMessage());
            identity = name != null ? NodeIdentity.createRandom(name) : NodeIdentity.createRandom();
        }

        Node node = new Node(config, identity);

        // Register JVM shutdown hook for Ctrl+C / SIGINT handling
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            node.stop();
            shutdownLatch.countDown();
        }, "meshdrop-shutdown-hook"));

        try {
            node.start();

            // Connect immediately if requested
            if (connectHost != null) {
                node.connectTo(connectHost, connectPort);
            }

            if (enableCli) {
                // Interactive CLI mode
                CommandLineInterface cli = new CommandLineInterface(node);
                cli.run();
                node.stop();
            } else {
                // Daemon mode: wait until interrupted
                try {
                    shutdownLatch.await();
                } catch (InterruptedException ignored) {}
            }
        } catch (IOException e) {
            Logger.severe("Failed to start MeshDrop node", e);
            System.exit(1);
        }
    }
}
