package com.meshdrop.cli;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;

/**
 * Unit tests for CommandLineInterface command execution without requiring an interactive console.
 */
public class CommandLineInterfaceTest {

    public void runAll() throws Exception {
        testHelpCommand();
        testStatusCommand();
        testInfoCommand();
        testPeersCommandEmpty();
        testPeersCommandWithPeers();
        testConnectionsCommandEmpty();
        testDiscoverCommand();
        testClearCommand();
        testUnknownCommandDoesNotCrash();
        testMissingArgumentsOnSend();
        testMissingArgumentsOnPing();
        testPeerNotFoundOnSend();
        testPeerNotFoundOnPing();
        testExitRequestsShutdown();
        testRunLoopWithPipedInput();
    }

    private void testHelpCommand() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("TestNode"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("help");
        assert result.success() : "help command should succeed";
        assert result.message().contains("MeshDrop commands:") : "help should list commands";
        assert result.message().contains("send <peer> <message>") : "help should mention send";
        assert result.message().contains("ping <peer>") : "help should mention ping";
    }

    private void testStatusCommand() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("StatusNode"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("status");
        assert result.success() : "status command should succeed";
        assert result.message().contains("Node Status") : "status should show header";
        assert result.message().contains("StatusNode") : "status should show node display name";
        assert result.message().contains("Peers:") : "status should show peers section";
        assert result.message().contains("Connections:") : "status should show connections section";
    }

    private void testInfoCommand() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("InfoNode"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("info");
        assert result.success() : "info command should succeed";
        assert result.message().contains("Local Node") : "info should show Local Node header";
        assert result.message().contains("InfoNode") : "info should show display name";
        assert result.message().contains("TCP Port:") : "info should show TCP port";
    }

    private void testPeersCommandEmpty() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("EmptyPeers"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("peers");
        assert result.success() : "peers command should succeed";
        assert result.message().contains("No peers discovered.") : "empty peers should display friendly message";
    }

    private void testPeersCommandWithPeers() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("PeersNode"));
        CommandLineInterface cli = new CommandLineInterface(node);

        NodeIdentity remote = NodeIdentity.createRandom("RemotePeer");
        node.getPeerManager().registerDiscovered(remote, new PeerAddress("192.168.1.50", 5000));

        CommandResult result = cli.executeCommand("peers");
        assert result.success() : "peers command should succeed";
        assert result.message().contains("RemotePeer") : "peers list should include RemotePeer";
        assert result.message().contains("192.168.1.50:5000") : "peers list should include address";
        assert result.message().contains("DISCOVERED") : "peers list should show state";
    }

    private void testConnectionsCommandEmpty() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("ConnNode"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("connections");
        assert result.success() : "connections command should succeed";
        assert result.message().contains("No active connections.") : "empty connections should display message";
    }

    private void testDiscoverCommand() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("DiscNode"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("discover");
        assert result.success() : "discover command should succeed";
        assert result.message().contains("Starting LAN discovery...") : "discover should announce starting discovery";
        assert result.message().contains("Known peers:") : "discover should show known peers count";
    }

    private void testClearCommand() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("ClearNode"));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);
        CommandLineInterface cli = new CommandLineInterface(node, null, out);

        CommandResult result = cli.executeCommand("clear");
        assert result.success() : "clear command should succeed without crashing";
    }

    private void testUnknownCommandDoesNotCrash() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("CrashTest"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult result = cli.executeCommand("banana");
        assert !result.success() : "unknown command should return failure result";
        assert result.message().contains("Unknown command: banana") : "should identify unknown command";
        assert result.message().contains("help") : "should suggest help";
    }

    private void testMissingArgumentsOnSend() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("SendTest"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult r1 = cli.executeCommand("send");
        assert !r1.success() : "send without args should fail";
        assert r1.message().contains("Usage: send <peer> <message>");

        CommandResult r2 = cli.executeCommand("send peer1");
        assert !r2.success() : "send with single arg should fail";
        assert r2.message().contains("Usage: send <peer> <message>");
    }

    private void testMissingArgumentsOnPing() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("PingTest"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult r1 = cli.executeCommand("ping");
        assert !r1.success() : "ping without args should fail";
        assert r1.message().contains("Usage: ping <peer>");
    }

    private void testPeerNotFoundOnSend() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("SendNotFound"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult r = cli.executeCommand("send NonExistentPeer 'hello'");
        assert !r.success();
        assert r.message().contains("Error: peer not found");
    }

    private void testPeerNotFoundOnPing() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("PingNotFound"));
        CommandLineInterface cli = new CommandLineInterface(node);

        CommandResult r = cli.executeCommand("ping NonExistentPeer");
        assert !r.success();
        assert r.message().contains("Error: peer not found");
    }

    private void testExitRequestsShutdown() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("ExitTest"));
        CommandLineInterface cli = new CommandLineInterface(node);

        // Simulate cli running
        CommandResult r1 = cli.executeCommand("exit");
        assert r1.success();
        assert !cli.isRunning() : "exit command must clear running flag";

        CommandResult r2 = cli.executeCommand("quit");
        assert r2.success();
    }

    private void testRunLoopWithPipedInput() {
        Node node = new Node(NodeConfig.withPortAndTimeout(0, 5000), NodeIdentity.createRandom("LoopTest"));
        String simulatedInput = "status\nhelp\nexit\n";
        BufferedReader reader = new BufferedReader(new StringReader(simulatedInput));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(baos);

        CommandLineInterface cli = new CommandLineInterface(node, reader, output);
        cli.run();

        String consoleOutput = baos.toString();
        assert consoleOutput.contains("LoopTest") : "Output must contain node name from status command";
        assert consoleOutput.contains("MeshDrop commands:") : "Output must contain help command result";
        assert consoleOutput.contains("Shutting down MeshDrop...") : "Output must contain exit command result";
        assert !cli.isRunning() : "CLI must be stopped after exit";
    }
}
