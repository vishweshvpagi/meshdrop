package com.meshdrop;

import com.meshdrop.cli.CliConcurrencyTest;
import com.meshdrop.cli.CommandLineInterfaceTest;
import com.meshdrop.cli.CommandParserTest;
import com.meshdrop.connection.ConnectionManagerTest;
import com.meshdrop.discovery.DiscoveryMessageTest;
import com.meshdrop.discovery.DiscoveryServiceTest;
import com.meshdrop.integration.NodeCliIntegrationTest;
import com.meshdrop.integration.TwoNodeIntegrationTest;
import com.meshdrop.integration.TwoNodeMessagingTest;
import com.meshdrop.message.ConcurrentMessagingTest;
import com.meshdrop.message.MessageAckTest;
import com.meshdrop.message.MessageAckTimeoutTest;
import com.meshdrop.message.MessageCodecTest;
import com.meshdrop.message.MessageDeduplicationTest;
import com.meshdrop.message.MessageListenerTest;
import com.meshdrop.message.MessageServiceTest;
import com.meshdrop.message.MessageTest;
import com.meshdrop.message.RecipientValidationTest;
import com.meshdrop.message.SenderIdentityValidationTest;
import com.meshdrop.message.ShutdownMessagingTest;
import com.meshdrop.message.UnicodeMessagingTest;
import com.meshdrop.network.TcpConnectionTest;
import com.meshdrop.network.TcpServerTest;
import com.meshdrop.peer.PeerManagerTest;
import com.meshdrop.peer.PeerTest;
import com.meshdrop.protocol.HandshakeTest;
import com.meshdrop.protocol.PacketDecoderTest;
import com.meshdrop.protocol.PacketEncoderTest;
import com.meshdrop.transfer.ChunkManagerTest;
import com.meshdrop.transfer.HashUtilsTest;
import com.meshdrop.transfer.TransferTest;
import com.meshdrop.integration.TwoNodeFileTransferTest;
import com.meshdrop.transfer.FileMetadataTest;
import com.meshdrop.transfer.FileChunkTest;
import com.meshdrop.transfer.FileMetadataCodecTest;
import com.meshdrop.transfer.FileChunkCodecTest;
import com.meshdrop.transfer.FileHashTest;
import com.meshdrop.transfer.FileSenderTest;
import com.meshdrop.transfer.FileReceiverTest;
import com.meshdrop.transfer.TransferStateTest;
import com.meshdrop.transfer.TransferManagerTest;
import com.meshdrop.transfer.FileTransferServiceTest;
import com.meshdrop.transfer.BinaryFileTransferTest;
import com.meshdrop.transfer.LargeFileTransferTest;
import com.meshdrop.transfer.UnicodeFilenameTest;
import com.meshdrop.transfer.CollisionTest;
import com.meshdrop.transfer.PathTraversalTest;
import com.meshdrop.transfer.HashMismatchTest;
import com.meshdrop.transfer.DisconnectDuringTransferTest;
import com.meshdrop.transfer.ConcurrentTransfersTest;
import com.meshdrop.transfer.ShutdownDuringTransferTest;
import com.meshdrop.transfer.TransferCheckpointTest;
import com.meshdrop.transfer.CheckpointAtomicWriteTest;
import com.meshdrop.transfer.ResumeRequestCodecTest;
import com.meshdrop.transfer.ResumeResponseCodecTest;
import com.meshdrop.transfer.InterruptedTransferTest;
import com.meshdrop.transfer.ResumeTransferTest;
import com.meshdrop.transfer.NoDuplicateDataTest;
import com.meshdrop.transfer.WrongOffsetTest;
import com.meshdrop.transfer.CheckpointMismatchTest;
import com.meshdrop.transfer.MetadataMismatchResumeTest;
import com.meshdrop.transfer.CompletedTransferResumeTest;
import com.meshdrop.transfer.RestartRecoveryTest;
import com.meshdrop.transfer.TwoNodeResumeTest;
import com.meshdrop.transfer.ResumeAfterRestartTest;
import com.meshdrop.transfer.CancelTransferTest;
import com.meshdrop.transfer.ConcurrentResumeTest;
import com.meshdrop.transfer.NetworkFailureTest;
import com.meshdrop.transfer.LargeFileResumeTest;
import com.meshdrop.transfer.CorruptedPartialFileTest;
import com.meshdrop.transfer.PathTraversalRecoveryTest;
import com.meshdrop.security.IdentityFingerprintTest;
import com.meshdrop.security.CryptoUtilsTest;
import com.meshdrop.security.TrustStoreTest;
import com.meshdrop.storage.StorageManagerTest;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone, zero-dependency test runner executing assertion-based test suites.
 */
public class TestRunner {

    public interface TestCase {
        String name();
        void run() throws Exception;
    }

    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println(" MeshDrop Test Suite Execution");
        System.out.println("=========================================");

        List<TestCase> allTests = new ArrayList<>();
        allTests.add(new TestCase() {
            public String name() { return "PacketEncoderTest"; }
            public void run() throws Exception { new PacketEncoderTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "PacketDecoderTest"; }
            public void run() throws Exception { new PacketDecoderTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "HandshakeTest"; }
            public void run() throws Exception { new HandshakeTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "DiscoveryMessageTest"; }
            public void run() throws Exception { new DiscoveryMessageTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "DiscoveryServiceTest"; }
            public void run() throws Exception { new DiscoveryServiceTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ConnectionManagerTest"; }
            public void run() throws Exception { new ConnectionManagerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TcpServerTest"; }
            public void run() throws Exception { new TcpServerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TcpConnectionTest"; }
            public void run() throws Exception { new TcpConnectionTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "PeerTest"; }
            public void run() throws Exception { new PeerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "PeerManagerTest"; }
            public void run() throws Exception { new PeerManagerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TwoNodeIntegrationTest"; }
            public void run() throws Exception { new TwoNodeIntegrationTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CommandParserTest"; }
            public void run() throws Exception { new CommandParserTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CommandLineInterfaceTest"; }
            public void run() throws Exception { new CommandLineInterfaceTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "NodeCliIntegrationTest"; }
            public void run() throws Exception { new NodeCliIntegrationTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CliConcurrencyTest"; }
            public void run() throws Exception { new CliConcurrencyTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageTest"; }
            public void run() throws Exception { new MessageTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageCodecTest"; }
            public void run() throws Exception { new MessageCodecTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageServiceTest"; }
            public void run() throws Exception { new MessageServiceTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageListenerTest"; }
            public void run() throws Exception { new MessageListenerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageDeduplicationTest"; }
            public void run() throws Exception { new MessageDeduplicationTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "SenderIdentityValidationTest"; }
            public void run() throws Exception { new SenderIdentityValidationTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "RecipientValidationTest"; }
            public void run() throws Exception { new RecipientValidationTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageAckTest"; }
            public void run() throws Exception { new MessageAckTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MessageAckTimeoutTest"; }
            public void run() throws Exception { new MessageAckTimeoutTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ConcurrentMessagingTest"; }
            public void run() throws Exception { new ConcurrentMessagingTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "UnicodeMessagingTest"; }
            public void run() throws Exception { new UnicodeMessagingTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ShutdownMessagingTest"; }
            public void run() throws Exception { new ShutdownMessagingTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TwoNodeMessagingTest"; }
            public void run() throws Exception { new TwoNodeMessagingTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ChunkManagerTest"; }
            public void run() throws Exception { new ChunkManagerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "HashUtilsTest"; }
            public void run() throws Exception { new HashUtilsTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TransferTest"; }
            public void run() throws Exception { new TransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileMetadataTest"; }
            public void run() throws Exception { new FileMetadataTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileChunkTest"; }
            public void run() throws Exception { new FileChunkTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileMetadataCodecTest"; }
            public void run() throws Exception { new FileMetadataCodecTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileChunkCodecTest"; }
            public void run() throws Exception { new FileChunkCodecTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileHashTest"; }
            public void run() throws Exception { new FileHashTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileSenderTest"; }
            public void run() throws Exception { new FileSenderTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileReceiverTest"; }
            public void run() throws Exception { new FileReceiverTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TransferStateTest"; }
            public void run() throws Exception { new TransferStateTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TransferManagerTest"; }
            public void run() throws Exception { new TransferManagerTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "FileTransferServiceTest"; }
            public void run() throws Exception { new FileTransferServiceTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TwoNodeFileTransferTest"; }
            public void run() throws Exception { new TwoNodeFileTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "BinaryFileTransferTest"; }
            public void run() throws Exception { new BinaryFileTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "LargeFileTransferTest"; }
            public void run() throws Exception { new LargeFileTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "UnicodeFilenameTest"; }
            public void run() throws Exception { new UnicodeFilenameTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CollisionTest"; }
            public void run() throws Exception { new CollisionTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "PathTraversalTest"; }
            public void run() throws Exception { new PathTraversalTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "HashMismatchTest"; }
            public void run() throws Exception { new HashMismatchTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "DisconnectDuringTransferTest"; }
            public void run() throws Exception { new DisconnectDuringTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ConcurrentTransfersTest"; }
            public void run() throws Exception { new ConcurrentTransfersTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ShutdownDuringTransferTest"; }
            public void run() throws Exception { new ShutdownDuringTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TransferCheckpointTest"; }
            public void run() throws Exception { new TransferCheckpointTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CheckpointAtomicWriteTest"; }
            public void run() throws Exception { new CheckpointAtomicWriteTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ResumeRequestCodecTest"; }
            public void run() throws Exception { new ResumeRequestCodecTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ResumeResponseCodecTest"; }
            public void run() throws Exception { new ResumeResponseCodecTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "InterruptedTransferTest"; }
            public void run() throws Exception { new InterruptedTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ResumeTransferTest"; }
            public void run() throws Exception { new ResumeTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "NoDuplicateDataTest"; }
            public void run() throws Exception { new NoDuplicateDataTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "WrongOffsetTest"; }
            public void run() throws Exception { new WrongOffsetTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CheckpointMismatchTest"; }
            public void run() throws Exception { new CheckpointMismatchTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "MetadataMismatchResumeTest"; }
            public void run() throws Exception { new MetadataMismatchResumeTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CompletedTransferResumeTest"; }
            public void run() throws Exception { new CompletedTransferResumeTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "RestartRecoveryTest"; }
            public void run() throws Exception { new RestartRecoveryTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TwoNodeResumeTest"; }
            public void run() throws Exception { new TwoNodeResumeTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ResumeAfterRestartTest"; }
            public void run() throws Exception { new ResumeAfterRestartTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CancelTransferTest"; }
            public void run() throws Exception { new CancelTransferTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "ConcurrentResumeTest"; }
            public void run() throws Exception { new ConcurrentResumeTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "NetworkFailureTest"; }
            public void run() throws Exception { new NetworkFailureTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "LargeFileResumeTest"; }
            public void run() throws Exception { new LargeFileResumeTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CorruptedPartialFileTest"; }
            public void run() throws Exception { new CorruptedPartialFileTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "PathTraversalRecoveryTest"; }
            public void run() throws Exception { new PathTraversalRecoveryTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "IdentityFingerprintTest"; }
            public void run() throws Exception { new IdentityFingerprintTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "CryptoUtilsTest"; }
            public void run() throws Exception { new CryptoUtilsTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "TrustStoreTest"; }
            public void run() throws Exception { new TrustStoreTest().runAll(); }
        });
        allTests.add(new TestCase() {
            public String name() { return "StorageManagerTest"; }
            public void run() throws Exception { new StorageManagerTest().runAll(); }
        });

        List<TestCase> testsToRun = allTests;
        if (args.length > 0 && !args[0].isBlank()) {
            String filter = args[0].trim();
            testsToRun = allTests.stream()
                    .filter(t -> t.name().equalsIgnoreCase(filter) || t.name().toLowerCase().contains(filter.toLowerCase()))
                    .toList();
        }

        int passed = 0;
        int failed = 0;

        for (TestCase test : testsToRun) {
            System.out.print("[RUN]  " + test.name() + " ... ");
            try {
                test.run();
                System.out.println("PASSED");
                passed++;
            } catch (Throwable t) {
                System.out.println("FAILED");
                System.err.println("       Error: " + t.getMessage());
                t.printStackTrace(System.err);
                failed++;
            }
        }

        System.out.println("=========================================");
        System.out.printf("Results: %d passed, %d failed%n", passed, failed);
        System.out.println("=========================================");

        if (failed > 0) {
            System.exit(1);
        }
    }
}
