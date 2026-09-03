package com.meshdrop.transfer;

import com.meshdrop.core.NodeIdentity;
import com.meshdrop.peer.Peer;
import com.meshdrop.peer.PeerAddress;
import com.meshdrop.peer.PeerManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Unit tests for FileTransferService offer, reject, and peer validation.
 */
public class FileTransferServiceTest {

    public void runAll() throws Exception {
        testSendFileToDisconnectedPeerFails();
        testNonExistentFileFails();
        testStopCancelsService();
    }

    private void testSendFileToDisconnectedPeerFails() throws Exception {
        NodeIdentity localId = NodeIdentity.createRandom("LocalNode");
        PeerManager pm = new PeerManager(localId.nodeId());
        Path dl = Files.createTempDirectory("dl-srv");
        Path tmp = Files.createTempDirectory("tmp-srv");
        Path testFile = Files.createTempFile("test-file", ".txt");

        try {
            FileTransferService service = new FileTransferService(localId, pm, dl, tmp);
            NodeIdentity remoteId = NodeIdentity.createRandom("RemoteNode");
            PeerAddress addr = new PeerAddress("127.0.0.1", 5000);
            Peer peer = pm.registerDiscovered(remoteId, addr);

            CompletableFuture<Transfer> future = service.sendFile(peer, testFile);
            boolean failed = false;
            try {
                future.get();
            } catch (Exception e) {
                failed = true;
            }
            assert failed : "Sending to disconnected peer must fail";
        } finally {
            Files.deleteIfExists(testFile);
            deleteDir(dl);
            deleteDir(tmp);
        }
    }

    private void testNonExistentFileFails() throws Exception {
        NodeIdentity localId = NodeIdentity.createRandom("LocalNode");
        PeerManager pm = new PeerManager(localId.nodeId());
        Path dl = Files.createTempDirectory("dl-srv2");
        Path tmp = Files.createTempDirectory("tmp-srv2");

        try {
            FileTransferService service = new FileTransferService(localId, pm, dl, tmp);
            NodeIdentity remoteId = NodeIdentity.createRandom("RemoteNode");
            PeerAddress addr = new PeerAddress("127.0.0.1", 5000);
            Peer peer = pm.registerDiscovered(remoteId, addr);

            CompletableFuture<Transfer> future = service.sendFile(peer, Path.of("non_existent_file_12345.bin"));
            boolean failed = false;
            try {
                future.get();
            } catch (Exception e) {
                failed = true;
            }
            assert failed : "Sending non-existent file must fail";
        } finally {
            deleteDir(dl);
            deleteDir(tmp);
        }
    }

    private void testStopCancelsService() throws Exception {
        NodeIdentity localId = NodeIdentity.createRandom("LocalNode");
        PeerManager pm = new PeerManager(localId.nodeId());
        Path dl = Files.createTempDirectory("dl-srv3");
        Path tmp = Files.createTempDirectory("tmp-srv3");

        try {
            FileTransferService service = new FileTransferService(localId, pm, dl, tmp);
            assert service.isRunning();
            service.stop();
            assert !service.isRunning();
        } finally {
            deleteDir(dl);
            deleteDir(tmp);
        }
    }

    private void deleteDir(Path dir) {
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
