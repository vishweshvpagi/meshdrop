package com.meshdrop.security;

import com.meshdrop.core.NodeIdentity;

import java.util.UUID;

/**
 * Validates and authenticates peer node identities and tokens.
 */
public class NodeAuthenticator {

    public boolean validateIdentity(UUID localNodeId, UUID remoteNodeId, String remoteDisplayName) {
        if (remoteNodeId == null || remoteNodeId.equals(localNodeId)) {
            return false; // Prevent self-connections or null IDs
        }
        return remoteDisplayName != null && !remoteDisplayName.isBlank();
    }
}
