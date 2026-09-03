package com.meshdrop.cli;

import com.meshdrop.core.Node;
import com.meshdrop.core.NodeConfig;
import com.meshdrop.core.NodeIdentity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concurrency test verifying CLI responsiveness while networking and discovery run concurrently.
 *
 * Verifies:
 *   - The CLI command execution loop does not deadlock during continuous discovery beacons or connection activity.
 *   - Concurrent command executions from multiple threads or during high network activity remain responsive.
 */
public class CliConcurrencyTest {

    public void runAll() throws Exception {
        testCliRemainsResponsiveUnderNetworkActivity();
    }

    private void testCliRemainsResponsiveUnderNetworkActivity() throws Exception {
        NodeIdentity id = NodeIdentity.createRandom("ConcurrentCliNode");
        NodeConfig config = NodeConfig.withDiscovery(0, 0, true);

        Node node = new Node(config, id);
        node.start();

        CommandLineInterface cli = new CommandLineInterface(node);

        try {
            int commandCount = 50;
            CountDownLatch latch = new CountDownLatch(commandCount);
            AtomicBoolean failed = new AtomicBoolean(false);

            // Execute rapid concurrent CLI commands while UDP discovery runs in background
            for (int i = 0; i < commandCount; i++) {
                final int idx = i;
                Thread.ofVirtual().name("cli-worker-" + idx).start(() -> {
                    try {
                        String cmd = switch (idx % 5) {
                            case 0 -> "status";
                            case 1 -> "info";
                            case 2 -> "peers";
                            case 3 -> "connections";
                            default -> "discover";
                        };
                        CommandResult result = cli.executeCommand(cmd);
                        if (!result.success()) {
                            failed.set(true);
                        }
                    } catch (Exception e) {
                        failed.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assert completed : "All concurrent CLI commands must complete within 5 seconds without deadlocking";
            assert !failed.get() : "No concurrent CLI command should fail or throw an unhandled exception";

        } finally {
            node.stop();
        }
    }
}
