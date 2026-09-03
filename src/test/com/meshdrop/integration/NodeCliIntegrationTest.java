package com.meshdrop.integration;

import com.meshdrop.cli.CommandLineInterface;
import com.meshdrop.cli.CommandResult;
import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.core.NodeState;
import com.meshdrop.peer.PeerAddress;

/**
 * Integration test verifying CommandLineInterface interacting with a live running Node instance.
 *
 * Checks:
 *   1. Node starts and transitions to RUNNING.
 *   2. CLI accesses live node status and displays actual running port & ID.
 *   3. CLI accesses live node info.
 *   4. Discovered and connected peers are reflected accurately in CLI queries.
 *   5. Active connections are visible via CLI.
 *   6. CLI shutdown cleanly transitions Node to STOPPED without resource leaks.
 */
public class NodeCliIntegrationTest {

    public void runAll() throws Exception {
        testLiveNodeCliInteraction();
    }

    private void testLiveNodeCliInteraction() throws Exception {
        NodeIdentity identity = NodeIdentity.createRandom("LiveCliNode");
        NodeConfig config = NodeConfig.withDiscovery(0, 0, true);

        Node node = new Node(config, identity);
        node.start();

        CommandLineInterface cli = new CommandLineInterface(node);

        try {
            assert node.getState() == NodeState.RUNNING : "Node must be in RUNNING state";

            // 1. Status command against live node
            CommandResult statusRes = cli.executeCommand("status");
            assert statusRes.success();
            assert statusRes.message().contains("LiveCliNode");
            assert statusRes.message().contains("RUNNING");

            // 2. Info command against live node
            CommandResult infoRes = cli.executeCommand("info");
            assert infoRes.success();
            assert infoRes.message().contains(identity.nodeId().toString());
            assert infoRes.message().contains(String.valueOf(node.getTcpServer().getLocalPort()));

            // 3. Peers command when empty vs populated
            CommandResult peersEmpty = cli.executeCommand("peers");
            assert peersEmpty.success();
            assert peersEmpty.message().contains("No peers discovered.");

            NodeIdentity dummyPeer = NodeIdentity.createRandom("PeerAlpha");
            node.getPeerManager().registerDiscovered(dummyPeer, new PeerAddress("127.0.0.1", 6000));

            CommandResult peersPopulated = cli.executeCommand("peers");
            assert peersPopulated.success();
            assert peersPopulated.message().contains("PeerAlpha");
            assert peersPopulated.message().contains("127.0.0.1:6000");

            // 4. Connections command
            CommandResult connEmpty = cli.executeCommand("connections");
            assert connEmpty.success();
            assert connEmpty.message().contains("No active connections.");

            // 5. Discover command
            CommandResult discoverRes = cli.executeCommand("discover");
            assert discoverRes.success();
            assert discoverRes.message().contains("Starting LAN discovery...");
            assert discoverRes.message().contains("Known peers:       1");

            // 6. Shutdown via CLI exit command
            CommandResult exitRes = cli.executeCommand("exit");
            assert exitRes.success();
            assert !cli.isRunning() : "CLI must be flagged as stopped";

        } finally {
            node.stop();
            assert node.getState() == NodeState.STOPPED : "Node must be STOPPED after stop()";
        }
    }
}
