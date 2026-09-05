import React, { useState } from 'react';
import { Badge } from '../../components/Badge/Badge';
import { Button } from '../../components/Button/Button';
import { Card } from '../../components/Card/Card';
import { EmptyState } from '../../components/EmptyState/EmptyState';
import { LoadingState } from '../../components/LoadingState/LoadingState';
import { PageHeader } from '../../components/PageHeader/PageHeader';
import { StatusIndicator } from '../../components/StatusIndicator/StatusIndicator';
import { ConfirmationDialog } from '../../components/common/ConfirmationDialog';
import { PeerDetailsModal } from '../../components/peers/PeerDetailsModal';
import { SendFileDialog } from '../../components/transfers/SendFileDialog';
import { useToast } from '../../context/ToastContext';
import { useNodeStatus } from '../../hooks/useNodeStatus';
import { usePeers } from '../../hooks/usePeers';
import { isTransferActive, useTransfers } from '../../hooks/useTransfers';
import { meshDropApi } from '../../services/meshdropApi';
import { Peer, PeerState } from '../../types/Peer';
import { formatRelativeTime } from '../../utils/formatters';
import './PeersPage.css';

export const PeersPage: React.FC = () => {
  const { peers, isLoading, error: peersError, lastUpdated, refresh, connectPeer, disconnectPeer } = usePeers(3000);
  const { connectionStatus } = useNodeStatus(3000);
  const { transfers, refresh: refreshTransfers, startTransfer } = useTransfers(3000);
  const { showToast } = useToast();

  const [filter, setFilter] = useState<'ALL' | 'CONNECTED' | 'DISCOVERED'>('ALL');
  const [connectHost, setConnectHost] = useState('');
  const [connectPort, setConnectPort] = useState('5000');
  const [isManualConnecting, setIsManualConnecting] = useState(false);
  const [connectMessage, setConnectMessage] = useState<{ text: string; isError: boolean } | null>(null);

  // Per-peer action state guards
  const [connectingPeerId, setConnectingPeerId] = useState<string | null>(null);
  const [disconnectingPeerId, setDisconnectingPeerId] = useState<string | null>(null);

  // Modal and safety dialog state
  const [selectedPeerForDetails, setSelectedPeerForDetails] = useState<Peer | null>(null);
  const [peerToDisconnect, setPeerToDisconnect] = useState<Peer | null>(null);
  const [sendToPeer, setSendToPeer] = useState<Peer | null>(null);

  const isBackendOffline = connectionStatus === 'DISCONNECTED' || connectionStatus === 'ERROR' || peersError !== null;

  const handleManualConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!connectHost.trim()) return;
    const portNum = parseInt(connectPort, 10);
    if (isNaN(portNum) || portNum <= 0 || portNum > 65535) {
      setConnectMessage({ text: 'Please enter a valid port (1-65535)', isError: true });
      return;
    }

    setIsManualConnecting(true);
    setConnectMessage(null);
    try {
      const res = await meshDropApi.connectPeer(connectHost.trim(), portNum);
      if (res.success) {
        setConnectMessage({ text: `Connection initiated (Socket #${res.connectionId})`, isError: false });
        setConnectHost('');
        showToast({
          type: 'success',
          title: 'Connection Initiated',
          message: `Connected to ${connectHost}:${portNum}`,
        });
        refresh();
      } else {
        setConnectMessage({ text: res.error || 'Connection failed', isError: true });
        showToast({
          type: 'error',
          title: 'Connection Failed',
          message: res.error || 'Could not connect to peer address',
        });
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to connect to peer';
      setConnectMessage({ text: msg, isError: true });
      showToast({ type: 'error', title: 'Connection Error', message: msg });
    } finally {
      setIsManualConnecting(false);
    }
  };

  const handleConnectPeer = async (peer: Peer) => {
    if (connectingPeerId || disconnectingPeerId) return;
    setConnectingPeerId(peer.id);
    try {
      const res = await connectPeer(peer.id);
      if (res.success) {
        showToast({
          type: 'success',
          title: 'Peer Connected',
          message: `Established connection with ${peer.displayName}`,
        });
      } else {
        showToast({
          type: 'error',
          title: 'Connection Failed',
          message: res.error || `Could not connect to ${peer.displayName}`,
        });
      }
    } catch (err) {
      showToast({
        type: 'error',
        title: 'Connection Error',
        message: err instanceof Error ? err.message : 'Failed to connect to peer',
      });
    } finally {
      setConnectingPeerId(null);
    }
  };

  const handleRequestDisconnect = (peer: Peer) => {
    setPeerToDisconnect(peer);
  };

  const handleConfirmDisconnect = async () => {
    if (!peerToDisconnect) return;
    const peer = peerToDisconnect;
    setDisconnectingPeerId(peer.id);
    try {
      const res = await disconnectPeer(peer.id);
      if (res.success) {
        showToast({
          type: 'info',
          title: 'Peer Disconnected',
          message: `Closed session with ${peer.displayName}. Checkpoints preserved.`,
        });
        refreshTransfers();
      } else {
        showToast({
          type: 'error',
          title: 'Disconnect Error',
          message: res.error || 'Failed to close connection',
        });
      }
    } catch (err) {
      showToast({
        type: 'error',
        title: 'Disconnect Error',
        message: err instanceof Error ? err.message : 'Failed to disconnect',
      });
    } finally {
      setDisconnectingPeerId(null);
      setPeerToDisconnect(null);
    }
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

  const displayedPeers =
    filter === 'CONNECTED'
      ? peers.filter((p) => p.connected || p.state === 'CONNECTED')
      : filter === 'DISCOVERED'
      ? peers.filter((p) => p.state === 'DISCOVERED' || p.state === 'DISCONNECTED')
      : peers;

  // Active transfers for confirmation check
  const activeTransfersForDisconnect = peerToDisconnect
    ? transfers.filter(
        (t) =>
          (t.peerId === peerToDisconnect.id || t.peerName === peerToDisconnect.displayName) &&
          isTransferActive(t.state || t.status)
      )
    : [];

  return (
    <div className="peers-page">
      <PageHeader
        title="Peers"
        description="Discover peers on local subnet, inspect encryption identities, and manage connections."
        actions={
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            {lastUpdated && (
              <span className="peers-count-text" style={{ fontSize: '0.75rem' }}>
                Updated {formatRelativeTime(lastUpdated)}
              </span>
            )}
            <Button variant="secondary" size="sm" onClick={() => refresh()}>
              Refresh
            </Button>
          </div>
        }
      />

      {/* Backend Offline Banner */}
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
              <strong>MeshDrop backend is offline.</strong> Previously discovered peers shown below{' '}
              {lastUpdated ? `(last updated ${formatRelativeTime(lastUpdated)})` : ''}.
            </div>
          </div>
          <Button variant="secondary" size="sm" onClick={() => refresh()}>
            Retry
          </Button>
        </div>
      )}

      {/* Direct Connect Form */}
      {!isBackendOffline && (
        <form className="connect-bar" onSubmit={handleManualConnect}>
          <span className="connect-bar-label">Direct Connect:</span>
          <div className="connect-bar-inputs">
            <input
              type="text"
              placeholder="Host / IP (e.g. 192.168.1.20)"
              value={connectHost}
              onChange={(e) => setConnectHost(e.target.value)}
              className="connect-input-host"
              disabled={isManualConnecting}
            />
            <input
              type="number"
              placeholder="Port"
              value={connectPort}
              onChange={(e) => setConnectPort(e.target.value)}
              className="connect-input-port"
              disabled={isManualConnecting}
            />
          </div>
          <Button variant="primary" size="sm" type="submit" isLoading={isManualConnecting}>
            Connect
          </Button>
          {connectMessage && (
            <span
              style={{
                fontSize: '0.8125rem',
                color: connectMessage.isError ? 'var(--error)' : 'var(--success)',
                fontWeight: 500,
              }}
            >
              {connectMessage.text}
            </span>
          )}
        </form>
      )}

      {/* Toolbar / Filters */}
      <div className="peers-toolbar">
        <span className="peers-count-text">
          Showing {displayedPeers.length} of {peers.length} known {peers.length === 1 ? 'peer' : 'peers'}
        </span>
        <div className="peers-filter-buttons" role="tablist">
          <Button
            size="sm"
            variant={filter === 'ALL' ? 'primary' : 'secondary'}
            onClick={() => setFilter('ALL')}
          >
            All ({peers.length})
          </Button>
          <Button
            size="sm"
            variant={filter === 'CONNECTED' ? 'primary' : 'secondary'}
            onClick={() => setFilter('CONNECTED')}
          >
            Connected ({peers.filter((p) => p.connected || p.state === 'CONNECTED').length})
          </Button>
          <Button
            size="sm"
            variant={filter === 'DISCOVERED' ? 'primary' : 'secondary'}
            onClick={() => setFilter('DISCOVERED')}
          >
            Discovered ({peers.filter((p) => !p.connected && p.state !== 'CONNECTED').length})
          </Button>
        </div>
      </div>

      {/* Content: Loading / Empty / Peers Grid */}
      {isLoading && peers.length === 0 ? (
        <LoadingState message="Querying MeshDrop discovery cache..." />
      ) : displayedPeers.length === 0 ? (
        <EmptyState
          title={filter === 'ALL' ? 'No peers discovered' : 'No matching peers'}
          description={
            filter === 'ALL'
              ? 'Waiting for UDP multicast announcements on the local network, or connect directly above.'
              : 'Try changing your filter criteria.'
          }
        />
      ) : (
        <div className="peers-grid">
          {displayedPeers.map((peer: Peer) => {
            const isConnected = peer.connected || peer.state === 'CONNECTED';
            const isConnectingThis = connectingPeerId === peer.id;
            const isDisconnectingThis = disconnectingPeerId === peer.id;

            return (
              <Card
                key={peer.id}
                className={`peer-card ${!isConnected ? 'is-disconnected' : ''}`}
                footer={
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      width: '100%',
                      gap: '0.5rem',
                    }}
                  >
                    <Badge variant={peer.trustDecision === 'TRUSTED' ? 'success' : 'neutral'}>
                      {peer.trustDecision || 'UNTRUSTED'}
                    </Badge>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setSelectedPeerForDetails(peer)}
                      >
                        Details
                      </Button>

                      {isConnected ? (
                        <>
                          <Button
                            variant="danger"
                            size="sm"
                            onClick={() => handleRequestDisconnect(peer)}
                            isLoading={isDisconnectingThis}
                            disabled={isBackendOffline || isDisconnectingThis}
                          >
                            {isDisconnectingThis ? 'Disconnecting...' : 'Disconnect'}
                          </Button>
                          <Button
                            variant="primary"
                            size="sm"
                            onClick={() => setSendToPeer(peer)}
                            disabled={isBackendOffline}
                          >
                            Send File
                          </Button>
                        </>
                      ) : (
                        <Button
                          variant="primary"
                          size="sm"
                          onClick={() => handleConnectPeer(peer)}
                          isLoading={isConnectingThis}
                          disabled={isBackendOffline || isConnectingThis || !peer.address}
                        >
                          {isConnectingThis ? 'Connecting...' : 'Connect'}
                        </Button>
                      )}
                    </div>
                  </div>
                }
              >
                <div className="peer-card-header">
                  <span className="peer-title">{peer.displayName}</span>
                  <StatusIndicator
                    status={isConnected ? 'connected' : 'offline'}
                    label={peer.state}
                  />
                </div>

                <div className="peer-meta-list">
                  <div className="peer-meta-row">
                    <span className="peer-meta-label">Peer ID</span>
                    <span className="peer-meta-val" title={peer.id}>
                      {peer.id.slice(0, 8)}...
                    </span>
                  </div>

                  <div className="peer-meta-row">
                    <span className="peer-meta-label">Address</span>
                    <span className="peer-meta-val">
                      {peer.address ? `${peer.address}:${peer.port}` : 'Address unknown'}
                    </span>
                  </div>

                  <div className="peer-meta-row">
                    <span className="peer-meta-label">Connection State</span>
                    <Badge variant={getPeerBadgeVariant(peer.state)} withDot>
                      {peer.state}
                    </Badge>
                  </div>

                  {peer.lastSeen && (
                    <div className="peer-meta-row">
                      <span className="peer-meta-label">Last Seen</span>
                      <span className="peer-meta-val" style={{ fontSize: '0.75rem' }}>
                        {formatRelativeTime(peer.lastSeen)}
                      </span>
                    </div>
                  )}

                  {peer.fingerprint && (
                    <div
                      className="peer-meta-row"
                      style={{ flexDirection: 'column', alignItems: 'flex-start', marginTop: '0.25rem' }}
                    >
                      <span className="peer-meta-label">Ed25519 Fingerprint</span>
                      <span className="peer-meta-fingerprint">{peer.fingerprint}</span>
                    </div>
                  )}
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {/* Peer Details Modal */}
      <PeerDetailsModal
        isOpen={selectedPeerForDetails !== null}
        peer={selectedPeerForDetails}
        transfers={transfers}
        onClose={() => setSelectedPeerForDetails(null)}
        onConnect={(peerId) => {
          const p = peers.find((item) => item.id === peerId);
          if (p) handleConnectPeer(p);
        }}
        onDisconnect={(peerId) => {
          const p = peers.find((item) => item.id === peerId);
          if (p) handleRequestDisconnect(p);
        }}
        onSendFile={(p) => setSendToPeer(p)}
      />

      {/* Active Transfer Safety / Disconnect Confirmation Dialog */}
      <ConfirmationDialog
        isOpen={peerToDisconnect !== null}
        title={
          activeTransfersForDisconnect.length > 0
            ? 'Warning: Active Transfers in Progress'
            : 'Disconnect Peer?'
        }
        message={
          activeTransfersForDisconnect.length > 0 ? (
            <div>
              <p style={{ marginBottom: '0.75rem', fontWeight: 600, color: 'var(--warning)' }}>
                {peerToDisconnect?.displayName} currently has {activeTransfersForDisconnect.length} active
                file transfer(s).
              </p>
              <p style={{ marginBottom: '0.5rem' }}>
                Disconnecting will sever the active TCP connection and halt transfers into resumable
                checkpoints on disk.
              </p>
              <p>Are you sure you want to disconnect now?</p>
            </div>
          ) : (
            `Disconnect transport session with ${peerToDisconnect?.displayName}? The peer will remain in discovered history.`
          )
        }
        confirmLabel={
          activeTransfersForDisconnect.length > 0 ? 'Disconnect & Pause Transfers' : 'Disconnect'
        }
        confirmVariant="danger"
        isConfirming={disconnectingPeerId !== null}
        onConfirm={handleConfirmDisconnect}
        onCancel={() => setPeerToDisconnect(null)}
      />

      {/* Send File Dialog */}
      <SendFileDialog
        isOpen={sendToPeer !== null}
        defaultPeerId={sendToPeer?.id}
        peers={peers}
        onClose={() => setSendToPeer(null)}
        onSend={async (peerId, filePath) => {
          const res = await startTransfer(peerId, filePath);
          if (!res.success) {
            throw new Error(res.error || 'Failed to dispatch file transfer');
          }
          showToast({
            type: 'info',
            title: 'Transfer Dispatched',
            message: `Offered file to peer`,
          });
        }}
      />
    </div>
  );
};

