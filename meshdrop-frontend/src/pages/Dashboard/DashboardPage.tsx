import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Badge } from '../../components/Badge/Badge';
import { Button } from '../../components/Button/Button';
import { Card } from '../../components/Card/Card';
import { EmptyState } from '../../components/EmptyState/EmptyState';
import { LoadingState } from '../../components/LoadingState/LoadingState';
import { PageHeader } from '../../components/PageHeader/PageHeader';
import { StatusIndicator } from '../../components/StatusIndicator/StatusIndicator';
import { TransferDetailsModal } from '../../components/transfers/TransferDetailsModal';
import { useConnections } from '../../hooks/useConnections';
import { useNodeStatus } from '../../hooks/useNodeStatus';
import { usePeers } from '../../hooks/usePeers';
import { useTransfers } from '../../hooks/useTransfers';
import { PeerState } from '../../types/Peer';
import { Transfer } from '../../types/Transfer';
import {
  calculatePercentage,
  formatBytes,
  formatRelativeTime,
  formatTransferState,
  formatUptime,
} from '../../utils/formatters';
import './DashboardPage.css';

interface ActivityItem {
  id: string;
  type: 'success' | 'info' | 'warning' | 'error' | 'neutral';
  category: 'Transfer' | 'Peer';
  title: string;
  detail: string;
  timestamp: number;
  transfer?: Transfer;
}

export const DashboardPage: React.FC = () => {
  const { node, connectionStatus, lastUpdated, isStale, refresh: refreshStatus } = useNodeStatus(2500);
  const { peers, isLoading: isPeersLoading, refresh: refreshPeers } = usePeers(2500);
  const { connections, isLoading: isConnsLoading, refresh: refreshConns } = useConnections(2500);
  const {
    transfers,
    counters,
    isLoading: isTransfersLoading,
    refresh: refreshTransfers,
    resumeTransfer,
    retryTransfer,
    cancelTransfer,
    removeTransfer,
  } = useTransfers(2500);

  const [selectedTransfer, setSelectedTransfer] = useState<Transfer | null>(null);

  const isBackendOffline = connectionStatus === 'DISCONNECTED' || connectionStatus === 'ERROR';
  const isInitialLoading = connectionStatus === 'CONNECTING' && !node;

  const handleRefreshAll = () => {
    refreshStatus();
    refreshPeers();
    refreshConns();
    refreshTransfers();
  };

  const getPeerBadgeVariant = (state: PeerState) => {
    switch (state) {
      case 'CONNECTED':
        return 'success';
      case 'CONNECTING':
        return 'info';
      case 'DISCOVERED':
        return 'warning';
      case 'DISCONNECTED':
      default:
        return 'neutral';
    }
  };

  const activities = useMemo<ActivityItem[]>(() => {
    const items: ActivityItem[] = [];

    // Authoritative events from transfers
    for (const tx of transfers) {
      const isUpload = tx.direction === 'OUTGOING' || tx.direction === 'UPLOAD';
      const status = tx.state || tx.status;
      const transferId = tx.transferId || tx.id;

      if (status === 'COMPLETED') {
        const time = tx.completedTime || (tx.completedAt ? new Date(tx.completedAt).getTime() : tx.startTime || Date.now());
        items.push({
          id: `tx-completed-${transferId}`,
          type: 'success',
          category: 'Transfer',
          title: 'Transfer Completed',
          detail: `${isUpload ? 'Sent' : 'Received'} "${tx.fileName}" (${formatBytes(tx.fileSize)}) ${isUpload ? 'to' : 'from'} ${tx.peerName || 'Peer'}`,
          timestamp: time,
          transfer: tx,
        });
      } else if (status === 'RESUMABLE' || status === 'INTERRUPTED') {
        const time = tx.lastUpdated || tx.startTime || Date.now();
        items.push({
          id: `tx-interrupted-${transferId}`,
          type: 'warning',
          category: 'Transfer',
          title: 'Transfer Interrupted (Checkpoint Ready)',
          detail: `"${tx.fileName}" paused at ${formatBytes(tx.transferredBytes)} (${calculatePercentage(tx.transferredBytes, tx.fileSize)}%). Resumable state preserved.`,
          timestamp: time,
          transfer: tx,
        });
      } else if (status === 'FAILED' || status === 'CANCELLED' || status === 'TIMED_OUT' || status === 'REJECTED') {
        const time = tx.completedTime || tx.lastUpdated || tx.startTime || Date.now();
        items.push({
          id: `tx-failed-${transferId}`,
          type: 'error',
          category: 'Transfer',
          title: `Transfer ${formatTransferState(status)}`,
          detail: `"${tx.fileName}" ${status.toLowerCase()}${tx.errorMessage ? `: ${tx.errorMessage}` : ''}`,
          timestamp: time,
          transfer: tx,
        });
      } else {
        const time = tx.startTime || (tx.startedAt ? new Date(tx.startedAt).getTime() : Date.now());
        items.push({
          id: `tx-active-${transferId}`,
          type: 'info',
          category: 'Transfer',
          title: 'Transfer In Progress',
          detail: `${isUpload ? 'Streaming' : 'Receiving'} "${tx.fileName}" (${calculatePercentage(tx.transferredBytes, tx.fileSize)}% • ${formatBytes(tx.transferredBytes)} / ${formatBytes(tx.fileSize)}) with ${tx.peerName || 'Peer'}`,
          timestamp: time,
          transfer: tx,
        });
      }
    }

    // Authoritative events from peers
    for (const peer of peers) {
      if (peer.connectedAt) {
        items.push({
          id: `peer-conn-${peer.id}`,
          type: 'success',
          category: 'Peer',
          title: 'Peer Connected',
          detail: `Established connection with ${peer.displayName} (${peer.address}:${peer.port})`,
          timestamp: new Date(peer.connectedAt).getTime(),
        });
      } else if (peer.connected || peer.state === 'CONNECTED') {
        items.push({
          id: `peer-conn-${peer.id}`,
          type: 'success',
          category: 'Peer',
          title: 'Peer Connected',
          detail: `Active session with ${peer.displayName} (${peer.address}:${peer.port})`,
          timestamp: peer.lastSeen ? new Date(peer.lastSeen).getTime() : (node?.uptimeMillis ? Date.now() - node.uptimeMillis : Date.now()),
        });
      } else if (peer.state === 'DISCOVERED') {
        items.push({
          id: `peer-disc-${peer.id}`,
          type: 'info',
          category: 'Peer',
          title: 'Peer Discovered',
          detail: `Discovered node "${peer.displayName}" via UDP multicast on ${peer.address}:${peer.port}`,
          timestamp: peer.lastSeen ? new Date(peer.lastSeen).getTime() : Date.now(),
        });
      } else if (peer.state === 'DISCONNECTED') {
        items.push({
          id: `peer-disc-${peer.id}`,
          type: 'neutral',
          category: 'Peer',
          title: 'Peer Disconnected',
          detail: `Session closed with ${peer.displayName}`,
          timestamp: peer.lastSeen ? new Date(peer.lastSeen).getTime() : Date.now(),
        });
      }
    }

    items.sort((a, b) => b.timestamp - a.timestamp);
    return items.slice(0, 8);
  }, [transfers, peers, node]);

  if (isInitialLoading) {
    return <LoadingState message="Connecting to MeshDrop node..." />;
  }

  const connectedPeers = peers.filter((p) => p.connected || p.state === 'CONNECTED');

  // Sync selected modal transfer with live data
  const activeModalTransfer = selectedTransfer
    ? transfers.find((t) => (t.transferId || t.id) === (selectedTransfer.transferId || selectedTransfer.id)) ||
      selectedTransfer
    : null;

  return (
    <div className="dashboard-page">
      <PageHeader
        title="Dashboard"
        description="Manage your MeshDrop node, monitor connections, and track file transfers."
        actions={
          <div className="dashboard-header-actions">
            {lastUpdated && (
              <span className="dashboard-updated-time">
                {isStale ? (
                  <span className="stale-indicator" title="Data might be stale while backend is unreachable">
                    Offline &bull; Last updated {formatRelativeTime(lastUpdated)}
                  </span>
                ) : (
                  <span>Updated {formatRelativeTime(lastUpdated)}</span>
                )}
              </span>
            )}
            <Button variant="secondary" size="sm" onClick={handleRefreshAll}>
              Refresh
            </Button>
          </div>
        }
      />

      {/* Backend Offline Warning Notice */}
      {isBackendOffline && (
        <div className="offline-banner" role="alert">
          <div className="offline-banner-content">
            <svg
              className="offline-banner-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <div>
              <strong>MeshDrop backend is unreachable.</strong> Ensure the Java node is running with HTTP API enabled (default port 8080).
            </div>
          </div>
          <Button variant="secondary" size="sm" onClick={handleRefreshAll}>
            Retry
          </Button>
        </div>
      )}

      {/* Local Node Status Card */}
      <Card
        title="Node Status"
        subtitle={node ? 'Local runtime parameters and cryptographic identity' : 'Backend unavailable'}
      >
        <div className="node-card-content">
          <div className="node-info-main">
            <div className="node-name">
              <span>{node ? node.displayName : 'Local Node (Offline)'}</span>
              <StatusIndicator
                status={!isBackendOffline && node?.running ? 'online' : 'offline'}
                label={!isBackendOffline && node?.running ? 'Online' : 'Offline'}
              />
            </div>
          </div>
        </div>

        <div className="node-details-grid">
          <div className="node-detail-item">
            <span className="node-detail-label">Node ID</span>
            <span className="node-detail-value">{node ? node.nodeId : '--'}</span>
          </div>

          <div className="node-detail-item">
            <span className="node-detail-label">TCP Port</span>
            <span className="node-detail-value">
              {node?.tcpPort ? `${node.tcpPort} (Listening)` : '--'}
            </span>
          </div>

          <div className="node-detail-item">
            <span className="node-detail-label">Discovery (UDP)</span>
            <span className="node-detail-value">
              {node?.discoveryRunning ? `${node.discoveryPort} (Active)` : 'Disabled'}
            </span>
          </div>

          <div className="node-detail-item">
            <span className="node-detail-label">Uptime</span>
            <span className="node-detail-value">{node ? formatUptime(node.uptimeMillis) : '--'}</span>
          </div>

          {node?.fingerprint && (
            <div className="node-detail-item" style={{ gridColumn: '1 / -1' }}>
              <span className="node-detail-label">Ed25519 Fingerprint</span>
              <span className="node-detail-value">{node.fingerprint}</span>
            </div>
          )}
        </div>
      </Card>

      {/* Connectivity Metrics Grid */}
      <div className="dashboard-metrics-grid">
        <Card className="metric-card">
          <span className="metric-label">Known Peers</span>
          <span className="metric-value">{isBackendOffline ? '--' : peers.length}</span>
          <span className="metric-subtext">Discovered across local network</span>
        </Card>

        <Card className="metric-card">
          <span className="metric-label">Connected</span>
          <span className="metric-value">{isBackendOffline ? '--' : connectedPeers.length}</span>
          <span className="metric-subtext">Handshake completed & ready</span>
        </Card>

        <Card className="metric-card">
          <span className="metric-label">Active Connections</span>
          <span className="metric-value">{isBackendOffline ? '--' : connections.length}</span>
          <span className="metric-subtext">Raw TCP transport sockets</span>
        </Card>
      </div>

      {/* 2-Column: Recent Peers & Active TCP Connections */}
      <div className="dashboard-grid">
        {/* Recent Peers */}
        <Card
          title="Peers"
          subtitle={`${peers.length} discovered peer${peers.length === 1 ? '' : 's'}`}
          headerAction={
            <Link to="/peers" className="view-all-link">
              View all
            </Link>
          }
        >
          {isPeersLoading && peers.length === 0 ? (
            <LoadingState message="Fetching peers..." />
          ) : isBackendOffline ? (
            <EmptyState
              title="Backend offline"
              description="Start the MeshDrop Java node to discover and connect to peers."
            />
          ) : peers.length === 0 ? (
            <EmptyState
              title="No peers discovered"
              description="Waiting for UDP multicast announcements on the local network."
            />
          ) : (
            <div className="quick-list">
              {peers.slice(0, 4).map((peer) => (
                <div key={peer.id} className="quick-peer-item">
                  <div className="peer-name-wrap">
                    <span className="peer-display-name">{peer.displayName}</span>
                    <span className="peer-ip-address">
                      {peer.address ? `${peer.address}:${peer.port}` : 'Address unknown'}
                    </span>
                  </div>
                  <div className="peer-state-badge-wrap">
                    <Badge variant={getPeerBadgeVariant(peer.state)} withDot>
                      {peer.state}
                    </Badge>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>

        {/* Active TCP Connections */}
        <Card
          title="TCP Connections"
          subtitle={`${connections.length} active socket${connections.length === 1 ? '' : 's'}`}
        >
          {isConnsLoading && connections.length === 0 ? (
            <LoadingState message="Fetching connections..." />
          ) : isBackendOffline ? (
            <EmptyState
              title="Backend offline"
              description="Cannot observe connections while backend is unreachable."
            />
          ) : connections.length === 0 ? (
            <EmptyState
              title="No active connections"
              description="Active TCP socket sessions will appear here."
            />
          ) : (
            <div className="quick-list">
              {connections.map((conn) => (
                <div key={conn.connectionId} className="quick-conn-item">
                  <div className="conn-left">
                    <div className="conn-title">
                      <span>{conn.displayName || `Connection #${conn.connectionId}`}</span>
                      <Badge variant="neutral">{conn.direction}</Badge>
                    </div>
                    <span className="conn-remote-addr">{conn.remoteAddress}</span>
                  </div>
                  <div className="conn-right">
                    <Badge
                      variant={conn.state === 'READY' || conn.state === 'CONNECTED' ? 'success' : 'info'}
                    >
                      {conn.state}
                    </Badge>
                    <span className="conn-duration">
                      Up: {formatUptime(conn.durationMillis)}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      {/* Transfers Section with Phase 4 Resumable counter & detailed modal */}
      <Card
        title="Transfers"
        subtitle={`${counters.active} active, ${counters.resumable} resumable, ${counters.completed} completed, ${counters.failed} failed`}
        headerAction={
          <Link to="/transfers" className="view-all-link">
            View all transfers
          </Link>
        }
      >
        <div className="dashboard-transfer-stats-bar">
          <Link to="/transfers" className="stat-pill active">
            <span className="pill-label">Active</span>
            <span className="pill-val">{isBackendOffline ? '--' : counters.active}</span>
          </Link>
          <Link to="/transfers" className="stat-pill resumable">
            <span className="pill-label">Resumable</span>
            <span className="pill-val">{isBackendOffline ? '--' : counters.resumable}</span>
          </Link>
          <Link to="/transfers" className="stat-pill completed">
            <span className="pill-label">Completed</span>
            <span className="pill-val">{isBackendOffline ? '--' : counters.completed}</span>
          </Link>
          <Link to="/transfers" className="stat-pill failed">
            <span className="pill-label">Failed / Stopped</span>
            <span className="pill-val">{isBackendOffline ? '--' : counters.failed}</span>
          </Link>
        </div>

        {isTransfersLoading && transfers.length === 0 ? (
          <LoadingState message="Fetching transfers..." />
        ) : isBackendOffline ? (
          <EmptyState
            title="Backend offline"
            description="Cannot observe file transfers while MeshDrop backend is unreachable."
          />
        ) : transfers.length === 0 ? (
          <EmptyState
            title="No transfers yet"
            description="Initiate a file transfer to stream data directly to connected peers."
            action={
              <Link to="/transfers">
                <Button size="sm" variant="primary">
                  Go to Transfers
                </Button>
              </Link>
            }
          />
        ) : (
          <div className="quick-list">
            {transfers.slice(0, 5).map((tx) => {
              const isUpload = tx.direction === 'OUTGOING' || tx.direction === 'UPLOAD';
              const state = tx.state || tx.status;
              const badgeVariant =
                state === 'COMPLETED'
                  ? 'success'
                  : state === 'TRANSFERRING' || state === 'ACCEPTED' || state === 'RESUMING'
                  ? 'info'
                  : state === 'RESUMABLE' || state === 'INTERRUPTED'
                  ? 'warning'
                  : state === 'FAILED' || state === 'CANCELLED' || state === 'REJECTED'
                  ? 'error'
                  : 'neutral';

              return (
                <div
                  key={tx.transferId || tx.id}
                  className="quick-transfer-item clickable"
                  onClick={() => setSelectedTransfer(tx)}
                  title="Click to view transfer details and controls"
                >
                  <div className="tx-left">
                    <div className="tx-title">
                      <span className="tx-filename">{tx.fileName}</span>
                      <Badge variant="neutral">
                        {isUpload ? 'OUTGOING' : 'INCOMING'}
                      </Badge>
                      {tx.hasCheckpoint && (
                        <span className="checkpoint-pill">Checkpoint</span>
                      )}
                    </div>
                    <span className="tx-peer">
                      {isUpload ? 'To' : 'From'} <strong>{tx.peerName}</strong> &bull;{' '}
                      {formatBytes(tx.transferredBytes)} / {formatBytes(tx.fileSize)}
                    </span>
                  </div>
                  <div className="tx-right">
                    <Badge variant={badgeVariant} withDot>
                      {formatTransferState(state)}
                    </Badge>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      {/* Authoritative Recent Activity */}
      <Card
        title="Recent Activity"
        subtitle={
          activities.length > 0
            ? 'Live events derived authoritatively from transfer engine & peer discovery'
            : 'No events recorded yet'
        }
      >
        {activities.length === 0 ? (
          <EmptyState
            title="No recent activity yet"
            description="Peer connections and file transfers will be automatically recorded here as they occur."
          />
        ) : (
          <div className="activity-timeline">
            {activities.map((act) => (
              <div
                key={act.id}
                className={`activity-item activity-${act.type} ${act.transfer ? 'clickable' : ''}`}
                onClick={act.transfer ? () => setSelectedTransfer(act.transfer!) : undefined}
                title={act.transfer ? 'Click to view transfer details' : undefined}
              >
                <div className="activity-icon-col">
                  <div className={`activity-icon-badge ${act.type}`}>
                    {act.type === 'success' && (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    )}
                    {act.type === 'info' && (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="10" />
                        <line x1="12" y1="16" x2="12" y2="12" />
                        <line x1="12" y1="8" x2="12.01" y2="8" />
                      </svg>
                    )}
                    {act.type === 'warning' && (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
                        <line x1="12" y1="9" x2="12" y2="13" />
                        <line x1="12" y1="17" x2="12.01" y2="17" />
                      </svg>
                    )}
                    {act.type === 'error' && (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="10" />
                        <line x1="15" y1="9" x2="9" y2="15" />
                        <line x1="9" y1="9" x2="15" y2="15" />
                      </svg>
                    )}
                    {act.type === 'neutral' && (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="4" />
                      </svg>
                    )}
                  </div>
                </div>

                <div className="activity-content">
                  <div className="activity-header-row">
                    <span className="activity-title">{act.title}</span>
                    <Badge variant={act.category === 'Transfer' ? 'info' : 'neutral'}>
                      {act.category}
                    </Badge>
                  </div>
                  <p className="activity-detail">{act.detail}</p>
                </div>

                <div className="activity-meta">
                  <span className="activity-time">{formatRelativeTime(act.timestamp)}</span>
                  {act.transfer && (
                    <span className="activity-action-hint">View &rarr;</span>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* Transfer Details Modal on Dashboard */}
      <TransferDetailsModal
        isOpen={selectedTransfer !== null}
        transfer={activeModalTransfer}
        onClose={() => setSelectedTransfer(null)}
        onResume={resumeTransfer}
        onRetry={retryTransfer}
        onCancel={cancelTransfer}
        onRemove={removeTransfer}
      />
    </div>
  );
};
