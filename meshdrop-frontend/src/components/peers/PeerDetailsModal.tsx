import React, { useState } from 'react';
import { Badge, BadgeVariant } from '../Badge/Badge';
import { Button } from '../Button/Button';
import { Peer, PeerState } from '../../types/Peer';
import { Transfer } from '../../types/Transfer';
import { formatBytes, formatTimestamp, formatTransferState } from '../../utils/formatters';
import './PeerDetailsModal.css';

export interface PeerDetailsModalProps {
  isOpen: boolean;
  peer: Peer | null;
  transfers: Transfer[];
  onClose: () => void;
  onConnect?: (peerId: string) => Promise<unknown> | void;
  onDisconnect?: (peerId: string) => Promise<unknown> | void;
  onSendFile?: (peer: Peer) => void;
}

export const PeerDetailsModal: React.FC<PeerDetailsModalProps> = ({
  isOpen,
  peer,
  transfers,
  onClose,
  onConnect,
  onDisconnect,
  onSendFile,
}) => {
  const [copiedField, setCopiedField] = useState<string | null>(null);
  const [isActionPending, setIsActionPending] = useState(false);

  if (!isOpen || !peer) return null;

  const isConnected = peer.connected || peer.state === 'CONNECTED';

  // Find transfers related to this specific peer
  const peerTransfers = transfers.filter(
    (t) => t.peerId === peer.id || t.peerName === peer.displayName
  );

  const activeTransfers = peerTransfers.filter((t) => {
    const s = t.state || t.status;
    return s === 'TRANSFERRING' || s === 'RESUMING' || s === 'ACCEPTED' || s === 'VERIFYING';
  });
  const completedTransfers = peerTransfers.filter((t) => (t.state || t.status) === 'COMPLETED');

  const copyToClipboard = (text: string, field: string) => {
    navigator.clipboard.writeText(text);
    setCopiedField(field);
    setTimeout(() => setCopiedField(null), 2000);
  };

  const getPeerBadgeVariant = (state: PeerState): BadgeVariant => {
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

  const handleConnect = async () => {
    if (!onConnect) return;
    setIsActionPending(true);
    try {
      await onConnect(peer.id);
    } finally {
      setIsActionPending(false);
    }
  };

  const handleDisconnect = async () => {
    if (!onDisconnect) return;
    setIsActionPending(true);
    try {
      await onDisconnect(peer.id);
    } finally {
      setIsActionPending(false);
    }
  };

  return (
    <div className="peer-modal-overlay" onClick={onClose}>
      <div
        className="peer-modal-card"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="peer-modal-title"
      >
        {/* Header */}
        <div className="peer-modal-header">
          <div className="peer-header-main">
            <h2 id="peer-modal-title" className="peer-modal-name">
              {peer.displayName}
            </h2>
            <Badge variant={getPeerBadgeVariant(peer.state)} withDot>
              {peer.state}
            </Badge>
          </div>
          <button
            type="button"
            className="peer-modal-close"
            onClick={onClose}
            aria-label="Close peer details"
          >
            &times;
          </button>
        </div>

        {/* Modal Body */}
        <div className="peer-modal-body">
          {/* Identity & Technical Specs */}
          <div className="peer-info-grid">
            <div className="peer-info-item">
              <span className="info-label">Node ID</span>
              <div className="info-val-copy">
                <span className="info-mono">{peer.id}</span>
                <button
                  type="button"
                  className="copy-btn"
                  onClick={() => copyToClipboard(peer.id, 'nodeId')}
                  title="Copy Node ID"
                >
                  {copiedField === 'nodeId' ? 'Copied' : 'Copy'}
                </button>
              </div>
            </div>

            <div className="peer-info-item">
              <span className="info-label">Network Address</span>
              <span className="info-val">
                {peer.address ? `${peer.address}:${peer.port}` : 'Address unavailable'}
              </span>
            </div>

            <div className="peer-info-item">
              <span className="info-label">Trust Evaluation</span>
              <Badge variant={peer.trustDecision === 'TRUSTED' ? 'success' : 'neutral'}>
                {peer.trustDecision || 'UNTRUSTED'}
              </Badge>
            </div>

            <div className="peer-info-item">
              <span className="info-label">Session Status</span>
              <span className="info-val">{isConnected ? 'Established & Verified' : 'No active TCP socket'}</span>
            </div>

            {peer.connectedAt && (
              <div className="peer-info-item">
                <span className="info-label">Connected Since</span>
                <span className="info-val">{formatTimestamp(peer.connectedAt)}</span>
              </div>
            )}

            {peer.lastSeen && (
              <div className="peer-info-item">
                <span className="info-label">Last Seen</span>
                <span className="info-val">{formatTimestamp(peer.lastSeen)}</span>
              </div>
            )}

            {peer.fingerprint && (
              <div className="peer-info-item full-width">
                <span className="info-label">Ed25519 Fingerprint</span>
                <div className="info-val-copy">
                  <span className="info-mono">{peer.fingerprint}</span>
                  <button
                    type="button"
                    className="copy-btn"
                    onClick={() => copyToClipboard(peer.fingerprint!, 'fingerprint')}
                    title="Copy fingerprint"
                  >
                    {copiedField === 'fingerprint' ? 'Copied' : 'Copy'}
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* Transfers With This Peer Section */}
          <div className="peer-transfers-section">
            <div className="section-title-wrap">
              <h3 className="section-title">Transfers with this Peer</h3>
              <span className="transfers-summary-count">
                {activeTransfers.length} active &bull; {completedTransfers.length} completed
              </span>
            </div>

            {peerTransfers.length === 0 ? (
              <div className="no-peer-transfers">No file transfers recorded with {peer.displayName}.</div>
            ) : (
              <div className="peer-transfers-mini-list">
                {peerTransfers.map((tx) => {
                  const state = tx.state || tx.status;
                  const isUpload = tx.direction === 'OUTGOING' || tx.direction === 'UPLOAD';
                  return (
                    <div key={tx.transferId || tx.id} className="peer-transfer-mini-item">
                      <div className="mini-item-left">
                        <span className="mini-file-name">{tx.fileName}</span>
                        <span className="mini-subtext">
                          {isUpload ? 'Outgoing' : 'Incoming'} &bull; {formatBytes(tx.transferredBytes)} / {formatBytes(tx.fileSize)}
                        </span>
                      </div>
                      <Badge
                        variant={
                          state === 'COMPLETED'
                            ? 'success'
                            : state === 'TRANSFERRING' || state === 'RESUMING'
                            ? 'info'
                            : state === 'RESUMABLE' || state === 'INTERRUPTED'
                            ? 'warning'
                            : state === 'FAILED' || state === 'CANCELLED'
                            ? 'error'
                            : 'neutral'
                        }
                      >
                        {formatTransferState(state)}
                      </Badge>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        {/* Footer Actions */}
        <div className="peer-modal-footer">
          <div className="footer-left">
            {isConnected ? (
              <Button
                variant="danger"
                size="sm"
                onClick={handleDisconnect}
                isLoading={isActionPending}
              >
                Disconnect
              </Button>
            ) : (
              <Button
                variant="primary"
                size="sm"
                onClick={handleConnect}
                isLoading={isActionPending}
              >
                Connect
              </Button>
            )}

            {isConnected && onSendFile && (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  onClose();
                  onSendFile(peer);
                }}
              >
                Send File
              </Button>
            )}
          </div>

          <Button variant="secondary" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </div>
  );
};
