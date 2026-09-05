import React, { useState } from 'react';
import { Badge, BadgeVariant } from '../Badge/Badge';
import { Button } from '../Button/Button';
import { Card } from '../Card/Card';
import { ConfirmationDialog } from '../common/ConfirmationDialog';
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
import './TransferCard.css';

export interface TransferCardProps {
  transfer: Transfer;
  defaultExpanded?: boolean;
  onCancel?: (transferId: string) => Promise<unknown> | void;
  onResume?: (transferId: string) => Promise<unknown> | void;
  onRetry?: (transferId: string) => Promise<unknown> | void;
  onRemove?: (transferId: string) => Promise<unknown> | void;
  onOpenDetails?: (transfer: Transfer) => void;
}

export const TransferCard: React.FC<TransferCardProps> = ({
  transfer,
  defaultExpanded = false,
  onCancel,
  onResume,
  onRetry,
  onRemove,
  onOpenDetails,
}) => {
  const [isExpanded, setIsExpanded] = useState<boolean>(defaultExpanded);
  const [showCancelConfirm, setShowCancelConfirm] = useState<boolean>(false);
  const [showRemoveConfirm, setShowRemoveConfirm] = useState<boolean>(false);
  const [actionLoading, setActionLoading] = useState<'resume' | 'retry' | 'cancel' | 'remove' | null>(null);

  const transferId = transfer.transferId || transfer.id;
  const isUpload = transfer.direction === 'OUTGOING' || transfer.direction === 'UPLOAD';
  const percentage = calculatePercentage(transfer.transferredBytes, transfer.fileSize);
  const status: TransferState = transfer.state || transfer.status;
  const remainingBytes = transfer.remainingBytes ?? Math.max(0, transfer.fileSize - transfer.transferredBytes);

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

  const handleConfirmCancel = async () => {
    if (!onCancel) return;
    setActionLoading('cancel');
    try {
      await onCancel(transferId);
      setShowCancelConfirm(false);
    } catch (err) {
      console.error('Failed to cancel transfer:', err);
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
    } catch (err) {
      console.error('Failed to remove transfer:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleResume = async () => {
    if (!onResume) return;
    setActionLoading('resume');
    try {
      await onResume(transferId);
    } catch (err) {
      console.error('Failed to resume transfer:', err);
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
      console.error('Failed to retry transfer:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const speed = transfer.speedBytesPerSecond ?? transfer.speed;
  const eta = transfer.etaSeconds ?? transfer.eta;

  return (
    <Card className={`transfer-card ${isActive ? 'is-active' : 'is-terminal'}`}>
      <div className="transfer-card-header">
        <div className="transfer-title-group">
          <div
            className={`transfer-direction-icon ${isUpload ? 'upload' : 'download'}`}
            title={isUpload ? 'Outgoing transfer' : 'Incoming transfer'}
            aria-hidden="true"
          >
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

          <div className="transfer-meta-titles">
            <h3
              className="transfer-filename"
              title={transfer.fileName}
              onClick={onOpenDetails ? () => onOpenDetails(transfer) : undefined}
              style={onOpenDetails ? { cursor: 'pointer' } : undefined}
            >
              {transfer.fileName}
            </h3>
            <span className="transfer-peer-desc">
              {isUpload ? 'Sending to' : 'Receiving from'}{' '}
              <strong>{transfer.peerName || 'Remote Peer'}</strong>
            </span>
          </div>
        </div>

        <div className="transfer-badge-group">
          {transfer.hasCheckpoint && (
            <span className="checkpoint-badge" title="Checkpoint verified on disk (.part + .meta)">
              Checkpoint
            </span>
          )}
          <Badge variant={getBadgeVariant(status)} withDot>
            {formatTransferState(status)}
          </Badge>
        </div>
      </div>

      {/* Authoritative Progress Bar */}
      <div className="transfer-progress-wrap">
        <ProgressBar progress={percentage} variant={getProgressVariant(status)} />
      </div>

      {/* Bytes & Metrics Row */}
      <div className="transfer-stats-row">
        <span className="transfer-bytes-info font-mono">
          {percentage}% &bull; {formatBytes(transfer.transferredBytes)} / {formatBytes(transfer.fileSize)}
          {status !== 'COMPLETED' && remainingBytes > 0 && ` (${formatBytes(remainingBytes)} left)`}
        </span>

        <div className="transfer-live-metrics">
          {isActive ? (
            <>
              <span className="metric-tag">{formatSpeed(speed)}</span>
              <span className="metric-tag">ETA: {formatEta(eta)}</span>
            </>
          ) : status === 'COMPLETED' ? (
            <span className="metric-tag completed-tag">Completed</span>
          ) : status === 'RESUMABLE' || status === 'INTERRUPTED' ? (
            <span className="metric-tag resumable-tag">Interrupted &bull; Ready to Resume</span>
          ) : (
            <span className="metric-tag terminal-tag">{formatTransferState(status)}</span>
          )}
        </div>
      </div>

      {/* Error Message Notice if Failed */}
      {transfer.errorMessage && (
        <div className="transfer-error-notice" role="alert">
          <strong>Error:</strong> {transfer.errorMessage}
        </div>
      )}

      {/* Actions and Expandable Details */}
      <div className="transfer-footer-actions">
        <div className="transfer-footer-left">
          {onOpenDetails ? (
            <button
              type="button"
              className="details-toggle-btn"
              onClick={() => onOpenDetails(transfer)}
            >
              View Details
            </button>
          ) : (
            <button
              type="button"
              className="details-toggle-btn"
              onClick={() => setIsExpanded(!isExpanded)}
              aria-expanded={isExpanded}
            >
              {isExpanded ? 'Hide Details' : 'View Details'}
            </button>
          )}

          {canRemove && onRemove && (
            <button
              type="button"
              className="remove-history-link-btn"
              onClick={() => setShowRemoveConfirm(true)}
              disabled={actionLoading !== null}
              title="Remove transfer from history (leaves downloaded files intact)"
            >
              {actionLoading === 'remove' ? 'Removing...' : 'Remove'}
            </button>
          )}
        </div>

        {/* Action Controls & Confirmation States */}
        <div className="transfer-actions-right">
          <div className="transfer-btn-cluster">
            {canResume && onResume && (
              <Button
                variant="primary"
                size="sm"
                onClick={handleResume}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'resume' ? 'Resuming...' : 'Resume'}
              </Button>
            )}

            {canRetry && onRetry && (
              <Button
                variant="primary"
                size="sm"
                onClick={handleRetry}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'retry' ? 'Retrying...' : 'Retry'}
              </Button>
            )}

            {canCancel && onCancel && (
              <Button
                variant="danger"
                size="sm"
                onClick={() => setShowCancelConfirm(true)}
                disabled={actionLoading !== null}
              >
                {actionLoading === 'cancel' ? 'Cancelling...' : 'Cancel'}
              </Button>
            )}
          </div>
        </div>
      </div>

      {/* Expanded Technical Details Panel (Fallback if modal not used) */}
      {isExpanded && !onOpenDetails && (
        <div className="transfer-expanded-details">
          <div className="detail-field">
            <span className="detail-k">Transfer ID</span>
            <span className="detail-v font-mono">{transferId}</span>
          </div>
          <div className="detail-field">
            <span className="detail-k">Direction</span>
            <span className="detail-v">{isUpload ? 'Outgoing (Upload)' : 'Incoming (Download)'}</span>
          </div>
          <div className="detail-field">
            <span className="detail-k">Peer ID</span>
            <span className="detail-v font-mono">{transfer.peerId}</span>
          </div>
          {transfer.sha256 && (
            <div className="detail-field full-width">
              <span className="detail-k">SHA-256 Digest</span>
              <span className="detail-v font-mono text-break">{transfer.sha256}</span>
            </div>
          )}
          {transfer.startTime ? (
            <div className="detail-field">
              <span className="detail-k">Duration</span>
              <span className="detail-v">
                {formatUptime(
                  (transfer.completedTime || Date.now()) - transfer.startTime
                )}
              </span>
            </div>
          ) : null}
          {transfer.lastUpdated ? (
            <div className="detail-field">
              <span className="detail-k">Last Updated</span>
              <span className="detail-v font-mono">{formatTimestamp(transfer.lastUpdated)}</span>
            </div>
          ) : null}
        </div>
      )}

      {/* Cancellation Modal Confirmation */}
      <ConfirmationDialog
        isOpen={showCancelConfirm}
        title="Cancel File Transfer?"
        message={`Are you sure you want to cancel the transfer of "${transfer.fileName}"? Any partial progress will be discarded.`}
        confirmLabel={actionLoading === 'cancel' ? 'Cancelling...' : 'Cancel Transfer'}
        cancelLabel="Keep Transfer"
        confirmVariant="danger"
        isConfirming={actionLoading === 'cancel'}
        onConfirm={handleConfirmCancel}
        onCancel={() => setShowCancelConfirm(false)}
      />

      {/* Remove from History Modal Confirmation */}
      <ConfirmationDialog
        isOpen={showRemoveConfirm}
        title="Remove Transfer Record?"
        message={`Remove "${transfer.fileName}" from the transfer list? Any completed files saved to disk will not be deleted.`}
        confirmLabel={actionLoading === 'remove' ? 'Removing...' : 'Remove Record'}
        cancelLabel="Keep Record"
        confirmVariant="danger"
        isConfirming={actionLoading === 'remove'}
        onConfirm={handleConfirmRemove}
        onCancel={() => setShowRemoveConfirm(false)}
      />
    </Card>
  );
};
