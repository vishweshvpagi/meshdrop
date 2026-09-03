package com.meshdrop.heartbeat;

import com.meshdrop.peer.PeerManager;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Service responsible for periodically sending PING packets and tracking peer liveness.
 */
public class HeartbeatService {
    public static final long DEFAULT_PING_INTERVAL_MS = 5000;
    public static final long DEFAULT_PEER_TIMEOUT_MS = 15000;

    private final PeerManager peerManager;
    private ScheduledExecutorService scheduler;

    public HeartbeatService(PeerManager peerManager) {
        this.peerManager = peerManager;
    }

    public void start() {
        // Scheduled task execution will be activated in Phase 11
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
}
