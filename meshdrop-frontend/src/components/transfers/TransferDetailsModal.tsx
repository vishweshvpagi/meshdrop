import React, { useEffect, useState } from 'react';
import { Badge, BadgeVariant } from '../Badge/Badge';
import { Button } from '../Button/Button';
import { ProgressBar } from '../ProgressBar/ProgressBar';
import { Transfer, TransferState } from '../../types/Transfer';
import {
  calculatePercentage,
  formatBytes,
  formatEta,
  formatSpeed,
  formatTimestamp,
  formatTransferState,
  formatUptime,
} from '../../utils/formatters';
import './TransferDetailsModal.css';

export interface TransferDetailsModalProps {
  isOpen: boolean;
  transfer: Transfer | null;
  onClose: () => void;
  onResume?: (transferId: string) => Promise<unknown> | void;
  onRetry?: (transferId: string) => Promise<unknown> | void;
  onCancel?: (transferId: string) => Promise<unknown> | void;
  onRemove?: (transferId: string) => Promise<unknown> | void;
}

export const TransferDetailsModal: React.FC<TransferDetailsModalProps> = ({
  isOpen,
  transfer,
  onClose,
  onResume,
  onRetry,
  onCancel,
  onRemove,
}) => {
  const [copiedUuid, setCopiedUuid] = useState(false);
  const [copiedSha, setCopiedSha] = useState(false);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);
  const [showRemoveConfirm, setShowRemoveConfirm] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  // Close on Escape key
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !actionLoading) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, actionLoading, onClose]);

  // Reset confirmation dialogs on transfer change or modal open
  useEffect(() => {
    if (isOpen) {
      setShowCancelConfirm(false);
      setShowRemoveConfirm(false);
      setActionLoading(null);
      setCopiedUuid(false);
      setCopiedSha(false);
    }
  }, [isOpen, transfer?.transferId, transfer?.id]);

  if (!isOpen || !transfer) return null;

  const transferId = transfer.transferId || transfer.id;
  const status: TransferState = transfer.state || transfer.status;
  const isUpload = transfer.direction === 'OUTGOING' || transfer.direction === 'UPLOAD';
  const percentage = calculatePercentage(transfer.transferredBytes, transfer.fileSize);
  const remainingBytes = transfer.remainingBytes ?? Math.max(0, transfer.fileSize - transfer.transferredBytes);
  const speed = transfer.speedBytesPerSecond ?? transfer.speed;
  const eta = transfer.etaSeconds ?? transfer.eta;

  const isActive =
    status === 'TRANSFERRING' ||
    status === 'WAITING_FOR_ACCEPT' ||
    status === 'ACCEPTED' ||
    status === 'OFFERING' ||
    status === 'VERIFYING' ||
    status === 'RESUMING';

  const canResume = transfer.canResume ?? (status === 'RESUMABLE' || status === 'INTERRUPTED');
  const canRetry = transfer.canRetry ?? (isUpload && (status === 'FAILED' || status === 'TIMED_OUT'));
  const canCancel = transfer.canCancel ?? isActive;
  const canRemove =
    transfer.canRemove ??
    (status === 'COMPLETED' || status === 'CANCELLED' || status === 'FAILED' || status === 'REJECTED');

  const getBadgeVariant = (s: TransferState): BadgeVariant => {
    switch (s) {
      case 'COMPLETED':
        return 'success';
      case 'TRANSFERRING':
      case 'ACCEPTED':
      case 'RESUMING':
        return 'info';
      case 'WAITING_FOR_ACCEPT':
      case 'OFFERING':
      case 'RESUMABLE':
      case 'INTERRUPTED':
        return 'warning';
      case 'FAILED':
      case 'CANCELLED':
      case 'REJECTED':
      case 'TIMED_OUT':
        return 'error';
      default:
        return 'neutral';
    }
  };

  const getProgressVariant = (s: TransferState) => {
    if (s === 'COMPLETED') return 'success';
    if (s === 'FAILED' || s === 'CANCELLED' || s === 'TIMED_OUT' || s === 'REJECTED') return 'error';
    if (s === 'RESUMABLE' || s === 'INTERRUPTED' || s === 'WAITING_FOR_ACCEPT') return 'warning';
    return 'primary';
  };

  const copyToClipboard = async (text: string, type: 'uuid' | 'sha') => {
    try {
      await navigator.clipboard.writeText(text);
      if (type === 'uuid') {
        setCopiedUuid(true);
        setTimeout(() => setCopiedUuid(false), 2000);
      } else {
        setCopiedSha(true);
        setTimeout(() => setCopiedSha(false), 2000);
      }
    } catch (err) {
      console.error('Failed to copy to clipboard', err);
    }
  };

  const handleResume = async () => {
    if (!onResume) return;
    setActionLoading('resume');
    try {
      await onResume(transferId);
    } catch (err) {
      console.error('Failed to resume transfer', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleRetry = async () => {
    if (!onRetry) return;
    setActionLoading('retry');
    try {
      await onRetry(transferId);
    } catch (err) {
      console.error('Failed to retry transfer', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleConfirmCancel = async () => {
    if (!onCancel) return;
    setActionLoading('cancel');
    try {
      await onCancel(transferId);
      setShowCancelConfirm(false);
    } catch (err) {
      console.error('Failed to cancel transfer', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleConfirmRemove = async () => {
    if (!onRemove) return;
    setActionLoading('remove');
    try {
      await onRemove(transferId);
      setShowRemoveConfirm(false);
      onClose();
    } catch (err) {
      console.error('Failed to remove transfer from history', err);
    } finally {
      setActionLoading(null);
    }
  };

  // Stepped Timeline calculation
  const timelineSteps = [
    { label: 'Offered', completed: true },
    {
      label: 'Accepted',
      completed:
        status !== 'OFFERING' &&
        status !== 'WAITING_FOR_ACCEPT' &&
        status !== 'REJECTED',
      failed: status === 'REJECTED',
    },
    {
      label: status === 'RESUMABLE' || status === 'INTERRUPTED'
        ? 'Interrupted'
        : status === 'RESUMING'
        ? 'Resuming'
        : 'Transferring',
      completed: status === 'VERIFYING' || status === 'COMPLETED',
      current:
        status === 'TRANSFERRING' ||
        status === 'RESUMING' ||
        status === 'RESUMABLE' ||
        status === 'INTERRUPTED',
      warning: status === 'RESUMABLE' || status === 'INTERRUPTED',
      failed: (status === 'FAILED' || status === 'CANCELLED' || status === 'TIMED_OUT') && percentage < 100,
    },
    {
      label: 'Verifying',
      completed: status === 'COMPLETED',
      current: status === 'VERIFYING',
      failed: status === 'FAILED' && percentage >= 100,
    },
    {
      label: 'Completed',
      completed: status === 'COMPLETED',
      current: status === 'COMPLETED',
      failed: (status === 'FAILED' || status === 'CANCELLED') && percentage >= 100,
    },
  ];

  return (
    <div className="modal-backdrop" onClick={!actionLoading ? onClose : undefined} role="presentation">
      <div
        className="transfer-details-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="transfer-details-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="dialog-header">
          <div className="transfer-header-title-wrap">
            <h2 id="transfer-details-title">Transfer Details</h2>
            <Badge variant={getBadgeVariant(status)} withDot>
              {formatTransferState(status)}
            </Badge>
          </div>
          <button
            type="button"
            className="dialog-close-btn"
            onClick={onClose}
            disabled={actionLoading !== null}
            aria-label="Close dialog"
          >
            &times;
          </button>
        </div>

        <div className="dialog-body">
          {/* Error Notice */}
          {transfer.errorMessage && (
            <div className="transfer-modal-error" role="alert">
              <strong>Error Encountered:</strong> {transfer.errorMessage}
            </div>
          )}

          {/* Top File Summary Card */}
          <div className="transfer-hero-card">
            <div className={`transfer-hero-icon ${isUpload ? 'upload' : 'download'}`} aria-hidden="true">
              {isUpload ? (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="12" y1="19" x2="12" y2="5" />
                  <polyline points="5 12 12 5 19 12" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="12" y1="5" x2="12" y2="19" />
                  <polyline points="19 12 12 19 5 12" />
                </svg>
              )}
            </div>
            <div className="transfer-hero-content">
              <h3 className="transfer-hero-filename" title={transfer.fileName}>
                {transfer.fileName}
              </h3>
              <span className="transfer-hero-peer">
                {isUpload ? 'Sending to' : 'Receiving from'}{' '}
                <strong>{transfer.peerName || 'Remote Peer'}</strong>
              </span>
            </div>
          </div>

          {/* Visual Stepped Timeline */}
          <div className="timeline-container" aria-label="Transfer status timeline">
            <div className="timeline-steps">
              {timelineSteps.map((step, idx) => {
                let stepClass = 'timeline-step';
                if (step.completed) stepClass += ' is-completed';
                if (step.current) stepClass += ' is-current';
                if (step.warning) stepClass += ' is-warning';
                if (step.failed) stepClass += ' is-failed';

                return (
                  <div key={idx} className={stepClass}>
                    <div className="timeline-marker">
                      {step.completed ? (
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" className="marker-icon">
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      ) : step.failed ? (
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" className="marker-icon">
                          <line x1="18" y1="6" x2="6" y2="18" />
                          <line x1="6" y1="6" x2="18" y2="18" />
                        </svg>
                      ) : (
                        <span className="marker-dot" />
                      )}
                    </div>
                    <span className="timeline-label">{step.label}</span>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Progress & Live Metrics */}
          <div className="transfer-modal-progress-section">
            <div className="progress-label-row">
              <span className="progress-percent">{percentage}% Complete</span>
              <span className="progress-bytes-label font-mono">
                {formatBytes(transfer.transferredBytes)} of {formatBytes(transfer.fileSize)}
              </span>
            </div>
            <ProgressBar progress={percentage} variant={getProgressVariant(status)} />
            <div className="progress-stats-grid">
              <div className="stat-box">
                <span className="stat-k">Speed</span>
                <span className="stat-v font-mono">{isActive ? formatSpeed(speed) : '--'}</span>
              </div>
              <div className="stat-box">
                <span className="stat-k">ETA</span>
                <span className="stat-v font-mono">{isActive ? formatEta(eta) : '--'}</span>
              </div>
              <div className="stat-box">
                <span className="stat-k">Remaining</span>
                <span className="stat-v font-mono">{formatBytes(remainingBytes)}</span>
              </div>
              <div className="stat-box">
                <span className="stat-k">Duration</span>
                <span className="stat-v font-mono">
                  {transfer.startTime
                    ? formatUptime((transfer.completedTime || Date.now()) - transfer.startTime)
                    : '--'}
                </span>
              </div>
            </div>
          </div>

          {/* Technical Metadata Grid */}
          <div className="details-section">
            <h4 className="section-heading">Technical Metadata</h4>
            <div className="metadata-grid">
              <div className="meta-item full-width">
                <span className="meta-k">Transfer UUID</span>
                <div className="copyable-value">
                  <span className="meta-v font-mono">{transferId}</span>
                  <button
                    type="button"
                    className="copy-btn"
                    onClick={() => copyToClipboard(transferId, 'uuid')}
                    title="Copy Transfer UUID"
                  >
                    {copiedUuid ? 'Copied!' : 'Copy'}
                  </button>
                </div>
              </div>

              <div className="meta-item">
                <span className="meta-k">Direction</span>
                <span className="meta-v">{isUpload ? 'Outgoing (Upload)' : 'Incoming (Download)'}</span>
              </div>

              <div className="meta-item">
                <span className="meta-k">Peer Identity</span>
                <span className="meta-v font-mono" title={transfer.peerId}>
                  {transfer.peerName} ({transfer.peerId?.slice(0, 8)}...)
                </span>
              </div>

              <div className="meta-item">
                <span className="meta-k">Total File Size</span>
                <span className="meta-v font-mono">
                  {formatBytes(transfer.fileSize)} ({transfer.fileSize.toLocaleString()} bytes)
                </span>
              </div>

              <div className="meta-item">
                <span className="meta-k">Transferred Data</span>
                <span className="meta-v font-mono">
                  {formatBytes(transfer.transferredBytes)} ({transfer.transferredBytes.toLocaleString()} bytes)
                </span>
              </div>

              <div className="meta-item">
                <span className="meta-k">Transfer Started</span>
                <span className="meta-v font-mono">{formatTimestamp(transfer.startTime)}</span>
              </div>

              <div className="meta-item">
                <span className="meta-k">Completed / Last Active</span>
                <span className="meta-v font-mono">
                  {formatTimestamp(transfer.completedTime || transfer.lastUpdated)}
                </span>
              </div>
            </div>
          </div>

          {/* Reliability & Checkpoint Section */}
          <div className="details-section">
            <h4 className="section-heading">Reliability & Recovery</h4>
            <div className="metadata-grid">
              <div className="meta-item">
                <span className="meta-k">Checkpoint Status</span>
                <span className="meta-v">
                  {transfer.hasCheckpoint ? (
                    <Badge variant="success">Available (.part + .meta on disk)</Badge>
                  ) : (
                    <Badge variant="neutral">None</Badge>
                  )}
                </span>
              </div>

              <div className="meta-item">
                <span className="meta-k">Authoritative Capabilities</span>
                <span className="meta-v capabilities-list">
                  {canResume && <Badge variant="warning">Resumable</Badge>}
                  {canRetry && <Badge variant="info">Retriable</Badge>}
                  {canCancel && <Badge variant="neutral">Cancellable</Badge>}
                  {canRemove && <Badge variant="neutral">Removable</Badge>}
                  {!canResume && !canRetry && !canCancel && !canRemove && <span>None</span>}
                </span>
              </div>
            </div>
          </div>

          {/* Cryptographic Verification Section */}
          <div className="details-section">
            <h4 className="section-heading">Cryptographic Verification (SHA-256)</h4>
            <div className="sha-container">
              {transfer.sha256 ? (
                <div className="copyable-value">
                  <span className="sha-hash font-mono text-break">{transfer.sha256}</span>
                  <button
                    type="button"
                    className="copy-btn"
                    onClick={() => copyToClipboard(transfer.sha256!, 'sha')}
                    title="Copy SHA-256 Digest"
                  >
                    {copiedSha ? 'Copied!' : 'Copy'}
                  </button>
                </div>
              ) : (
                <span className="sha-pending">
                  {status === 'VERIFYING'
                    ? 'Computing cryptographic digest from disk...'
                    : 'SHA-256 digest is verified upon transfer completion.'}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Modal Actions Footer */}
        <div className="dialog-footer transfer-modal-footer">
          {showCancelConfirm ? (
            <div className="confirm-subbar danger">
              <span>Cancel this active transfer immediately?</span>
              <div className="confirm-btns">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setShowCancelConfirm(false)}
                  disabled={actionLoading !== null}
                >
                  Keep Transfer
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  onClick={handleConfirmCancel}
                  disabled={actionLoading !== null}
                >
                  {actionLoading === 'cancel' ? 'Cancelling...' : 'Confirm Cancel'}
                </Button>
              </div>
            </div>
          ) : showRemoveConfirm ? (
            <div className="confirm-subbar neutral">
              <span>Remove from history? Downloaded files on disk remain intact.</span>
              <div className="confirm-btns">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setShowRemoveConfirm(false)}
                  disabled={actionLoading !== null}
                >
                  Keep in History
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  onClick={handleConfirmRemove}
                  disabled={actionLoading !== null}
                >
                  {actionLoading === 'remove' ? 'Removing...' : 'Confirm Remove'}
                </Button>
              </div>
            </div>
          ) : (
            <div className="footer-action-row">
              <div className="action-left">
                {canRemove && onRemove && (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => setShowRemoveConfirm(true)}
                    disabled={actionLoading !== null}
                  >
                    Remove from History
                  </Button>
                )}
              </div>
              <div className="action-right">
                {canResume && onResume && (
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={handleResume}
                    disabled={actionLoading !== null}
                  >
                    {actionLoading === 'resume' ? 'Resuming...' : 'Resume Transfer'}
                  </Button>
                )}
                {canRetry && onRetry && (
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={handleRetry}
                    disabled={actionLoading !== null}
                  >
                    {actionLoading === 'retry' ? 'Retrying...' : 'Retry Transfer'}
                  </Button>
                )}
                {canCancel && onCancel && (
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={() => setShowCancelConfirm(true)}
                    disabled={actionLoading !== null}
                  >
                    Cancel Transfer
                  </Button>
                )}
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={onClose}
                  disabled={actionLoading !== null}
                >
                  Close
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
