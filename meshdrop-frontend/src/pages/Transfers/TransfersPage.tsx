import React, { useState } from 'react';
import { Button } from '../../components/Button/Button';
import { Card } from '../../components/Card/Card';
import { EmptyState } from '../../components/EmptyState/EmptyState';
import { LoadingState } from '../../components/LoadingState/LoadingState';
import { PageHeader } from '../../components/PageHeader/PageHeader';
import { SendFileDialog } from '../../components/transfers/SendFileDialog';
import { TransferCard } from '../../components/transfers/TransferCard';
import { TransferDetailsModal } from '../../components/transfers/TransferDetailsModal';
import { usePeers } from '../../hooks/usePeers';
import { useTransfers } from '../../hooks/useTransfers';
import { Transfer } from '../../types/Transfer';
import { formatRelativeTime } from '../../utils/formatters';
import './TransfersPage.css';

type TransferFilter = 'ALL' | 'ACTIVE' | 'RESUMABLE' | 'COMPLETED' | 'FAILED';

export const TransfersPage: React.FC = () => {
  const {
    transfers,
    activeTransfers,
    resumableTransfers,
    completedTransfers,
    failedTransfers,
    counters,
    isLoading,
    error,
    lastUpdated,
    isStale,
    refresh,
    startTransfer,
    resumeTransfer,
    retryTransfer,
    cancelTransfer,
    removeTransfer,
  } = useTransfers(1500);

  const { peers } = usePeers(2500);
  const [isSendDialogOpen, setIsSendDialogOpen] = useState<boolean>(
    () => typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('send') === 'true'
  );
  const [filter, setFilter] = useState<TransferFilter>('ALL');
  const [selectedTransfer, setSelectedTransfer] = useState<Transfer | null>(null);

  if (isLoading && transfers.length === 0) {
    return <LoadingState message="Loading transfers from MeshDrop node..." />;
  }

  const isBackendOffline = error !== null;

  // Filter transfers according to active filter
  let displayedTransfers: Transfer[] = [];
  if (filter === 'ALL') {
    displayedTransfers = [...transfers];
  } else if (filter === 'ACTIVE') {
    displayedTransfers = [...activeTransfers];
  } else if (filter === 'RESUMABLE') {
    displayedTransfers = [...resumableTransfers];
  } else if (filter === 'COMPLETED') {
    displayedTransfers = [...completedTransfers];
  } else if (filter === 'FAILED') {
    displayedTransfers = [...failedTransfers];
  }

  // Sort transfers: Active/Resuming first, Resumable second, then newest to oldest
  displayedTransfers.sort((a, b) => {
    const aActive = activeTransfers.some((t) => (t.transferId || t.id) === (a.transferId || a.id));
    const bActive = activeTransfers.some((t) => (t.transferId || t.id) === (b.transferId || b.id));
    if (aActive && !bActive) return -1;
    if (!aActive && bActive) return 1;

    const aResumable = resumableTransfers.some((t) => (t.transferId || t.id) === (a.transferId || a.id));
    const bResumable = resumableTransfers.some((t) => (t.transferId || t.id) === (b.transferId || b.id));
    if (aResumable && !bResumable) return -1;
    if (!aResumable && bResumable) return 1;

    const aTime = a.lastUpdated || a.completedTime || a.startTime || 0;
    const bTime = b.lastUpdated || b.completedTime || b.startTime || 0;
    return bTime - aTime;
  });

  // Keep selected modal transfer in sync with updated live state
  const activeModalTransfer = selectedTransfer
    ? transfers.find((t) => (t.transferId || t.id) === (selectedTransfer.transferId || selectedTransfer.id)) ||
      selectedTransfer
    : null;

  return (
    <div className="transfers-page">
      <PageHeader
        title="Transfers"
        description="Monitor active streaming, resume interrupted downloads from checkpoints, and track file delivery."
        actions={
          <div className="page-header-actions">
            {lastUpdated && (
              <span className="transfers-count-text" style={{ fontSize: '0.75rem' }}>
                {isStale ? (
                  <span style={{ color: 'var(--warning, #d97706)' }}>
                    Offline &bull; Last updated {formatRelativeTime(lastUpdated)}
                  </span>
                ) : (
                  <span>Updated {formatRelativeTime(lastUpdated)}</span>
                )}
              </span>
            )}
            <Button variant="secondary" size="sm" onClick={() => refresh()}>
              Refresh
            </Button>
            <Button
              variant="primary"
              size="sm"
              onClick={() => setIsSendDialogOpen(true)}
              disabled={isBackendOffline}
            >
              Send File
            </Button>
          </div>
        }
      />

      {/* Backend Offline or Error Notice */}
      {isBackendOffline && (
        <div className="transfers-offline-banner" role="alert">
          <div className="offline-banner-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
          </div>
          <div className="offline-banner-text">
            <strong>Unable to reach MeshDrop control server.</strong> Live transfer updates and controls are paused.
          </div>
          <Button variant="secondary" size="sm" onClick={() => refresh()}>
            Retry
          </Button>
        </div>
      )}

      {/* Summary Metric Counters */}
      <div className="transfers-summary-counters">
        <Card
          className={`counter-card ${filter === 'ACTIVE' ? 'active-filter' : ''}`}
          onClick={() => setFilter(filter === 'ACTIVE' ? 'ALL' : 'ACTIVE')}
        >
          <span className="counter-label">Active</span>
          <span className="counter-value active-color">{counters.active}</span>
          <span className="counter-desc">Streaming TCP chunks</span>
        </Card>

        <Card
          className={`counter-card ${filter === 'RESUMABLE' ? 'active-filter' : ''}`}
          onClick={() => setFilter(filter === 'RESUMABLE' ? 'ALL' : 'RESUMABLE')}
        >
          <span className="counter-label">Resumable</span>
          <span className="counter-value resumable-color">{counters.resumable}</span>
          <span className="counter-desc">Checkpoint ready on disk</span>
        </Card>

        <Card
          className={`counter-card ${filter === 'COMPLETED' ? 'active-filter' : ''}`}
          onClick={() => setFilter(filter === 'COMPLETED' ? 'ALL' : 'COMPLETED')}
        >
          <span className="counter-label">Completed</span>
          <span className="counter-value completed-color">{counters.completed}</span>
          <span className="counter-desc">Verified SHA-256</span>
        </Card>

        <Card
          className={`counter-card ${filter === 'FAILED' ? 'active-filter' : ''}`}
          onClick={() => setFilter(filter === 'FAILED' ? 'ALL' : 'FAILED')}
        >
          <span className="counter-label">Failed / Stopped</span>
          <span className="counter-value failed-color">{counters.failed}</span>
          <span className="counter-desc">Interrupted / cancelled</span>
        </Card>
      </div>

      {/* Toolbar / Filters */}
      <div className="transfers-toolbar">
        <span className="transfers-count-text">
          Showing {displayedTransfers.length} of {transfers.length} transfers
        </span>
        <div className="transfers-filter-buttons" role="tablist" aria-label="Transfer filters">
          <Button
            size="sm"
            variant={filter === 'ALL' ? 'primary' : 'secondary'}
            onClick={() => setFilter('ALL')}
          >
            All ({counters.total})
          </Button>
          <Button
            size="sm"
            variant={filter === 'ACTIVE' ? 'primary' : 'secondary'}
            onClick={() => setFilter('ACTIVE')}
          >
            Active ({counters.active})
          </Button>
          <Button
            size="sm"
            variant={filter === 'RESUMABLE' ? 'primary' : 'secondary'}
            onClick={() => setFilter('RESUMABLE')}
          >
            Resumable ({counters.resumable})
          </Button>
          <Button
            size="sm"
            variant={filter === 'COMPLETED' ? 'primary' : 'secondary'}
            onClick={() => setFilter('COMPLETED')}
          >
            Completed ({counters.completed})
          </Button>
          <Button
            size="sm"
            variant={filter === 'FAILED' ? 'primary' : 'secondary'}
            onClick={() => setFilter('FAILED')}
          >
            Failed / Cancelled ({counters.failed})
          </Button>
        </div>
      </div>

      {/* Empty State */}
      {displayedTransfers.length === 0 ? (
        <EmptyState
          title={
            filter === 'ACTIVE'
              ? 'No active transfers'
              : filter === 'RESUMABLE'
              ? 'No resumable transfers'
              : filter === 'COMPLETED'
              ? 'No completed transfers'
              : filter === 'FAILED'
              ? 'No failed transfers'
              : 'No transfers found'
          }
          description={
            filter !== 'ALL'
              ? 'Switch filters or send a file to view transfer activity.'
              : 'Select a local file and target a connected peer to stream data over TCP.'
          }
          action={
            filter !== 'ALL' ? (
              <Button size="sm" variant="secondary" onClick={() => setFilter('ALL')}>
                Reset Filter
              </Button>
            ) : (
              <Button
                size="sm"
                variant="primary"
                onClick={() => setIsSendDialogOpen(true)}
                disabled={isBackendOffline}
              >
                Send File
              </Button>
            )
          }
        />
      ) : (
        <div className="transfers-sections">
          {/* Categorized List */}
          <div className="transfers-list">
            {displayedTransfers.map((t) => (
              <TransferCard
                key={t.transferId || t.id}
                transfer={t}
                onResume={resumeTransfer}
                onRetry={retryTransfer}
                onCancel={cancelTransfer}
                onRemove={removeTransfer}
                onOpenDetails={(selected) => setSelectedTransfer(selected)}
              />
            ))}
          </div>
        </div>
      )}

      {/* Detailed Transfer Technical Inspection Modal */}
      <TransferDetailsModal
        isOpen={selectedTransfer !== null}
        transfer={activeModalTransfer}
        onClose={() => setSelectedTransfer(null)}
        onResume={resumeTransfer}
        onRetry={retryTransfer}
        onCancel={cancelTransfer}
        onRemove={removeTransfer}
      />

      {/* Send File Dialog */}
      <SendFileDialog
        isOpen={isSendDialogOpen}
        onClose={() => setIsSendDialogOpen(false)}
        peers={peers}
        onSend={async (peerId, filePath) => {
          const res = await startTransfer(peerId, filePath);
          if (!res.success) {
            throw new Error(res.error || 'Failed to start file transfer');
          }
        }}
      />
    </div>
  );
};
